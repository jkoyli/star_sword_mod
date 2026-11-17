package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.MechanicService;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：删除某个机制的存档项（面板每行右侧的「删除存档项」按钮）。
 *
 * <p>语义是「回到默认」而不是「设为相反值」：玩家项删掉 NBT 键（内存表镜像一并清），
 * 全服项删掉世界存档里的项并让本局值回到默认（例如严格写入语义回到启动时的
 * {@code -Dstar_sword.health.lockStrict}）。删除会跨自动保存保持，不会被「按当前值刷新」
 * 撤销。服务端复核持剑/权限/限频后回发全量快照。
 */
public class MechanicResetPacket {

    private final String id;

    public MechanicResetPacket(String id) {
        this.id = id;
    }

    public static void encode(MechanicResetPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id, 64);
    }

    public static MechanicResetPacket decode(FriendlyByteBuf buf) {
        return new MechanicResetPacket(buf.readUtf(64));
    }

    public static void handle(MechanicResetPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                ModLog.defense("MechanicResetPacket.handle 被拒：sender 为 null 或非服务端", player,
                        "id=" + msg.id);
                return;
            }
            if (MechanicService.reset(player, msg.id)) {
                ModMessages.sendMechanicSnapshot(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
