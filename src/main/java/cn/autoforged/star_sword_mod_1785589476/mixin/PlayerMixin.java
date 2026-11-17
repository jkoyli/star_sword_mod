package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家死亡拦截（Player 覆写版）。{@code Player.die(DamageSource)} 是 {@code Player} 自己声明的
 * 方法（mc-src Player.java:646），它内部会调 {@code super.die}，所以在 {@code LivingEntityMixin}
 * 的 die 注入之外再补一层，保证即使某模组改写了 {@code Player.die} 的第一步也能被拦下。
 *
 * <p>{@code dropAllDeathLoot} 未在 {@code Player} 声明（只在 {@code LivingEntity}，
 * mc-src LivingEntity.java:1395），Mixin 无法对继承方法注入，故掉落拦截继续由
 * {@code LivingEntityMixin.starSword$guardDeathLoot} 覆盖；本类的 die 取消会让
 * {@code Player.die} 内的 {@code this.dropAllDeathLoot(...)} 一并跳过。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// [REVIVE-MARK] 复活：Player.die 覆写层拦截。
// ============================================================================
@Mixin(Player.class)
public abstract class PlayerMixin {

    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：die HEAD 拦截。
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void starSword$die(DamageSource source, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (SwordGuard.shouldBlockDirectDie(self)) {
            ci.cancel();
        }
    }
}
