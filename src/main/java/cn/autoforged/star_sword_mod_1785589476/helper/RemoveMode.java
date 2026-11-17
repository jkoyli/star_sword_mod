package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.world.entity.player.Player;

/**
 * “移除模式”开关：在机制面板（M）里切换，状态写玩家 {@code PersistentData}（跨存档/跨死亡克隆）。
 * 原先的 Shift+C 快捷键已在 2.3.1 取消；{@code ToggleRemoveModePacket} 仍保留以兼容旧客户端。
 *
 * <p>开启后 shift+右键清场不再走 {@code Entity.remove(KILLED)}——那条路径可能被其他模组拦截
 * （本模组自己的 {@code EntityMixin} 也会拦 KILLED），而是直接操作实体存储
 * （{@code EntityLookup} / {@code EntitySection} / {@code EntityTickList}）摘除实体，
 * 再补发 {@code ClientboundRemoveEntitiesPacket} 通知客户端，见 {@link EntityRemover}。
 *
 * <p>开关写进玩家 PersistentData（跟存档走），跨死亡由 {@code PlayerEvent.Clone} 手动拷贝。
 * 只适用于普通生物；玩家走 PlayerList + ServerGamePacketListenerImpl，见 {@link EntityRemover}。
 */
public final class RemoveMode {

    /** PersistentData 里的开关键。 */
    public static final String NBT_ENABLED = "StarSwordRemoveMode";

    private RemoveMode() {
    }

    public static boolean isEnabled(Player player) {
        return player != null && player.getPersistentData().getBoolean(NBT_ENABLED);
    }

    public static void setEnabled(Player player, boolean value) {
        player.getPersistentData().putBoolean(NBT_ENABLED, value);
        ModLog.defense("RemoveMode.setEnabled 切换移除模式", player, "enabled=" + value);
    }
}
