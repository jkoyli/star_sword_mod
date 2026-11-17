package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 实体完整性自检（<b>只观测，不改任何行为</b>）。
 *
 * <h2>为什么需要它</h2>
 * <p>"压制 {@code isRemoved()} / 拦截移除包"这类方案都建立在一个前提上：实体到底是<b>被移除</b>
 * （removalReason 被写、从实体查询结构里摘掉），还是<b>被跳过</b>（tick 不再调用它，但没被移除）。
 * 两种情况解法完全不同。这里先把事实量出来。
 *
 * <h2>读什么（全部纯读，不触发实体/区块加载）</h2>
 * <ol>
 *   <li>{@code Entity.removalReason}（{@code f_146795_}）——反射直读，绕开我们的 Mixin 与 agent 注入；</li>
 *   <li>{@code EntityLookup.byId / byUuid}——不是 {@code containsKey}，而是<b>身份比较</b>
 *       {@code map.get(id) == entity}：实体 id 会被复用、玩家重生会产生同 UUID 的新实例，
 *       只查 key 会把"槽位被别人占了"误判成"老实体还在"；</li>
 *   <li>{@code EntityTickList.contains}——第二证据（tick 表里到底有没有它）；</li>
 *   <li>{@code EntitySectionStorage.sections[...].storage.contains}——第三证据（区块 section）；</li>
 *   <li>本窗口内我们压制了它几次（{@code PlayerDefense.forceNotRemoved} 与 agent {@code isRemoved} 闸门）。</li>
 * </ol>
 *
 * <h2>判读</h2>
 * <table border="1">
 *   <tr><th>removalReason</th><th>还在查询表</th><th>tick 表 / tickCount</th><th>结论</th></tr>
 *   <tr><td>非 null</td><td>否</td><td>—</td><td>{@link Verdict#REMOVED}（路径 A：被移除）</td></tr>
 *   <tr><td>非 null</td><td>是</td><td>—</td><td>{@link Verdict#REASON_ONLY}（理由写了但没真摘掉）</td></tr>
 *   <tr><td>null</td><td>否</td><td>—</td><td>{@link Verdict#UNLINKED}（结构摘了但没写理由）</td></tr>
 *   <tr><td>null</td><td>是</td><td>不在 tick 表 / tickCount 停滞</td><td>{@link Verdict#SKIPPED}（路径 B：被跳过）</td></tr>
 *   <tr><td>null</td><td>是</td><td>正常</td><td>{@link Verdict#CONSISTENT}（正常）</td></tr>
 * </table>
 *
 * <p>任何一处读不到（映射漂移、反射失败）一律记 {@link Verdict#UNKNOWN}——把"读不到"当成"不在表里"
 * 会把正常情况误判成路径 A。
 *
 * <p>采样点在<b>服务器 tick</b>（{@code TickEvent.ServerTickEvent} END）而不是玩家 tick：实体真的被跳过时
 * 玩家 tick 根本不会发生，从里面采样就永远看不到路径 B。
 */
public final class EntityIntegrityProbe {

    /** 采样窗口：与日志限频一致（40 tick ≈ 2 秒）。 */
    public static final int WINDOW_TICKS = ModLog.TICK_INTERVAL;

    /** 每多少个窗口打一次存活汇总（30 × 40t ≈ 60 秒），证明探针还在跑。 */
    public static final int SUMMARY_WINDOWS = 30;

    /** 单次采样的结论。 */
    public enum Verdict {
        /** 反射不可用 / 尚未采样。 */
        UNKNOWN,
        /** 没被移除、tick 正常推进。 */
        CONSISTENT,
        /** 被移除（removalReason 已写且引擎结构里已经没有它）。 */
        REMOVED,
        /** 被跳过（没被移除，但不在 tick 表里 / tickCount 停滞）。 */
        SKIPPED,
        /** removalReason 已写，但引擎结构仍认它。 */
        REASON_ONLY,
        /** 引擎结构里已经没有它，但 removalReason 没写。 */
        UNLINKED
    }

    private static final Map<UUID, Integer> LAST_TICK_COUNT = new ConcurrentHashMap<>();
    private static final Map<UUID, Verdict> LAST_VERDICT = new ConcurrentHashMap<>();
    private static final Map<UUID, LongAdder> SUPPRESSED = new ConcurrentHashMap<>();
    private static final Map<Verdict, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private static volatile Verdict lastVerdict = Verdict.UNKNOWN;
    private static volatile String lastDetail = "尚未采样";
    private static int window;
    private static int summaryWindows;

    private EntityIntegrityProbe() {
    }

    /**
     * 我们压制了一次 {@code isRemoved()}（{@code PlayerDefense.forceNotRemoved} 或 agent 的
     * {@code shouldHideRemoved} 强制分支）。
     *
     * <p>语义：这是"被压制的<b>读取</b>次数"，包含本模组自己的调用（AI/死亡判定也会读
     * {@code isRemoved()}），不等于"外部实现企图移除的次数"。
     */
    public static void noteSuppressed(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        SUPPRESSED.computeIfAbsent(player.getUUID(), key -> new LongAdder()).increment();
    }

    /** 每个服务器 tick 调用一次；内部按 {@link #WINDOW_TICKS} 降采样。 */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++window < WINDOW_TICKS) {
            return;
        }
        window = 0;
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            for (ServerPlayer player : players) {
                try {
                    sample(player);
                } catch (Throwable ignored) {
                    // 单个玩家失败不影响其它玩家
                }
            }
        } catch (Throwable ignored) {
            // 服务器尚未就绪
        }
        if (++summaryWindows >= SUMMARY_WINDOWS) {
            summaryWindows = 0;
            if (!COUNTS.isEmpty()) {
                ModLog.log(ModLog.DEFENSE, "实体完整性 汇总 " + describe());
            }
        }
    }

    private static void sample(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        UUID id = player.getUUID();
        // 黏性追踪：曾经受保护过就继续采，否则实体被移出世界/死亡清背包后 isProtected 变 false，
        // 恰好会漏掉我们最想看的 REMOVED 样本。
        boolean tracked = SwordGuard.isProtected(player)
                || PlayerDefense.hasAuthoritativeHealth(player)
                || LAST_TICK_COUNT.containsKey(id);
        if (!tracked) {
            return;
        }

        int tickCount = player.tickCount;
        Integer previousTick = LAST_TICK_COUNT.put(id, tickCount);
        boolean tickCountStalled = previousTick != null && previousTick.intValue() == tickCount;

        Boolean removedByReason = removedByReason(player);
        Boolean inLookup = inEntityLookup(level, player);
        Boolean inTickList = inTickList(level, player);
        Boolean inSection = inSection(level, player);

        Verdict verdict;
        if (removedByReason == null || inLookup == null) {
            verdict = Verdict.UNKNOWN;
        } else if (removedByReason) {
            verdict = inLookup ? Verdict.REASON_ONLY : Verdict.REMOVED;
        } else if (!inLookup) {
            verdict = Verdict.UNLINKED;
        } else if (Boolean.FALSE.equals(inTickList) || tickCountStalled) {
            verdict = Verdict.SKIPPED;
        } else {
            verdict = Verdict.CONSISTENT;
        }

        COUNTS.computeIfAbsent(verdict, key -> new LongAdder()).increment();
        lastVerdict = verdict;
        lastDetail = "player=" + player.getGameProfile().getName()
                + " tick=" + tickCount
                + " inLookup=" + inLookup
                + " inTickList=" + inTickList
                + " inSection=" + inSection
                + " 压制命中=" + suppressedCount(id)
                + " 旧壳=" + isSuperseded(player);

        Verdict previous = LAST_VERDICT.put(id, verdict);
        boolean anomaly = verdict == Verdict.REMOVED || verdict == Verdict.SKIPPED
                || verdict == Verdict.REASON_ONLY || verdict == Verdict.UNLINKED;
        // 异常持续时每个窗口都打（限频 2s）：只在"变化"时打会让一整段故障只剩一行，
        // 反而分不清"一直正常"和"探针没跑"。
        if (previous != verdict || anomaly) {
            ModLog.log(ModLog.DEFENSE, "实体完整性 " + verdict + " " + lastDetail
                    + " removalReason=" + rawRemovalReasonName(player));
        }
    }

    private static long suppressedCount(UUID id) {
        LongAdder adder = SUPPRESSED.get(id);
        return adder == null ? 0L : adder.sum();
    }

    /**
     * 这个实体是不是"已经被同 UUID 的新实体顶替的旧壳"。
     *
     * <p>为什么需要：{@code UNLINKED}（不在 lookup/section 但还在 tickList）有两种成因——我们自己
     * 拦了移除，或者外部把玩家实体换成了新的（例如走 {@code PlayerList.respawn} 的复活/替换路径），
     * 旧壳被摘掉引用后短暂残留在 tick 列表里。两种情况的处置完全相反（前者是我们的 bug，后者是
     * 外部换了实体），所以日志必须把这一位带上，否则只能靠猜。
     */
    private static boolean isSuperseded(net.minecraft.server.level.ServerPlayer player) {
        try {
            if (player == null) {
                return false;
            }
            var level = player.serverLevel();
            return level.getPlayerByUUID(player.getUUID()) != player;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== 四元组读取（纯读，失败返回 null） =====================

    /** {@code removalReason != null}；字段解析失败返回 {@code null}（与"未移除"严格区分）。 */
    private static Boolean removedByReason(Entity entity) {
        Field field = removalReasonField();
        if (field == null) {
            return null;
        }
        try {
            return field.get(entity) != null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String rawRemovalReasonName(Entity entity) {
        Field field = removalReasonField();
        if (field == null) {
            return "<字段不可用>";
        }
        try {
            Object value = field.get(entity);
            return String.valueOf(value);
        } catch (Throwable t) {
            return "<?>";
        }
    }

    /**
     * 引擎是否仍认<b>这个实体实例</b>（{@code EntityLookup.byId / byUuid} 的身份比较）。
     *
     * <p>注意不能用 {@code containsKey}：实体 id 会复用，玩家重生后是同 UUID 的新实例。
     */
    private static Boolean inEntityLookup(ServerLevel level, Entity entity) {
        try {
            Field managerField = entityManagerField();
            Field storageField = visibleStorageField();
            Field byIdField = byIdField();
            Field byUuidField = byUuidField();
            if (managerField == null || storageField == null
                    || byIdField == null || byUuidField == null) {
                return null;
            }
            Object manager = managerField.get(level);
            if (manager == null) {
                return null;
            }
            Object lookup = storageField.get(manager);
            if (lookup == null) {
                return null;
            }
            int entityId = entity.getId();
            UUID uuid = entity.getUUID();
            Object byId = byIdField.get(lookup);
            if (byId instanceof Map<?, ?> idMap && idMap.get(entityId) == entity) {
                return Boolean.TRUE;
            }
            Object byUuid = byUuidField.get(lookup);
            if (byUuid instanceof Map<?, ?> uuidMap && uuidMap.get(uuid) == entity) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 实体是否仍在 {@code EntityTickList} 里；反射不可用返回 {@code null}。 */
    private static Boolean inTickList(ServerLevel level, Entity entity) {
        try {
            Field field = tickListField();
            Method contains = tickListContains();
            if (field == null || contains == null) {
                return null;
            }
            Object tickList = field.get(level);
            if (tickList == null) {
                return null;
            }
            Object result = contains.invoke(tickList, entity);
            return result instanceof Boolean value ? value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 实体是否仍在所属区块 section 里（第三证据；失败返回 {@code null}）。 */
    private static Boolean inSection(ServerLevel level, Entity entity) {
        try {
            Field managerField = entityManagerField();
            Field storageField = sectionStorageField();
            Field sectionsField = sectionsField();
            Field sectionStorageField = sectionStorageFieldOfSection();
            if (managerField == null || storageField == null || sectionsField == null
                    || sectionStorageField == null) {
                return null;
            }
            Object manager = managerField.get(level);
            if (manager == null) {
                return null;
            }
            Object sectionStorage = storageField.get(manager);
            if (sectionStorage == null) {
                return null;
            }
            Object sections = sectionsField.get(sectionStorage);
            if (!(sections instanceof Map<?, ?> sectionMap)) {
                return null;
            }
            long key = net.minecraft.core.SectionPos.asLong(entity.blockPosition());
            Object section = sectionMap.get(key);
            if (section == null) {
                return Boolean.FALSE;
            }
            Object storage = sectionStorageField.get(section);
            if (storage instanceof java.util.Collection<?> instances) {
                for (Object instance : instances) {
                    if (instance == entity) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================== 反射句柄（懒解析一次，失败返回 null） =====================

    private static volatile boolean resolved;
    private static Field removalReasonField;
    private static Field entityManagerField;
    private static Field visibleStorageField;
    private static Field sectionStorageField;
    private static Field sectionsField;
    private static Field sectionStorageFieldOfSection;
    private static Field byIdField;
    private static Field byUuidField;
    private static Field tickListField;
    private static Method tickListContains;

    private static Field removalReasonField() {
        resolve();
        return removalReasonField;
    }

    private static Field entityManagerField() {
        resolve();
        return entityManagerField;
    }

    private static Field visibleStorageField() {
        resolve();
        return visibleStorageField;
    }

    private static Field sectionStorageField() {
        resolve();
        return sectionStorageField;
    }

    private static Field sectionsField() {
        resolve();
        return sectionsField;
    }

    private static Field sectionStorageFieldOfSection() {
        resolve();
        return sectionStorageFieldOfSection;
    }

    private static Field byIdField() {
        resolve();
        return byIdField;
    }

    private static Field byUuidField() {
        resolve();
        return byUuidField;
    }

    private static Field tickListField() {
        resolve();
        return tickListField;
    }

    private static Method tickListContains() {
        resolve();
        return tickListContains;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (EntityIntegrityProbe.class) {
            if (resolved) {
                return;
            }
            ClassLoader loader = EntityIntegrityProbe.class.getClassLoader();
            try {
                removalReasonField = ReflectionHelper.findFieldCompat(
                        Entity.class, "f_146795_", "removalReason");
                entityManagerField = ReflectionHelper.findFieldCompat(
                        ServerLevel.class, "f_143244_", "entityManager");
                tickListField = ReflectionHelper.findFieldCompat(
                        ServerLevel.class, "f_143243_", "entityTickList");

                Class<?> managerClass = Class.forName(
                        "net.minecraft.world.level.entity.PersistentEntitySectionManager", false, loader);
                visibleStorageField = ReflectionHelper.findFieldCompat(
                        managerClass, "f_157494_", "visibleEntityStorage");
                sectionStorageField = ReflectionHelper.findFieldCompat(
                        managerClass, "f_157495_", "sectionStorage");

                Class<?> lookupClass = Class.forName(
                        "net.minecraft.world.level.entity.EntityLookup", false, loader);
                byIdField = ReflectionHelper.findFieldCompat(lookupClass, "f_156807_", "byId");
                byUuidField = ReflectionHelper.findFieldCompat(lookupClass, "f_156808_", "byUuid");

                Class<?> sectionStorageClass = Class.forName(
                        "net.minecraft.world.level.entity.EntitySectionStorage", false, loader);
                sectionsField = ReflectionHelper.findFieldCompat(
                        sectionStorageClass, "f_156852_", "sections");

                Class<?> sectionClass = Class.forName(
                        "net.minecraft.world.level.entity.EntitySection", false, loader);
                sectionStorageFieldOfSection = ReflectionHelper.findFieldCompat(
                        sectionClass, "f_156827_", "storage");

                Class<?> tickListClass = Class.forName(
                        "net.minecraft.world.level.entity.EntityTickList", false, loader);
                tickListContains = ReflectionHelper.findMethodCompat(
                        tickListClass, "m_156914_", "contains", Entity.class);
            } catch (Throwable ignored) {
                // 解析失败：各 getter 返回 null，结论记 UNKNOWN。
            }
            resolved = true;
        }
    }

    // ===================== 客户端本地玩家（另一半事实） =====================

    /**
     * 客户端侧结论。
     *
     * <p>"立正 / 空血"最常见的组合是<b>服务端一切正常、客户端本地实体坏了</b>（被移除包摘掉、
     * 或者客户端本地实体被换掉）。只观测服务端会得出"没问题"的错误结论，所以这里补另一半。
     */
    public enum ClientVerdict {
        UNKNOWN,
        /** 本地实体在、未被移除、还在本地世界里。 */
        OK,
        /** 本地实体被标了 removalReason。 */
        REMOVED,
        /** 本地实体不在本地世界里（被摘除/被替换）。 */
        MISSING
    }

    private static volatile ClientVerdict lastClientVerdict = ClientVerdict.UNKNOWN;
    private static volatile String lastClientDetail = "尚未采样";
    private static int clientWindow;

    /** 客户端每 tick 调用；内部按 {@link #WINDOW_TICKS} 降采样。 */
    public static void tickClient(net.minecraft.world.entity.player.Player localPlayer) {
        if (++clientWindow < WINDOW_TICKS) {
            return;
        }
        clientWindow = 0;
        ClientVerdict verdict;
        String detail;
        if (localPlayer == null) {
            verdict = ClientVerdict.MISSING;
            detail = "本地玩家为空";
        } else {
            Boolean removed = removedByReason(localPlayer);
            boolean inLevel;
            try {
                inLevel = localPlayer.level().getEntity(localPlayer.getId()) == localPlayer;
            } catch (Throwable t) {
                inLevel = false;
            }
            detail = "player=" + localPlayer.getGameProfile().getName()
                    + " tick=" + localPlayer.tickCount
                    + " inLevel=" + inLevel
                    + " removalReason=" + rawRemovalReasonName(localPlayer);
            if (removed == null) {
                verdict = ClientVerdict.UNKNOWN;
            } else if (removed) {
                verdict = ClientVerdict.REMOVED;
            } else if (!inLevel) {
                verdict = ClientVerdict.MISSING;
            } else {
                verdict = ClientVerdict.OK;
            }
        }
        ClientVerdict previous = lastClientVerdict;
        lastClientVerdict = verdict;
        lastClientDetail = detail;
        boolean anomaly = verdict == ClientVerdict.REMOVED || verdict == ClientVerdict.MISSING;
        if (previous != verdict || anomaly) {
            ModLog.log(ModLog.DEFENSE, "实体完整性（客户端） " + verdict + " " + detail);
        }
    }

    // ===================== 展示 =====================

    /** 一行摘要，供 {@code /starsword agent status} 展示。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        if (COUNTS.isEmpty()) {
            sb.append("服务端未采样");
        } else {
            sb.append("最近=").append(lastVerdict)
                    .append("，A 被移除=").append(count(Verdict.REMOVED))
                    .append("，B 被跳过=").append(count(Verdict.SKIPPED))
                    .append("，第三类=").append(count(Verdict.REASON_ONLY))
                    .append("，结构摘除但无理由=").append(count(Verdict.UNLINKED))
                    .append("，正常=").append(count(Verdict.CONSISTENT))
                    .append("，未知=").append(count(Verdict.UNKNOWN))
                    .append("（").append(lastDetail).append("）");
        }
        sb.append("；客户端=").append(lastClientVerdict).append("（")
                .append(lastClientDetail).append("）");
        return sb.toString();
    }

    private static long count(Verdict verdict) {
        LongAdder adder = COUNTS.get(verdict);
        return adder == null ? 0L : adder.sum();
    }

    /** 测试/退服用：清空观测状态。 */
    public static void reset() {
        LAST_TICK_COUNT.clear();
        LAST_VERDICT.clear();
        SUPPRESSED.clear();
        COUNTS.clear();
        lastVerdict = Verdict.UNKNOWN;
        lastDetail = "尚未采样";
        window = 0;
        summaryWindows = 0;
        lastClientVerdict = ClientVerdict.UNKNOWN;
        lastClientDetail = "尚未采样";
        clientWindow = 0;
    }
}
