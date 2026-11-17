package cn.autoforged.star_sword_mod_1785589476.hijack;

import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【已停用 · 仅作保留的核心代码】复活状态压制逻辑。
 *
 * <p>本类原来是 ByteBuddy 运行时劫持子系统（职责 B）的一部分：{@code HijackScanner} 对每个被
 * 星辉剑攻击到的实体类做 retransform，{@code HealthHijackInterceptor}（setHealth 方法体替换）
 * 与 {@code ReviveGuardAdvice}（tick 开头内联守卫）调用这里把 SynchedEntityData 里代表
 * “复活次数 / 复活计时”的数据项清零，并配合血量清零彻底封堵目标的复活逻辑。
 *
 * <p>此后为降低维护成本，ByteBuddy（含 {@code HijackScanner}/{@code HijackTransformer}/
 * {@code BuddyAgent} 与各 Advice 类）已整体移除；本类**不再被任何运行路径调用**，仅作为
 * “曾经有这一功能”的核心实现保留，未来若重新引入字节码劫持可直接接回。真正杀敌现在依赖
 * {@code KillHelper} 的死亡锁 + {@code ReflectionHelper.setHealthDirectly/scanAndZeroFields}。
 *
 * <p>直接读写 {@link SynchedEntityData} 内部数据项的辅助（用于运行时劫持的检测与压制）。
 *
 * <p>SynchedEntityData 在 1.20.1 用一个字段（Int2ObjectMap）保存全部已定义的数据项
 * （mc-src SynchedEntityData.java:31，Mojmap 名 itemsById；SRG 名由 ReflectionHelper 多候选解析），
 * 每个项为 {@code DataItem}，可通过公共方法 getValue()/setValue()/getAccessor() 访问。
 * 字段名必须按运行环境区分（生产用 SRG 名），统一走 ReflectionHelper 解析。
 *
 * <p>设计约定：
 * - 只在实体"血量已归零"的死亡窗口内清复活数据，绝不触碰正常存活时的字段；
 * - 浮点阈值沿用既有 ReflectionHelper 的 (0, 2048] —— 既覆盖"4.0 / 小于 10 的复活次数
 *   浮点"，又避开 8000.0F 这类大数值血量键（真到死亡窗口血量键值也是 0，不受影响）。
 */
// ============================================================================
// [REVIVE-MARK] 复活：复活状态压制逻辑（已停用、保留的核心代码，下一步修改目标）。
// ============================================================================
public final class ReviveSuppression {

    /** 复活次数/计时浮点的"小正浮点"判断上界（含）。8000.0F 血量键在此范围之外。 */
    private static final float REVIVE_FLOAT_UPPER = 2048.0F;

    /**
     * {@code SynchedEntityData} 数据项表字段。字段名随运行环境/映射源不同（生产为 SRG 名，
     * 开发为 Mojmap 名 {@code itemsById}），统一由 {@link ReflectionHelper#itemsByIdField()}
     * 解析，避免写死单一名字在生产反射静默失败。
     */
    private static final Field ITEMS_BY_ID_FIELD = ReflectionHelper.itemsByIdField();

    private ReviveSuppression() {
    }

    /**
     * 每 tick 守卫（由 ReviveGuardAdvice 内联到目标 tick() 开头调用）：
     * 仅在服务端且血量已 ≤0 时清理复活数据，避免对正常存活的实体做无谓反射。
     */
    public static void suppressIfDead(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide) {
            return;
        }
        if (entity.getHealth() > 0.0F) {
            return;
        }
        clearReviveState(entity);
    }

    /**
     * 把 SynchedEntityData 里代表复活状态的项复位：
     * - 小正浮点（复活次数/计时，通常 4.0 或 <10）置 0；
     * - 布尔型（复活状态标记）置 false。
     * 通过 DataItem.setValue 直接改值，setValue 内部会置 dirty=true，由原版发包机制同步给客户端。
     */
    public static void clearReviveState(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        // 用该实体实际使用的血量数据键（可能为自定义键），不要写死父类 DATA_HEALTH_ID。
        EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor(entity);
        for (SynchedEntityData.DataItem<?> item : allItems(entity.getEntityData())) {
            try {
                Object value = item.getValue();
                EntityDataAccessor<?> accessor = item.getAccessor();
                if (accessor == healthKey) {
                    // 血量键交给 setHealth 拦截器统一处理，这里不动它。
                    continue;
                }
                if (value instanceof Float f) {
                    if (f > 0.0F && f <= REVIVE_FLOAT_UPPER) {
                        setRawValue(item, 0.0F);
                    }
                } else if (value instanceof Boolean b && b) {
                    setRawValue(item, Boolean.FALSE);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 检查 SynchedEntityData 中是否存在"当前值恰为 expected 的 Float 数据键"（血量指纹）。 */
    public static boolean hasPoolFloat(LivingEntity entity, float expected) {
        if (entity == null) {
            return false;
        }
        for (SynchedEntityData.DataItem<?> item : allItems(entity.getEntityData())) {
            Object value = item.getValue();
            if (value instanceof Float f && f == expected) {
                return true;
            }
        }
        return false;
    }

    /** 读取 SynchedEntityData 内部全部数据项（失败时返回空表，绝不让反射异常上抛）。 */
    public static List<SynchedEntityData.DataItem<?>> allItems(SynchedEntityData data) {
        if (data == null || ITEMS_BY_ID_FIELD == null) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<Integer, SynchedEntityData.DataItem<?>> itemsById =
                    (Map<Integer, SynchedEntityData.DataItem<?>>) ITEMS_BY_ID_FIELD.get(data);
            if (itemsById == null || itemsById.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(itemsById.values());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setRawValue(SynchedEntityData.DataItem item, Object value) {
        item.setValue(value);
    }
}
