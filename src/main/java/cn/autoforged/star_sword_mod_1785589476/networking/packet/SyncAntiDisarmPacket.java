package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.client.AntiDisarmClientState;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：同步防缴械开关状态与剑备份，供客户端预测性回填。
 * 客户端分支才引用客户端类，物理服务端不会加载它。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 同步防缴械开关状态与剑备份。
// ============================================================================
public class SyncAntiDisarmPacket {

    private final boolean enabled;
    private final ItemStack backup;

    public SyncAntiDisarmPacket(boolean enabled, ItemStack backup) {
        this.enabled = enabled;
        this.backup = backup == null ? ItemStack.EMPTY : backup.copy();
    }

    public static void encode(SyncAntiDisarmPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enabled);
        buf.writeItem(msg.backup);
    }

    public static SyncAntiDisarmPacket decode(FriendlyByteBuf buf) {
        return new SyncAntiDisarmPacket(buf.readBoolean(), buf.readItem());
    }

    public static void handle(SyncAntiDisarmPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                AntiDisarmClientState.update(msg.enabled, msg.backup);
                ModLog.inventory("SyncAntiDisarmPacket.handle[层=客户端] 更新本地开关/备份", null,
                        "enabled=" + msg.enabled + " 备份=" + msg.backup.getItem()
                                + (msg.backup.isEmpty() ? "(空)" : ""));
            } else {
                ModLog.inventory("SyncAntiDisarmPacket.handle 忽略：非客户端接收", null, "");
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
