package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

/**
 * 通用浮点数据项代理：只做<b>数值合法性收敛</b>，不改语义。
 *
 * <p>用于原版里"另一个浮点通道"（玩家吸收量）：它和血量一样是 Float 同步项，同样可能被外部写入
 * NaN / 无穷 / 负值 / 荒谬大数，从而影响按比例换算出来的读数。这里把写入收敛到
 * {@code [0, cap]}，读取原样返回。
 *
 * <p>与血量代理的区别：血量代理要经过权威存储裁决（可能返回锁定值），本类只做净化，
 * 因此不会覆盖任何合法写入。
 */
public final class SanitizedFloatItemProxy extends SynchedEntityData.DataItem<Float> {

    /** 上限：远高于原版任何合法吸收量，同时挡住荒谬值。 */
    private static final float DEFAULT_CAP = 1024.0F;

    private final LivingEntity owner;
    private final float cap;
    private final SynchedEntityData.DataItem<Float> delegate;

    @SuppressWarnings("unchecked")
    public SanitizedFloatItemProxy(LivingEntity owner, SynchedEntityData.DataItem<?> original) {
        this(owner, original, DEFAULT_CAP);
    }

    @SuppressWarnings("unchecked")
    public SanitizedFloatItemProxy(LivingEntity owner, SynchedEntityData.DataItem<?> original, float cap) {
        super((net.minecraft.network.syncher.EntityDataAccessor<Float>) original.getAccessor(),
                (Float) original.getValue());
        this.owner = owner;
        this.cap = cap > 0.0F ? cap : DEFAULT_CAP;
        this.delegate = (SynchedEntityData.DataItem<Float>) original;
    }

    /** 被替换掉的原始数据项（还原时用）。 */
    public SynchedEntityData.DataItem<Float> delegate() {
        return delegate;
    }

    /** 纯函数：把任意写入值收敛到合法区间。 */
    public static float sanitize(float value, float cap) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0.0F) {
            return 0.0F;
        }
        float limit = cap > 0.0F ? cap : DEFAULT_CAP;
        return Math.min(value, limit);
    }

    @Override
    public void setValue(Float value) {
        super.setValue(sanitize(value == null ? Float.NaN : value, cap));
    }

    @Override
    public Float getValue() {
        return super.getValue();
    }

    /** 所有者（诊断用）。 */
    public LivingEntity owner() {
        return owner;
    }

    /**
     * 永远不认为自己是默认值：新追踪的客户端才能收到该通道的完整初值，
     * 否则会被 {@code getNonDefaultValues()} 过滤掉（与血量代理同一个理由）。
     */
    @Override
    public boolean isSetToDefault() {
        return false;
    }
}
