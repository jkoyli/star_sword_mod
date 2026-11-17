package cn.autoforged.star_sword_mod_1785589476.client;

import net.minecraft.world.item.ItemStack;

/**
 * 客户端本地防缴械状态：开关 + 剑备份，来自服务端的 {@code SyncAntiDisarmPacket}。
 * 客户端不修改服务端，只用它在本地预判性把剑放回快捷栏第 0 格。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 防缴械客户端本地状态（开关 + 剑备份）。
// ============================================================================
public final class AntiDisarmClientState {

    private static boolean enabled = false;
    private static ItemStack backup = ItemStack.EMPTY;

    private AntiDisarmClientState() {
    }

    public static void update(boolean enabled, ItemStack backup) {
        AntiDisarmClientState.enabled = enabled;
        AntiDisarmClientState.backup = backup == null ? ItemStack.EMPTY : backup.copy();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static ItemStack getBackup() {
        return backup;
    }
}
