package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.client.gui.CommandTreeScreen;
import cn.autoforged.star_sword_mod_1785589476.helper.CommandPanelData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：完整指令树数据，填充“更多”二级界面。
 */
public class CommandTreeSyncPacket {

    private final List<CommandPanelData.Node> roots;

    public CommandTreeSyncPacket(List<CommandPanelData.Node> roots) {
        this.roots = roots;
    }

    public static void encode(CommandTreeSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.roots.size());
        for (CommandPanelData.Node node : msg.roots) {
            node.encode(buf);
        }
    }

    public static CommandTreeSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<CommandPanelData.Node> roots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            roots.add(CommandPanelData.Node.decode(buf));
        }
        return new CommandTreeSyncPacket(roots);
    }

    public static void handle(CommandTreeSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                CommandTreeScreen.acceptData(msg.roots);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
