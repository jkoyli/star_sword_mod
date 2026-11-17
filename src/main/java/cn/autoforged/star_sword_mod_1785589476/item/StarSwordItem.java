package cn.autoforged.star_sword_mod_1785589476.item;

import cn.autoforged.star_sword_mod_1785589476.helper.EffectHelper;
import cn.autoforged.star_sword_mod_1785589476.helper.EntitySearchHelper;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class StarSwordItem extends SwordItem {

    private final float attackSpeedModifier;

    public StarSwordItem(Tier tier, int attackDamageModifier, float attackSpeedModifier, Properties properties) {
        super(tier, attackDamageModifier, attackSpeedModifier, properties);
        this.attackSpeedModifier = attackSpeedModifier;
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) {
            ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
            builder.put(Attributes.ATTACK_DAMAGE,
                    new AttributeModifier(BASE_ATTACK_DAMAGE_UUID, "Weapon modifier",
                            Double.POSITIVE_INFINITY, AttributeModifier.Operation.ADDITION));
            builder.put(Attributes.ATTACK_SPEED,
                    new AttributeModifier(BASE_ATTACK_SPEED_UUID, "Weapon modifier",
                            this.attackSpeedModifier, AttributeModifier.Operation.ADDITION));
            return builder.build();
        }
        return super.getDefaultAttributeModifiers(slot);
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    /**
     * 附魔光晕（原版 glint）。
     *
     * <p>这是 1.20.1 里**唯一**能叠在物品图标上、且真的按加色混合绘制、逐帧滚动的层——
     * 想让"剑的边缘有泛光"，靠贴图是做不到的（贴图只有 alpha 混合，压在深色背景上只是发灰），
     * 原版这个 glint 才是真正意义上的"发光层"。剑的贴图里已经烘焙了边缘冷光，
     * 两者叠起来才在浅色/深色背景下都读得出"这把剑在发光"。
     *
     * <p>副作用：外观上会和"附魔物品"一样带一层流光。不想要就
     * {@code -Dstar_sword.fx.glint=false}，或者直接把这个方法改成返回 false。
     */
    private static final boolean GLINT =
            "true".equalsIgnoreCase(System.getProperty("star_sword.fx.glint", "false"));

    @Override
    public boolean isFoil(ItemStack stack) {
        return GLINT;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (entity instanceof Player player && ModConstants.hasSwordInInventory(player)) {
            if (!level.isClientSide) {
                // 方向一/四：补血、死亡检测、反劫持收归星辉剑自己的防御子系统；
                // 不再依赖可能被其它模组劫持的 player.getHealth()，旁路真实血量存在
                // cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense 中。
                // [DEFENSE-MARK] 防御：每 tick 旁路血量/死亡状态防御（另一个调用点在 ModForgeEvents.onPlayerTick）。
                PlayerDefense.serverTick(player);
                if (!player.getAbilities().mayfly) {
                    if (!ModConstants.FLY_STATES.containsKey(player.getUUID())) {
                        ModConstants.FLY_STATES.put(player.getUUID(), player.getAbilities().mayfly);
                    }
                    player.getAbilities().mayfly = true;
                    player.onUpdateAbilities();
                    ModLog.sword("inventoryTick 开启创造飞行 mayfly=true", player, "onUpdateAbilities");
                }

                if (player.getRemainingFireTicks() > 0) {
                    ModLog.sword("inventoryTick 灭火", player,
                            "remainingFireTicks=" + player.getRemainingFireTicks() + "->0");
                    player.setRemainingFireTicks(0);
                }

                Collection<MobEffectInstance> effects = player.getActiveEffects();
                List<MobEffect> toRemove = new ArrayList<>();
                for (MobEffectInstance instance : effects) {
                    if (!instance.getEffect().isBeneficial()) {
                        toRemove.add(instance.getEffect());
                    }
                }
                for (MobEffect effect : toRemove) {
                    player.removeEffect(effect);
                    ModLog.sword("inventoryTick 清除负面效果", player, "effect=" + effect);
                }
            } else {
                if (player.hurtTime > 0 || player.hurtDuration > 0) {
                    // 客户端每 tick 检查：正常重置受伤动画时限频 40t 一次。
                    ModLog.swordThrottled("StarSwordItem.inventoryTick.hurtAnim:" + player.getUUID(),
                            ModLog.TICK_INTERVAL,
                            "inventoryTick[层=客户端] 重置受伤动画", player,
                            "hurtTime=" + player.hurtTime + "->0 hurtDuration=" + player.hurtDuration + "->0");
                }
                player.hurtTime = 0;
                player.hurtDuration = 0;
                if (player.tickCount % 4 == 0) {
                    boolean isLocalPlayer = player == Minecraft.getInstance().player;
                    if (isLocalPlayer && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
                        return;
                    }
                    EffectHelper.spawnPlayerParticles(level, player);
                }
            }
        }
    }

    @Override
    public boolean hurtEnemy(ItemStack pStack, LivingEntity pTarget, LivingEntity pAttacker) {
        ModLog.attack("hurtEnemy[层=原版攻击命中] 返回 true（星辉剑不消耗耐久）", pAttacker, pTarget);
        return true;
    }

    @Override
    public boolean mineBlock(ItemStack pStack, Level pLevel, BlockState pState, BlockPos pPos, LivingEntity pEntityLiving) {
        ModLog.log(ModLog.ATTACK, "mineBlock[层=原版挖掘] 返回 true（星辉剑不消耗耐久） 实体="
                + ModLog.describe(pEntityLiving) + " 方块=" + pState.getBlock());
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            boolean isShift = player.isShiftKeyDown();
            ModLog.attack("StarSwordItem.use 右键", player, null,
                    "shift=" + isShift + " 调用=" + (isShift ? "clearMobsInRadius(100)" : "aoeAttackInRadius(50)+attractItemsInRadius"));
            if (isShift) {
                EntitySearchHelper.clearMobsInRadius(level, player, 100);
            } else {
                EntitySearchHelper.aoeAttackInRadius(level, player, 50);
                EntitySearchHelper.attractItemsInRadius(level, player);
            }
            EffectHelper.spawnRangeVisualEffects(serverLevel, player, isShift);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.5f, 1.5f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
