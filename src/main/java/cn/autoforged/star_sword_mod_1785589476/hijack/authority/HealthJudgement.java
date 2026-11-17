package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 「这个实体算不算死了」的<b>唯一权威判定器</b>（机制层）。移植自星-穹工程 {@code HealthJudgement}。
 *
 * <h2>为什么需要它</h2>
 * <p>原版的死亡链条是 {@code getHealth() <= 0 → isDeadOrDying() → tickDeath() → deathTime++}。
 * 外部实现整体替换 {@code getHealth()} 之后，这条链的<b>输入</b>就是替换后的读数；我们此前只能在
 * {@code deathTime} 已经被推进之后每 tick 复位，形成两个写者的拉锯（日志里表现为
 * {@code deathTime} 恒为 1、模型立正、背包打不开）。
 *
 * <p>本类把判定从那条链上摘出来：只要实体处于防御态（受保护 或 有权威血量），就<b>直接回答「没死」</b>，
 * 不再询问 {@code getHealth()}。于是 {@code LivingEntity.tick()} 里的
 * {@code if (isDeadOrDying()) tickDeath();} 根本不会执行，deathTime 自然为 0。
 *
 * <h2>两条谓词（与母本同构）</h2>
 * <ul>
 *   <li>{@link #isDeathBlocked}：受保护 <b>或</b> 有权威血量 —— 供 {@code isDeadOrDying} /
 *       {@code tickDeath} / {@code die} 三个拦截点使用；</li>
 *   <li>{@link #isDead}：{@code isDeathBlocked} 为真时一律 {@code false}；否则读<b>权威血量</b>
 *       （{@link HealthAuthority#read}，走字段直读，不调 {@code getHealth()}），{@code <= 0} 才算死。</li>
 * </ul>
 *
 * <p><b>绝不调用 {@link LivingEntity#isDeadOrDying()}</b>：该方法可能已被本类重定向，
 * 调用它会构成无限递归（母本把这条写进了契约）。
 *
 * <p><b>Never throws</b>：异常返回 {@code false}（保守：宁可认为活着，一次偶发异常不该把实体判死）。
 */
public final class HealthJudgement {

    private HealthJudgement() {
    }

    /**
     * 该实体是否处于「我们负责」的状态：受保护，或存在权威血量记录。
     *
     * <p>为 {@code false} 表示本模组对它的死亡<b>没有意见</b>，调用方应保持原版行为。
     */
    public static boolean isDeathBlocked(LivingEntity self) {
        try {
            if (!(self instanceof Player player)) {
                return false;
            }
            if (SwordGuard.isProtected(player)) {
                return true;
            }
            // 「记录存在」不等于「记录权威」：保护到期后记录仍在但已退役（PlayerDefense.RETIRED）。
            // 走 isAuthoritative 才能避免退役值把玩家永远按在「没死」上（0 血不死）。
            return HealthAuthority.isAuthoritative(player.getUUID());
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 本模组是否对该实体的死亡判定持有意见（非玩家一律不持有）。 */
    public static boolean hasOpinion(LivingEntity self) {
        return isDeathBlocked(self);
    }

    /**
     * 权威死亡判定：防御态下一律「没死」；否则按权威血量 {@code <= 0} 判定。
     *
     * @return true 表示已死
     */
    public static boolean isDead(LivingEntity self) {
        try {
            if (self == null) {
                return false;
            }
            if (isDeathBlocked(self)) {
                return false;
            }
            float health = HealthAuthority.read(self);
            if (Float.isNaN(health)) {
                // 读不到权威血量（数据项尚未就绪）：不给意见，交给原版。
                return self.getHealth() <= 0.0F;
            }
            return health <= 0.0F;
        } catch (Throwable throwable) {
            return false;
        }
    }
}
