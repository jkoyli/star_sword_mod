package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.AntiDisarm;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：切换防缴械开关。
 * 服务端校验玩家持剑后才翻转，并在聊天栏提示，同时把新状态同步回客户端。
 *
 * <p>触发它的 Shift+Z 快捷键已在 2.3.1 取消，开关改为在机制面板（M）里设值；
 * 本包保留是为了协议 id 不迁移，以及旧客户端仍能工作。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 切换防缴械开关（旧快捷键 Shift+Z，现由机制面板驱动）。
// ============================================================================
public class ToggleAntiDisarmPacket {

    public ToggleAntiDisarmPacket() {
    }

    public static void encode(ToggleAntiDisarmPacket msg, FriendlyByteBuf buf) {
    }

    public static ToggleAntiDisarmPacket decode(FriendlyByteBuf buf) {
        return new ToggleAntiDisarmPacket();
    }

    public static void handle(ToggleAntiDisarmPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                ModLog.defense("ToggleAntiDisarmPacket.handle 被拒：sender 为 null 或非服务端", player, "");
                return;
            }
            // 校验玩家持剑（手上必须有星辉剑）。
            if (!ModConstants.isHoldingSword(player)) {
                ModLog.defense("ToggleAntiDisarmPacket.handle 被拒：未手持星辉剑", player, "");
                return;
            }
            boolean now = !AntiDisarm.isEnabled(player);
            AntiDisarm.setEnabled(player, now);
            player.displayClientMessage(
                    Component.literal("防缴械已" + (now ? "开启" : "关闭")).withStyle(ChatFormatting.GREEN),
                    false);
            AntiDisarm.syncToClient(player);
            ModLog.defense("ToggleAntiDisarmPacket.handle 处理完成", player, "enabled=" + now);
        });
        ctx.get().setPacketHandled(true);
    }
}
