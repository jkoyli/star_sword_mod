package cn.autoforged.star_sword_mod_1785589476.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 注入规则表 —— agent 侧所有"往哪个方法里写什么"的唯一声明处。
 *
 * <p>以前这是一段手写代码（{@code buildInjections}），加一条覆盖要同时改三处：注入表、桥接方法、
 * 可能还有 Mixin，而"到底声明了哪些规则"只能靠读代码数。现在规则是数据：
 * <ul>
 *   <li>{@link Rule#id()} 是稳定标识，日志与"声明 / 实际"对账都用它；</li>
 *   <li>{@link Kind} 表达施加方式，生成器按 kind 决定怎么落字节码；</li>
 *   <li>{@code body} 是 javassist 片段模板，{@code %1$s} 由生成器替换成桥接类名。</li>
 * </ul>
 *
 * <p><b>规则表只是"声明"</b>：目标方法不存在（子类没声明该覆写）会被跳过，单条失败会被单类降级剔除，
 * 这些都会体现在对账里。不要拿这张表当"已覆盖"的证据。
 */
public final class Rules {

    /** 桥接类（mod 侧），生成的代码只调用它。 */
    public static final String BRIDGE_CLASS = "cn.autoforged.star_sword_mod_1785589476.agent.AgentHooks";

    public static final String DAMAGE_SOURCE = "Lnet/minecraft/world/damagesource/DamageSource;";
    public static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";
    public static final String PLAYER = "net.minecraft.world.entity.player.Player";
    public static final String SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer";
    public static final String LOCAL_PLAYER = "net.minecraft.client.player.LocalPlayer";
    public static final String ENTITY = "net.minecraft.world.entity.Entity";
    public static final String SYNCHED_ENTITY_DATA = "net.minecraft.network.syncher.SynchedEntityData";
    public static final String CONNECTION = "net.minecraft.network.Connection";

    /** 一条规则的施加方式。 */
    public enum Kind {
        /** 方法体最前面插一段代码（早退 / 守卫），不改返回值。 */
        HEAD,
        /** 整段替换方法体（片段必须自己 {@code return}）。 */
        REPLACE,
        /** 入参就地改写：HEAD 插入，赋值给 {@code $1..$n}，不接管方法体。 */
        WRAP,
        /** 只记录不干预：HEAD 插入一次计数，原逻辑照常执行。 */
        OBSERVE
    }

    /**
     * @param id         稳定标识（日志、对账、单类剔除都用它）
     * @param owner      目标类（二进制名）
     * @param method     方法名（Mojang 名，SRG 由 {@link MappingTable} 解析）
     * @param descriptor JVM 描述符
     * @param kind       施加方式
     * @param body       javassist 片段模板（{@code %1$s} 是桥接类名）
     * @param fallback   {@link Kind#REPLACE} 失败时退回的 HEAD 片段；可为 {@code null}
     */
    public record Rule(String id, String owner, String method, String descriptor,
                       Kind kind, String body, String fallback) {
    }

    private Rules() {
    }

    /** 按 agent 模式取规则表。 */
    public static List<Rule> forMode(GuardTransformer.Mode mode) {
        return switch (mode) {
            case PROBE -> List.of();
            case GUARD -> List.of(guardProbe());
            case BRIDGE -> bridge();
        };
    }

    /** {@code guard} 模式：一条哨兵守卫，用于证明"改写成功"，不承担任何防御职责。 */
    private static Rule guardProbe() {
        return new Rule("guard.getHealth", LIVING_ENTITY, "getHealth", "()F", Kind.REPLACE, """
                {
                    if (java.lang.System.getProperty("starsword.guard.hit") == null) {
                        java.lang.System.setProperty("starsword.guard.hit", $0.getClass().getName());
                        System.out.println("[ss-agent] guard HIT getHealth on " + $0.getClass().getName());
                    }
                    return 42.0F;
                }
                """, null);
    }

    /** {@code bridge} 模式：真正的方法层防线。 */
    private static List<Rule> bridge() {
        List<Rule> rules = new ArrayList<>();

        // getHealth 用整段替换，而不是 HEAD + TAIL：
        //   - TAIL（javassist insertAfter）在这个方法上产出不可验证的字节码，实测每次 retransform 都
        //     VerifyError，导致整类注入回滚（对照实验：关掉 BODY 后 TAIL 依旧失败）；
        //   - 整段替换由桥接层按原版语义自己算出返回值，因此 RETURN 侧也归我们管。
        // 注意：不能把原方法另存成新方法——retransform 禁止增删方法（会 VerifyError）。
        rules.add(new Rule("living.getHealth", LIVING_ENTITY, "getHealth", "()F", Kind.REPLACE, """
                { return %1$s.getHealthBody($0); }
                """, null));

        // LivingEntity.hurt 同时承载"反击之符"的副作用：桥接层调用共享的 CounterAttack，
        // 所以在这里取消伤害不会丢掉那个副作用。
        rules.add(new Rule("living.hurt", LIVING_ENTITY, "hurt", "(" + DAMAGE_SOURCE + "F)Z",
                Kind.HEAD, """
                { if (%1$s.shouldCancelHurt($0, $1, $2)) { return false; } }
                """, null));

        // 写入入口的入参归一（原 Mixin @ModifyVariable 的等价物）：只就地改写 $1，不接管方法体，
        // 原版 clamp 语义仍由原方法自己承担。Mixin 侧保留同一判据的调用，但只在 agent 没有实际
        // 接管这个入口时才执行——否则同一次写入会被过滤两次，countWrite 翻倍会提前触发写入锁定。
        rules.add(new Rule("living.setHealth", LIVING_ENTITY, "setHealth", "(F)V", Kind.WRAP, """
                { $1 = %1$s.filterSetHealthArg($0, $1); }
                """, null));

        String suppressDeath = """
                { if (%1$s.shouldSuppressDeath($0)) { return false; } }
                """;
        // 死亡语义解耦：整段替换方法体，而不是在 HEAD 插一条早退。注入点会被外部实现的 transformer
        // 一并覆盖（实测：retransform 后注入点可能在很短的时间内被覆盖）；换成"整个方法体只调用我们的
        // 判定"之后，逻辑里不再有原版 getHealth() 读取，也就不存在"注入被擦掉 → 公式生效 → 每 tick
        // 进 tickDeath"的拉锯。
        String deadBody = """
                { return %1$s.isDeadOrDying($0); }
                """;
        rules.add(new Rule("living.isDeadOrDying", LIVING_ENTITY, "isDeadOrDying", "()Z",
                Kind.REPLACE, deadBody, suppressDeath));

        // 实测：外部实现的读数可能栽在子类覆写里，而不是都挂在 LivingEntity 上。子类只要**声明了**
        // 这两个方法就同样替换；没声明时 findMethod 返回 null，安全跳过（不像 Mixin 那样直接失败）。
        for (String owner : List.of(PLAYER, SERVER_PLAYER, LOCAL_PLAYER)) {
            String prefix = shortName(owner);
            rules.add(new Rule(prefix + ".getHealth", owner, "getHealth", "()F", Kind.REPLACE, """
                    { return %1$s.getHealthBody($0); }
                    """, null));
            rules.add(new Rule(prefix + ".isDeadOrDying", owner, "isDeadOrDying", "()Z",
                    Kind.REPLACE, deadBody, suppressDeath));
        }

        rules.add(new Rule("living.tickDeath", LIVING_ENTITY, "tickDeath", "()V", Kind.HEAD, """
                { if (%1$s.shouldSuppressDeath($0)) { return; } }
                """, null));

        // LocalPlayer 覆写了 tickDeath（`++deathTime`），父类的注入对它无效——客户端就是靠这条覆写
        // 把死亡计时推进到 1，日志里表现为“deathTime 恒为 1、我们清零、它再加”。只对 LocalPlayer 声明，
        // 避免给 Player/ServerPlayer 加出两条必然“方法不存在”的对账噪声。
        rules.add(new Rule("local_player.tickDeath", LOCAL_PLAYER, "tickDeath", "()V", Kind.HEAD, """
                { if (%1$s.shouldSuppressDeath($0)) { return; } }
                """, null));

        String blockDie = """
                { if (%1$s.shouldBlockDie($0)) { return; } }
                """;
        rules.add(new Rule("living.die", LIVING_ENTITY, "die", "(" + DAMAGE_SOURCE + ")V",
                Kind.HEAD, blockDie, null));
        rules.add(new Rule("player.die", PLAYER, "die", "(" + DAMAGE_SOURCE + ")V",
                Kind.HEAD, blockDie, null));
        rules.add(new Rule("server_player.die", SERVER_PLAYER, "die", "(" + DAMAGE_SOURCE + ")V",
                Kind.HEAD, blockDie, null));

        rules.add(new Rule("living.dropAllDeathLoot", LIVING_ENTITY, "dropAllDeathLoot",
                "(" + DAMAGE_SOURCE + ")V", Kind.HEAD, """
                { if (%1$s.shouldCancelDeathLoot($0)) { return; } }
                """, null));

        rules.add(new Rule("entity.remove", ENTITY, "remove",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", Kind.HEAD, """
                        { if (%1$s.shouldCancelRemove($0, $1)) { return; } }
                        """, null));

        // remove() 只是委派给 setRemoved()，而它是 public final：外部实现可以直接调它绕开上面的闸门，
        // 所以同一个白名单要在第二个入口再守一次。
        rules.add(new Rule("entity.setRemoved", ENTITY, "setRemoved",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", Kind.HEAD, """
                        { if (%1$s.shouldCancelSetRemoved($0, $1)) { return; } }
                        """, null));

        rules.add(new Rule("entity.isRemoved", ENTITY, "isRemoved", "()Z", Kind.HEAD, """
                { if (%1$s.shouldHideRemoved($0)) { return false; } }
                """, null));

        // isAlive() 与 isDeadOrDying() 同源：大量逻辑读它（AI/渲染/交互），但原版实现只看"是否已被移除"。
        // 整体替换成桥接判定，避免"血量活着但被判定为不可用"的状态。
        rules.add(new Rule("entity.isAlive", ENTITY, "isAlive", "()Z", Kind.REPLACE, """
                { return %1$s.isAlive($0); }
                """, null));

        // 写侧：所有同步数据项的唯一入口。判据来自 serializer 语义 + 原始存储值，不关心血量挂在哪个键上。
        rules.add(new Rule("synched.set", SYNCHED_ENTITY_DATA, "set",
                "(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V", Kind.HEAD, """
                        { if (%1$s.shouldBlockSynchedWrite($0, $1, $2, $3)) { return; } }
                        """, null));

        // 持久化：写出前覆盖 tag，读入前修复被污染的 tag。
        rules.add(new Rule("living.save", LIVING_ENTITY, "addAdditionalSaveData",
                "(Lnet/minecraft/nbt/CompoundTag;)V", Kind.HEAD, """
                        { %1$s.beforeSave($0, $1); }
                        """, null));
        rules.add(new Rule("living.load", LIVING_ENTITY, "readAdditionalSaveData",
                "(Lnet/minecraft/nbt/CompoundTag;)V", Kind.HEAD, """
                        { %1$s.afterLoad($0, $1); }
                        """, null));

        // 出站：包离开服务端之前改写血量 / 剔除受保护实体 / 丢弃击杀包。
        // null = 丢包。send 是 void，所以必须在片段里提前 return——把 null 交给原方法会在
        // ConnectionProtocol.getProtocolForPacket 的 Packet.getClass() 处直接 NPE。
        // 两个重载共用同一段：send(Packet) 内部会委派到 send(Packet, listener)，丢包时第一遍
        // 就 return；保留时第二遍看到"已无受保护 id"会原样放行，幂等。
        String filterOutgoing = """
                {
                    $1 = (net.minecraft.network.protocol.Packet) %1$s.filterOutgoing($0, $1);
                    if ($1 == null) { return; }
                }
                """;
        rules.add(new Rule("connection.send1", CONNECTION, "send",
                "(Lnet/minecraft/network/protocol/Packet;)V", Kind.WRAP, filterOutgoing, null));
        rules.add(new Rule("connection.send2", CONNECTION, "send",
                "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
                Kind.WRAP, filterOutgoing, null));

        return rules;
    }

    private static String shortName(String className) {
        return className.substring(className.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
