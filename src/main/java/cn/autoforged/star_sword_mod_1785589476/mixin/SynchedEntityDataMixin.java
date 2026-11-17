package cn.autoforged.star_sword_mod_1785589476.mixin;

import cn.autoforged.star_sword_mod_1785589476.agent.AgentHooks;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 拦截 {@code SynchedEntityData.set} —— 实体同步数据的唯一写入口。
 *
 * <p>1.20.1 的 {@code SynchedEntityData} 私有持有创建它的实体（{@code mc-src/.../SynchedEntityData.java:30}），
 * 因此可以在 HEAD 处从一次 set 反查实体，判断是否是受保护玩家并拦截非法/减血的血量写入。
 *
 * <p>两个重载 {@code set(key, value)} / {@code set(key, value, force)} 中，两参版本会委托三参版本，
 * 故只注入三参版本的 HEAD 即可覆盖全部写入。命中时直接 {@code ci.cancel()}，值不会写进同步数据、
 * 也不会标记 dirty 发给客户端（原版 {@code set} 在 :132-140 才 setValue/markDirty）。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 拦截受保护玩家血量同步数据的非法 / 减血写入。
// ============================================================================
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {

    // [DEFENSE-MARK] 防御：SynchedEntityData.set HEAD 血量写入拦截。
    @Shadow
    @Final
    private Entity entity;

    @Inject(
            method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void starSword$guardHealthWrite(EntityDataAccessor<?> key, Object value, boolean force,
                                            CallbackInfo ci) {
        // 执行者唯一：agent 已经在这个入口注入了同一判据时直接退出。否则同一次写入会被过滤两遍——
        // 拒绝计数翻倍（诊断失真），对手 100+ 次/秒的写入还要白跑两次。
        if (AgentHooks.hasAgentInjection("synched.set")) {
            return;
        }
        if (PlayerDefense.shouldBlockSynchedWrite(entity, key, value)) {
            ci.cancel();
        }
    }

    /**
     * 客户端收包走的是 {@code assignValues}（{@code ClientPacketListener.handleSetEntityData}），
     * **不经过 {@code set}**——所以 {@code set} 上的写侧闸门对“服务端同步下来的私有 Float 通道”
     * 完全无效。日志实测：客户端那个累加值一路涨到 9.85，而服务端写入早被我们拦住了。
     *
     * <p>这里在进入原版处理前把列表里的值改掉：血量项走既有纠正，白名单外的可疑累加项直接压回 0。
     */
    @ModifyVariable(method = "assignValues", at = @At("HEAD"), argsOnly = true)
    private List<SynchedEntityData.DataValue<?>> starSword$sanitizeAssignedValues(
            List<SynchedEntityData.DataValue<?>> entries) {
        return PlayerDefense.sanitizeAssignedValues(entity, entries);
    }
}
