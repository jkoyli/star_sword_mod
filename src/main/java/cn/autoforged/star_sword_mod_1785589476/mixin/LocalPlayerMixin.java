package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端死亡计时兜底：拦截 {@link LocalPlayer#tickDeath()}。
 *
 * <h2>为什么必须单独一个 mixin</h2>
 * <p>{@code LocalPlayer} <b>覆写</b>了 {@code LivingEntity.tickDeath()}：
 *
 * <pre>
 * protected void tickDeath() {
 *     ++this.deathTime;
 *     if (this.deathTime == 20) { this.remove(Entity.RemovalReason.KILLED); }
 * }
 * </pre>
 *
 * <p>而 {@code LivingEntityMixin} 织入的是父类方法——客户端这条覆写完全不在它的管辖范围内，
 * 于是出现"对方/原版每 tick 把 {@code deathTime} 推到 1，我们每 tick 清零"的拉锯，
 * 日志里就是 {@code deathTime=1} 反复出现。同一类覆写漏网在 {@code ServerPlayer.die}
 * 上已经踩过一次（那边靠单独声明规则解决），这里用同样的办法：agent 规则 + 本 mixin 双保险，
 * 谁先接管都不会漏。
 *
 * <p>只清零死亡字段（{@code dead}/{@code deathTime}/{@code hurtTime}/{@code hurtDuration}），
 * 不写血量：血量由权威存储与状态锁负责，多一条写者只会制造新的拉锯。
 */
// ============================================================================
// [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：客户端 LocalPlayer 死亡计时拦截。
// ============================================================================
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：取消 LocalPlayer.tickDeath 并清零死亡字段。
    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void starSword$guardLocalTickDeath(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (PlayerDefense.clearDeathFieldsIfProtected(self)) {
            ci.cancel();
        }
    }
}
