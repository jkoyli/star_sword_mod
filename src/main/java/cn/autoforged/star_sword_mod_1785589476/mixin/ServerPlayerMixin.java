package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家死亡拦截（ServerPlayer 覆写版）。
 *
 * <p>{@code ServerPlayer.die(DamageSource)} 覆写了 {@code Player.die} 且**不调用 super**
 * （mc-src ServerPlayer.java:598），因此 {@code LivingEntityMixin}/{@code PlayerMixin} 的 die 注入
 * 都拦不到它——必须在 {@code ServerPlayer} 自身补一层。取消后死亡消息、掉落、统计等原版死亡
 * 副作用一并跳过，符合“受保护玩家不该死”的预期。
 *
 * <p>{@code dropAllDeathLoot} 由 {@code LivingEntity} 声明，注入在 {@code LivingEntityMixin}；
 * 本类取消 die 后，{@code ServerPlayer.die} 内的 {@code this.dropAllDeathLoot(...)} 不会执行。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// [REVIVE-MARK] 复活：ServerPlayer.die 覆写层拦截 + 取消后 forceRevive。
// ============================================================================
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：die HEAD 拦截并强制复活。
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void starSword$die(DamageSource source, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (SwordGuard.shouldBlockDirectDie(self)) {
            // 取消整个 die()：原版在 LivingDeathEvent 之后才发 ClientboundPlayerCombatKillPacket
            // （mc-src ServerPlayer.java:600-627），这里直接返回就不会发包，客户端也不会进入死亡处理。
            ci.cancel();
            // 需求：取消后手动清除死亡状态，真正复活。
            PlayerDefense.forceRevive(self);
            ModLog.defense("ServerPlayerMixin.die 取消直接死亡调用并强制复活", self,
                    "来源=" + (source == null ? "null" : source.getMsgId()));
        }
    }
}
