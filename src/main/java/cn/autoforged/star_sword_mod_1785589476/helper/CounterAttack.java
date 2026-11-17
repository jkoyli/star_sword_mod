package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 反击之符：受保护玩家被攻击时反打攻击者。
 *
 * <p>这段逻辑原先内联在 {@code LivingEntityMixin} 的 {@code hurt} HEAD handler 里。
 * 自附加 agent 需要在同一个方法的最前面插一个早退守卫，而那个守卫一旦命中，
 * Mixin 的 handler 就再也不会执行——把逻辑抽到这里之后，Mixin 与 agent 桥
 * （{@code AgentHooks.shouldCancelHurt}）调用同一份实现，两边不会出现行为漂移。
 *
 * <p>所有入口都容错：{@code source} 为 null、攻击者不是生物、或玩家已关闭反击时静默返回。
 */
// [DEFENSE-MARK] 防御（反击）：受保护玩家被攻击时反打攻击者。
public final class CounterAttack {

    private CounterAttack() {
    }

    /** 反打 {@code source} 的攻击者。调用方负责确认玩家确实处于防御状态。 */
    // [DEFENSE-MARK] 防御（反击）：反打攻击者。
    public static void onHurt(Player player, DamageSource source, float amount) {
        if (player == null || source == null) {
            return;
        }
        Entity attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity livingAttacker)) {
            return;
        }
        if (ModConstants.B1_PROCESSING.contains(livingAttacker.getUUID())) {
            // 反伤链条里再次进入 hurt 时不递归。
            return;
        }
        if (!ModConstants.COUNTERATTACK_ENABLED.getOrDefault(
                player.getUUID(), ModConstants.COUNTERATTACK_DEFAULT_ENABLED)) {
            // 默认关闭：只有玩家用反击之符显式开启过才会走到下面。
            return;
        }
        ModLog.attack("反击之符触发，反伤攻击者", player, livingAttacker,
                "counterattackAmount=" + amount);
        ModConstants.B1_PROCESSING.add(livingAttacker.getUUID());
        try {
            livingAttacker.hurt(source, amount);
            livingAttacker.hurt(
                    ModConstants.createStarSwordAbsoluteDamageSource(player.level(), player),
                    Float.MAX_VALUE);
            livingAttacker.hurt(player.level().damageSources().fellOutOfWorld(), 1.0F);

            if (ModConstants.REFLECTION_ENABLED.getOrDefault(player.getUUID(), false)) {
                ReflectionHelper.setHealthDirectly(livingAttacker, 0.0F);
                ReflectionHelper.setMaxHealthDirectly(livingAttacker, 0.0);
                ModLog.attack("反击+反射：setHealthDirectly(0) 攻击者", player, livingAttacker);
            }
        } finally {
            ModConstants.B1_PROCESSING.remove(livingAttacker.getUUID());
        }
    }
}
