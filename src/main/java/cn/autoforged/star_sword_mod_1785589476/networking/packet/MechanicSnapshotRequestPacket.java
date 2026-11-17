package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求一份机制开关快照（打开面板时发一次）。
 *
 * <p>包体为空；服务端校验持剑后回 {@link MechanicSnapshotPacket}。
 * 不做节流也没关系：请求只读五项、无副作用，且面板每次打开只发一次。
 */
public class MechanicSnapshotRequestPacket {

    public MechanicSnapshotRequestPacket() {
    }

    public static void encode(MechanicSnapshotRequestPacket msg, FriendlyByteBuf buf) {
    }

    public static MechanicSnapshotRequestPacket decode(FriendlyByteBuf buf) {
        return new MechanicSnapshotRequestPacket();
    }

    public static void handle(MechanicSnapshotRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                return;
            }
            if (!ModConstants.isHoldingSword(player)) {
                ModLog.defense("MechanicSnapshotRequestPacket.handle 被拒：未手持星辉剑", player, "");
                return;
            }
            ModMessages.sendMechanicSnapshot(player);
        });
        ctx.get().setPacketHandled(true);
    }
}
