package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.agent.AgentHooks;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.ForeignChannelGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.HealthAuthority;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.HealthDataItemInstaller;
import cn.autoforged.star_sword_mod_1785589476.mixinmon.MixinFailureRegistry;
import cn.autoforged.star_sword_mod_1785589476.mixinmon.MixinProbe;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 方法层防御的“失败可见性”。
 *
 * <p>best-effort 的最大风险不是恢复不了，而是<b>恢复不了却没人知道</b>。这里把 agent 的运行状态
 * 归纳成三档并主动播报：
 *
 * <ul>
 *   <li>{@link State#ACTIVE}：{@code getHealth} 注入的钩子计数在增长，方法层防御有效。</li>
 *   <li>{@link State#DEGRADED}：计数连续 {@value #DEGRADED_AFTER_SAMPLES} 秒不增长 —— 方法被
 *       别人持续覆盖，watchdog 还在尝试恢复。此状态下数据层（写侧闸门/观察者广播/发包过滤）
 *       仍然生效。</li>
 *   <li>{@link State#FAILED}：持续 {@value #FAILED_AFTER_SAMPLES} 秒不增长，判定恢复失败。</li>
 * </ul>
 *
 * <p>状态变化时向全服与日志播报一次，并有 {@link #NOTICE_COOLDOWN_MS} 的冷却，避免拉锯时刷屏。
 * {@code /starsword agent status} 输出同一份快照。
 */
// [DEFENSE-MARK] 诊断：方法层防御的运行状态（ACTIVE / DEGRADED / FAILED）与播报。
public final class AgentStatus {

    public enum State {
        /** 还没收到 agent 的就绪信号：方法层根本没启用，不做停滞判定。 */
        NOT_ATTACHED,
        ACTIVE,
        DEGRADED,
        FAILED
    }

    /** 采样间隔（tick）。20 tick = 1 秒。 */
    private static final int SAMPLE_TICKS = 20;

    /** 连续多少个采样看不到钩子计数增长就降级（3 秒）。 */
    private static final int DEGRADED_AFTER_SAMPLES = 3;

    /** 连续多少个采样看不到增长就判定失败（120 秒）。 */
    private static final int FAILED_AFTER_SAMPLES = 120;

    /** 两次播报之间的最小间隔（毫秒）。 */
    private static final long NOTICE_COOLDOWN_MS = 60_000L;

    private static volatile State state = State.NOT_ATTACHED;
    private static long lastHookValue = -1L;
    private static int stalledSamples;
    private static long degradedSince;
    private static long lastNoticeAt;
    private static int lastSampleTick = Integer.MIN_VALUE;

    private AgentStatus() {
    }

    /** 每个服务端玩家 tick 调用；内部按 {@link #SAMPLE_TICKS} 降采样。 */
    // [DEFENSE-MARK] 诊断：降采样读取钩子计数并维护状态机。
    public static void tick(ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        int tick = player.tickCount;
        if (lastSampleTick != Integer.MIN_VALUE && tick - lastSampleTick < SAMPLE_TICKS) {
            return;
        }
        lastSampleTick = tick;

        if (!AgentHooks.agentAttached()) {
            // 未附加时钩子计数本来就不会增长，不能据此判降级。
            if (state != State.NOT_ATTACHED) {
                state = State.NOT_ATTACHED;
            }
            return;
        }
        if (state == State.NOT_ATTACHED) {
            state = State.ACTIVE;
        }

        long current = AgentHooks.hookCallCount();
        if (lastHookValue < 0L) {
            lastHookValue = current;
            return;
        }
        if (current > lastHookValue) {
            lastHookValue = current;
            stalledSamples = 0;
            transition(State.ACTIVE, player);
            return;
        }
        stalledSamples++;
        if (stalledSamples >= FAILED_AFTER_SAMPLES) {
            transition(State.FAILED, player);
        } else if (stalledSamples >= DEGRADED_AFTER_SAMPLES) {
            transition(State.DEGRADED, player);
        }
    }

    private static void transition(State next, ServerPlayer player) {
        State previous = state;
        if (previous == next) {
            return;
        }
        state = next;
        if (next != State.ACTIVE) {
            degradedSince = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        if (now - lastNoticeAt < NOTICE_COOLDOWN_MS) {
            return;
        }
        lastNoticeAt = now;
        String message = switch (next) {
            case NOT_ATTACHED -> "agent 未附加，方法层防御未启用（数据层防御仍生效）";
            case DEGRADED -> "检测到持续字节码覆盖，方法层防御已降级，数据层防御仍生效"
                    + "（写侧闸门/观察者广播/发包过滤不受影响）";
            case FAILED -> "方法层防御恢复失败（持续 " + (stalledSamples * SAMPLE_TICKS / 20)
                    + " 秒），数据层防御仍生效";
            case ACTIVE -> "方法层防御已恢复（钩子计数重新增长）";
        };
        ModLog.defense("AgentStatus " + previous + " -> " + next, player, message);
        broadcast(player, "[星辉剑] " + message);
    }

    private static void broadcast(ServerPlayer player, String message) {
        try {
            MinecraftServer server = player.server;
            if (server != null) {
                server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
            }
        } catch (Throwable t) {
            ModLog.health("AgentStatus 播报失败", player, "异常=" + t);
        }
    }

    /** {@code /starsword agent status} 里最多列出的失败条数。 */
    private static final int MAX_FAILURES_SHOWN = 8;

    /** {@code /starsword agent status} 的多行输出。 */
    // [DEFENSE-MARK] 诊断：状态快照。
    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("方法层防御状态: " + state);
        lines.add("  - 最近采样: 钩子计数=" + AgentHooks.hookCallCount()
                + " 停滞采样=" + stalledSamples + " (" + (stalledSamples * SAMPLE_TICKS / 20) + "s)");
        lines.add("  - 重放次数: " + AgentHooks.replayCount()
                + "，最近一次 " + AgentHooks.lastReplayClasses() + " 个类 / "
                + AgentHooks.lastReplayMillis() + "ms"
                + (AgentHooks.lastReplayAt() > 0 ? " @ " + format(AgentHooks.lastReplayAt()) : ""));
        lines.add("  - 篡改次数: " + AgentHooks.tamperCount()
                + (AgentHooks.lastTamperAt() > 0 ? "，最近一次 @ " + format(AgentHooks.lastTamperAt()) : ""));
        if (degradedSince > 0 && state != State.ACTIVE) {
            lines.add("  - 降级自: " + format(degradedSince));
        }
        lines.add("数据层防御: 写侧闸门 / 观察者广播 / 发包过滤 仍生效");
        lines.add("权威血量存储: " + HealthAuthority.describe()
                + "，已装数据项代理=" + HealthDataItemInstaller.installedCount()
                + "，换装写锁=" + (HealthDataItemInstaller.lockAvailable() ? "可用" : "缺失（退回无锁）"));
        lines.add("同步键: " + syncKeySummary());
        lines.add("Mixin 注入自检: " + MixinFailureRegistry.describe()
                + (MixinProbe.ran() ? "（探针已运行）" : "（探针未运行）"));
        lines.add("注入对账: " + AgentHooks.describeInjectionState());
        String observed = AgentHooks.describeObserved();
        if (!observed.isEmpty()) {
            lines.add("观察规则命中: " + observed);
        }
        lines.add("方法层心跳: " + MethodLayerState.describe());
        lines.add("逐钩子存活: " + HookLiveness.describe());
        lines.add("实体完整性: " + EntityIntegrityProbe.describe());
        lines.add("未知浮点通道: " + ForeignChannelGuard.describe());
        lines.add("外部通道趋势: " + ChannelTrend.describe());
        lines.add("状态锁: " + cn.autoforged.star_sword_mod_1785589476.hijack.authority.VanillaStateLock
                .describe());
        int shown = 0;
        for (MixinFailureRegistry.Failure failure : MixinFailureRegistry.snapshot()) {
            if (shown >= MAX_FAILURES_SHOWN) {
                lines.add("  - …其余 " + (MixinFailureRegistry.size() - shown) + " 条略");
                break;
            }
            shown++;
            lines.add("  - [" + failure.phase() + "] " + failure.summary());
        }
        return lines;
    }

    public static State state() {
        return state;
    }

    /**
     * 运行态的数据键 id（血量 / 吸收量）。
     *
     * <p><b>这两个数字不是原版常量</b>：Forge 的 {@code SynchedEntityData.defineId} 用的是按继承链
     * 单调递增的池，任何在 {@code Entity}/{@code LivingEntity}/{@code Player} 上多定义几个数据项的模组
     * （含在 {@code <clinit>} 头部插注入的实现）都会把所有后续 id 整体推移。实测环境里血量键是 14、
     * 吸收量是 41，而不是"原版序号"的 9 / 15。代码一律按 accessor 对象比较、从不写字面 id，
     * 但日志和对账必须把真实 id 打出来，否则"id=8 被中和"这种行会被误读成"连血量都清了"。
     */
    private static String syncKeySummary() {
        String health;
        try {
            var accessor = cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper.healthDataAccessor();
            health = accessor == null ? "未解析" : String.valueOf(accessor.getId());
        } catch (Throwable t) {
            health = "未解析";
        }
        String absorption;
        try {
            int id = cn.autoforged.star_sword_mod_1785589476.hijack.authority.ForeignChannelGuard
                    .absorptionChannelId();
            absorption = id == Integer.MIN_VALUE ? "未解析" : String.valueOf(id);
        } catch (Throwable t) {
            absorption = "未解析";
        }
        return "血量=" + health + " 吸收=" + absorption
                + "（运行态；随其它模组定义的同步项数量变化，不是原版常量）";
    }

    private static String format(long epochMillis) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(epochMillis));
    }
}
