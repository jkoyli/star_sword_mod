package cn.autoforged.star_sword_mod_1785589476.event;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import cn.autoforged.star_sword_mod_1785589476.helper.*;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.HealthDataItemInstaller;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.VanillaStateLock;
import cn.autoforged.star_sword_mod_1785589476.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// [REVIVE-MARK] 复活 / [RESPAWN-MARK] 重生：本类集中了服务端侧的防御事件监听：
//   取消受伤/爆炸/死亡、拦截投射物、防缴械丢出与回收、死亡克隆与重生定位、保护状态同步。
// ============================================================================
@Mod.EventBusSubscriber(modid = StarSwordMod.MOD_ID)
public class ModForgeEvents {

    private static final Set<UUID> DEATH_PROCESSING = new HashSet<>();

    // [DEFENSE-MARK] 防御：取消受保护玩家受伤事件。
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (target instanceof Player player) {
            if (SwordGuard.isProtected(player)) {
                ModLog.defense("onLivingHurt 取消玩家受伤", player,
                        "伤害来源=" + (event.getSource() == null ? "null" : event.getSource().getMsgId())
                                + " 攻击者=" + ModLog.describe(event.getSource() == null ? null : event.getSource().getEntity())
                                + " 伤害值=" + event.getAmount()
                                + " 当前血量=" + player.getHealth());
                event.setCanceled(true);
            }
        }
    }

    // [DEFENSE-MARK] 防御：把受保护玩家移出爆炸影响列表。
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        List<Entity> affected = event.getAffectedEntities();
        affected.removeIf(entity -> {
            if (entity instanceof Player player && SwordGuard.isProtected(player)) {
                ModLog.defense("onExplosionDetonate 将受保护玩家移出爆炸影响列表", player);
                return true;
            }
            return false;
        });
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        Entity target = event.getTarget();

        if (player.level().isClientSide) return;
        if (!(target instanceof LivingEntity livingTarget)) {
            if (ModConstants.isHoldingSword(player)) {
                ModLog.attack("onAttackEntity 被拒：目标不是 LivingEntity", player, target);
            }
            return;
        }
        if (!ModConstants.isHoldingSword(player)) {
            ModLog.attackThrottled("ModForgeEvents.onAttackEntity.notHolding:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "onAttackEntity 被拒：未手持星辉剑，走原版攻击", player, livingTarget);
            return;
        }

        // 不再取消原版左键攻击：让星辉剑同时拥有正常的左键攻击（原版伤害/击退/横扫/挥击冷却结算）。
        // 星辉剑的无限攻击力本身即可一击致命；下方击杀辅助只在原版伤害被其他模组拦截/限制时兜底。
        boolean isShift = player.isShiftKeyDown();
        ServerLevel serverLevel = (ServerLevel) player.level();
        ModLog.attack("onAttackEntity 左键近战", player, livingTarget,
                "shift=" + isShift + " 目标血量=" + livingTarget.getHealth()
                        + " 调用=" + (isShift ? "executeFullAttackWithHealthZero" : "executeFullAttack"));
        if (isShift) {
            KillHelper.executeFullAttackWithHealthZero(livingTarget, player, player.level());
            if (!(livingTarget instanceof Player)) {
                KillHelper.scheduleDelayedKill(livingTarget, player, player.level());
            }
        } else {
            KillHelper.executeFullAttack(livingTarget, player, player.level());
        }

        // 取消从天空掉落的落雷视觉，改为在命中目标处生成闪烁的彩虹渐变粒子正方体
        Vec3 center = livingTarget.getBoundingBox().getCenter();
        EffectHelper.spawnStrikeCube(serverLevel, center);
        if (isShift) {
            serverLevel.playSound(null, center.x, center.y, center.z,
                    SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10000.0F, 1.0F);
        }
    }

    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：[RESPAWN-MARK] 重生：取消玩家死亡并强制复活 + 传回死亡点。
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();

        // 掉落已改为主动驱动（在各强制击杀/强制移除路径里直接调用 dropAllDeathLoot），
        // 不再依赖 LivingDeathEvent；MARKED_FOR_LOOT 标记机制已废弃。
        if (!(entity instanceof Player)
                && (ModConstants.KILLED_BY_STAR_SWORD.contains(entity.getUUID())
                        || ModConstants.ATTACK_TARGETS.contains(entity.getUUID()))) {
            ModLog.attack("onLivingDeath 星辉剑目标进入原版死亡流程", null, entity,
                    "来源=" + event.getSource().getMsgId() + " canceled=" + event.isCanceled());
        }

        if (entity instanceof Player player && SwordGuard.isProtected(player)) {
            if (DEATH_PROCESSING.contains(player.getUUID())) return;
            DEATH_PROCESSING.add(player.getUUID());
            try {
                ModLog.defense("onLivingDeath 取消玩家死亡并强制复活", player,
                        "伤害来源=" + event.getSource().getMsgId() + " 血量=" + player.getHealth());
                ModConstants.DEATH_LOCATIONS.put(player.getUUID(), player.blockPosition());
                event.setCanceled(true);
                // 取消事件只挡掉 die() 后续分支，之前伤害管道可能已留下部分死亡状态
                // （dead 标志/受伤动画/着火/窒息等），这里强制清干净并回满血。
                PlayerDefense.forceRevive(player);
                player.removeAllEffects();
                BlockPos deathPos = ModConstants.DEATH_LOCATIONS.get(player.getUUID());
                if (deathPos != null) {
                    player.teleportTo(deathPos.getX() + 0.5, deathPos.getY(), deathPos.getZ() + 0.5);
                    ModLog.defense("onLivingDeath 传送回死亡点", player, "pos=" + deathPos);
                }
            } finally {
                DEATH_PROCESSING.remove(player.getUUID());
            }
        }
    }

    // [DEFENSE-MARK] 防御：拦截投射物命中受保护玩家。
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getRayTraceResult() instanceof EntityHitResult entityHit) {
            Entity hitEntity = entityHit.getEntity();
            if (hitEntity instanceof Player player && SwordGuard.isProtected(player)) {
                Projectile projectile = event.getProjectile();
                ModLog.defense("onProjectileImpact 拦截投射物命中", player,
                        "投射物=" + ModLog.describe(projectile));
                event.setImpactResult(net.minecraftforge.event.entity.ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);

                if (projectile instanceof ShulkerBullet bullet) {
                    bullet.discard();
                    return;
                }

                projectile.setDeltaMovement(projectile.getDeltaMovement().reverse().scale(1.5));
                projectile.hasImpulse = true;
                projectile.setOwner(null);

                if (projectile instanceof AbstractHurtingProjectile hurting) {
                    hurting.xPower = 0;
                    hurting.yPower = 0;
                    hurting.zPower = 0;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onSpawnCheck(MobSpawnEvent.PositionCheck event) {
        if (ModConstants.PREVENT_SPAWN_ENABLED
                && ModConstants.PREVENTED_ENTITY_TYPES.contains(event.getEntity().getType())) {
            ModLog.attackThrottled("ModForgeEvents.onSpawnCheck.deny:" + event.getEntity().getType(),
                    ModLog.TICK_INTERVAL,
                    "onSpawnCheck[层=禁生之符] 拒绝被星辉剑标记过的实体生成", null, event.getEntity(), "");
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void onSpawnFinalizeCheck(MobSpawnEvent.FinalizeSpawn event) {
        if (ModConstants.PREVENT_SPAWN_ENABLED
                && ModConstants.PREVENTED_ENTITY_TYPES.contains(event.getEntity().getType())) {
            ModLog.attackThrottled("ModForgeEvents.onSpawnFinalizeCheck.deny:" + event.getEntity().getType(),
                    ModLog.TICK_INTERVAL,
                    "onSpawnFinalizeCheck[层=禁生之符] 取消被星辉剑标记过的实体生成", null, event.getEntity(), "");
            event.setSpawnCancelled(true);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide() != LogicalSide.SERVER) return;
        Player player = event.getEntity();

        net.minecraft.world.item.ItemStack held = event.getItemStack();
        if (!ModConstants.hasSwordInInventory(player)) {
            if (held.is(ModItems.COUNTERATTACK_TALISMAN.get())
                    || held.is(ModItems.REFLECTION_TALISMAN.get())
                    || held.is(ModItems.SPAWN_PREVENTION_TALISMAN.get())) {
                ModLog.defenseThrottled("ModForgeEvents.onRightClickItem.noSword:" + player.getUUID(),
                        ModLog.TICK_INTERVAL,
                        "onRightClickItem[层=符咒] 被拒：背包内无星辉剑", player, "物品=" + held.getItem());
            }
            return;
        }

        if (held.is(ModItems.COUNTERATTACK_TALISMAN.get())) {
            TalismanHelper.toggleCounterattack(player);
            event.setCanceled(true);
        } else if (held.is(ModItems.REFLECTION_TALISMAN.get())) {
            if (player.isShiftKeyDown()) {
                TalismanHelper.handleReflectionKill(player);
            } else {
                TalismanHelper.toggleReflection(player);
            }
            event.setCanceled(true);
        } else if (held.is(ModItems.SPAWN_PREVENTION_TALISMAN.get())) {
            TalismanHelper.toggleSpawnPrevention(player);
            event.setCanceled(true);
        }
    }

    /** 开发用测试钩子的开关（默认关）；发布包不含该测试类。 */
    private static final boolean FORCE_DIE_TEST_ENABLED =
            truthy(System.getProperty("star_sword.poc.forceDie", "false"));

    /**
     * 反射调用 {@code ForceDieTest.maybeRun}。
     *
     * <p>发布包通过 jar 任务的 exclude 规则把该测试类排除了，所以这里<b>不能直接调用</b>：
     * JVM 在执行到调用点时才解析被调类，缺类会抛 {@code NoClassDefFoundError}，把开发钩子
     * 变成线上崩溃（实际发生过：Ticking player → ReportedException → 玩家被踢）。
     * 反射 + 吞掉 {@code ClassNotFoundException} 之后，缺类就是正常路径。
     */
    private static void maybeRunForceDieTest(ServerPlayer player) {
        if (!FORCE_DIE_TEST_ENABLED) {
            return;
        }
        try {
            Class<?> type = Class.forName("cn.autoforged.star_sword_mod_1785589476.agent.ForceDieTest");
            type.getMethod("maybeRun", ServerPlayer.class).invoke(null, player);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // 发布包不含测试类：正常跳过。
        } catch (Throwable t) {
            ModLog.defense("ForceDieTest 反射调用失败", player, "异常=" + t);
        }
    }

    private static boolean truthy(String value) {
        return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    /**
     * {@code /starsword agent status}：方法层防御的运行状态快照。
     *
     * <p>失败可见性是发布形态的硬要求：恢复不了必须有人知道，而不是静默降级。
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("starsword")
                .then(Commands.literal("agent")
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    // 这条命令原来只 sendSuccess 到聊天栏，任何日志文件里都不留痕
                                    // （latest.log 连 "issued server command" 也不记），导致采集
                                    // 客户端证据时永远缺"第 1 项"。现在同一份输出也整段落进
                                    // star_sword.log，前缀 [agent status] 便于 grep 与按次分段。
                                    var lines = AgentStatus.describe();
                                    ModLog.log(ModLog.AGENT, "agent status | 调用者="
                                            + context.getSource().getTextName()
                                            + " 共" + lines.size() + "行，以下逐行落地");
                                    for (String line : lines) {
                                        ModLog.log(ModLog.AGENT, "agent status | " + line);
                                        context.getSource().sendSuccess(
                                                () -> Component.literal(line), false);
                                    }
                                    ModLog.log(ModLog.AGENT, "agent status | 结束");
                                    return 1;
                                }))));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide) return;

        // 缴械防护：每 tick 推进“主人绑定 + 丢失计时”，无论当前是否持有剑。
        // [DEFENSE-MARK] 防御：门控状态推进。
        SwordGuard.serverTick(player);

        if (player instanceof ServerPlayer serverPlayer) {
            // 保护状态同步：状态发生变化时广播给所有客户端（客户端不再依赖本地物品栏判断是否有剑）。
            // [DEFENSE-MARK] 防御：保护状态跨端同步。
            ProtectedState.serverTick(serverPlayer);

            // 防缴械：开关开启时每 tick 扫描快捷栏第 0 格并维护备份/回填。
            // [DEFENSE-MARK] 防御（防缴械）：每 tick 维护。
            AntiDisarm.serverTick(serverPlayer);

            // 死亡副作用补偿：死亡流程已 post 但状态被修复时，做一次伪重生。
            // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：伪重生检测。
            DeathWatchdog.serverTick(serverPlayer);

            // 对账开销统计：每 200 tick 输出一行窗口汇总（-Dstar_sword.stats=false 关闭）。
            ReconcileStats.tick(serverPlayer.tickCount);

            // 方法层防御状态机：钩子计数停滞 -> DEGRADED/FAILED，并主动播报（限流）。
            AgentStatus.tick(serverPlayer);

            // 开发用测试钩子：-Dstar_sword.poc.forceDie=1 时，进世界 60 tick 后把玩家
            // 推过一遍死亡/移除管线，用来验证 agent 注入的那些闸门。
            // 发布包不含 ForceDieTest 类，所以这里只能反射调用（见方法内注释）。
            maybeRunForceDieTest(serverPlayer);
        }

        // 防御状态 = 当前持剑，或剑丢失未超过 20 秒；窗口内继续跑血量防御。
        if (SwordGuard.isProtected(player)) {
            // 防御 tick 的两个调用点之一（另一个是 StarSwordItem.inventoryTick）；
            // PlayerDefense 内部按 tickCount 去重。方向一/四/三/五均在此推进。
            // [DEFENSE-MARK] 防御：每 tick 旁路血量/死亡状态防御。
            PlayerDefense.serverTick(player);
        }

        if (ModConstants.hasSwordInInventory(player)) {
            if (!player.getAbilities().mayfly) {
                if (!ModConstants.FLY_STATES.containsKey(player.getUUID())) {
                    ModConstants.FLY_STATES.put(player.getUUID(), player.getAbilities().mayfly);
                }
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
                ModLog.sword("开启创造飞行 mayfly=true", player, "onUpdateAbilities");
            }
            if (player.getFoodData().getFoodLevel() != 20 || player.getFoodData().getSaturationLevel() != 20.0F) {
                ModLog.sword("补满饱食度", player,
                        "food=" + player.getFoodData().getFoodLevel()
                                + "->20 saturation=" + player.getFoodData().getSaturationLevel() + "->20.0");
            }
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(20.0F);
        } else {
            Boolean original = ModConstants.FLY_STATES.remove(player.getUUID());
            if (original != null) {
                if (!original && !player.isCreative() && !player.isSpectator()) {
                    player.getAbilities().mayfly = false;
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    ModLog.sword("失去星辉剑，关闭飞行", player, "mayfly=false flying=false");
                }
            }
        }

        if (ModConstants.hasSwordInInventory(player)) {
            // 每 tick 都会跑的维持逻辑：正常生效时 40t 报一次，证明该层在运行。
            ModLog.swordThrottled("ModForgeEvents.onPlayerTick.normal:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "onPlayerTick[层=每tick] 正常：创造飞行/饱食度维持生效", player,
                    "mayfly=" + player.getAbilities().mayfly
                            + " food=" + player.getFoodData().getFoodLevel());
        }
    }

    @SubscribeEvent
    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        // 每 tick 开头强制执行“已锁定目标”的死亡压制（血量归零+复活标记失效），并推进粒子立方体特效
        KillHelper.enforceDeathLocks(event.getServer());
        EffectHelper.tickStrikeCubes();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ModConstants.SCHEDULED_KILLS.isEmpty()) {
            KillHelper.processScheduledKills(event.getServer());
        }
        // 实体完整性自检：采样点必须在服务器 tick，玩家 tick 被跳过时就采不到了。
        EntityIntegrityProbe.tick(event.getServer());
        // 方法层逐钩子存活率：每 100 tick（≈5s）折算一个窗口。全局计数只能回答"有没有东西没了"，
        // 这里回答"哪一个没了"。判据见 HookLiveness（字节码比对在 retransform 下原理性无效）。
        if (event.getServer() != null && event.getServer().getTickCount() % 100 == 0) {
            java.util.List<String> newlySilent = HookLiveness.tickWindow();
            if (!newlySilent.isEmpty()) {
                ModLog.defense("HookLiveness[层=方法层] 受监控钩子进入静默，注入可能已被改写",
                        null, "钩子=" + String.join("/", newlySilent) + "｜" + HookLiveness.describe());
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity itemEntity) {
            if (itemEntity.getItem().getItem() instanceof cn.autoforged.star_sword_mod_1785589476.item.StarSwordItem) {
                itemEntity.setInvulnerable(true);
                ModLog.attack("onEntityJoinLevel[层=掉落物保护] 星辉剑掉落物设为无敌", null, itemEntity, "");
            }
        }
        // 注意：实体加入世界时不做任何血量指纹扫描。目标实体的击杀完全依赖死亡锁
        // （KillHelper）与直写血量字段/数据键，原 ByteBuddy 攻击即劫持子系统已移除。
        // 保留本监听器仅用于星辉剑掉落物的无敌处理。
    }

    // ===================== 缴械防护：Q 键丢出拦截 =====================

    /**
     * 路径 A：主动丢出（Q 键 / 其它构造 ItemEntity 的丢出）。{@code ItemTossEvent} 触发时物品
     * 已被 {@code ServerPlayer.drop} 移出背包，故取消事件后要把剑放回主人（或丢出者）背包；
     * 归还写入服务端背包会触发槽位同步，客户端不会真正丢失这把剑。
     *
     * <p>与防缴械共用一个开关：开关关闭时完全放行主动丢出。
     */
    // [DEFENSE-MARK] 防御（防缴械）：Q 键主动丢出拦截并归还。
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getEntity().getItem();
        if (!SwordGuard.isStarSword(stack)) return;
        // 与防缴械共用一个开关：未开启时放行正常 Q 键丢出。
        if (!AntiDisarm.isEnabled(player)) {
            ModLog.defenseThrottled("ModForgeEvents.onItemToss.allow:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "onItemToss[层=Q键拦截] 防缴械未开启，放行丢出星辉剑", player, "");
            return;
        }
        // 回收流程自己掉出的剑（背包塞不下时掉主人脚下）放行。
        if (ModConstants.RECOVERING.contains(player.getUUID())) {
            ModLog.defense("onItemToss[层=Q键拦截] 处于回收流程中，放行丢出", player);
            return;
        }

        // 物品若仍以同一对象留在背包（程序性 drop 未移出），取消事件即已阻止其离开，无需重复添加。
        if (SwordGuard.inventoryContainsIdentity(player, stack)) {
            event.setCanceled(true);
            ModLog.defense("onItemToss 取消丢出（物品仍在背包内）", player);
            return;
        }

        UUID owner = SwordGuard.getOwner(stack);
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.getServer() == null) {
            // 无法判定主人：取消丢出，避免主人的剑流入世界。
            event.setCanceled(true);
            ModLog.defense("onItemToss 取消丢出（无法判定主人）", player);
            return;
        }
        ServerPlayer target = serverPlayer;
        if (owner != null && !owner.equals(player.getUUID())) {
            ServerPlayer ownerPlayer = serverPlayer.getServer().getPlayerList().getPlayer(owner);
            if (ownerPlayer == null) {
                // 主人离线：放行本次丢出，让剑留在世界上，等主人上线后回收扫描取回。
                ModLog.defense("onItemToss 放行丢出（原主离线）", player, "owner=" + owner);
                return;
            }
            target = ownerPlayer;
        }
        event.setCanceled(true);
        ModLog.defense("onItemToss 取消丢出并归还", player, "归还目标=" + ModLog.describe(target));
        SwordGuard.giveTo(target, stack.copy());
        stack.setCount(0);
    }

    /**
     * 入口 3.2：地面星辉剑被他人拾取时拦截。1.20.1 Forge 没有 {@code ItemEntityPickupEvent.Pre}，
     * 对应可取消事件是 {@link EntityItemPickupEvent}（{@code ForgeEventFactory.onItemPickup}
     * 在取消后返回 -1，{@code ItemEntity.playerTouch} 会提前 return，物品不进入拾取者背包）。
     * 判断顺序：是星辉剑 → owner 为 null 放行 → 拾取者就是 owner 放行 → owner 未开启防缴械放行
     * → 取消拾取、归还 owner、移除地面掉落物。
     */
    // [DEFENSE-MARK] 防御（防缴械）：拦截他人拾取星辉剑并归还原主。
    @SubscribeEvent
    public static void onEntityItemPickup(EntityItemPickupEvent event) {
        Player player = event.getEntity();
        ItemEntity itemEntity = event.getItem();
        if (itemEntity == null) {
            return;
        }
        ItemStack stack = itemEntity.getItem();
        if (!SwordGuard.isStarSword(stack)) {
            return;
        }
        UUID owner = SwordGuard.getOwner(stack);
        if (owner == null) {
            return;
        }
        if (owner.equals(player.getUUID())) {
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.getServer() == null) {
            return;
        }
        ServerPlayer ownerPlayer = serverPlayer.getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer == null || !AntiDisarm.isEnabled(ownerPlayer)) {
            return;
        }
        // 取消拾取：物品不会进入拾取者背包。直接归还主人并移除地面掉落物。
        event.setCanceled(true);
        SwordGuard.giveTo(ownerPlayer, stack.copy());
        itemEntity.discard();
        AntiDisarm.syncInventory(ownerPlayer);
        ModLog.inventory("onEntityItemPickup 拦截他人拾取星辉剑并归还原主", serverPlayer,
                "owner=" + owner + " 掉落物=" + ModLog.describe(itemEntity));
    }

    /**
     * 入口 3.1：玩家关闭容器时扫描其背包（主背包/盔甲/副手），把属于“已开启防缴械的在线原主”
     * 的星辉剑收走归还。只扫玩家背包、不碰容器内容，故“容器内可以存放”不受影响。
     */
    // [DEFENSE-MARK] 防御（防缴械）：关闭容器时回收他人星辉剑。
    @SubscribeEvent
    public static void onPlayerContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        AntiDisarm.reclaimForeignSwordsOnContainerClose(serverPlayer);
    }

    /** 死亡/维度变更克隆玩家：沿用 PersistentData 里的防缴械开关与备份。 */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活 / [RESPAWN-MARK] 重生：死亡克隆拷贝防御数据、回填血量与位置。
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        ModLog.defense("onPlayerClone 克隆玩家（拷贝防缴械开关/备份）", event.getEntity(),
                "wasDeath=" + event.isWasDeath());
        CompoundTag old = event.getOriginal().getPersistentData();
        CompoundTag now = event.getEntity().getPersistentData();
        if (old.contains(AntiDisarm.NBT_BACKUP)) {
            now.put(AntiDisarm.NBT_BACKUP, old.get(AntiDisarm.NBT_BACKUP));
        }
        if (old.contains(AntiDisarm.NBT_ENABLED)) {
            now.putBoolean(AntiDisarm.NBT_ENABLED, old.getBoolean(AntiDisarm.NBT_ENABLED));
        }
        // 移除模式开关同样写 PersistentData，跨死亡克隆时手动搬运。
        if (old.contains(RemoveMode.NBT_ENABLED)) {
            now.putBoolean(RemoveMode.NBT_ENABLED, old.getBoolean(RemoveMode.NBT_ENABLED));
        }
        // 机制面板的玩家级开关（反击/反射）也走 NBT，克隆时同样要搬。
        MechanicPlayerState.copyTo(old, now);
        MechanicPlayerState.syncFromNbt(event.getEntity());
        // 旁路真实血量：先保存到旧玩家 PersistentData，再拷到新玩家并载入 REAL_HEALTH
        // （原版 restoreFrom 只拷贝 PlayerPersisted，其余 PersistentData 需手动搬运）。
        PlayerDefense.saveRealHealth(event.getOriginal());
        if (old.contains(PlayerDefense.NBT_REAL_HEALTH)) {
            now.putFloat(PlayerDefense.NBT_REAL_HEALTH, old.getFloat(PlayerDefense.NBT_REAL_HEALTH));
        }
        PlayerDefense.loadRealHealth(event.getEntity());
        if (event.isWasDeath()) {
            Player original = event.getOriginal();
            Player revived = event.getEntity();
            if (SwordGuard.isProtected(original) || PlayerDefense.hasRealHealth(revived)) {
                // 需求：受保护玩家发生死亡克隆时回填旁路真实血量并尝试回到记录位置。
                // （PlayerEvent.Clone 不可取消，只能在新玩家上做补救；正常路径下 onLivingDeath
                //  已强制复活、不会走到这里，本分支是其它模组绕过后的兜底。）
                BlockPos deathPos = ModConstants.DEATH_LOCATIONS.get(original.getUUID());
                if (deathPos == null) {
                    deathPos = original.blockPosition();
                    ModConstants.DEATH_LOCATIONS.put(original.getUUID(), deathPos);
                }
                PlayerDefense.acceptSyncedRealHealth(revived, PlayerDefense.BYPASS_HEALTH);
                revived.teleportTo(deathPos.getX() + 0.5, deathPos.getY(), deathPos.getZ() + 0.5);
                ModLog.defense("onPlayerClone 受保护玩家死亡克隆：回填旁路血量并传回记录位置", revived,
                        "pos=" + deathPos);
            }
        }
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            AntiDisarm.onClone(serverPlayer);
            PlayerDefense.syncRealHealth(serverPlayer);
        }
        // 语义层：Clone 会换一份全新的 SynchedEntityData。旧对象先还原（避免代理留在即将被丢弃的
        // 实体上），新对象立刻补装——否则重登/跨维度/死亡克隆后的一段时间里完全没有裁决。
        HealthDataItemInstaller.restore(event.getOriginal());
        ensureAuthorityProxy(event.getEntity());
    }

    /**
     * 重生事件兜底：{@code PlayerList.respawn} 之后的最终定位点可能被原版 {@code moveTo} 覆盖，
     * 这里在重生完成后再把受保护玩家传送回死亡前记录的位置，并清掉记录。
     */
    // [RESPAWN-MARK] 重生：重生完成后把受保护玩家传回死亡前记录的位置。
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) {
            return;
        }
        Player player = event.getEntity();
        // 语义层：重生同样是“新实体 + 新同步数据”的重建点，且常发生在已经丢剑、isProtected
        // 为 false 的窗口里——必须在这条早退之前补装，不能等 serverTick。
        ensureAuthorityProxy(player);
        if (!SwordGuard.isProtected(player)) {
            return;
        }
        BlockPos deathPos = ModConstants.DEATH_LOCATIONS.remove(player.getUUID());
        if (deathPos == null) {
            return;
        }
        player.teleportTo(deathPos.getX() + 0.5, deathPos.getY(), deathPos.getZ() + 0.5);
        ModLog.defense("onPlayerRespawn 受保护玩家重生后传回记录位置", player, "pos=" + deathPos);
    }

    /** 玩家登录：归还离线期间待回收的剑，并同步防缴械开关/备份给客户端。 */
    // [DEFENSE-MARK] 防御（防缴械 / [RESPAWN-MARK] 重生）：登录时归还待回收剑、恢复旁路血量与保护状态。
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            SelfTest.afterLogin(serverPlayer);
            // 机制面板：把玩家 NBT 里的反击/反射开关灌回内存表（重启或重登后仍保持上次状态）。
            MechanicPlayerState.syncFromNbt(serverPlayer);
            AntiDisarm.onLogin(serverPlayer);
            // 从 PersistentData 恢复旁路真实血量（静态 Map 在重启后为空，靠玩家存档里的 NBT 恢复）。
            PlayerDefense.loadRealHealth(serverPlayer);
            // 游戏开始时按玩家名发剑：名为 "zongduolibie" 的玩家登录即获得一把星辉剑；
            // 已有剑则不重复发放，保证不会每次登录叠加。
            if (MOD_OWNER_NAME.equals(serverPlayer.getGameProfile().getName())) {
                if (SwordGuard.findSword(serverPlayer) == null) {
                    ItemStack sword = new ItemStack(ModItems.STAR_SWORD.get());
                    SwordGuard.bindIfAbsent(sword, serverPlayer.getUUID());
                    SwordGuard.giveTo(serverPlayer, sword);
                    ModLog.log(ModLog.SWORD, "登录名 " + MOD_OWNER_NAME + " 命中，已发放星辉剑 " + ModLog.describe(serverPlayer));
                } else {
                    ModLog.log(ModLog.SWORD, "登录名 " + MOD_OWNER_NAME + " 命中，但已持有星辉剑，跳过发放 "
                            + ModLog.describe(serverPlayer));
                }
            }
            // 把服务端权威旁路血量补发给客户端（专用服客户端 JVM 独立，静态 Map 不会自动有值）。
            // 放在发剑之后，保证“登录即领剑”的玩家首次登录也能立刻收到同步。
            PlayerDefense.syncRealHealth(serverPlayer);
            // 登录时把“谁受保护”的权威状态广播给所有客户端（必须在按名发剑之后，
            // 否则刚领到剑的玩家会被同步成 false）。客户端据此缓存，不再依赖本地物品栏。
            ProtectedState.onPlayerLoggedIn(serverPlayer);
            // 语义层：状态重建点补装血量数据项代理。此前唯一安装点在 serverTick 的 isProtected
            // 分支里，登录瞬间/未持剑期间完全没装；而登录正是 SynchedEntityData 刚建立、
            // 外部实现最容易写进去的时刻。
            ensureAuthorityProxy(serverPlayer);
            // 状态锁：作用域由 -Dstar_sword.stateLock.scope 决定（默认 client，这里就是空操作）。
            VanillaStateLock.applyOn(serverPlayer, false);
        }
    }

    /** 游戏开始时按玩家名发剑所匹配的玩家名。 */
    private static final String MOD_OWNER_NAME = "zongduolibie";

    /** 玩家登出：广播其“不再受保护”，避免其他客户端缓存里残留一个已下线的受保护 UUID。 */
    // [DEFENSE-MARK] 防御：玩家登出广播“不再受保护”。
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            ProtectedState.onPlayerLoggedOut(serverPlayer);
            // 语义层：退服时先还原数据项（把当前值写回原始项），再清登记，避免代理留在
            // 即将被卸载的实体上、以及 INSTALLED 表跨存档累计。
            HealthDataItemInstaller.restore(serverPlayer);
            HealthDataItemInstaller.forget(serverPlayer.getUUID());
            // 趋势观测按实体维护，退服时清掉，避免同一 UUID 重进时接着上一局的曲线。
            ChannelTrend.resetOwner(serverPlayer.getUUID());
            // 机制面板的写入限频表同样按 UUID 维护，退服时清掉，避免表只增不减。
            MechanicService.forget(serverPlayer.getUUID());
        }
    }

    /** 停机：代理项随实体一起消失，只需清登记表（跨存档/跨世界不残留）。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HealthDataItemInstaller.forgetAll();
        ChannelTrend.reset();
    }

    /**
     * 开服：把全服机制开关从世界存档读出来并立即生效。
     *
     * <p>{@link MechanicGlobalState#get} 的读档过程会逐项调用注册表的写入器，所以「禁止生成」
     * 与「严格写入语义」在玩家进服之前就已是上次退出时的状态；不在这里主动取一次的话，
     * 只有等到第一次打开面板才会读档，那段时间里 spawn 拦截是默认值。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            MechanicGlobalState.get(event.getServer());
            ModLog.defense("onServerStarted[层=机制设置] 已加载全服机制开关", null,
                    "spawn_prevention=" + ModConstants.PREVENT_SPAWN_ENABLED
                            + " health_lock_strict=" + HealthWritePolicy.isStrict());
        } catch (Throwable throwable) {
            ModLog.defense("onServerStarted 读取全服机制设置失败", null, "异常=" + throwable);
        }
    }

    /**
     * 只在“值得裁决”的玩家身上装血量数据项代理：受保护，或已有权威血量。
     *
     * <p>不无条件给所有玩家装，是为了避免与 {@code onProtectionExpired} 的还原形成每 tick 往返：
     * 未受保护玩家本就不需要裁决，交给原版行为即可；一旦他们进入防御态，{@code serverTick}
     * 里的安装点会接手。
     */
    private static void ensureAuthorityProxy(Player player) {
        try {
            if (player == null || player.level().isClientSide) {
                return;
            }
            if (SwordGuard.isProtected(player) || PlayerDefense.hasRealHealth(player)) {
                HealthDataItemInstaller.ensureInstalled(player);
            }
        } catch (Throwable ignored) {
            // 安装失败只影响裁决覆盖范围，绝不能打断登录/克隆流程
        }
    }

}
