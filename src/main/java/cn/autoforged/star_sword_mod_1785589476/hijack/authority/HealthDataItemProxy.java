package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

/**
 * 血量数据项代理：把 {@code SynchedEntityData} 里的血量 {@code DataItem} 换成
 * 「读写都要经过 {@link HealthAuthority} 裁决」的子类。移植自星-穹工程 {@code ProxyDataItem}。
 *
 * <h2>为什么换数据项而不是拦方法</h2>
 * <p>本工程此前在三个<b>外围</b>各自打补丁：{@code SynchedEntityData.set} 的写侧闸门、
 * {@code assignValues} 的收包钳制、{@code Connection.send} 的发包改写。三者各自漏一条路，
 * 且都会被外部实现换掉注入点。换掉数据项本身之后，<b>读、写、发包（{@code value()}）三处天然收口</b>：
 * 原版 {@code getHealth()}、{@code entityData.get(...)}、{@code packDirty} 走到的都是本类。
 *
 * <h2>为什么不覆写成 {@code owner.getHealth()}</h2>
 * <p>那会形成 {@code getHealth() → entityData.get() → DataItem.getValue() → getHealth()} 的无限递归。
 * 本类只与 {@link HealthAuthority} 交互，而 {@code HealthAuthority.read} 走的是字段直读。
 *
 * <h2>存储只有一份</h2>
 * <p>值存在<b>本类继承来的</b> {@code value} 字段里（母本把值留在被替换的原始项里，两份存储要靠
 * 覆写保持同步，任何一处直写字段就会漂移）。这样做的好处是：
 * <ul>
 *   <li>{@code RawState.rawFloatOf} / {@code writeRawFloat} 这类「反射直读直写字段」的代码
 *       读写的正是同一份存储，不会读到旧值；</li>
 *   <li>被替换掉的原始项只用于 {@link #delegate()} 还原，还原前由
 *       {@link HealthDataItemInstaller#restore} 把当前值写进去。</li>
 * </ul>
 */
public final class HealthDataItemProxy extends SynchedEntityData.DataItem<Float> {

    private final LivingEntity owner;
    private final SynchedEntityData.DataItem<Float> delegate;

    @SuppressWarnings("unchecked")
    public HealthDataItemProxy(LivingEntity owner, SynchedEntityData.DataItem<?> original) {
        super((net.minecraft.network.syncher.EntityDataAccessor<Float>) original.getAccessor(),
                (Float) original.getValue());
        this.owner = owner;
        this.delegate = (SynchedEntityData.DataItem<Float>) original;
    }

    /** 被替换掉的原始数据项（还原时用）。 */
    public SynchedEntityData.DataItem<Float> delegate() {
        return delegate;
    }

    @Override
    public void setValue(Float value) {
        // 写入钳制后再按「对外发布值」落盘，保证 field == getValue()：
        // 否则锁 5 时被写入 20，字段留在 20、对外报 5，解锁瞬间血量会跳变；RawState 直读也读错口径。
        float clamped = HealthAuthority.renderWrite(owner, value == null ? Float.NaN : value);
        super.setValue(HealthAuthority.renderRead(owner, clamped));
    }

    @Override
    public Float getValue() {
        return HealthAuthority.renderRead(owner, super.getValue());
    }

    @Override
    public SynchedEntityData.DataValue<Float> value() {
        return SynchedEntityData.DataValue.create(getAccessor(), getValue());
    }

    /**
     * 永远不认为自己是「默认值」。
     *
     * <p>{@code ServerEntity} 用 {@code getNonDefaultValues()} 给新追踪者做全量同步，而它按
     * {@code isSetToDefault()} 过滤（比较 {@code initialValue} 与字段，而非 {@link #getValue()}）。
     * 代理安装时复制了当时的血量（如 20），满血时就会被判为 default，整条血量项被省略——
     * 新追踪的客户端会保持 {@code LivingEntity} 的默认 1.0F，且等值写入又被 {@code set} 的
     * notEqual 短路，不会再有纠正包。代价只是每个追踪者多 5 字节。
     */
    @Override
    public boolean isSetToDefault() {
        return false;
    }
}
