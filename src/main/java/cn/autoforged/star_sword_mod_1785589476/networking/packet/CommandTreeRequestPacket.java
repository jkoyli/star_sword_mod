package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.CommandPanelData;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求完整指令树（含来源模组标记），用于“更多”二级界面。
 */
public class CommandTreeRequestPacket {

    public CommandTreeRequestPacket() {
    }

    public static void encode(CommandTreeRequestPacket msg, FriendlyByteBuf buf) {
    }

    public static CommandTreeRequestPacket decode(FriendlyByteBuf buf) {
        return new CommandTreeRequestPacket();
    }

    public static void handle(CommandTreeRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                ModLog.defense("CommandTreeRequestPacket.handle 被拒：sender 为 null 或非服务端", player, "");
                return;
            }
            if (!ModConstants.isHoldingSword(player)) {
                ModLog.defense("CommandTreeRequestPacket.handle 被拒：未手持星辉剑", player, "");
                return;
            }
            List<CommandPanelData.Node> roots =
                    CommandPanelData.buildTree(player.getServer().getCommands().getDispatcher());
            ModMessages.sendToPlayer(new CommandTreeSyncPacket(roots), player);
            ModLog.defense("CommandTreeRequestPacket.handle[层=指令树同步] 已发送完整指令树", player,
                    "根节点数=" + roots.size());
        });
        ctx.get().setPacketHandled(true);
    }
}
