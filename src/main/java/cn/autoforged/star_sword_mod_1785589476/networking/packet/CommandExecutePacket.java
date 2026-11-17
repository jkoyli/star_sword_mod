package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：执行命令面板中输入的指令。
 * 服务端以满权限（4 级）命令源执行，无视权限限制；反馈只发给持有者本人。
 */
public class CommandExecutePacket {

    private final String command;

    public CommandExecutePacket(String command) {
        this.command = command;
    }

    public static void encode(CommandExecutePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.command, 32500);
    }

    public static CommandExecutePacket decode(FriendlyByteBuf buf) {
        return new CommandExecutePacket(buf.readUtf(32500));
    }

    public static void handle(CommandExecutePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                ModLog.defense("CommandExecutePacket.handle 被拒：sender 为 null 或非服务端", player, "");
                return;
            }
            if (msg.command.isEmpty()) {
                ModLog.defense("CommandExecutePacket.handle 被拒：命令为空", player, "");
                return;
            }
            // 只有持有星辉剑的玩家才能通过面板执行命令
            if (!ModConstants.isHoldingSword(player)) {
                ModLog.defense("CommandExecutePacket.handle 被拒：未手持星辉剑", player, "命令=" + msg.command);
                return;
            }
            ModLog.defense("CommandExecutePacket.handle[层=服务端满权限执行] 执行命令", player,
                    "命令=" + msg.command);
            CommandSourceStack source = player.createCommandSourceStack().withPermission(4);
            player.getServer().getCommands().performPrefixedCommand(source, msg.command);
        });
        ctx.get().setPacketHandled(true);
    }
}
