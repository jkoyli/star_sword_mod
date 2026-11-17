package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.ProtectedState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：同步“某个玩家当前是否受星辉剑保护”的权威状态。
 *
 * <p>专用服上客户端与服务端是不同 JVM，客户端无法自行判断（重进时物品栏同步包可能尚未到达，
 * 本地 {@code findSword} 返回 null），必须由服务端在登录时与状态变化时广播。客户端把它缓存进
 * {@link ProtectedState} 的全局 {@code Set/Map<UUID>}，之后所有保护判定都不再依赖本地物品栏。
 *
 * <p>不仅发给本人，也广播给所有客户端：这样其他玩家视角的血条也能用安全血量显示
 * （{@code LivingEntityMixin} 的 getHealth 兜底会读该缓存）。
 *
 * <p>本类不引用任何 {@code net.minecraft.client} 类，物理服务端安全；收包只写纯静态缓存。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 服务端 -> 客户端同步“是否受保护”的权威状态。
// ============================================================================
public class SyncProtectedStatePacket {

    private final UUID playerId;
    private final boolean protectedState;

    public SyncProtectedStatePacket(UUID playerId, boolean protectedState) {
        this.playerId = playerId;
        this.protectedState = protectedState;
    }

    public static void encode(SyncProtectedStatePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.playerId);
        buf.writeBoolean(msg.protectedState);
    }

    public static SyncProtectedStatePacket decode(FriendlyByteBuf buf) {
        return new SyncProtectedStatePacket(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(SyncProtectedStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                ProtectedState.acceptClientState(msg.playerId, msg.protectedState);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
