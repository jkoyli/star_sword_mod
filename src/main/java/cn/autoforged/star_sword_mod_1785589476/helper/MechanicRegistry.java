package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 机制开关注册表 —— 面板上每个开关的唯一声明处。
 *
 * <p>读值/写值只是转调既有的公开入口
 * （{@link AntiDisarm#isEnabled}/{@link RemoveMode#isEnabled}、
 * {@link ModConstants#COUNTERATTACK_ENABLED}、{@link ModConstants#PREVENT_SPAWN_ENABLED} 等），
 * 与符咒右键切换共用同一批副作用；玩家的开关状态写 {@link MechanicPlayerState}（NBT），
 * 全服项由 {@link MechanicService} 记进世界存档。
 *
 * <p>之所以要一层注册表，是因为要展示「当前是开还是关」必须有一份服务端权威快照；
 * 五个开关散在三个包里、其中两个没有回同步，没有统一入口就没法一次性发给面板。
 */
public final class MechanicRegistry {

    /** 开关类型：决定存哪儿、谁能改。 */
    public enum Kind {
        /** 按玩家存储（玩家 NBT 或按 UUID 的内存表），持剑即可切换。 */
        BOOL_PLAYER(0),
        /** 全服共享（静态字段），需要 op2。 */
        BOOL_GLOBAL(2);

        /** 修改该项所需的最低权限等级。 */
        public final int opLevel;

        Kind(int opLevel) {
            this.opLevel = opLevel;
        }
    }

    /** 一个开关的声明。 */
    public static final class Def {
        public final String id;
        public final Kind kind;
        public final boolean defaultValue;
        /** 语言键后缀：界面用 {@code gui.<modid>.mech.<id>} 取显示名。 */
        public final BooleanSupplier globalGetter;
        public final Consumer<Boolean> globalSetter;

        Def(String id, Kind kind, boolean defaultValue,
            BooleanSupplier globalGetter, Consumer<Boolean> globalSetter) {
            this.id = id;
            this.kind = kind;
            this.defaultValue = defaultValue;
            this.globalGetter = globalGetter;
            this.globalSetter = globalSetter;
        }

        public boolean isPlayerScoped() {
            return this.kind == Kind.BOOL_PLAYER;
        }
    }

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        // 玩家级：四项都写玩家 NBT；快捷键（Shift+Z / Shift+C）已取消，只能从面板切换。
        player("anti_disarm", false);
        player("remove_mode", false);
        player("counterattack", ModConstants.COUNTERATTACK_DEFAULT_ENABLED);
        player("reflection", false);

        // 全服级：对应「禁生之符」右键。它是全局静态字段，所以必须限权。
        global("spawn_prevention", false,
                () -> ModConstants.PREVENT_SPAWN_ENABLED,
                value -> {
                    ModConstants.PREVENT_SPAWN_ENABLED = value;
                    if (!value) {
                        ModConstants.PREVENTED_ENTITY_TYPES.clear();
                    }
                });

        // 全服级：防御的血量「写入语义」策略（宽松 / 严格）。
        // 默认值取启动时的 -Dstar_sword.health.lockStrict；切换后立即影响 resolve 的裁决。
        global("health_lock_strict", HealthWritePolicy.isStrict(),
                HealthWritePolicy::isStrict,
                HealthWritePolicy::setStrict);

        // 攻击侧三项（{@link AttackOptions} 的闸门）。它们影响的是「星辉剑打出去的那一下」，
        // 服务端全局生效（攻击判定在服务端跑），所以同样是全服项、需要 op2。
        // 默认值取 AttackOptions 的启动口径（deathLock / itemAttraction 默认开）。
        global("attack_all_damage_types", AttackOptions.allDamageTypes(),
                AttackOptions::allDamageTypes,
                AttackOptions::setAllDamageTypes);
        global("attack_death_lock", AttackOptions.deathLock(),
                AttackOptions::deathLock,
                AttackOptions::setDeathLock);
        global("attack_item_attraction", AttackOptions.itemAttraction(),
                AttackOptions::itemAttraction,
                AttackOptions::setItemAttraction);
    }

    private MechanicRegistry() {
    }

    private static void player(String id, boolean def) {
        DEFS.put(id, new Def(id, Kind.BOOL_PLAYER, def, null, null));
    }

    private static void global(String id, boolean def,
                               BooleanSupplier getter, Consumer<Boolean> setter) {
        DEFS.put(id, new Def(id, Kind.BOOL_GLOBAL, def, getter, setter));
    }

    public static List<Def> all() {
        return new ArrayList<>(DEFS.values());
    }

    public static Def byId(String id) {
        return id == null ? null : DEFS.get(id);
    }

    // ===================== 读 =====================

    /** 读取开关当前值；玩家级委托既有状态，全服级读静态字段。 */
    public static boolean get(Player player, Def def) {
        if (def == null) {
            return false;
        }
        if (!def.isPlayerScoped()) {
            return def.globalGetter != null && def.globalGetter.getAsBoolean();
        }
        if (player == null) {
            return def.defaultValue;
        }
        return MechanicPlayerState.get(player, def.id);
    }

    // ===================== 写 =====================

    /**
     * 写入开关。玩家级与既有 toggle 入口保持一致（同样的 NBT 键 / 内存表 / 日志），
     * 只是从「取反」变成「设成指定值」，避免界面与服务端不同步时点一次得到相反结果。
     */
    public static void set(Player player, Def def, boolean value) {
        if (def == null) {
            return;
        }
        if (!def.isPlayerScoped()) {
            if (def.globalSetter != null) {
                def.globalSetter.accept(value);
            }
            return;
        }
        if (player == null) {
            return;
        }
        if ("anti_disarm".equals(def.id)) {
            AntiDisarm.setEnabled(player, value);
            // 补齐既有 toggle 入口的副作用：写完 NBT 之后还要把「开关 + 备份」同步给客户端。
            // 客户端那半条防缴械（本地预判回填、缺剑通知包）以这份同步为总闸
            // （ModClientForgeEvents#handleAntiDisarmClientRestore）；漏掉就会出现
            // 「面板显示已开、客户端逻辑仍按关闭跑」。
            if (player instanceof ServerPlayer serverPlayer) {
                AntiDisarm.syncToClient(serverPlayer);
            }
            return;
        }
        // 其余玩家级开关（移除模式 / 反击 / 反射）统一走 NBT 持久化。
        MechanicPlayerState.set(player, def.id, value);
    }

    /**
     * 删除该项的存档记录，并把本局的值改回默认（面板上的「删除存档项」）。
     *
     * @return 删除后的实际值（即该声明的 {@link Def#defaultValue}）
     */
    public static boolean reset(Player player, Def def) {
        if (def == null) {
            return false;
        }
        if (def.isPlayerScoped()) {
            MechanicPlayerState.clear(player, def.id);
            if ("anti_disarm".equals(def.id) && player instanceof ServerPlayer serverPlayer) {
                // 客户端那半条防缴械以服务端同步为总闸，删档后要让它立刻知道「已关闭」。
                AntiDisarm.syncToClient(serverPlayer);
            }
            return def.defaultValue;
        }
        if (player != null) {
            MechanicGlobalState.forget(player.getServer(), def.id);
        }
        if (def.globalSetter != null) {
            // 本局立即回到默认（例如严格写入语义回到启动时的 -D 值），不等下次重启。
            def.globalSetter.accept(def.defaultValue);
        }
        return def.defaultValue;
    }
}
