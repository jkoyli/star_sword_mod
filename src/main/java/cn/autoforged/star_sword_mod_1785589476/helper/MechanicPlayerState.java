package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 玩家级机制开关的持久化：写玩家 {@code PersistentData}（跟存档走、跨死亡克隆、跨重启）。
 *
 * <p>改造前只有防缴械与移除模式是 NBT 的，反击/反射存在 {@code ModConstants} 的
 * {@code Map<UUID,Boolean>} 里（重启即丢、重登即丢）。现在四项统一：
 * <ul>
 *   <li>{@code anti_disarm} / {@code remove_mode} 仍委托 {@link AntiDisarm} / {@link RemoveMode}，
 *       沿用原有 NBT 键，老存档无需迁移；</li>
 *   <li>{@code counterattack} / {@code reflection} 新增独立 NBT 键，并镜像进
 *       {@link ModConstants} 的内存表——战斗逻辑读的是那张表，改它的成本高于写两份；</li>
 *   <li>{@link #syncFromNbt(Player)} 在登录/克隆时把 NBT 灌回内存表。</li>
 * </ul>
 */
public final class MechanicPlayerState {

    /** 反击之符（新增 NBT 键）。 */
    public static final String NBT_COUNTERATTACK = "StarSwordCounterAttack";
    /** 反射之符（新增 NBT 键）。 */
    public static final String NBT_REFLECTION = "StarSwordReflection";

    private MechanicPlayerState() {
    }

    /** 读取玩家当前值。 */
    public static boolean get(Player player, String id) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUUID();
        CompoundTag data = player.getPersistentData();
        return switch (id) {
            case "anti_disarm" -> AntiDisarm.isEnabled(player);
            case "remove_mode" -> RemoveMode.isEnabled(player);
            case "counterattack" -> ModConstants.COUNTERATTACK_ENABLED.getOrDefault(uuid,
                    data.getBoolean(NBT_COUNTERATTACK));
            case "reflection" -> ModConstants.REFLECTION_ENABLED.getOrDefault(uuid,
                    data.getBoolean(NBT_REFLECTION));
            default -> data.getBoolean(persistKey(id));
        };
    }

    /** 写入玩家当前值（NBT + 内存表双写，保证战斗逻辑与下次登录都一致）。 */
    public static void set(Player player, String id, boolean value) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        CompoundTag data = player.getPersistentData();
        switch (id) {
            case "anti_disarm" -> AntiDisarm.setEnabled(player, value);
            case "remove_mode" -> RemoveMode.setEnabled(player, value);
            case "counterattack" -> {
                data.putBoolean(NBT_COUNTERATTACK, value);
                ModConstants.COUNTERATTACK_ENABLED.put(uuid, value);
            }
            case "reflection" -> {
                data.putBoolean(NBT_REFLECTION, value);
                ModConstants.REFLECTION_ENABLED.put(uuid, value);
            }
            default -> data.putBoolean(persistKey(id), value);
        }
    }

    /**
     * 登录/克隆后把 NBT 灌回内存表。
     *
     * <p>只在 NBT 里已有显式记录时才写，避免把「从没设置过」的默认值硬塞进表里，
     * 那属于 {@link ModConstants#COUNTERATTACK_DEFAULT_ENABLED} 的职责。
     */
    public static void syncFromNbt(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        CompoundTag data = player.getPersistentData();
        if (data.contains(NBT_COUNTERATTACK)) {
            ModConstants.COUNTERATTACK_ENABLED.put(uuid, data.getBoolean(NBT_COUNTERATTACK));
        }
        if (data.contains(NBT_REFLECTION)) {
            ModConstants.REFLECTION_ENABLED.put(uuid, data.getBoolean(NBT_REFLECTION));
        }
    }

    /** 一批玩家级开关键，供克隆时整体搬运。 */
    public static void copyTo(CompoundTag from, CompoundTag to) {
        if (from == null || to == null) {
            return;
        }
        for (String key : new String[]{NBT_COUNTERATTACK, NBT_REFLECTION}) {
            if (from.contains(key)) {
                to.putBoolean(key, from.getBoolean(key));
            }
        }
    }

    /**
     * 删除这一项在玩家存档里的记录（面板上的「删除存档项」）。
     *
     * <p>删掉之后 {@link #get} 会回落到注册表默认值，所以效果是「回到默认」而不是「设为相反值」。
     * 内存表里的镜像项一并移除，避免删除存档了、本局却还按旧值跑。
     */
    public static void clear(Player player, String id) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        CompoundTag data = player.getPersistentData();
        switch (id) {
            case "anti_disarm" -> data.remove(AntiDisarm.NBT_ENABLED);
            case "remove_mode" -> data.remove(RemoveMode.NBT_ENABLED);
            case "counterattack" -> {
                data.remove(NBT_COUNTERATTACK);
                ModConstants.COUNTERATTACK_ENABLED.remove(uuid);
            }
            case "reflection" -> {
                data.remove(NBT_REFLECTION);
                ModConstants.REFLECTION_ENABLED.remove(uuid);
            }
            default -> data.remove(persistKey(id));
        }
    }

    private static String persistKey(String id) {
        return "StarSwordMechanic." + id;
    }
}
