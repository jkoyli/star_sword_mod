package cn.autoforged.star_sword_mod_1785589476.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编辑器侧的机制清单（与 mod 里的 {@code MechanicRegistry} 一一对应）。
 *
 * <p>为什么不共享 mod 的注册表类：本子工程有硬约束「只依赖 JDK，不碰 Minecraft」，
 * 一旦引用 {@code MechanicRegistry} 就会把 MC/Forge 拖进来，编辑器也就没法在游戏启动前独立跑了。
 * 所以这里保留一份纯数据声明，代价是加开关要改两处；{@link #all()} 的顺序与 mod 一致，
 * id 必须与 {@code MechanicRegistry} 完全相同（写错的话 mod 会当成未知项忽略，不会崩）。
 */
final class SettingsCatalog {

    /** 一个开关的展示声明。 */
    static final class Def {
        /** 与 mod 的 MechanicRegistry id 一致。 */
        final String id;
        /** 面板上的名字。 */
        final String title;
        /** 一句话人话说明（副标题）。 */
        final String plain;
        /** 详细说明（悬停气泡）。 */
        final String detail;
        /** 分组名（玩家级 / 全服级）。 */
        final String group;
        final boolean defaultValue;
        /** 是否需要 op2（全服项）。 */
        final boolean needsOp;

        Def(String id, String title, String plain, String detail,
            String group, boolean defaultValue, boolean needsOp) {
            this.id = id;
            this.title = title;
            this.plain = plain;
            this.detail = detail;
            this.group = group;
            this.defaultValue = defaultValue;
            this.needsOp = needsOp;
        }
    }

    static final String GROUP_PLAYER = "玩家开关";
    static final String GROUP_ATTACK = "攻击开关";
    static final String GROUP_GLOBAL = "全服开关";

    private static final List<Def> DEFS = new ArrayList<>();

    static {
        DEFS.add(new Def("anti_disarm", "防缴械", "拦截 Q 键丢出并自动回填星辉剑",
                "开启后：Q/其它主动丢出会被取消，剑回到背包；客户端断线重连的空窗期也会补剑。"
                        + "与原来的 Shift+Z 是同一个开关，快捷键已在 2.3.1 取消。",
                GROUP_PLAYER, false, false));
        DEFS.add(new Def("remove_mode", "移除模式", "清场时直接摘除实体，绕过移除拦截",
                "开启后：Shift+右键清场不再走 Entity.remove（可能被其它模组拦截），"
                        + "而是直接操作实体存储并补发移除包。与原来的 Shift+C 是同一个开关。",
                GROUP_PLAYER, false, false));
        DEFS.add(new Def("counterattack", "反击之符", "受击时对攻击者反击 3 下",
                "开启后：受保护玩家被命中时，对攻击者连续反击（含绝对伤害）。"
                        + "对血量恒为 Infinity 的 Boss 无效，且可能堆出大量召唤物，默认关闭。",
                GROUP_PLAYER, false, false));
        DEFS.add(new Def("reflection", "反射之符", "对视线内目标强制清血",
                "开启后：右键反射之符时，对 64 格内准星目标执行反射击杀（绕过 setHealth 下限）。",
                GROUP_PLAYER, false, false));

        // 攻击侧（影响「星辉剑打出去的那一下」，服务端全局生效，需要 op2）
        DEFS.add(new Def("attack_all_damage_types", "全部伤害类型轮打",
                "每次挥砍依次施加 30 种伤害类型（默认关）",
                "开启后：能硬穿「对某类伤害免疫」的模组生物，代价是每次挥砍最多 30 次 hurt()"
                        + "与约 30 行日志，并会触发对方所有受击生成逻辑。默认关闭。",
                GROUP_ATTACK, false, true));
        DEFS.add(new Def("attack_death_lock", "死亡锁",
                "被限伤/被复活的目标每 tick 继续压制（默认开）",
                "开启后：命中目标会被写进死亡锁，每 tick 继续强制击杀，直到真正死亡。"
                        + "关闭后只有挥砍那一下生效，目标被限伤或复活就杀不掉。",
                GROUP_ATTACK, true, true));
        DEFS.add(new Def("attack_item_attraction", "远距离拾取",
                "右键时把 128 格内掉落物吸进背包（默认开）",
                "开启后：右键除了 AoE 攻击，还会把 128 格内掉落物吸过来。"
                        + "关闭后右键只打怪，不拾取。",
                GROUP_ATTACK, true, true));

        DEFS.add(new Def("spawn_prevention", "禁止生成", "全服阻止新实体生成（需 op2）",
                "开启后：新实体生成被拦下，并记录被禁的实体类型。它是全服静态开关，"
                        + "任何持剑且有 op2 的玩家都能改，影响所有人。",
                GROUP_GLOBAL, false, true));
        DEFS.add(new Def("health_lock_strict", "严格写入语义", "锁定值压过一切写入，治疗也突破不了",
                "开启后：只要存在权威血量，任何血量写入都会被替换成该权威值（治疗无法突破）；"
                        + "关闭则只拦减血与非法值，允许合法抬高。启动初值仍可由 "
                        + "-Dstar_sword.health.lockStrict 指定。",
                GROUP_GLOBAL, false, true));
    }

    private SettingsCatalog() {
    }

    static List<Def> all() {
        return new ArrayList<>(DEFS);
    }

    static List<Def> ofGroup(String group) {
        List<Def> out = new ArrayList<>();
        for (Def def : DEFS) {
            if (def.group.equals(group)) {
                out.add(def);
            }
        }
        return out;
    }

    /** 全部默认值（id → 默认开关）。 */
    static Map<String, Boolean> defaults() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Def def : DEFS) {
            out.put(def.id, def.defaultValue);
        }
        return out;
    }

    static Def byId(String id) {
        for (Def def : DEFS) {
            if (def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }
}
