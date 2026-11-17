package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家防御方向三的 Mixin 等价物（原 ByteBuddy {@code PlayerIsRemovedAdvice}）。
 *
 * <p>1.20.1 的 {@code Entity.isRemoved()} 读私有 {@code removalReason}（SRG {@code f_146795_}）。
 * 受保护玩家的真实血量仍 &gt; 0、且移除原因是被其它模组置为 KILLED/DISCARDED 时，这里把返回值
 * 强制改为 false，让死亡检测/移除判定看不到“已被移除”。跨维度（CHANGED_DIMENSION）等正常移除
 * 不干预，否则会卡死传送（判定见 {@link PlayerDefense#forceNotRemoved}）。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 压制受保护玩家的 isRemoved()（等价原 PlayerIsRemovedAdvice）。
// ============================================================================
@Mixin(Entity.class)
public abstract class EntityIsRemovedMixin {

    // [DEFENSE-MARK] 防御：isRemoved RETURN 强制 false。
    @Inject(method = "isRemoved", at = @At("RETURN"), cancellable = true)
    private void starSword$forceNotRemoved(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        cir.setReturnValue(PlayerDefense.forceNotRemoved(self, cir.getReturnValueZ()));
    }
}
