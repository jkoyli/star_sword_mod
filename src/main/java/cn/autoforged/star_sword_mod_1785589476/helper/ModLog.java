package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 星辉剑统一日志入口 —— 本模组<b>唯一</b>的日志出口。
 *
 * <h2>只在开发环节启用</h2>
 * <p>开关由运行环境决定，不需要改代码也不需要加参数：
 * <ul>
 *   <li><b>开发运行</b>（{@code runClient} / {@code runServer}，即 {@link FMLEnvironment#production} 为
 *       {@code false}）：全部日志打开，同时写 log4j（控制台 + {@code logs/latest.log}，logger 名
 *       {@code StarSword}）与独立文件 {@code logs/star_sword_mod_1785589476.log}。</li>
 *   <li><b>发布版</b>（构建出的 jar 运行在正式环境，{@code production} 为 {@code true}）：所有日志方法
 *       在任何字符串拼接之前就返回，既不写 latest.log，也不创建独立日志文件，更不会留下新文件。</li>
 * </ul>
 * <p>可用系统属性强制覆盖判定的两种方向：{@code -Dstar_sword.log=true} 在发布版里临时打开，
 * {@code -Dstar_sword.log=false} 在开发环境里彻底关掉（排查“日志本身是不是问题”时用）。
 * 同样的约定适用于 {@link ReconcileStats} 的采样开关 {@code -Dstar_sword.stats}。
 *
 * <h2>格式</h2>
 * <p>所有输出统一成一行：{@code [分类] 内容 | 调用层: 类.方法:行 <- 类.方法:行}，分类见下方常量。
 * 每 tick 检查、正常生效时无需逐 tick 观察的行为走 {@code *Throttled}（同一个 key 每 N tick 最多一行）；
 * 异常 / 回退 / 被拒绝等事件走非限频方法，立即输出。
 *
 * <p>本类不做任何游戏逻辑，纯输出；文件写入失败只标记一次并静默降级，绝不影响游戏。
 */
public final class ModLog {

    // ===================== 分类（调用点一律使用常量，避免拼写漂移） =====================

    /** 攻击：对任何实体的主动伤害 / 击杀 / 直接血量改写。 */
    public static final String ATTACK = "攻击";
    /** 防御：受伤取消、死亡拦截、复活、控制权恢复。 */
    public static final String DEFENSE = "防御";
    /** 血量：旁路真实血量、同步数据键、写侧闸门。 */
    public static final String HEALTH = "血量";
    /** 背包：星辉剑备份、回收、槽位迁移。 */
    public static final String INVENTORY = "背包";
    /** 星辉剑：持有者的飞行 / 饱食度 / 负面效果等自身效果。 */
    public static final String SWORD = "星辉剑";
    /** 网络：自定义包收发与发包过滤。 */
    public static final String NETWORK = "网络";
    /** 解析：反射 / 字节码层面的字段与数据键探测。 */
    public static final String PARSE = "解析";
    /** agent：方法层防御的安装与运行状态。 */
    public static final String AGENT = "agent";
    /** 统计：{@link ReconcileStats} 的窗口汇总。 */
    public static final String STATS = "统计";
    /** 初始化：模组加载阶段的一次性信息。 */
    public static final String INIT = "初始化";
    /** 测试：仅在显式打开的开发期自检（如 {@code -Dstar_sword.poc.forceDie}）里出现。 */
    public static final String TEST = "测试";

    /** log4j logger 名，便于在 latest.log 里按 StarSword 过滤。 */
    public static final String LOGGER_TAG = "StarSword";

    /** 系统属性：强制日志开关（{@code true}/{@code false}，未设置时按运行环境判定）。 */
    public static final String PROP_ENABLED = "star_sword.log";

    /** 系统属性：{@link ReconcileStats} 采样开关（未设置时跟随 {@link #ENABLED}）。 */
    public static final String PROP_STATS = "star_sword.stats";

    /**
     * 系统属性：<b>计算后的最终开关</b>，由本类在静态初始化时写入。
     *
     * <p>agent 子项目（独立 jar、独立类加载器，看不到 Forge/Minecraft）和 attach 辅助进程
     * 通过它同步同一个决定，避免发布版里它们仍然往控制台打字。
     */
    public static final String PROP_ACTIVE = "star_sword.log.active";

    /** 总开关：开发环境为 {@code true}，发布版为 {@code false}。所有方法的第一道判断。 */
    public static final boolean ENABLED = resolveEnabled();

    /** 对账采样开关：开发环境默认开，发布版默认关，可被 {@value #PROP_STATS} 覆盖。 */
    public static final boolean STATS_ENABLED = resolveStatsEnabled();

    static {
        // 发布最终决定：agent / 辅助进程用它，而不是各自重复一遍环境判定。
        publishActiveState();
    }

    public static final Logger LOGGER = LogManager.getLogger(LOGGER_TAG);

    /** 每 tick 检查一次、正常生效时的日志间隔：40 tick 输出一次（按 20tps ≈ 2 秒折算）。 */
    public static final int TICK_INTERVAL = 40;

    private static final Object LOCK = new Object();
    private static final Path LOG_FILE = Paths.get("logs", "star_sword_mod_1785589476.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 一个 Minecraft tick 的毫秒数，用于把“tick 间隔”换算成限频时间窗。 */
    private static final long MILLIS_PER_TICK = 50L;

    /** 限频窗口：key -> 上次真正输出的毫秒时间戳。关掉日志时不会写入任何 key。 */
    private static final Map<String, Long> THROTTLE = new ConcurrentHashMap<>();

    /** 本模组的包前缀，用于从调用栈里识别“哪一层”调用的。 */
    private static final String MOD_PACKAGE = "cn.autoforged.star_sword_mod_1785589476.";

    private static BufferedWriter writer;
    private static boolean fileReady;
    private static boolean fileFailed;
    private static final AtomicBoolean SHUTDOWN_HOOK = new AtomicBoolean();

    private ModLog() {
    }

    // ===================== 运行环境判定 =====================

    private static boolean resolveEnabled() {
        String forced = System.getProperty(PROP_ENABLED);
        if (forced != null && !forced.isBlank()) {
            return truthy(forced);
        }
        try {
            // 开发运行（Gradle runClient/runServer、IDE 中启动）时 production == false。
            return !FMLEnvironment.production;
        } catch (Throwable t) {
            // 极早期（例如 agent 先于 FML 初始化）拿不到环境信息：按开发环境处理，
            // 宁可多记一行，也不要静默丢失诊断信息。
            return true;
        }
    }

    private static boolean resolveStatsEnabled() {
        String forced = System.getProperty(PROP_STATS);
        if (forced != null && !forced.isBlank()) {
            return truthy(forced);
        }
        return ENABLED;
    }

    private static boolean truthy(String value) {
        return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    private static void publishActiveState() {
        try {
            System.setProperty(PROP_ACTIVE, Boolean.toString(ENABLED));
        } catch (Throwable ignored) {
            // 写属性失败（极少见的安全管理器场景）时，agent 侧退化为自身默认值。
        }
    }

    /** 日志总开关当前状态；调用点可用它做更早的短路（见各热路径）。 */
    public static boolean isEnabled() {
        return ENABLED;
    }

    // ===================== 启动摘要 =====================

    /**
     * 模组构造时调用一次：开发环境打印一行状态（日志去哪儿找、统计开没开）；
     * 发布版什么都不做，连一行输出都不产生。
     */
    public static void init() {
        if (!ENABLED) {
            return;
        }
        log(INIT, "星辉剑 " + modVersion() + " 已加载；日志=开发模式，输出 latest.log(" + LOGGER_TAG + ") + "
                + LOG_FILE.toString().replace('\\', '/') + "；" + PROP_ENABLED + " 可强制覆盖");
        log(INIT, "对账采样=" + (STATS_ENABLED ? "开" : "关")
                + "（" + PROP_STATS + " 可覆盖），窗口 " + ReconcileStats.WINDOW_TICKS + " tick");
        log(INIT, "权威血量存储 " + cn.autoforged.star_sword_mod_1785589476.hijack.authority.HealthAuthority
                .describe() + "（数据项代理在读/写/发包三处收口）");
        log(INIT, "写入语义 " + HealthWritePolicy.describe()
                + "（-D" + HealthWritePolicy.PROP_STRICT + " 切换）；读取语义=非法读数（负数/NaN/无穷）"
                + "回退最近一次合法值，不落成 0");
        logHealthAccessor();
    }

    /**
     * 启动时打印“原版血量数据键”的 id。
     *
     * <p>排查用：如果这个 id 与日志里 {@code assignValues ... id=N} 的健康项对不上，
     * 就说明反射拿到的血量键不是运行时真正生效的那个，所有按 id 判断的逻辑都会失准。
     */
    private static void logHealthAccessor() {
        try {
            net.minecraft.network.syncher.EntityDataAccessor<Float> key =
                    cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper.healthDataAccessor();
            if (key == null) {
                log(INIT, "血量数据键=null（反射解析失败，按 id 的判定全部失效）");
                return;
            }
            log(INIT, "血量数据键 id=" + key.getId() + " 实例=" + key);
        } catch (Throwable t) {
            log(INIT, "血量数据键探测失败：" + t);
        }
    }

    /**
     * 当前运行的模组版本。
     *
     * <p>写进启动横幅，用来回答"玩家/自己到底跑的是不是刚打出来的那个 jar"——没有这行，
     * 只能靠日志文案比对，出问题时非常费时。
     */
    private static String modVersion() {
        try {
            return net.minecraftforge.fml.ModList.get()
                    .getModContainerById("star_sword_mod_1785589476")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    // ===================== 通用写入 =====================

    /** 通用写入：{@code [分类] 内容 | 调用层: a.b <- c.d}。 */
    public static void log(String category, String message) {
        if (!ENABLED) {
            return;
        }
        write(category, message, null);
    }

    /** 带异常的写入：内容里附上异常摘要，异常栈完整写入 log4j 与独立文件。 */
    public static void log(String category, String message, Throwable error) {
        if (!ENABLED) {
            return;
        }
        write(category, message, error);
    }

    private static void write(String category, String message, Throwable error) {
        String caller = layer();
        StringBuilder line = new StringBuilder("[")
                .append(category).append("] ").append(message);
        if (error != null) {
            line.append(" 异常=").append(error);
        }
        if (!caller.isEmpty()) {
            line.append(" | 调用层: ").append(caller);
        }
        String text = line.toString();
        if (error == null) {
            LOGGER.info(text);
            appendFile(text);
        } else {
            LOGGER.error(text, error);
            appendFile(text + System.lineSeparator() + stackTraceOf(error));
        }
    }

    /** 从当前调用栈里取本模组的最多 3 层（{@code 类名.方法名:行}），用于定位调用来源。 */
    public static String layer() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int found = 0;
            for (StackTraceElement element : stack) {
                String className = element.getClassName();
                if (!className.startsWith(MOD_PACKAGE)) {
                    continue;
                }
                if (className.equals(ModLog.class.getName())) {
                    continue;
                }
                if (found > 0) {
                    sb.append(" <- ");
                }
                int dot = className.lastIndexOf('.');
                sb.append(dot < 0 ? className : className.substring(dot + 1))
                        .append('.').append(element.getMethodName())
                        .append(':').append(element.getLineNumber());
                if (++found >= 3) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ===================== 限频 =====================

    /**
     * 限频输出：同一个 {@code key} 每 {@code intervalTicks} tick 最多输出一次。
     *
     * <p>用于“每 tick 都会检查、但正常生效时不需要每 tick 都看见”的日志；异常 / 回退 / 被拒绝等
     * 事件仍走 {@link #log} 立即输出，不受限频影响。
     */
    public static void throttled(String category, String key, int intervalTicks, String message) {
        if (!allowThrottled(key, intervalTicks)) {
            return;
        }
        log(category, "[限频" + Math.max(1, intervalTicks) + "t] " + message);
    }

    /** 任意分类的限频输出便捷入口。 */
    public static void logThrottled(String key, int intervalTicks, String category, String message) {
        throttled(category, key, intervalTicks, message);
    }

    /** 判定限频 key 当前是否允许输出；允许时刷新时间戳。日志关闭时恒为 {@code false}。 */
    public static boolean allowThrottled(String key, int intervalTicks) {
        if (!ENABLED) {
            return false;
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(1, intervalTicks) * MILLIS_PER_TICK;
        // 必须原子判定：客户端线程与服务端线程会用同一个 key（同一玩家 UUID）打同一条日志，
        // 先 get 再 put 会让两边都认为"已过期"，出现同毫秒的重复行（实测 tick RETURN 打了两遍）。
        boolean[] allowed = new boolean[1];
        THROTTLE.compute(key, (ignored, last) -> {
            if (last != null && now - last < interval) {
                return last;
            }
            allowed[0] = true;
            return now;
        });
        return allowed[0];
    }

    // ===================== 分类便捷入口 =====================

    public static void attack(String action, Entity attacker, Entity target) {
        if (!ENABLED) {
            return;
        }
        log(ATTACK, action + " 攻击者=" + describe(attacker) + " 目标=" + describe(target));
    }

    public static void attack(String action, Entity attacker, Entity target, String detail) {
        if (!ENABLED) {
            return;
        }
        log(ATTACK, action + " 攻击者=" + describe(attacker) + " 目标=" + describe(target)
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    public static void defense(String action, Entity player) {
        if (!ENABLED) {
            return;
        }
        log(DEFENSE, action + " 玩家=" + describe(player));
    }

    public static void defense(String action, Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        log(DEFENSE, action + " 玩家=" + describe(player)
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    public static void health(String action, Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        log(HEALTH, action + " 玩家=" + describe(player)
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    public static void inventory(String action, Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        log(INVENTORY, action + " 玩家=" + describe(player)
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    public static void sword(String action, Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        log(SWORD, action + " 玩家=" + describe(player)
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** 网络分类写入。 */
    public static void network(String action, String detail) {
        if (!ENABLED) {
            return;
        }
        log(NETWORK, action + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** agent 分类写入。 */
    public static void agent(String action, String detail) {
        if (!ENABLED) {
            return;
        }
        log(AGENT, action + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** 攻击分类限频输出（每 tick 检查的攻击相关行为，正常时 40t 一次）。 */
    public static void attackThrottled(String key, int intervalTicks, String action,
                                       Entity attacker, Entity target, String detail) {
        if (!ENABLED) {
            return;
        }
        throttled(ATTACK, key, intervalTicks,
                action + " 攻击者=" + describe(attacker) + " 目标=" + describe(target)
                        + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** 攻击分类限频输出（无 detail 版本）。 */
    public static void attackThrottled(String key, int intervalTicks, String action,
                                       Entity attacker, Entity target) {
        attackThrottled(key, intervalTicks, action, attacker, target, "");
    }

    /** 防御分类限频输出。 */
    public static void defenseThrottled(String key, int intervalTicks, String action,
                                        Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        throttled(DEFENSE, key, intervalTicks,
                action + " 玩家=" + describe(player)
                        + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** 血量分类限频输出。 */
    public static void healthThrottled(String key, int intervalTicks, String action,
                                       Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        throttled(HEALTH, key, intervalTicks,
                action + " 玩家=" + describe(player)
                        + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** 背包分类限频输出。 */
    public static void inventoryThrottled(String key, int intervalTicks, String action,
                                          Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        throttled(INVENTORY, key, intervalTicks,
                action + " 玩家=" + describe(player)
                        + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** 星辉剑分类限频输出。 */
    public static void swordThrottled(String key, int intervalTicks, String action,
                                      Entity player, String detail) {
        if (!ENABLED) {
            return;
        }
        throttled(SWORD, key, intervalTicks,
                action + " 玩家=" + describe(player)
                        + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    /** 实体描述：名称 + 实体运行时 id + UUID（攻击日志要求能拿到被攻击对象 id）。 */
    public static String describe(Entity entity) {
        if (entity == null) {
            return "<null>";
        }
        String name;
        try {
            name = entity.getName().getString();
        } catch (Throwable t) {
            name = String.valueOf(entity.getType());
        }
        String uuid;
        try {
            uuid = String.valueOf(entity.getUUID());
        } catch (Throwable t) {
            uuid = "?";
        }
        int id;
        try {
            id = entity.getId();
        } catch (Throwable t) {
            id = -1;
        }
        return name + "(entityId=" + id + ", uuid=" + uuid + ")";
    }

    // ===================== 独立日志文件（仅开发环境） =====================

    private static void appendFile(String text) {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            if (fileFailed) {
                return;
            }
            try {
                if (!fileReady) {
                    Path parent = LOG_FILE.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    writer = Files.newBufferedWriter(LOG_FILE, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    fileReady = true;
                    writer.write("===== " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            + " 星辉剑日志会话开始 =====");
                    writer.newLine();
                    registerShutdownHook();
                }
                writer.write("[" + LocalTime.now().format(TIME) + "] " + text);
                writer.newLine();
                writer.flush();
            } catch (Throwable t) {
                fileFailed = true;
                closeQuietly();
            }
        }
    }

    private static void registerShutdownHook() {
        if (!SHUTDOWN_HOOK.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(ModLog::closeQuietly, "StarSword-Log-Flush"));
        } catch (Throwable ignored) {
            // 关闭钩子注册失败不影响日志可用性：每行都已 flush。
        }
    }

    private static void closeQuietly() {
        synchronized (LOCK) {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (Throwable ignored) {
                    // ignore
                }
                writer = null;
            }
        }
    }

    private static String stackTraceOf(Throwable error) {
        StringWriter out = new StringWriter();
        try (PrintWriter printer = new PrintWriter(out)) {
            error.printStackTrace(printer);
        }
        return out.toString();
    }
}
