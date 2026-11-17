package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通用防御一：按<b>白名单</b>拦截受保护玩家被移除。
 *
 * <p>1.20.1 的 {@code Player.remove(RemovalReason)} / {@code LivingEntity.remove(RemovalReason)}
 * 都覆写了 {@code Entity.remove} 并逐级调用 {@code super.remove}，所以即使实际类型是 Player，
 * 注入在 {@code Entity.remove} HEAD 的这段代码依然会被执行。取消后 {@code setRemoved} 与
 * {@code invalidateCaps} 都不会发生，实体不再被标记移除。
 *
 * <p>为什么还要拦 {@code setRemoved}：{@code remove()} 本身只是转调它，而它是 {@code public final}，
 * 外部实现完全可以绕过 {@code remove()} 直调 {@code setRemoved(DISCARDED)}。两个入口用同一份白名单
 * （{@link PlayerDefense#isRemovalAllowed}）。
 *
 * <p>白名单只放行 {@code UNLOADED_WITH_PLAYER}（退服/踢出）与 {@code CHANGED_DIMENSION}（传送门），
 * 其余理由——包括未来版本新增的未知理由——默认拒绝；不是当前会话实体的旧实体一律放行，
 * 否则重生/重复登录清理会留下同 UUID 的幽灵玩家。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 按白名单拦截受保护玩家的移除。
// ============================================================================
@Mixin(Entity.class)
public abstract class EntityMixin {

    // [DEFENSE-MARK] 防御：remove 入口的白名单拦截。
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void starSword$guardRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (blockRemoval(self, reason, "remove")) {
            ci.cancel();
            return;
        }
        if (self instanceof Player player) {
            afterAllowedRemoval(player, reason);
        }
    }

    // [DEFENSE-MARK] 防御：setRemoved 入口的白名单拦截（remove 只是转调它，这里是直调后门）。
    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void starSword$guardSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (blockRemoval(self, reason, "setRemoved")) {
            ci.cancel();
            return;
        }
        if (self instanceof Player player) {
            afterAllowedRemoval(player, reason);
        }
    }

    /** 白名单判定 + 日志；返回 true 表示这次移除已被取消。 */
    private static boolean blockRemoval(Entity self, Entity.RemovalReason reason, String gate) {
        if (!(self instanceof Player player)) {
            return false;
        }
        if (PlayerDefense.isRemovalAllowed(self, reason)) {
            return false;
        }
        if (!PlayerDefense.isRemovalGuarded(player)) {
            return false;
        }
        ModLog.defense("EntityMixin." + gate + " 拦截白名单外的移除", player, "reason=" + reason);
        return true;
    }

    /**
     * 真正被移除之后才允许彻底清掉旁路记录。
     *
     * <p>{@code CHANGED_DIMENSION} 由克隆/登录的 {@code loadRealHealth} 重新载入，不在清理范围。
     */
    private static void afterAllowedRemoval(Player player, Entity.RemovalReason reason) {
        if (reason != Entity.RemovalReason.CHANGED_DIMENSION) {
            PlayerDefense.onPlayerRemoved(player);
        }
    }
}
