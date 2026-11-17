package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import cn.autoforged.star_sword_mod_1785589476.helper.RawState;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * 血量语义的<b>单一出口</b>（语义层）。移植自星-穹工程 {@code HealthController} + {@code HealthProxy}
 * 的职责划分，收敛成本工程的三个入口。
 *
 * <p>所有「对外可见的血量」只有两条路：{@link #read}（我们内部用）与数据项代理里的
 * {@link #renderRead}/{@link #renderWrite}（游戏与其它模组用）。两条路都先查
 * {@link AuthorityHealthStore}，查不到才退回原版数据项——<b>不再直接读 {@code getHealth()}</b>，
 * 因为那个方法体可能已经被外部实现整体替换。
 *
 * <h2>为什么读原始数据项而不是 getHealth</h2>
 * <p>{@code LivingEntity.getHealth()} 在 1.20.1 就是 {@code entityData.get(DATA_HEALTH_ID)}；
 * 外部实现替换的是这个<b>方法</b>，数据项本身还是服务端同步下来的值。直读字段（{@link RawState}）
 * 连 {@code SynchedEntityData.get} 都绕开，因此外部模组把 getter 一起换掉也读不到假值。
 *
 * <p><b>Never throws</b>：全部 try/catch，异常时返回中性值（{@code NaN} / 原值）。
 */
public final class HealthAuthority {

    /**
     * 最近一次观察到的「合法」血量（按 UUID）。
     *
     * <p>用途：外部把算式结果（负数 / NaN / 无穷）写进血量项时，读取侧不能把它当数值用，
     * 也不能净化成 0（0 在死亡语义里就是"死"）。回退到这个记录，让客户端/其它模组看到的
     * 仍然是最后一次真实值。只记正数，0 表示确实死亡、不作为基线。
     */
    private static final java.util.Map<UUID, Float> LAST_GOOD =
            new java.util.concurrent.ConcurrentHashMap<>();

    private HealthAuthority() {
    }

    /** 清掉某实体的"最近合法值"（退服 / 实体移除时调用，避免跨存档残留）。 */
    public static void forget(UUID id) {
        try {
            if (id != null) {
                LAST_GOOD.remove(id);
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 清空全部"最近合法值"（停机用）。 */
    public static void forgetAll() {
        try {
            LAST_GOOD.clear();
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /**
     * 该玩家的权威值是否<b>可用</b>：记录存在、未退役、有限且 &gt; 0。
     *
     * <p>{@code PlayerDefense} 的既有契约是「记录存在 ≠ 记录权威」：保护到期时会保留记录并只标记
     * {@code RETIRED}。权威层如果只看「存在」，退役值就会无限期压住死亡判定与写入——表现为
     * 血量 0 却不死、{@code deathTime} 冻结。判据集中在 {@code PlayerDefense.isRetired} 一处。
     */
    public static boolean isAuthoritative(UUID id) {
        try {
            if (id == null || PlayerDefense.isRetired(id)) {
                return false;
            }
            Float value = AuthorityHealthStore.get(id);
            return value != null && !Float.isNaN(value) && !Float.isInfinite(value) && value > 0.0F;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * 权威血量：有锁定值用锁定值，否则用原版数据项；两者都不可用时返回 {@code NaN}。
     *
     * <p>刻意不调用 {@link LivingEntity#getHealth()}（可能已被替换），只在数据项也读不到时，
     * 才由调用方决定是否退回 getHealth。
     */
    public static float read(LivingEntity self) {
        if (self == null) {
            return Float.NaN;
        }
        try {
            if (isAuthoritative(self.getUUID())) {
                Float locked = AuthorityHealthStore.get(self.getUUID());
                if (locked != null) {
                    return locked;
                }
            }
            EntityDataAccessor<Float> key =
                    cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper.healthDataAccessor();
            float raw = RawState.rawFloatOf(self, key);
            if (Float.isNaN(raw)) {
                // 数据项还没就绪：不给读数，由调用方决定是否退回 getHealth。
                return Float.NaN;
            }
            return resolveRead(self, raw);
        } catch (Throwable throwable) {
            return Float.NaN;
        }
    }

    /** 数据项代理读取路径：有可用权威值时返回权威值，否则按读取策略裁决底层值。 */
    public static float renderRead(LivingEntity self, float base) {
        try {
            if (self == null) {
                return base;
            }
            if (isAuthoritative(self.getUUID())) {
                Float locked = AuthorityHealthStore.get(self.getUUID());
                if (locked != null) {
                    return locked;
                }
            }
            return resolveRead(self, base);
        } catch (Throwable throwable) {
            return base;
        }
    }

    /**
     * 读取侧的最终裁决：非法读数（负数 / NaN / 无穷）不当作血量，回退最近一次合法值。
     *
     * <p>这是「算式把负数写进血量」这条攻击的落点：负血如果被净化成 0，客户端依旧会判死
     * （原版 {@code isDeadOrDying()} 就是 {@code <= 0}）。判据与回退顺序集中在
     * {@link cn.autoforged.star_sword_mod_1785589476.helper.HealthReadPolicy}。
     */
    private static float resolveRead(LivingEntity self, float raw) {
        UUID id = self.getUUID();
        Float lastGood = LAST_GOOD.get(id);
        float resolved = cn.autoforged.star_sword_mod_1785589476.helper.HealthReadPolicy
                .resolve(raw, null, lastGood, maxHealthOf(self));
        if (cn.autoforged.star_sword_mod_1785589476.helper.HealthReadPolicy.shouldRecordLastGood(raw)) {
            LAST_GOOD.put(id, raw);
        }
        return resolved;
    }

    /**
     * 数据项代理写入路径：锁定值存在时，非法值 / 低于锁定值的写入一律被替换为锁定值。
     *
     * <p>与母本 {@code HealthWriteGuard.shouldBlockWrite} 的判定同源：<b>严格小于</b>才拦——
     * 把 {@code <=} 改成 {@code <=} 会连原版「血量归零 → 死亡」的收尾流程一起拦住，
     * 制造出打不死的卡死实体（星-穹资产清单把它列为「不得改动」的契约）。
     */
    public static float renderWrite(LivingEntity self, float incoming) {
        try {
            if (self == null) {
                return incoming;
            }
            Float locked = isAuthoritative(self.getUUID())
                    ? AuthorityHealthStore.get(self.getUUID())
                    : null;
            return cn.autoforged.star_sword_mod_1785589476.helper.HealthWritePolicy
                    .resolve(incoming, locked, currentStored(self), maxHealthOf(self));
        } catch (Throwable throwable) {
            return incoming;
        }
    }

    /** 数据项里当前存着的值（直读字段）；读不到返回 {@code NaN}。 */
    private static float currentStored(LivingEntity self) {
        try {
            EntityDataAccessor<Float> key =
                    cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper.healthDataAccessor();
            return RawState.rawFloatOf(self, key);
        } catch (Throwable throwable) {
            return Float.NaN;
        }
    }

    /** 血量上限；取不到时返回 {@code NaN}（策略层据此跳过上限夹取）。 */
    private static float maxHealthOf(LivingEntity self) {
        try {
            return self == null ? Float.NaN : self.getMaxHealth();
        } catch (Throwable throwable) {
            return Float.NaN;
        }
    }

    /**
     * 强制写入权威血量：先更新权威存储，再直写数据项字段（绕过任何 {@code set} 拦截），
     * 最后经 {@code SynchedEntityData.set} 标脏，让客户端也能收到。
     *
     * @return true 表示至少有一条路径成功
     */
    public static boolean write(LivingEntity self, float value) {
        if (self == null) {
            return false;
        }
        boolean stored = false;
        try {
            AuthorityHealthStore.set(self.getUUID(), value);
            stored = AuthorityHealthStore.has(self.getUUID());
        } catch (Throwable ignored) {
            // 继续尝试直写
        }
        boolean rawWritten = false;
        try {
            EntityDataAccessor<Float> key =
                    cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper.healthDataAccessor();
            if (key != null) {
                rawWritten = RawState.writeRawFloat(self, key, value);
            }
        } catch (Throwable ignored) {
            // 继续
        }
        return stored || rawWritten;
    }

    /** 非法值净化：{@code NaN}/无穷/负数 → 0（原版语义下这就是「已死」，不再制造僵尸态）。 */
    private static float sanitize(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0.0F) {
            return 0.0F;
        }
        return value;
    }

    /** 一行诊断，供状态命令与启动横幅使用。 */
    public static String describe() {
        try {
            return AuthorityHealthStore.describe();
        } catch (Throwable throwable) {
            return "unavailable";
        }
    }
}
