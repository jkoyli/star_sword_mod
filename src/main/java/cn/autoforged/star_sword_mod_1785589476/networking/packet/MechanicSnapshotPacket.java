package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.client.ClientMechanicState;
import cn.autoforged.star_sword_mod_1785589476.helper.MechanicRegistry;
import cn.autoforged.star_sword_mod_1785589476.helper.MechanicService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：机制开关的权威快照。
 *
 * <p>面板打开（含打开期间的 20 tick 轮询）、每次切换成功后各发一次。之所以要全量而不是增量：
 * 五个开关分散在三个存储里，其中「移除模式」原本完全没有回同步，
 * 增量协议还得再维护一份版本号；总共只有五行数据，全量最省事也最不容易错。
 */
public class MechanicSnapshotPacket {

    private final List<MechanicService.Entry> entries;

    public MechanicSnapshotPacket(List<MechanicService.Entry> entries) {
        this.entries = entries;
    }

    public static void encode(MechanicSnapshotPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (MechanicService.Entry entry : msg.entries) {
            buf.writeUtf(entry.id, 64);
            buf.writeByte(entry.kind.ordinal());
            buf.writeBoolean(entry.value);
            buf.writeBoolean(entry.editable);
        }
    }

    public static MechanicSnapshotPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        // 上限保护：恶意服务端不该能让客户端分配一个超大列表。夹取而不是抛异常，
        // 避免一次坏包直接把玩家踢下线（真正的长度不符会由 buf 的读取越界暴露）。
        if (count < 0) {
            count = 0;
        } else if (count > 256) {
            count = 256;
        }
        List<MechanicService.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf(64);
            int kindOrdinal = buf.readByte() & 0xFF;
            MechanicRegistry.Kind[] kinds = MechanicRegistry.Kind.values();
            MechanicRegistry.Kind kind = kindOrdinal < kinds.length
                    ? kinds[kindOrdinal] : MechanicRegistry.Kind.BOOL_PLAYER;
            boolean value = buf.readBoolean();
            boolean editable = buf.readBoolean();
            entries.add(MechanicService.Entry.of(id, kind, value, editable));
        }
        return new MechanicSnapshotPacket(entries);
    }

    public static void handle(MechanicSnapshotPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                ClientMechanicState.accept(msg.entries);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
