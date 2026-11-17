package cn.autoforged.star_sword_mod_1785589476.helper;

/**
 * 客户端“关屏”的死亡态延迟策略 —— 纯逻辑，不依赖 Minecraft 类型，便于离线验证。
 *
 * <p>背景（一次真实的自递归）：{@code Minecraft.setScreen(null)} 在
 * {@code player.isDeadOrDying()} 为真时，会把 {@code null} 换成一个新的
 * {@code DeathScreen}，然后才 post {@code ScreenEvent.Opening}（mc-src
 * {@code Minecraft.java:988-1000}）。如果 Opening 的 handler 里又调用 {@code setScreen(null)}，
 * 就会再次走同一条链路：{@code null → DeathScreen → Opening → setScreen(null) → …}，直到爆栈。
 *
 * <p>修法有两条：handler 内只改事件（{@code setNewScreen(null)}），以及“其它地方关屏时，
 * 死亡态先挂起、等死亡态复位后再关”。本类负责后者的判定：
 * <ul>
 *   <li>挂起后每 tick 推进一次；死亡态一结束就立刻返回“可以关了”；</li>
 *   <li>长期不复位（服务端没同步过来）则在 {@link #MAX_DEFER_TICKS} tick 后强制关一次，
 *       避免界面永久卡住——此时即使仍在死亡态也安全，因为 Opening handler 会把
 *       {@code newScreen} 改回 {@code null}，不会再递归；</li>
 *   <li>挂起期间重复请求不会重置计时（否则每 tick 都请求会让“强制关”永远到不了）。</li>
 * </ul>
 */
public final class ScreenClosePolicy {

    /** 死亡态下最多延迟多少 tick 再强制关屏。 */
    public static final int MAX_DEFER_TICKS = 40;

    private ScreenClosePolicy() {
    }

    /** 一次挂起的关屏请求。 */
    public static final class Deferral {

        private final int maxDeferTicks;
        private boolean pending;
        private int ticks;

        public Deferral() {
            this(MAX_DEFER_TICKS);
        }

        public Deferral(int maxDeferTicks) {
            this.maxDeferTicks = Math.max(1, maxDeferTicks);
        }

        /** 请求关屏；已经在挂起时不重置计时。 */
        public void request() {
            if (!pending) {
                pending = true;
                ticks = 0;
            }
        }

        public boolean isPending() {
            return pending;
        }

        /** 已经等了多少 tick（诊断用）。 */
        public int deferredTicks() {
            return ticks;
        }

        /**
         * 推进一 tick。
         *
         * @param deadOrDying 当前是否仍处于死亡态
         * @return true 表示现在应当真正关屏（并已清除挂起状态）
         */
        public boolean tick(boolean deadOrDying) {
            if (!pending) {
                return false;
            }
            if (deadOrDying && ++ticks < maxDeferTicks) {
                return false;
            }
            pending = false;
            ticks = 0;
            return true;
        }
    }
}
