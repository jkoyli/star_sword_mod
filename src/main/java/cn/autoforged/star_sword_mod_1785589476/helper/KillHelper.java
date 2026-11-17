package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.*;

public final class KillHelper {

    public static class ScheduledKill {
        public final UUID entityId;
        public final UUID attackerId;
        public final ResourceKey<Level> dimension;
        public int retriesLeft;
        public int tickCounter;

        public static final int MAX_RETRIES = 10;
        public static final int RETRY_INTERVAL = 2;

        public ScheduledKill(UUID entityId, UUID attackerId, ResourceKey<Level> dimension) {
            this.entityId = entityId;
            this.attackerId = attackerId;
            this.dimension = dimension;
            this.retriesLeft = MAX_RETRIES;
            this.tickCounter = 0;
        }
    }

    public static void executeFullAttack(LivingEntity target, Player player, Level level) {
        UUID targetId = target.getUUID();
        if (ModConstants.B1_PROCESSING.contains(targetId)) {
            ModLog.attack("executeFullAttack 被拒：B1_PROCESSING 防递归命中", player, target);
            return;
        }

        ModLog.attack("executeFullAttack 开始", player, target);
        lockTarget(target);
        ModConstants.B1_PROCESSING.add(targetId);
        ModConstants.ATTACK_TARGETS.add(targetId);
        try {
            // 有些模组的 hurt() 会抛异常：单条伤害失败不能炸掉整条攻击链，否则后面的
            // setHealthDirectly / lockTarget 后续逻辑都不会执行。
            try {
                boolean hurt = target.hurt(ModConstants.createStarSwordAbsoluteDamageSource(level, player), Float.MAX_VALUE);
                ModLog.attack("target.hurt(star_sword_absolute, Float.MAX_VALUE)", player, target,
                        "result=" + hurt + " health=" + safeHealth(target));
            } catch (Throwable ignored) {
                ModLog.attack("target.hurt(star_sword_absolute, Float.MAX_VALUE)", player, target,
                        "抛出异常=" + ignored);
            }

            if (ModConstants.REFLECTION_ENABLED.getOrDefault(player.getUUID(), false)) {
                boolean ok = ReflectionHelper.setHealthDirectly(target, 0.0F);
                ReflectionHelper.setMaxHealthDirectly(target, 0.0);
                ModLog.attack("ReflectionHelper.setHealthDirectly(target, 0.0F)", player, target, "result=" + ok);
            }

            // A2（可开关）：追加全部伤害类型轮打。默认关闭，见 {@link AttackOptions#allDamageTypes()}。
            // 单独吞异常：二十多条 hurt() 里任意一条抛异常都不能中断后面的 PREVENT_SPAWN 记录。
            if (AttackOptions.allDamageTypes()) {
                try {
                    applyAllDamageTypes(target, player, level);
                } catch (Throwable ignored) {
                }
            }

            if (ModConstants.PREVENT_SPAWN_ENABLED) {
                ModConstants.PREVENTED_ENTITY_TYPES.add(target.getType());
            }
            ModLog.attack("executeFullAttack 完成", player, target,
                    "isDeadOrDying=" + safeBool(target) + " health=" + safeHealth(target));
        } finally {
            ModConstants.B1_PROCESSING.remove(targetId);
            ModConstants.ATTACK_TARGETS.remove(targetId);
        }
    }

