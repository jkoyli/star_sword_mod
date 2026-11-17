package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.MechanicService;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：把某个机制开关设成指定值。
 *
 * <p>与既有的 {@code ToggleAntiDisarmPacket} / {@code ToggleRemoveModePacket} 的区别只有一点：
 * 那两个是「取反」，界面一旦与服务端不同步，点一次就会得到相反结果；这里是幂等的「设成 X」，
 * 重发、丢包、连点都不会把状态点反。
 *
 * <p>服务端复核持剑、权限、限频之后才落值（见 {@link MechanicService#set}），
 * 并回发一次全量快照让界面收敛到权威值。
 */
public class MechanicSetPacket {

    private final String id;
    private final boolean value;

    public MechanicSetPacket(String id, boolean value) {
        this.id = id;
        this.value = value;
    }

    public static void encode(MechanicSetPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id, 64);
        buf.writeBoolean(msg.value);
    }

    public static MechanicSetPacket decode(FriendlyByteBuf buf) {
        return new MechanicSetPacket(buf.readUtf(64), buf.readBoolean());
    }

    public static void handle(MechanicSetPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                ModLog.defense("MechanicSetPacket.handle 被拒：sender 为 null 或非服务端", player,
                        "id=" + msg.id);
                return;
            }
            if (MechanicService.set(player, msg.id, msg.value)) {
                ModMessages.sendMechanicSnapshot(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
