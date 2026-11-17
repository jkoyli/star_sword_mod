package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡副作用看门狗：处理“死亡流程已经跑过、但状态被我们修复回来”的窗口。
 *
 * <p>{@code LivingDeathEvent} 一旦 post 出去就无法撤销，触发的模组可能已经做了永久性动作
 * （记分板、掉落、发消息、传送）。此时把 {@code dead}/{@code deathTime} 清掉只能救回实体本身，
 * 对“已经通知出去的世界”没有补偿。本类负责检测这个窗口并做一次**伪重生**：
 *
 * <ol>
 *   <li>检测：受保护玩家的 {@code dead} 标志为真，或 {@code deathTime > 0}——而血量/移除状态
 *       已被本模组修复（{@code isRemoved()} 被压制、权威血量大于 0）。</li>
 *   <li>修复：调用 {@link PlayerDefense#forceRevive} 清掉死亡标志、受伤动画、着火、窒息、
 *       KILLED removalReason，并把血量恢复为权威旁路值。</li>
 *   <li>通知：给客户端补发一次权威血量同步包，让客户端的死亡界面/血量显示随之下线
 *       （客户端 tick 侧已有 {@code clientRecoverDeathStateIfNeeded} 兜底）。</li>
 * </ol>
 *
 * <p>限频：同一玩家 {@link #COOLDOWN_TICKS} tick 内只触发一次，避免与 vanilla 死亡流程
 * 每 tick 互相打架。正常路径下触发一次后标志即被清空，条件自然不再成立。
 */
// [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：死亡副作用补偿（伪重生）。
public final class DeathWatchdog {

    /** 同一玩家两次伪重生之间的最小间隔（tick）。 */
    private static final int COOLDOWN_TICKS = 20;

    /** 玩家 UUID -> 上次触发时的 tickCount。 */
    private static final Map<UUID, Integer> LAST_TRIGGER = new ConcurrentHashMap<>();

    /** 测试开关：与 agent 的 -Dstar_sword.poc.forceProtected 对齐，用于无剑玩家上验证伪重生。 */
    private static final boolean FORCE_PROTECTED =
            truthy(System.getProperty("star_sword.poc.forceProtected"));

    /** {@code LivingEntity.dead}（SRG {@code f_20890_}）：protected 字段，走反射。 */
    private static volatile Field deadField;
    private static volatile boolean deadFieldResolved;

    private DeathWatchdog() {
    }

    /**
     * 每个服务端玩家每 tick 调用一次。
     *
     * <p>先判“是否处于受保护状态”，再读 {@code dead}/{@code deathTime}：两者都只在
     * 死亡流程真正跑过之后才会被置上，因此这是一个低成本的窗口检测。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：检测“死亡已 post 但状态被修复”的窗口。
    public static void serverTick(ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        if (!FORCE_PROTECTED && !SwordGuard.isProtected(player)) {
            LAST_TRIGGER.remove(player.getUUID());
            return;
        }
        boolean dead = isDeadFlag(player);
        int deathTime = player.deathTime;
        if (!dead && deathTime <= 0) {
            return;
        }
        UUID id = player.getUUID();
        Integer last = LAST_TRIGGER.get(id);
        if (last != null && player.tickCount - last < COOLDOWN_TICKS) {
            return;
        }
        LAST_TRIGGER.put(id, player.tickCount);

        ModLog.defense("DeathWatchdog 检测到死亡流程已执行而状态已被修复，触发伪重生", player,
                "dead=" + dead + " deathTime=" + deathTime + " 权威血量="
                        + PlayerDefense.getRealHealth(player, Float.NaN));
        try {
            PlayerDefense.forceRevive(player);
        } catch (Throwable t) {
            ModLog.health("DeathWatchdog 伪重生调用 forceRevive 失败", player, "异常=" + t);
        }
        // forceRevive 不清死亡计时：不显式归零的话死亡条件永远成立，伪重生会每 COOLDOWN_TICKS
        // 反复触发（实测表现为日志刷屏）。
        player.deathTime = 0;
        player.hurtTime = 0;
        player.hurtDuration = 0;
        try {
            // 通知客户端：权威血量 + 受保护状态，客户端据此清掉死亡界面与残血显示。
            ModMessages.sendRealHealthSync(player, PlayerDefense.BYPASS_HEALTH, true);
        } catch (Throwable t) {
            ModLog.health("DeathWatchdog 伪重生通知客户端失败", player, "异常=" + t);
        }
        // 观察者一致性：别的玩家客户端上也要看到权威血量，而不是已经发出去的那个 0。
        ObserverSync.broadcastHealth(player,
                PlayerDefense.getRealHealth(player, PlayerDefense.BYPASS_HEALTH));
        ModLog.defense("DeathWatchdog 伪重生完成", player,
                "死亡界面/血量已重新同步，vanilla respawn 流程未参与");
    }

    /** 读取 {@code LivingEntity.dead}（protected，SRG {@code f_20890_}），失败按未死亡处理。 */
    private static boolean isDeadFlag(Player player) {
        Field field = deadField();
        if (field == null) {
            return false;
        }
        try {
            return field.getBoolean(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field deadField() {        if (!deadFieldResolved) {
            synchronized (DeathWatchdog.class) {
                if (!deadFieldResolved) {
                    deadField = ReflectionHelper.findFieldCompat(
                            LivingEntity.class, "f_20890_", "dead");
                    deadFieldResolved = true;
                    if (deadField == null) {
                        ModLog.health("DeathWatchdog 无法解析 LivingEntity.dead，伪重生检测降级为 "
                                + "只看 deathTime", null, "");
                    }
                }
            }
        }
        return deadField;
    }

    /** 与 agent 侧一致：接受 1/true/yes/on。 */
    private static boolean truthy(String value) {
        return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }
}
