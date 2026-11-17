package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.agent.AgentHooks;
import cn.autoforged.star_sword_mod_1785589476.helper.CounterAttack;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.HealthJudgement;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 本类整体承载“受保护玩家”的伤害 / 死亡 / 血量防御拦截：
//   取消 hurt、锁定 getHealth、每 tick 写回旁路血量、压制 isDeadOrDying / tickDeath、
//   拦截直接 die 与死亡掉落。
// ============================================================================
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /**
     * 原版死亡标志 {@code LivingEntity.dead}（mc-src LivingEntity.java:212），
     * {@code die()} 在 :1357 置 true 后不再进入死亡流程。受保护玩家每 tick 强制重置为 false。
     */
    @Shadow
    protected boolean dead;

    // [DEFENSE-MARK] 防御：受保护玩家在 hurt HEAD 直接取消受伤。
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void starSword$hurtHead(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && SwordGuard.isProtected(player)) {
            cir.setReturnValue(false);
            // 被多个伤害源连续攻击时会高频触发：正常拒绝时限频 40t 一次；反击之符触发仍即时记录。
            ModLog.defenseThrottled("LivingEntityMixin.hurtHead:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "LivingEntityMixin.hurt HEAD 取消受伤", player,
                    "来源=" + (source == null ? "null" : source.getMsgId())
                            + " 攻击者=" + ModLog.describe(source == null ? null : source.getEntity())
                            + " 伤害=" + amount + " 血量=" + player.getHealth());

            // 反击逻辑与 agent 桥共用同一份实现（守卫命中时本 handler 不会再执行）。
            CounterAttack.onHurt(player, source, amount);
        }
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void starSword$hurtReturn(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        UUID selfId = self.getUUID();
        if (ModConstants.ATTACK_TARGETS.contains(selfId)) {
            if (!cir.getReturnValue() || self.getHealth() > 0.0F) {
                if (ModConstants.KILL_SETHEALTH_GUARD.add(selfId)) {
                    try {
                        // 直接写实体数据键归零，绕过其他模组对 setHealth 的覆写/下限限制
                        ModLog.attack("hurt RETURN 兜底：目标未死，setHealthDirectly(0)", null, self,
                                "hurtReturn=" + cir.getReturnValue() + " health=" + self.getHealth());
                        ReflectionHelper.setHealthDirectly(self, 0.0F);
                    } finally {
                        ModConstants.KILL_SETHEALTH_GUARD.remove(selfId);
                    }
                }
            }
        }
    }

    /**
     * 通用防御二：覆盖非法血量写入 + 受保护玩家“只增不减”。
     *
     * <p><b>执行者是 agent，这里只是兜底。</b>同一个判据（
     * {@link AgentHooks#filterSetHealthArg}）现在由 agent 在 {@code setHealth} 的 HEAD 处就地改写
     * {@code $1} 完成；本注入只在 agent 没有真正接管这个入口时才执行，否则同一次写入会被过滤两次，
     * {@code countWrite} 翻倍会提前触发高频写入锁定。
     *
     * <p>入参语义不变：NaN / 无限替换为该实体当前最大血量（无效时退回当前有效血量）；受保护玩家的
     * 减血写入替换为当前血量。负数不在此处理——致命伤害产生的负数是正常中间值，由原版
     * {@code setHealth} 内部 {@code Mth.clamp} 压到合法范围。
     */
    // [DEFENSE-MARK] 防御：setHealth 入参归一（仅 NaN/无限兜底 + 受保护玩家以旁路血量为准）。
    @ModifyVariable(method = "setHealth", at = @At("HEAD"), argsOnly = true)
    private float starSword$sanitizeHealth(float value) {
        if (AgentHooks.hasAgentInjection(AgentHooks.INJ_SET_HEALTH)) {
            return value;
        }
        return AgentHooks.filterSetHealthArg((LivingEntity) (Object) this, value);
    }

    /**
     * 通用防御二之补充：把 {@code getHealth()} 的返回值交给 {@link PlayerDefense#forceGetHealth}。
     *
     * <p>这是原 ByteBuddy {@code PlayerGetHealthAdvice} 的 Mixin 等价物：受保护玩家读旁路真实血量；
     * 旁路为空且返回值非法时，客户端/受保护玩家兜底为最大血量。原版 {@code getHealth()} 声明在
     * {@code LivingEntity} 且只有一条返回路径，注入 RETURN 覆盖全部返回点。
     */
    // [DEFENSE-MARK] 防御：getHealth RETURN 锁定为旁路真实血量。
    @Inject(method = "getHealth", at = @At("RETURN"), cancellable = true)
    private void starSword$forceGetHealth(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        cir.setReturnValue(PlayerDefense.forceGetHealth(self, cir.getReturnValueF()));
    }

    /**
     * 每个 tick 强制把旁路真实血量写回原版同步数据（{@code LivingEntity.DATA_HEALTH_ID}）。
     *
     * <p>tick HEAD 与 RETURN 各写一次：HEAD 让 tick 内的死亡判定（{@code isDeadOrDying()}）
     * 读到真实血量，RETURN 覆盖 tick 中途被其它模组通过 {@code entityData.set(...)} 篡改的同步数据。
     *
     * <p>另一种做法是注入 {@code SynchedEntityData.set} 拦截所有写入，但该类不直接持有实体
     * （无法从一次 set 反查是哪个玩家），故采用“在实体 tick 中强制覆盖”这一更简单可靠的方式。
     */
    // [DEFENSE-MARK] 防御：tick HEAD 重置 dead + 写回旁路血量。
    @Inject(method = "tick", at = @At("HEAD"))
    private void starSword$forceWriteVanillaHealthHead(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && SwordGuard.isProtected(player)) {
            // 需求：重置 Player（LivingEntity）的 dead 字段。die() 一旦把它置 true，
            // 后续 die() 会因 `!this.dead` 判定直接跳过，这里每 tick 强制清掉。
            if (this.dead) {
                this.dead = false;
                ModLog.defense("tick HEAD[层=dead] 重置受保护玩家 dead=false", player);
            }
            // 热路径：发布版关闭日志时连 key/UUID 都不拼（ModLog 内部虽已短路，但实参在调用前就会求值）。
            if (ModLog.ENABLED) {
                ModLog.healthThrottled("LivingEntityMixin.tickHead:" + player.getUUID(), ModLog.TICK_INTERVAL,
                        "tick HEAD[层=tick] 对受保护玩家强制写回旁路血量", player, "");
            }
            PlayerDefense.forceWriteVanillaHealth(player);
        }
    }

    // [DEFENSE-MARK] 防御：tick RETURN 覆盖 tick 中途被篡改的血量同步数据。
    @Inject(method = "tick", at = @At("RETURN"))
    private void starSword$forceWriteVanillaHealthReturn(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && SwordGuard.isProtected(player)) {
            if (ModLog.ENABLED) {
                ModLog.healthThrottled("LivingEntityMixin.tickReturn:" + player.getUUID(), ModLog.TICK_INTERVAL,
                        "tick RETURN[层=tick] 覆盖 tick 中途被篡改的旁路血量", player, "");
            }
            PlayerDefense.forceWriteVanillaHealth(player);
        }
    }

    /**
     * 通用防御四：直接织入 {@code isDeadOrDying()}。
     *
     * <p>原版链条是 {@code getHealth() <= 0 → isDeadOrDying() → tickDeath() → deathTime++}。
     * 外部实现替换 {@code getHealth()} 后，这条链的输入就是替换后的读数；只在 {@code deathTime} 被推进之后再复位，
     * 会形成两个写者的拉锯（日志实测：{@code deathTime} 恒为 1，模型立正、背包打不开）。
     *
     * <p>现在改为委托 {@link HealthJudgement#isDead}：防御态（受保护或有权威血量）直接回答「没死」，
     * 于是 {@code LivingEntity.tick()} 里的 {@code if (isDeadOrDying()) tickDeath();} 根本不会执行。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：isDeadOrDying 交给权威判定，阻止进入 tickDeath。
    @Inject(method = "isDeadOrDying", at = @At("HEAD"), cancellable = true)
    private void starSword$isDeadOrDying(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!HealthJudgement.hasOpinion(self)) {
            return;
        }
        cir.setReturnValue(HealthJudgement.isDead(self));
        // 每 tick / 每个读取点都会调用：正常拦截时限频 40t 输出一次；发布版连字符串都不拼。
        if (ModLog.ENABLED && self instanceof Player player) {
            ModLog.defenseThrottled("LivingEntityMixin.isDeadOrDying:" + player.getUUID(),
                    ModLog.TICK_INTERVAL, "isDeadOrDying[层=death] 由权威判定接管", player,
                    "结论=false");
        }
    }

    /**
     * 通用防御五：即使 {@code isDeadOrDying()} 已返回 false，只要前几 tick 累积过 deathTime，
     * {@code tickDeath()} 仍会继续累加它。受保护玩家直接取消本次 tickDeath。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：取消 tickDeath 并立即 forceRevive。
    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void starSword$tickDeath(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && SwordGuard.isProtected(player)) {
            ci.cancel();
            // 需求：不要只取消 tickDeath，取消后立刻强制复活，清掉已累积的死亡状态。
            PlayerDefense.forceRevive(player);
            ModLog.defenseThrottled("LivingEntityMixin.tickDeath:" + player.getUUID(),
                    ModLog.TICK_INTERVAL, "tickDeath[层=death] 取消死亡计时并强制复活", player, "");
        }
    }

    /**
     * 通用防御三：只拦截绕过 hurt 的直接 die 调用。正常伤害管道（调用栈里含
     * {@code hurt} / SRG {@code m_6469_}）进来的死亡放行；直接从别处调 {@code die} 的取消。
     * 判定逻辑统一由 {@link SwordGuard#shouldBlockDirectDie} 提供，供 Player/ServerPlayer
     * 覆写的 die 复用（{@code ServerPlayer.die} 不调 super，本注入拦不到）。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：拦截绕过 hurt 的直接 die。
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void starSword$die(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && SwordGuard.shouldBlockDirectDie(player)) {
            ci.cancel();
            ModLog.defense("LivingEntityMixin.die 取消直接死亡调用", player,
                    "来源=" + (source == null ? "null" : source.getMsgId()));
        }
    }

    /**
     * 缴械防护路径 B：某些“灵魂死亡”链绕过 {@code die()} 直接调用 {@code dropAllDeathLoot}。
     * 对处于防御状态的玩家取消整次掉落，背包（含星辉剑）不会洒落。
     */
    // [DEFENSE-MARK] 防御（防缴械）：取消受保护玩家的死亡掉落，保护背包。
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void starSword$guardDeathLoot(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && SwordGuard.isProtected(player)) {
            ci.cancel();
            ModLog.inventory("取消受保护玩家的死亡掉落（保护背包）", player,
                    "来源=" + (source == null ? "null" : source.getMsgId()));
        }
    }

    /**
     * 记录“原版已经为这个星辉剑目标掉过掉落”。{@code dropAllDeathLoot} 会同时跑 loot table
     * 与 dropCustomDeathLoot，是所有掉落（含模组自定义）的唯一入口。
     * 记录下来后，本模组在强制清除实体前主动补调的 {@code LootHelper.dropDeathLoot}
     * 就不会重复掉落。
     */
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
    private void starSword$markLootDropped(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        UUID selfId = self.getUUID();
        if (ModConstants.ATTACK_TARGETS.contains(selfId)
                || ModConstants.KILLED_BY_STAR_SWORD.contains(selfId)) {
            ModConstants.LOOT_DROPPED.add(selfId);
            ModLog.attack("dropAllDeathLoot 已被原版执行，记录单次掉落标记", null, self,
                    "来源=" + (source == null ? "null" : source.getMsgId()));
        }
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void starSword$setHealthReturn(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        UUID selfId = self.getUUID();
        boolean shouldBeDead = ModConstants.KILLED_BY_STAR_SWORD.contains(selfId)
                || ModConstants.ATTACK_TARGETS.contains(selfId);
        if (shouldBeDead && self.getHealth() > 0.0F) {
            if (ModConstants.KILL_SETHEALTH_GUARD.add(selfId)) {
                try {
                    // 直接写实体数据键归零，绕过其他模组对 setHealth 的覆写/下限限制
                    ModLog.attack("setHealth RETURN 兜底：星辉剑目标仍存活，setHealthDirectly(0)",
                            null, self, "health=" + self.getHealth());
                    ReflectionHelper.setHealthDirectly(self, 0.0F);
                } finally {
                    ModConstants.KILL_SETHEALTH_GUARD.remove(selfId);
                }
            }
        }
    }

    /**
     * 存档落盘前修正：外部实现把 {@code getHealth()} 整体替换成负数公式后，原版
     * {@code addAdditionalSaveData} 会把负数写进 {@code Health}。这里在方法体执行完后直接改写 tag，
     * 不经过任何 getter，是 agent 未附加时（{@code AgentHooks.beforeSave} 缺席）的等价兜底。
     */
    // [DEFENSE-MARK] 防御：存档落盘前把 Health 改写成权威值。
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void starSword$afterVanillaSave(CompoundTag tag, CallbackInfo ci) {
        PlayerDefense.afterVanillaSave((LivingEntity) (Object) this, tag);
    }

    /**
     * 存档读取后修复：已经落盘的负数 / 非零 DeathTime 在这里被改回合法值，
     * 避免“重登继续是负数”的循环。
     */
    // [DEFENSE-MARK] 防御：读取存档后修复污染值。
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void starSword$repairPersistedHealth(CompoundTag tag, CallbackInfo ci) {
        PlayerDefense.repairPersistedHealth((LivingEntity) (Object) this, tag);
    }
}
