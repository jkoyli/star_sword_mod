package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：同步受保护玩家的旁路真实血量（{@code PlayerDefense.REAL_HEALTH}）。
 *
 * <p>专用服上客户端与服务端是不同 JVM，客户端的旁路 Map 不会自动有值；服务端每 20 tick 把
 * 固定 20 的真实血量发来，客户端写入 {@code REAL_HEALTH} 并强制 {@code setHealth}，从而
 * 让客户端血量与服务端权威值一致，且不受其它模组扣血包影响。
 *
 * <p>{@code active=false} 表示服务端已判定该玩家不再受保护，客户端应清除本地旁路记录。
 *
 * <p>本类不引用任何 {@code net.minecraft.client} 类：收包只把值暂存到 {@link PlayerDefense}
 * 的待处理槽，由客户端只有的 {@code ModClientForgeEvents.onClientTick} 在主线程消费，
 * 避免物理服务端加载客户端类。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 服务端 -> 客户端同步旁路真实血量。
// ============================================================================
public class SyncRealHealthPacket {

    private final float health;
    private final boolean active;

    public SyncRealHealthPacket(float health, boolean active) {
        this.health = health;
        this.active = active;
    }

    public static void encode(SyncRealHealthPacket msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.health);
        buf.writeBoolean(msg.active);
    }

    public static SyncRealHealthPacket decode(FriendlyByteBuf buf) {
        return new SyncRealHealthPacket(buf.readFloat(), buf.readBoolean());
    }

    public static void handle(SyncRealHealthPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                PlayerDefense.setPendingClientRealHealth(msg.health, msg.active);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
