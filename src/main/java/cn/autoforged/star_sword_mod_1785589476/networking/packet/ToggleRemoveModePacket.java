package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.RemoveMode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：切换“移除模式”开关（需手持星辉剑）。
 *
 * <p>服务端校验玩家持剑后才翻转，并在聊天栏提示。开启后 shift+右键清场改走
 * {@code EntityRemover.hardRemove}（直接操作实体存储，绕过 {@code Entity.remove} 拦截）。
 *
 * <p>触发它的 Shift+C 快捷键已在 2.3.1 取消，开关改为在机制面板（M）里设值；
 * 本包保留是为了协议 id 不迁移，以及旧客户端仍能工作。
 */
public class ToggleRemoveModePacket {

    public ToggleRemoveModePacket() {
    }

    public static void encode(ToggleRemoveModePacket msg, FriendlyByteBuf buf) {
    }

    public static ToggleRemoveModePacket decode(FriendlyByteBuf buf) {
        return new ToggleRemoveModePacket();
    }

    public static void handle(ToggleRemoveModePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.level().isClientSide) {
                ModLog.defense("ToggleRemoveModePacket.handle 被拒：sender 为 null 或非服务端", player, "");
                return;
            }
            // 安全底线：服务端校验玩家持剑。
            if (!ModConstants.isHoldingSword(player)) {
                ModLog.defense("ToggleRemoveModePacket.handle 被拒：未手持星辉剑", player, "");
                return;
            }
            boolean now = !RemoveMode.isEnabled(player);
            RemoveMode.setEnabled(player, now);
            player.displayClientMessage(
                    Component.literal("移除模式已" + (now ? "开启" : "关闭")).withStyle(ChatFormatting.GOLD),
                    false);
            ModLog.defense("ToggleRemoveModePacket.handle 处理完成", player, "enabled=" + now);
        });
        ctx.get().setPacketHandled(true);
    }
}