    public static void executeFullAttackWithHealthZero(LivingEntity target, Player player, Level level) {
        UUID targetId = target.getUUID();

        ModLog.attack("executeFullAttackWithHealthZero 开始", player, target);
        lockTarget(target);
        ModConstants.ATTACK_TARGETS.add(targetId);
        try {
            executeFullAttack(target, player, level);

            boolean reflectionUsed = false;
            if (ModConstants.REFLECTION_ENABLED.getOrDefault(player.getUUID(), false)) {
                reflectionUsed = ReflectionHelper.setHealthDirectly(target, 0.0F);
                ReflectionHelper.setMaxHealthDirectly(target, 0.0);
            }
            if (!reflectionUsed) {
                var maxHealthAttr = target.getAttribute(Attributes.MAX_HEALTH);
                if (maxHealthAttr != null) {
                    maxHealthAttr.setBaseValue(0);
                    ModLog.attack("MAX_HEALTH.setBaseValue(0)", player, target);
                }
                // 不再调用 target.setHealth(0)（SRG m_21153_，会命中其它模组覆写的限伤实现），
                // 改为直接写该实体实际使用的血量数据键。
                boolean ok = ReflectionHelper.setHealthDirectly(target, 0.0F);
                ModLog.attack("ReflectionHelper.setHealthDirectly(target, 0.0F)", player, target, "result=" + ok);
            }

            boolean voidHurt = target.hurt(level.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            ModLog.attack("target.hurt(fellOutOfWorld, Float.MAX_VALUE)", player, target,
                    "result=" + voidHurt + " health=" + safeHealth(target));

            // 主动驱动掉落：强制归零血量绕过 die()，血量≤0 后 hurt() 直接返回 false，
            // 不会再触发 die()→dropAllDeathLoot，必须在此补调（dropAllDeathLoot 覆盖
            // loot table + dropCustomDeathLoot 两条路径，兼容模组生物自定义掉落）。
            if (target.isDeadOrDying()) {
                ModLog.attack("LootHelper.dropDeathLoot", player, target);
                LootHelper.dropDeathLoot(target,
                        ModConstants.createStarSwordAbsoluteDamageSource(level, player));
            }
            ModLog.attack("executeFullAttackWithHealthZero 完成", player, target,
                    "isDeadOrDying=" + safeBool(target) + " health=" + safeHealth(target));
        } finally {
            ModConstants.ATTACK_TARGETS.remove(targetId);
        }
    }

    public static void executeReflectionKill(LivingEntity target, Player player, Level level) {
        UUID targetId = target.getUUID();

        ModLog.attack("executeReflectionKill 开始", player, target);
        lockTarget(target);
        ModConstants.ATTACK_TARGETS.add(targetId);
        try {
            boolean first = ReflectionHelper.setHealthDirectly(target, 0.0F);

            // 再直写一次血量数据键（替代 target.setHealth(0.0F)：SRG m_21153_ 会命中其它模组
            // 覆写的 setHealth 限伤实现，写不进真正的血量存储位置）。
            boolean second = ReflectionHelper.setHealthDirectly(target, 0.0F);
            ModLog.attack("ReflectionHelper.setHealthDirectly(target, 0.0F) x2", player, target,
                    "first=" + first + " second=" + second);

            boolean absolute = target.hurt(ModConstants.createStarSwordAbsoluteDamageSource(level, player), Float.MAX_VALUE);
            ModLog.attack("target.hurt(star_sword_absolute, Float.MAX_VALUE)", player, target,
                    "result=" + absolute + " health=" + safeHealth(target));

            ReflectionHelper.scanAndZeroSynchedData(target);
            ModLog.attack("ReflectionHelper.scanAndZeroSynchedData", player, target);

            ReflectionHelper.scanAndZeroFields(target);
            ModLog.attack("ReflectionHelper.scanAndZeroFields", player, target);

            boolean voidHurt = target.hurt(level.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            ModLog.attack("target.hurt(fellOutOfWorld, Float.MAX_VALUE)", player, target,
                    "result=" + voidHurt + " health=" + safeHealth(target));

            if (!(target instanceof Player)) {
                scheduleDelayedKill(target, player, level);
            }

            // 主动驱动掉落：本方法先把血量直写为 0，之后的 hurt() 全部因 isDeadOrDying() 直接
            // 返回 false，die() 永不触发，必须在此补调 dropAllDeathLoot（覆盖 loot table +
            // dropCustomDeathLoot 两条路径）。
            if (target.isDeadOrDying()) {
                ModLog.attack("LootHelper.dropDeathLoot", player, target);
                LootHelper.dropDeathLoot(target,
                        ModConstants.createStarSwordAbsoluteDamageSource(level, player));
            }
            ModLog.attack("executeReflectionKill 完成", player, target,
                    "isDeadOrDying=" + safeBool(target) + " health=" + safeHealth(target));
        } finally {
            ModConstants.ATTACK_TARGETS.remove(targetId);
        }
    }

    /**
     * A2（可开关，默认关）：依次对目标施加原版全部 30 种伤害类型，每条都用 {@code Integer.MAX_VALUE}。
     *
     * <p>用途：某些模组生物对特定伤害类型免疫（或按类型分流减伤），换伤害源能硬穿。
     * 由 {@link AttackOptions#allDamageTypes()} 决定是否调用；关掉时整段不执行。
     */
    public static void applyAllDamageTypes(LivingEntity target, Player player, Level level) {
        UUID targetId = target.getUUID();
        if (ModConstants.B1_PROCESSING.contains(targetId)) {
            ModLog.attack("applyAllDamageTypes 被拒：B1_PROCESSING 防递归命中", player, target);
            return;
        }
        ModConstants.B1_PROCESSING.add(targetId);
        try {
            ModLog.attack("applyAllDamageTypes 开始（依次施加全部伤害类型）", player, target);
            DamageSources sources = level.damageSources();
            float dmg = Integer.MAX_VALUE;
            hurtAndLog(target, player, sources.generic(), dmg);
            hurtAndLog(target, player, sources.fellOutOfWorld(), dmg);
            hurtAndLog(target, player, sources.genericKill(), dmg);
            hurtAndLog(target, player, sources.inFire(), dmg);
            hurtAndLog(target, player, sources.onFire(), dmg);
            hurtAndLog(target, player, sources.lava(), dmg);
            hurtAndLog(target, player, sources.hotFloor(), dmg);
            hurtAndLog(target, player, sources.inWall(), dmg);
            hurtAndLog(target, player, sources.cramming(), dmg);
            hurtAndLog(target, player, sources.drown(), dmg);
            hurtAndLog(target, player, sources.starve(), dmg);
            hurtAndLog(target, player, sources.cactus(), dmg);
            hurtAndLog(target, player, sources.fall(), dmg);
            hurtAndLog(target, player, sources.flyIntoWall(), dmg);
            hurtAndLog(target, player, sources.magic(), dmg);
            hurtAndLog(target, player, sources.wither(), dmg);
            hurtAndLog(target, player, sources.anvil(player), dmg);
            hurtAndLog(target, player, sources.fallingBlock(player), dmg);
            hurtAndLog(target, player, sources.dragonBreath(), dmg);
            hurtAndLog(target, player, sources.dryOut(), dmg);
            hurtAndLog(target, player, sources.sweetBerryBush(), dmg);
            hurtAndLog(target, player, sources.freeze(), dmg);
            hurtAndLog(target, player, sources.stalagmite(), dmg);
            hurtAndLog(target, player, sources.lightningBolt(), dmg);
            hurtAndLog(target, player, sources.mobAttack(player), dmg);
            hurtAndLog(target, player, sources.indirectMagic(player, null), dmg);
            hurtAndLog(target, player, sources.thorns(player), dmg);
            hurtAndLog(target, player, sources.sonicBoom(player), dmg);
            hurtAndLog(target, player, sources.badRespawnPointExplosion(player.position()), dmg);
            hurtAndLog(target, player, ModConstants.createStarSwordAbsoluteDamageSource(level, player), dmg);
            ModLog.attack("applyAllDamageTypes 完成", player, target, "health=" + safeHealth(target));
        } finally {
            ModConstants.B1_PROCESSING.remove(targetId);
        }
    }

    /** 记录一条 {@code target.hurt(source, dmg)} 调用（攻击“调用了什么”），并吞掉异常避免中断整条链。 */
    private static void hurtAndLog(LivingEntity target, Player player, DamageSource source, float dmg) {
        String sourceName;
        try {
            sourceName = source.getMsgId();
        } catch (Throwable t) {
            sourceName = "?";
        }
        try {
            boolean result = target.hurt(source, dmg);
            ModLog.attack("target.hurt(" + sourceName + ", " + dmg + ")", player, target,
                    "result=" + result + " health=" + safeHealth(target));
        } catch (Throwable t) {
            ModLog.attack("target.hurt(" + sourceName + ", " + dmg + ")", player, target, "抛出异常=" + t);
        }
    }

    private static String safeHealth(LivingEntity target) {
        try {
            return String.valueOf(target.getHealth());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean safeBool(LivingEntity target) {
        try {
            return target.isDeadOrDying();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void scheduleDelayedKill(LivingEntity target, Player player, Level level) {
        UUID targetId = target.getUUID();
        ScheduledKill existing = ModConstants.SCHEDULED_KILLS.get(targetId);
        if (existing != null) {
            existing.retriesLeft = ScheduledKill.MAX_RETRIES;
            existing.tickCounter = 0;
            ModLog.attack("scheduleDelayedKill 重置", player, target);
            return;
        }
        ModConstants.SCHEDULED_KILLS.put(targetId,
                new ScheduledKill(targetId, player.getUUID(), level.dimension()));
        ModLog.attack("scheduleDelayedKill 注册延迟补刀", player, target);
    }

    public static void processScheduledKills(MinecraftServer server) {
        List<ScheduledKill> tasks = new ArrayList<>(ModConstants.SCHEDULED_KILLS.values());
        for (ScheduledKill task : tasks) {
            task.tickCounter++;
            if (task.tickCounter < ScheduledKill.RETRY_INTERVAL) continue;
            task.tickCounter = 0;

            ServerLevel level = server.getLevel(task.dimension);
            if (level == null) {
                ModLog.attack("processScheduledKills 丢弃任务：维度不存在", null, null,
                        "targetUuid=" + task.entityId + " dimension=" + task.dimension.location());
                ModConstants.SCHEDULED_KILLS.remove(task.entityId);
                continue;
            }

            Entity entity = level.getEntity(task.entityId);
            if (!(entity instanceof LivingEntity target)) {
                ModLog.attack("processScheduledKills 丢弃任务：目标实体不存在", null, null,
                        "targetUuid=" + task.entityId);
                ModConstants.SCHEDULED_KILLS.remove(task.entityId);
                continue;
            }

            task.retriesLeft--;
            if (task.retriesLeft <= 0) {
                ModLog.attack("processScheduledKills 重试耗尽，转入死亡锁", null, target,
                        "retriesLeft=" + task.retriesLeft);
                lockTarget(target);
                ModConstants.SCHEDULED_KILLS.remove(task.entityId);
                continue;
            }

            ModConstants.ATTACK_TARGETS.add(task.entityId);
            try {
                Player attacker = server.getPlayerList().getPlayer(task.attackerId);
                ModLog.attack("processScheduledKills 延迟补刀", attacker, target,
                        "retriesLeft=" + task.retriesLeft + " health=" + safeHealth(target));
                if (attacker != null && ModConstants.REFLECTION_ENABLED.getOrDefault(attacker.getUUID(), false)) {
                    ReflectionHelper.setHealthDirectly(target, 0.0F);
                    ReflectionHelper.setMaxHealthDirectly(target, 0.0);
                    ModLog.attack("processScheduledKills ReflectionHelper.setHealthDirectly", attacker, target);
                } else {
                    // 不再 target.setHealth(0)（SRG m_21153_，会被覆写实现拦截），直写血量数据键。
                    ReflectionHelper.setHealthDirectly(target, 0.0F);
                    var maxHealthAttr = target.getAttribute(Attributes.MAX_HEALTH);
                    if (maxHealthAttr != null) {
                        maxHealthAttr.setBaseValue(0);
                    }
                    ModLog.attack("processScheduledKills setHealthDirectly + MAX_HEALTH=0", attacker, target);
                }

                boolean result = target.hurt(ModConstants.createStarSwordAbsoluteDamageSource(level, attacker), Float.MAX_VALUE);
                ModLog.attack("processScheduledKills target.hurt(star_sword_absolute)", attacker, target,
                        "result=" + result + " health=" + safeHealth(target));

                lockTarget(target);
            } finally {
                ModConstants.ATTACK_TARGETS.remove(task.entityId);
            }
            ModConstants.SCHEDULED_KILLS.remove(task.entityId);
        }
    }

    /** 死亡锁最长持续 tick 数：锁定后目标若迟迟不被移除/不真正死亡，观察窗口到期即自动解除。 */
    public static final int KILL_LOCK_MAX_TICKS = 120;
    /**
     * 单个目标“强制击杀（血量归零 + 伤害补刀）”的总调用上限。
     * 之前每 tick 无脑调用一次 setHealth(0)/补刀直到锁过期（120 tick），会造成持续卡顿；
     * 现在只有目标“仍活着”时才消耗一次该配额，配额用尽即解除锁，避免太多无谓触发。
     */
    public static final int KILL_LOCK_MAX_FORCE_HITS = 50;
    /**
     * 旧实现里“尸体超宽限期仍不消失就强删”的阈值。现在 {@code enforceLockedTarget} 血量归零后
     * 只释放锁、绝不删实体（remove 唯一入口是 shift+右键的 {@code LootHelper.forceRemoveAndDrop}），
     * 本常量已无引用，保留仅为避免改动无关代码。
     */
    public static final int KILL_LOCK_FORCE_REMOVE_TICKS = 40;

    /**
     * 把目标加入“死亡锁”。KILLED_BY_STAR_SWORD 供 Mixin 在任意 setHealth 后兜底归零，
     * KILL_LOCK_TTL 是锁的剩余 tick，KILL_LOCK_FORCE_LEFT 是本锁剩余的强制击杀配额。
     * 玩家目标不锁定：玩家死亡重生使用同一 UUID，若锁定会在其重生后形成无休止的追逐压制。
     */
    public static void lockTarget(LivingEntity target) {
        // A7（可开关）：关闭死亡锁时这里整段不生效——不写 KILL_LOCK_*、不写 KILLED_BY_STAR_SWORD，
        // 因此每 tick 的 enforceDeathLocks 也没有目标可维护。
        if (!AttackOptions.deathLock()) {
            ModLog.attack("lockTarget 跳过：死亡锁开关已关闭", null, target);
            return;
        }
        if (target == null || target.level().isClientSide || target instanceof Player) return;
        ModConstants.KILL_LOCK_TTL.put(target.getUUID(), KILL_LOCK_MAX_TICKS);
        ModConstants.KILL_LOCK_FORCE_LEFT.put(target.getUUID(), KILL_LOCK_MAX_FORCE_HITS);
        ModConstants.KILL_LOCK_DEAD_TICKS.remove(target.getUUID());
        ModConstants.KILLED_BY_STAR_SWORD.add(target.getUUID());
        ModLog.attack("lockTarget 加入死亡锁", null, target,
                "ttl=" + KILL_LOCK_MAX_TICKS + " forceHits=" + KILL_LOCK_MAX_FORCE_HITS);
    }

    private static void releaseLock(UUID id) {
        ModConstants.KILL_LOCK_TTL.remove(id);
        ModConstants.KILL_LOCK_FORCE_LEFT.remove(id);
        ModConstants.KILL_LOCK_DEAD_TICKS.remove(id);
        ModConstants.KILLED_BY_STAR_SWORD.remove(id);
        ModConstants.LOOT_DROPPED.remove(id);
        ModLog.log(ModLog.ATTACK, "releaseLock 解除死亡锁 targetUuid=" + id);
    }

    /**
     * 每 tick 开头调用：对所有“死亡锁”目标做死亡压制。
     * 目标已被移除或锁定超时 → 解除锁；仍活着 → 消耗一次强制击杀配额重新处决。
     */
    public static void enforceDeathLocks(MinecraftServer server) {
        // A7（可开关）：关闭时每 tick 直接返回，连 size() 与限频日志都不做。
        if (!AttackOptions.deathLock()) {
            return;
        }
        int locks = ModConstants.KILL_LOCK_TTL.size();
        if (locks > 0) {
            // 每 tick 都会跑：正常维持死亡锁时限频 40t 输出一次；单个目标的异常/回退另见 enforceLockedTarget。
            ModLog.attackThrottled("KillHelper.enforceDeathLocks", ModLog.TICK_INTERVAL,
                    "enforceDeathLocks[层=每tick死亡锁] 正在维护锁", null, null,
                    "锁定目标数=" + locks);
        }
        for (UUID id : new ArrayList<>(ModConstants.KILL_LOCK_TTL.keySet())) {
            LivingEntity target = findLockedEntity(server, id);
            if (target == null) {
                ModLog.attack("enforceDeathLocks 目标找不到，解除锁", null, null, "targetUuid=" + id);
                releaseLock(id);
                continue;
            }
            int remaining = ModConstants.KILL_LOCK_TTL.getOrDefault(id, 0) - 1;
            if (remaining <= 0) {
                ModLog.attack("enforceDeathLocks 锁观察窗口到期，解除锁", null, target,
                        "targetUuid=" + id + " health=" + safeHealth(target));
                releaseLock(id);
                continue;
            }
            ModConstants.KILL_LOCK_TTL.put(id, remaining);
            enforceLockedTarget(target, id);
        }
    }

    private static LivingEntity findLockedEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity living && !living.isRemoved()) {
                return living;
            }
        }
        return null;
    }

    /**
     * 单个锁定目标的死亡锁维护（每 tick 开头执行一次）：
     * 1) 血量已 ≤0（死亡流程/死亡动画进行中）：只释放锁，绝不删除实体。原版生物会由原版
     *    tickDeath 在 ~20 tick 内自行移除（mc-src LivingEntity.java:567-571）；模组生物若
     *    自己不移除就留着，等玩家 shift+右键清场。remove 的唯一入口是
     *    LootHelper.forceRemoveAndDrop（仅 clearMobsInRadius / shift+右键触发）。
     * 2) 血量仍 >0（首次击杀失败，或被其他模组复活/限伤顶住）：才消耗一次强制击杀配额，
     *    先走伤害管道触发正式 die()，伤害被吞再清复活字段并直写血量归零，交由原版死亡流程收尾。
     *    配额用尽（KILL_LOCK_MAX_FORCE_HITS=50）即解除本锁，避免每 tick 无限触发。
     */
    public static void enforceLockedTarget(LivingEntity target, UUID id) {
        // A7（可开关）：与 enforceDeathLocks 同口径，供任何直接调用方保持一致。
        if (!AttackOptions.deathLock()) {
            return;
        }
        if (target.isRemoved()) {
            ModLog.attack("enforceLockedTarget 目标已移除，解绑", null, target);
            releaseLock(id);
            return;
        }
        if (target.getHealth() <= 0.0F) {
            // 血量已归零：只释放锁，绝不删除实体。原版生物会由原版 tickDeath 在 ~20 tick 内自行
            // 移除（mc-src LivingEntity.java:567-571）；模组生物若自己不移除就留着，等玩家
            // shift+右键清场。remove 的唯一入口是 LootHelper.forceRemoveAndDrop（仅 shift+右键触发）。
            ModLog.attack("enforceLockedTarget 血量已归零，释放锁（不删实体）", null, target,
                    "health=" + safeHealth(target));
            releaseLock(id);
            return;
        }

        int forceLeft = ModConstants.KILL_LOCK_FORCE_LEFT.getOrDefault(id, 0);
        if (forceLeft <= 0) {
            ModLog.attack("enforceLockedTarget 强制击杀配额用尽，释放锁", null, target);
            releaseLock(id);
            return;
        }
        ModConstants.KILL_LOCK_FORCE_LEFT.put(id, forceLeft - 1);

        DamageSource absolute = ModConstants.createStarSwordAbsoluteDamageSource(target.level(), null);
        DamageSource voidFall = target.level().damageSources().fellOutOfWorld();
        // 血>0 时先进伤害管道触发正式 die()（血量已 0 时 hurt() 直接返回 false，无法触发死亡）；
        // 伤害被其他模组吞掉导致血量仍 >0 时，才清一次复活字段并把血量直写归零，交由原版死亡流程收尾。
        boolean absoluteResult = target.hurt(absolute, Float.MAX_VALUE);
        boolean voidResult = target.hurt(voidFall, Float.MAX_VALUE);
        // 目标被反复复活/限伤时这里会每 tick 触发：正常压制过程限频 40t 一次，配额用尽/解除锁仍是即时日志。
        ModLog.attackThrottled("KillHelper.enforceLockedTarget:" + id, ModLog.TICK_INTERVAL,
                "enforceLockedTarget[层=每tick死亡锁] 强制补刀", null, target,
                "forceLeftAfter=" + (forceLeft - 1)
                        + " hurt(absolute)=" + absoluteResult + " hurt(void)=" + voidResult
                        + " health=" + safeHealth(target));
        if (target.getHealth() > 0.0F) {
            ReflectionHelper.scanAndZeroFields(target);
            ReflectionHelper.scanAndZeroSynchedData(target);
            ReflectionHelper.setHealthDirectly(target, 0.0F);
            ModLog.attackThrottled("KillHelper.enforceLockedTarget.zero:" + id, ModLog.TICK_INTERVAL,
                    "enforceLockedTarget[层=每tick死亡锁] scanAndZero + setHealthDirectly(0)", null, target,
                    "health=" + safeHealth(target));
        }
    }
}
