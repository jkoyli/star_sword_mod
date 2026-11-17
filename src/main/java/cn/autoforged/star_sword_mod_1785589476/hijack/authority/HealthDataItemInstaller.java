package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.RawState;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 安装 / 还原血量数据项代理。移植自星-穹工程 {@code DataItemReplacer}。
 *
 * <p>职责严格单一：只做「把某实体的血量 {@code DataItem} 换成 {@link HealthDataItemProxy}」和
 * 「装回去」。不决定策略、不订阅事件、不判「什么时候该装」——那是调用方（
 * {@code PlayerDefense.serverTick} 与客户端 tick）的事。
 *
 * <p>装过一次的实体会记在 {@link #INSTALLED} 里，因此每 tick 调用是 O(1) 的
 * {@code ConcurrentHashMap.containsKey}；实体被移除/换世界时由 {@link #forget(UUID)} 清掉。
 *
 * <p>装过之后每次调用都**重新校验**「这个实体当前的血量数据项是不是代理项」，而不是按 UUID 记忆：
 * 玩家死亡重生 / 换维度会新建 {@code ServerPlayer} 与全新的 {@code SynchedEntityData}，UUID 却不变；
 * 按 UUID 缓存会让新实体上永远不再装代理（整套收口静默失效）。
 *
 * <p><b>Never throws</b>：失败原因每玩家只记一次日志（静默失败正是最糟糕的形态）。
 */
public final class HealthDataItemInstaller {

    /** 已经装过代理的玩家 UUID（只用于避免重复反射查找）。 */
    private static final Map<UUID, Boolean> INSTALLED = new ConcurrentHashMap<>();

    /** 安装失败过的玩家：只记一次日志，避免每 tick 刷屏。 */
    private static final Map<UUID, Boolean> FAILED_ONCE = new ConcurrentHashMap<>();

    private HealthDataItemInstaller() {
    }

    /**
     * 幂等安装：已经装过直接返回 {@code true}。
     *
     * @return true 表示该 player 的血量数据项当前是代理项（本次装上或之前已装）
     */
    public static boolean ensureInstalled(LivingEntity player) {
        if (player == null) {
            return false;
        }
        try {
            EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor();
            if (healthKey == null) {
                fail(player, "原版血量数据键解析失败（ReflectionHelper.healthDataAccessor() == null）");
                return false;
            }
            if (RawState.dataOf(player) == null) {
                fail(player, "Entity.entityData 反射不可用；RawState 缺失字段=" + RawState.describeMissing());
                return false;
            }
            // 换装必须在 SynchedEntityData 自己的写锁内做：itemsById 不是并发容器，
            // 原版的 set/assignValues/get 全靠这把锁互斥（1.20.1：lock = f_135346_）。
            return underItemsLock(player, () -> installLocked(player, healthKey));
        } catch (Throwable throwable) {
            fail(player, "安装异常=" + throwable);
            return false;
        }
    }

    /** 已持写锁的安装主体：表在锁内<b>重新读取</b>，避免与并发替换产生 TOCTOU。 */
    private static boolean installLocked(LivingEntity player, EntityDataAccessor<Float> healthKey) {
        try {
            Map<Integer, SynchedEntityData.DataItem<?>> items = RawState.itemsOf(player);
            if (items == null) {
                fail(player, "itemsById 反射不可用；RawState 缺失字段=" + RawState.describeMissing());
                return false;
            }
            SynchedEntityData.DataItem<?> current = items.get(healthKey.getId());
            if (current == null) {
                fail(player, "同步数据表里没有 id=" + healthKey.getId() + " 的项");
                return false;
            }
            if (current instanceof HealthDataItemProxy) {
                INSTALLED.put(player.getUUID(), Boolean.TRUE);
                return true;
            }
            items.put(healthKey.getId(), new HealthDataItemProxy(player, current));
            INSTALLED.put(player.getUUID(), Boolean.TRUE);
            FAILED_ONCE.remove(player.getUUID());
            // 每个玩家只打一次：这是"数据项代理真的装上了"的唯一直接证据（否则只能靠"没报错"推断）。
            ModLog.log(ModLog.HEALTH, "血量数据项已换成代理项：读/写/发包三处收口 player="
                    + player.getName().getString() + " id=" + healthKey.getId());
            dumpFloatChannels(player, healthKey.getId(), absorptionChannelId());
            return true;
        } catch (Throwable throwable) {
            fail(player, "安装异常=" + throwable);
            return false;
        }
    }

    // ===================== 原版写锁（SynchedEntityData.lock / f_135346_） =====================

    /** {@code SynchedEntityData.lock}：原版用它保护 {@code itemsById} 的全部读写。 */
    private static final Field ITEMS_LOCK =
            ReflectionHelper.findFieldCompat(SynchedEntityData.class, "f_135346_", "lock");

    /** 是否拿到了原版写锁（诊断用：false 表示退回无锁换装）。 */
    public static boolean lockAvailable() {
        return ITEMS_LOCK != null;
    }

    /**
     * 在 {@code SynchedEntityData} 的写锁内执行一次操作；拿不到锁字段时退化为直接执行。
     *
     * <p>为什么必须是<b>同一把</b>锁：原版 {@code set} 在写锁下"先取项、再写值"，若我们在两者之间
     * 把表里的项换成代理，它会写进即将被丢弃的旧项——这次写入就丢了；{@code assignValues} 在写锁下
     * 遍历这张表，我们并发 put 还可能让它看到结构性改动。用 {@code synchronized} 不行：那是另一把锁，
     * 与原版互不排除。
     */
    private static boolean underItemsLock(LivingEntity player, java.util.function.Supplier<Boolean> action) {
        ReadWriteLock lock = lockOf(player);
        if (lock == null) {
            return action.get();
        }
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static ReadWriteLock lockOf(LivingEntity player) {
        if (ITEMS_LOCK == null) {
            return null;
        }
        try {
            SynchedEntityData data = RawState.dataOf(player);
            if (data == null) {
                return null;
            }
            Object value = ITEMS_LOCK.get(data);
            return value instanceof ReadWriteLock readWriteLock ? readWriteLock : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * 一次性把玩家身上所有 Float 同步项列出来。
     *
     * <p>为什么需要：日志里的 `id=` 不是原版常量——Forge 的 id 池按继承链单调递增，任何多定义几个
     * 同步项的模组都会把后续 id 整体推移（实测血量键=14、吸收量=41）。没有这张清单，
     * "id=8 被中和"这类行只能靠猜是谁的通道。
     */
    private static void dumpFloatChannels(LivingEntity player, int healthId, int absorptionId) {
        try {
            Map<Integer, SynchedEntityData.DataItem<?>> items = RawState.itemsOf(player);
            if (items == null || items.isEmpty()) {
                return;
            }
            EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor();
            EntityDataAccessor<?> absorptionKey = ForeignChannelGuard.absorptionAccessor();
            StringBuilder floats = new StringBuilder();
            for (Map.Entry<Integer, SynchedEntityData.DataItem<?>> entry : items.entrySet()) {
                Object value;
                try {
                    value = entry.getValue() == null ? null : entry.getValue().getValue();
                } catch (Throwable t) {
                    value = "<?>";
                }
                if (!(value instanceof Float)) {
                    continue;
                }
                if (floats.length() > 0) {
                    floats.append(", ");
                }
                EntityDataAccessor<?> slot = entry.getValue() == null ? null : entry.getValue().getAccessor();
                // 标注"这一项是不是我们认定的血量/吸收键"，判据是 **accessor 对象身份**，不是 id。
                String tag = slot == healthKey ? "/血量键"
                        : (absorptionKey != null && slot == absorptionKey ? "/吸收键" : "");
                floats.append(entry.getKey()).append('=').append(value)
                        .append('(').append(serializerName(slot)).append(tag).append(')');
            }
            ModLog.log(ModLog.HEALTH, "同步项清单 player=" + player.getName().getString()
                    + " 血量键=" + healthId + " 吸收键=" + absorptionId
                    + " Float项=[" + floats + "]");
            logHealthKeySelfCheck(player, items, healthId, healthKey);
        } catch (Throwable ignored) {
            // 纯诊断，失败不影响安装
        }
    }

    /**
     * 血量键自检：回答"我们认定的血量键，是不是这个实体真正在读的那一项"。
     *
     * <p>四项证据，少一项都不足以定性（这也是被用户追问过一轮之后补的）：
     * <ul>
     *   <li>{@code 字段=}：反射命中的静态字段名（生产 {@code f_20961_} / 开发 {@code DATA_HEALTH_ID}）；</li>
     *   <li>{@code 同一对象=}：槽位里的 accessor 与字段值是否<b>同一个对象</b>——这是唯一能排除
     *       "字段被别的模组换成了另一个 accessor"的判据；</li>
     *   <li>{@code 类型=}：该槽位的 serializer（血量必须是 FLOAT）；</li>
     *   <li>{@code raw=} / {@code getHealth=}：槽位原始值与 {@code getHealth()} 是否一致。
     *       注意 {@code getHealth()} 可能已被 agent 整体替换成权威值，所以"不一致"只说明两侧读的不是同一个
     *       来源，要配合"同一对象"一起看。</li>
     * </ul>
     */
    private static void logHealthKeySelfCheck(LivingEntity player,
                                              Map<Integer, SynchedEntityData.DataItem<?>> items,
                                              int healthId, EntityDataAccessor<Float> healthKey) {
        try {
            SynchedEntityData.DataItem<?> healthItem = items.get(healthId);
            EntityDataAccessor<?> slotAccessor = healthItem == null ? null : healthItem.getAccessor();
            boolean sameObject = slotAccessor != null && slotAccessor == healthKey;
            float raw = healthKey == null ? Float.NaN : RawState.rawFloatOf(player, healthKey);
            float health = player.getHealth();
            boolean equal = !Float.isNaN(raw) && Math.abs(raw - health) < 1.0E-3F;
            ModLog.log(ModLog.HEALTH, "血量键自检 player=" + player.getName().getString()
                    + " 实体=" + player.getClass().getSimpleName()
                    + " 字段=" + ReflectionHelper.healthFieldName()
                    + " id=" + healthId
                    + " 反射accessor=#" + System.identityHashCode(healthKey)
                    + " 槽位accessor=#" + System.identityHashCode(slotAccessor)
                    + " 同一对象=" + sameObject
                    + " 类型=" + serializerName(slotAccessor)
                    + " raw=" + raw + " getHealth=" + health
                    + " 差=" + (Float.isNaN(raw) ? "?" : Math.abs(raw - health))
                    + " 判定=" + (sameObject && equal ? "一致"
                            : sameObject ? "同对象但读数不同（getHealth 被接管？）"
                            : "不一致——血量键可能认错"));
        } catch (Throwable ignored) {
            // 诊断失败静默
        }
    }

    /** serializer 的注册名（诊断用）：转调 RawState，避免两处各写一套映射。 */
    private static String serializerName(EntityDataAccessor<?> accessor) {
        return RawState.serializerName(accessor);
    }

    private static int absorptionChannelId() {
        EntityDataAccessor<?> accessor = absorptionAccessor();
        return accessor == null ? Integer.MIN_VALUE : accessor.getId();
    }

    /** 每玩家只报一次失败原因：静默失败正是上一轮"代理没装上却看不出来"的根源。 */
    private static void fail(LivingEntity player, String reason) {        try {
            UUID id = player.getUUID();
            if (id != null && FAILED_ONCE.putIfAbsent(id, Boolean.TRUE) == null) {
                ModLog.log(ModLog.HEALTH, "数据项代理安装失败（每玩家只报一次）：" + reason);
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /**
     * 给「另一个原版浮点通道」（玩家吸收量）装上净化代理。
     *
     * <p>只做数值合法性收敛（NaN/无穷/负值 → 0，超大值截断），不覆盖合法写入——
     * 与血量代理的职责刻意分开：血量是权威值，吸收量是普通数值。
     *
     * @return true 表示该通道当前是净化代理
     */
    public static boolean ensureAbsorptionSanitized(Player player) {
        if (player == null) {
            return false;
        }
        try {
            EntityDataAccessor<?> absorption = absorptionAccessor();
            if (absorption == null) {
                return false;
            }
            Map<Integer, SynchedEntityData.DataItem<?>> items = RawState.itemsOf(player);
            if (items == null) {
                return false;
            }
            SynchedEntityData.DataItem<?> current = items.get(absorption.getId());
            if (current == null) {
                return false;
            }
            if (current instanceof SanitizedFloatItemProxy) {
                return true;
            }
            if (!(current.getValue() instanceof Float)) {
                return false;
            }
            items.put(absorption.getId(), new SanitizedFloatItemProxy(player, current));
            ModLog.log(ModLog.HEALTH, "吸收量通道已装净化代理：id=" + absorption.getId()
                    + " player=" + player.getName().getString());
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 原版吸收量访问器；解析失败返回 {@code null}。 */
    private static EntityDataAccessor<?> absorptionAccessor() {
        try {
            java.lang.reflect.Field field = ReflectionHelper.findFieldCompat(
                    Player.class, "f_36107_", "DATA_PLAYER_ABSORPTION_ID");
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof EntityDataAccessor<?> accessor ? accessor : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 还原成原始数据项（退服 / 清保护状态时用）。 */
    public static boolean restore(LivingEntity player) {
        if (player == null) {
            return false;
        }
        try {
            UUID id = player.getUUID();
            INSTALLED.remove(id);
            EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor();
            if (healthKey == null || RawState.dataOf(player) == null) {
                return false;
            }
            // 与安装同口径：换表项必须在原版写锁内，且锁内重新取项（并发下可能已被换过）。
            return underItemsLock(player, () -> restoreLocked(player, healthKey));
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 已持写锁的还原主体。 */
    private static boolean restoreLocked(LivingEntity player, EntityDataAccessor<Float> healthKey) {
        try {
            Map<Integer, SynchedEntityData.DataItem<?>> items = RawState.itemsOf(player);
            if (items == null) {
                return false;
            }
            SynchedEntityData.DataItem<?> current = items.get(healthKey.getId());
            if (!(current instanceof HealthDataItemProxy proxy)) {
                return false;
            }
            // 还原前把「代理里当前的值」写回原始项，避免还原后血量跳回安装时的旧值。
            proxy.delegate().setValue(proxy.getValue());
            items.put(healthKey.getId(), proxy.delegate());
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 忘记某玩家（实体移除 / 退服）。 */
    public static void forget(UUID id) {
        try {
            if (id != null) {
                INSTALLED.remove(id);
                FAILED_ONCE.remove(id);
                // 顺带清掉读取侧"最近合法值"，避免同一 UUID 复用/重进时带着上一份基线。
                HealthAuthority.forget(id);
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /**
     * 清空全部登记（服务器停机用）。
     *
     * <p>停机时代理项会随实体一起消失，不需要逐个还原；只需把静态登记清掉，否则同一 JVM
     * 里换存档/重开世界时会残留上一局的 UUID，{@link #isInstalled} 与诊断计数都会说谎。
     */
    public static void forgetAll() {
        try {
            INSTALLED.clear();
            FAILED_ONCE.clear();
            HealthAuthority.forgetAll();
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 当前已装代理的玩家数（诊断用）。 */
    public static int installedCount() {
        try {
            return INSTALLED.size();
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /** 某个玩家是否已装（诊断用，不触发安装）。 */
    public static boolean isInstalled(UUID id) {
        try {
            return id != null && INSTALLED.containsKey(id);
        } catch (Throwable throwable) {
            return false;
        }
    }
}
