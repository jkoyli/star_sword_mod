package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.client.ModClientForgeEvents;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.ProtectedState;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.VanillaStateLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端死亡界面 / 血量包拦截（仅客户端加载，见 mixins.json 的 client 列表）。
 *
 * <ul>
 *   <li>{@code handlePlayerCombatKill}：原版在这里 {@code setScreen(new DeathScreen(...))}
 *       （mc-src ClientPacketListener.java:1594-1603）。受保护玩家直接取消，死亡界面与客户端
 *       重生一并跳过。</li>
 *   <li>{@code handleSetHealth}：原版把服务端血量包写入 {@code minecraft.player.hurtTo(...)}
 *       （ClientPacketListener.java:1076-1080）。受保护玩家不走原版扣血值，直接用旁路真实血量
 *       覆盖，并把食物/饱和度照常同步。这样其它模组把服务端血量扣到 1 甚至 0 也影响不到客户端显示。</li>
 * </ul>
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// [REVIVE-MARK] 复活 / [RESPAWN-MARK] 重生：客户端死亡界面与血量包拦截。
// ============================================================================
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活 / [RESPAWN-MARK] 重生：取消死亡界面与客户端重生。
    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"), cancellable = true)
    private void starSword$blockDeathScreen(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null) {
            // 标记本地玩家身份，使 isProtected 的方向 B（未知即暂保）在收包路径上也成立。
            ProtectedState.setClientLocalPlayer(player.getUUID());
        }
        if (player != null && SwordGuard.isProtected(player)) {
            // 直接取消整个包处理：原版下面的 setScreen(DeathScreen) / respawn() 都跳过。
            ci.cancel();
            // 需求：把客户端血量、死亡状态清掉，别让 LocalPlayer 自认为死了。
            PlayerDefense.clientClearDeathState(player);
            // 关屏必须走死亡态安全入口：死亡态下裸调 setScreen(null) 会被 Forge 换成
            // 新的 DeathScreen 并再次 post Opening，和 Opening handler 形成自递归。
            ModClientForgeEvents.safeCloseScreen(mc);
            ModLog.defense("ClientPacketListenerMixin 取消死亡界面/客户端重生并清空本地死亡态", player);
        }
    }

    /**
     * 日常血量同步走的是 {@code ClientboundSetEntityDataPacket}
     * （{@code handleSetEntityData} → {@code SynchedEntityData.assignValues}），
     * 不是 {@code ClientboundSetHealthPacket}——原来只守后者，日常更新这条路上没有任何纠正。
     *
     * <p>这里在包进入原版处理前纠正血量项：非法值换成权威值/当前数据项，有权威记录时不允许被改小。
     * 判定在 {@link PlayerDefense#sanitizeIncomingHealthPacket}。
     */
    // [DEFENSE-MARK] 防御：纠正实体数据包里的血量项。
    @ModifyVariable(method = "handleSetEntityData", at = @At("HEAD"), argsOnly = true)
    private ClientboundSetEntityDataPacket starSword$sanitizeHealthData(
            ClientboundSetEntityDataPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (packet == null || mc.level == null) {
            return packet;
        }
        Entity entity = mc.level.getEntity(packet.id());
        if (!(entity instanceof Player player)) {
            return packet;
        }
        return PlayerDefense.sanitizeIncomingHealthPacket(packet, player);
    }

    // [DEFENSE-MARK] 防御：用旁路真实血量覆盖原版血量包。
    @Inject(method = "handleSetHealth", at = @At("HEAD"), cancellable = true)
    private void starSword$overrideHealth(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null) {
            // 标记本地玩家身份，使 isProtected 方向 B 在血量包路径上也成立。
            ProtectedState.setClientLocalPlayer(player.getUUID());
        }
        if (player == null || !SwordGuard.isProtected(player)) {
            return;
        }
        // 旁路已有真实血量则用它；尚未收到同步包时兜底用最大血量，绝不让扣血值写进客户端。
        float real = PlayerDefense.getRealHealth(player, player.getMaxHealth());
        player.getFoodData().setFoodLevel(packet.getFood());
        player.getFoodData().setSaturation(packet.getSaturation());
        // 状态锁（仅客户端作用域）：食物/饱和度不跟随服务端包，直接钉在锁定值上。
        // 位置很关键——包处理在客户端 tick 之后，若只在 tick 里钳，包一到就会被改回服务端的值。
        if (VanillaStateLock.appliesOn(true)) {
            player.getFoodData().setFoodLevel(VanillaStateLock.LOCK_FOOD);
            player.getFoodData().setSaturation(VanillaStateLock.LOCK_SATURATION);
        }
        PlayerDefense.acceptSyncedRealHealth(player, real);
        ci.cancel();
        ModLog.healthThrottled("ClientPacketListenerMixin.overrideHealth:" + player.getUUID(),
                ModLog.TICK_INTERVAL,
                "ClientPacketListenerMixin[层=handleSetHealth] 用旁路真实血量覆盖原版血量包",
                player, "包内血量=" + packet.getHealth() + " -> " + real);
    }
}
