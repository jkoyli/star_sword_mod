package cn.autoforged.star_sword_mod_1785589476.mixinmon;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mixin <b>自报注入失败</b>的记录表（启动期只写、探测期只读）。
 *
 * <p>移植自星-穹工程的 {@code StarDomeMixinFailureRegistry}（同类实现，改为本模组的配置名与包前缀）。
 *
 * <h2>为什么需要它</h2>
 * <p>注入器 handler 的方法体在 <b>MAIN pass</b> 就合并进了目标类
 * （{@code MixinPreProcessorStandard.attachInjectorMethod()} → {@code context.addMixinMethod(...)}，
 * {@code MixinApplicatorStandard} 的 MAIN pass 无条件 {@code mergeMethod()}），而
 * {@code @At} 没命中 / 目标方法不存在这类失败发生在之后的 <b>INJECT pass</b>。因此
 * 「目标类里有没有这个 handler 名」这种探测<b>看不见</b>注入点失败。本表记录 Mixin 自己报告的失败，
 * 补上这一层信号；{@link MixinProbe} 再把「handler 是否真的在目标类里」补成另一半。
 *
 * <h2>早期加载安全（硬要求）</h2>
 * <p>本类以及 {@link StarSwordMixinConfigPlugin} / {@link StarSwordMixinErrorHandler} 会在游戏
 * <b>极早期</b>（MC 类都还没加载时）被 Mixin 用服务自己的类加载器加载，因此<b>只允许引用</b>
 * {@code java.*}、{@code org.spongepowered.asm.*}、{@code org.objectweb.asm.*} 与
 * {@code org.apache.logging.log4j.*}——<b>禁止</b>引用任何 {@code net.minecraft.*}、
 * {@code net.minecraftforge.*}、{@link cn.autoforged.star_sword_mod_1785589476.helper.ModLog}
 * （它 import 了 MC 的 Entity 与 Forge 的 FMLEnvironment）。违反即 {@code NoClassDefFoundError}，
 * 且发生在 Mixin 插件加载期，表现为「plugin = null」。
 *
 * <p><b>只记本模组（关键）</b>：{@code Mixins.registerErrorHandlerClass} 注册的是<b>全局</b>错误
 * 处理器，Mixin 对<b>任何</b>配置（含同装几百个模组的别人的 mixin）的失败都会回调我们。因此
 * {@link #record} 第一步就做「是不是本模组的」过滤，不命中直接丢弃、不打印——否则刷屏并挤占容量。
 *
 * <p><b>线程模型</b>：Mixin 变换可能多线程触发回调，故内部为 {@code synchronized(FAILURES)} 保护的
 * {@link LinkedHashMap}（保序 + 去重）。
 *
 * <p><b>异常契约（Never throws）</b>：所有方法（含日志助手）都吞 {@link Throwable}——
 * 这个类运行在 Mixin 的回调里，抛一次就会打断类变换。
 */
public final class MixinFailureRegistry {

    /** 日志前缀，便于 grep。 */
    public static final String PREFIX = "[StarSword-Mixin]";

    /**
     * 本模组 mixin 配置文件名的白名单。
     *
     * <p>与 {@code src/main/resources/*.mixins.json} 必须保持一致；{@link MixinProbe} 启动时做一次
     * 漂移自检（配置里登记的名字不在本表 → 记一条 error），避免「新加了一份配置但失败被当外部丢弃」。
     */
    private static final Set<String> OUR_CONFIGS = Set.of(
            "star_sword_mod_1785589476.mixins.json");

    /** 本模组类的前缀：mixin 全限定名必然是 {@code cn.autoforged.star_sword_mod_1785589476.*}。 */
    private static final String OWN_PACKAGE_PREFIX = "cn.autoforged.star_sword_mod_1785589476.";

    /** 最多留存的失败条数：异常风暴时不让表无限增长。 */
    private static final int MAX_RECORDS = 256;

    /** 去重键 → 失败记录。键含全部字段，故同一处失败重复上报只留一条。 */
    private static final Map<String, Failure> FAILURES = new LinkedHashMap<>();

    private MixinFailureRegistry() {
    }

    /** 该配置名是否属于本模组的 mixin 配置（供漂移自检；<b>Never throws</b>）。 */
    public static boolean isOurConfigName(String configName) {
        try {
            // Set.of 的不可变集合 contains(null) 会抛 NPE，必须先判空。
            return configName != null && OUR_CONFIGS.contains(configName);
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 本模组全部 mixin 配置名（防御性副本；Never throws）。 */
    public static Set<String> ourConfigNames() {
        try {
            return Set.copyOf(OUR_CONFIGS);
        } catch (Throwable throwable) {
            return Set.of();
        }
    }

    /**
     * 一条 Mixin 自报的失败。
     *
     * <p><b>刻意不持有 {@link Throwable}</b>：只留类名与 message。完整堆栈由 Mixin 自己
     * 以 WARN 打出，本表只做「点名」，也不会把早期类加载器 / 异常对象长期钉在内存里。
     *
     * @param phase           {@code prepare} / {@code apply} / {@code probe}
     * @param configName      mixin 配置文件名；未知时为空串
     * @param mixinClassName  mixin 的全限定类名；未知时为空串
     * @param targetClassName 出错时正在变换的目标类名（{@code prepare}/{@code probe} 阶段可为空串）
     * @param throwableClass  异常类全名；未知时为 {@code unknown}
     * @param message         异常 message；可能为空串
     */
    public record Failure(String phase, String configName, String mixinClassName, String targetClassName,
                          String throwableClass, String message) {

        /** 一行摘要，用于日志与状态报告。 */
        public String summary() {
            String text = message == null || message.isBlank() ? throwableClass : message;
            return "配置=" + configName
                    + "，阶段=" + phase
                    + "，mixin=" + mixinClassName
                    + (targetClassName == null || targetClassName.isBlank() ? "" : "，目标=" + targetClassName)
                    + "，异常=" + throwableClass + "：" + text;
        }
    }

    /**
     * 记录一条 Mixin 自报的失败（幂等：同一条只留一份）。
     *
     * <p>第一步就是「是不是本模组的」过滤（{@link #isOurs}）：不属于就直接 {@code return}。
     * 属于本模组时额外打一条 WARN（不带堆栈——Mixin 自己已经打过）。
     */
    public static void record(String phase, String configName, String mixinClassName, String targetClassName,
                              Throwable throwable) {
        try {
            if (!isOurs(configName, mixinClassName)) {
                return;
            }
            String throwableClass = throwable == null ? "unknown" : throwable.getClass().getName();
            String message = throwable == null ? "" : nullToEmpty(throwable.getMessage());
            recordFailure(new Failure(nullToEmpty(phase), nullToEmpty(configName), nullToEmpty(mixinClassName),
                    nullToEmpty(targetClassName), throwableClass, message));
        } catch (Throwable ignored) {
            // 记录失败绝不允许影响 mixin 加载
        }
    }

    /**
     * 记录一条<b>探针发现</b>的失败（不经过「是不是本模组」过滤：探针只查本模组的目标）。
     *
     * <p>{@link MixinProbe} 用它报告「handler 不在目标类里」这类 Mixin 自己不会报的缺口。
     */
    public static void recordProbeFailure(String mixinClassName, String targetClassName, String message) {
        try {
            recordFailure(new Failure("probe", firstOurConfigName(), nullToEmpty(mixinClassName),
                    nullToEmpty(targetClassName), "MissingHandler", nullToEmpty(message)));
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    private static void recordFailure(Failure failure) {
        try {
            String key = failure.phase() + "|" + failure.configName() + "|" + failure.mixinClassName() + "|"
                    + failure.targetClassName() + "|" + failure.throwableClass() + "|" + failure.message();
            boolean added;
            synchronized (FAILURES) {
                added = !FAILURES.containsKey(key) && FAILURES.size() < MAX_RECORDS;
                if (added) {
                    FAILURES.put(key, failure);
                }
            }
            if (added) {
                logWarn("记录到 mixin 注入失败：" + failure.summary());
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /**
     * 该失败是否属于本模组：mixin 全限定类名以 {@code cn.autoforged.star_sword_mod_1785589476.} 开头
     * <b>或</b> 配置名 ∈ {@link #OUR_CONFIGS}。两者任一成立即算本模组；<b>只做 String 比较</b>，
     * 不加载任何类。
     */
    private static boolean isOurs(String configName, String mixinClassName) {
        try {
            if (isOurConfigName(configName)) {
                return true;
            }
            return mixinClassName != null && mixinClassName.startsWith(OWN_PACKAGE_PREFIX);
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static String firstOurConfigName() {
        try {
            return OUR_CONFIGS.iterator().next();
        } catch (Throwable throwable) {
            return "";
        }
    }

    /** 当前失败记录的<b>防御性副本</b>（有序：先记录在前）。Never throws。 */
    public static List<Failure> snapshot() {
        try {
            synchronized (FAILURES) {
                return new ArrayList<>(FAILURES.values());
            }
        } catch (Throwable throwable) {
            return new ArrayList<>();
        }
    }

    /** 当前失败记录条数；出错时返回 {@code 0}，Never throws。 */
    public static int size() {
        try {
            synchronized (FAILURES) {
                return FAILURES.size();
            }
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /** 一行汇总：{@code Mixin 自报/探针发现的失败：N 条}。Never throws。 */
    public static String describe() {
        try {
            int total = size();
            if (total == 0) {
                return "Mixin 注入自检：0 条失败";
            }
            return "Mixin 注入自检：" + total + " 条失败（详见 /starsword agent status）";
        } catch (Throwable throwable) {
            return "Mixin 注入自检：状态不可读";
        }
    }

    /** 清空记录表（仅供离线验证使用）。 */
    public static void clearForTest() {
        try {
            synchronized (FAILURES) {
                FAILURES.clear();
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    // ---------------------------------------------------------------- 日志助手

    static void logInfo(String message) {
        try {
            LogHolder.LOGGER.info("{} {}", PREFIX, message);
        } catch (Throwable ignored) {
            // 日志不可用不影响 mixin 加载
        }
    }

    static void logWarn(String message) {
        try {
            LogHolder.LOGGER.warn("{} {}", PREFIX, message);
        } catch (Throwable ignored) {
            // 日志不可用不影响 mixin 加载
        }
    }

    static void logError(String message, Throwable throwable) {
        try {
            LogHolder.LOGGER.error("{} {}", PREFIX, message, throwable);
        } catch (Throwable ignored) {
            // 日志不可用不影响 mixin 加载
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 日志器持有类：把 {@code LogManager.getLogger} 从外层类的 {@code <clinit>} 里挪出来，
     * 只有第一次真正打日志时才初始化（极早期 log4j 可能还没就绪）。
     */
    private static final class LogHolder {

        private static final Logger LOGGER = LogManager.getLogger("StarSword-Mixin");

        private LogHolder() {
        }
    }
}
