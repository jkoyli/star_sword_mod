package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.AntiDisarm;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：客户端发现第 0 格缺剑的通知包。
 * 服务端自行校验（持剑、开关开启、第 0 格确实缺剑），满足才补剑；否则只回同步包修正显示。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 防缴械缺剑通知包。
// ============================================================================
public class MissingSwordPacket {

    public MissingSwordPacket() {
    }

    public static void encode(MissingSwordPacket msg, FriendlyByteBuf buf) {
    }

    public static MissingSwordPacket decode(FriendlyByteBuf buf) {
        return new MissingSwordPacket();
    }

    public static void handle(MissingSwordPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                ModLog.inventory("MissingSwordPacket.handle 被拒：sender 为 null 或非服务端", player, "");
                return;
            }
            // 不信客户端：全部条件由服务端自己复核。
            if (!ModConstants.isHoldingSword(player)
                    || !AntiDisarm.isEnabled(player)
                    || AntiDisarm.hasOwnSwordInSlot0(player)) {
                ModLog.inventoryThrottled("MissingSwordPacket.reject:" + (player == null ? "?" : player.getUUID()),
                        ModLog.TICK_INTERVAL,
                        "MissingSwordPacket.handle[层=服务端自查] 不满足补剑条件，仅回同步包", player,
                        "持剑=" + ModConstants.isHoldingSword(player)
                                + " 开关=" + AntiDisarm.isEnabled(player)
                                + " 第0格已有剑=" + AntiDisarm.hasOwnSwordInSlot0(player));
                AntiDisarm.syncToClient(player);
                return;
            }
            if (!AntiDisarm.replenish(player)) {
                ModLog.inventory("MissingSwordPacket.handle[层=服务端自查] replenish 失败（无备份），仅回同步包",
                        player, "");
                AntiDisarm.syncToClient(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
