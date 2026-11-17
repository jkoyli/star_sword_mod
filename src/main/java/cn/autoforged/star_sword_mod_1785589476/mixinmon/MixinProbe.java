package cn.autoforged.star_sword_mod_1785589476.mixinmon;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注入自检：确认「mixin handler 真的被合并进目标类」。
 *
 * <p>与 {@link MixinFailureRegistry} 互补：
 * <ul>
 *   <li>错误处理器只能拿到 Mixin <b>主动报告</b>的失败；</li>
 *   <li>本类在 {@code FMLLoadCompleteEvent} 直接检查目标类上有没有对应的方法体——
 *       覆盖「Mixin 认为成功、但方法被后续 transformer 又擦掉」这种既不报错也看不见的情况
 *       （本工程实测过：外部的类变换器会把我们的注入整段覆盖）。</li>
 * </ul>
 *
 * <p>检查方式刻意简单：Mixin 把 {@code @Inject}/{@code @Accessor} 方法合并进目标类时保留方法名，
 * 本工程所有 handler 都以 {@code starSword$} 开头，因此在目标类 {@code getDeclaredMethods()}
 * 里按名字找即可，不依赖 mixin 内部结构，也不需要反射进 Mixin 的私有表。
 *
 * <p>只做观测，绝不修改任何东西；加载目标类时用 {@code initialize=false}，不触发静态初始化。
 */
public final class MixinProbe {

    /** 插件是否真的被 Mixin 装载过（跨类加载器可见，用系统属性转达）。 */
    public static final String PLUGIN_MARKER = "star_sword.mixin.plugin";

    /** 一条期望：目标类 + 应存在的方法名 + 提供它的 mixin 类。 */
    private record Expectation(String targetClass, String handler, String mixinClass) {
    }

    private static final List<Expectation> EXPECTED = List.of(
            // LivingEntityMixin
            e("net.minecraft.world.entity.LivingEntity", "starSword$hurtHead"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$hurtReturn"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$sanitizeHealth"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$forceGetHealth"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$forceWriteVanillaHealthHead"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$forceWriteVanillaHealthReturn"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$isDeadOrDying"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$tickDeath"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$die"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$guardDeathLoot"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$markLootDropped"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$setHealthReturn"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$afterVanillaSave"),
            e("net.minecraft.world.entity.LivingEntity", "starSword$repairPersistedHealth"),
            // PlayerMixin / EntityMixin / EntityIsRemovedMixin / ServerPlayerMixin
            e("net.minecraft.world.entity.player.Player", "starSword$die"),
            e("net.minecraft.server.level.ServerPlayer", "starSword$die"),
            e("net.minecraft.world.entity.Entity", "starSword$guardRemoval"),
            e("net.minecraft.world.entity.Entity", "starSword$guardSetRemoved"),
            e("net.minecraft.world.entity.Entity", "starSword$forceNotRemoved"),
            // SynchedEntityDataMixin
            e("net.minecraft.network.syncher.SynchedEntityData", "starSword$guardHealthWrite"),
            e("net.minecraft.network.syncher.SynchedEntityData", "starSword$sanitizeAssignedValues"),
            // PersistentEntitySectionManagerAccessor（@Accessor 生成的方法同样合并进目标类）
            e("net.minecraft.world.level.entity.PersistentEntitySectionManager", "starSword$getVisibleEntityStorage"),
            e("net.minecraft.world.level.entity.PersistentEntitySectionManager", "starSword$getSectionStorage"),
            e("net.minecraft.world.level.entity.PersistentEntitySectionManager", "starSword$getKnownUuids"),
            // ClientPacketListenerMixin（专用服无此类，缺失按「跳过」处理）
            e("net.minecraft.client.multiplayer.ClientPacketListener", "starSword$blockDeathScreen"),
            e("net.minecraft.client.multiplayer.ClientPacketListener", "starSword$sanitizeHealthData"),
            e("net.minecraft.client.multiplayer.ClientPacketListener", "starSword$overrideHealth")
    );

    /** 客户端专用目标：专用服上加载不到属正常，不算失败。 */
    private static final List<String> CLIENT_ONLY = List.of(
            "net.minecraft.client.multiplayer.ClientPacketListener");

    private static volatile boolean ran;

    private MixinProbe() {
    }

    private static Expectation e(String targetClass, String handler) {
        return new Expectation(targetClass, handler, "cn.autoforged.star_sword_mod_1785589476.mixin");
    }

    /** 是否已经跑过一次（幂等，避免重复记录）。 */
    public static boolean ran() {
        return ran;
    }

    /**
     * 执行自检：在 {@code FMLLoadCompleteEvent} 调用一次。
     *
     * <p>输出两行摘要（全部在时一行；有缺失时逐条列出），并把缺失项写进
     * {@link MixinFailureRegistry}，供 {@code /starsword agent status} 读取。
     */
    public static void run() {
        if (ran) {
            return;
        }
        ran = true;
        List<MixinFailureRegistry.Failure> missing = new ArrayList<>();
        Map<String, int[]> perTarget = new LinkedHashMap<>();
        for (Expectation expectation : EXPECTED) {
            Class<?> target;
            try {
                target = Class.forName(expectation.targetClass(), false, MixinProbe.class.getClassLoader());
            } catch (Throwable notFound) {
                if (CLIENT_ONLY.contains(expectation.targetClass())) {
                    continue; // 专用服：该类本就不存在，跳过
                }
                missing.add(missing(expectation, "目标类加载失败：" + notFound));
                continue;
            }
            int[] counters = perTarget.computeIfAbsent(expectation.targetClass(), k -> new int[2]);
            if (hasHandler(target, expectation.handler())) {
                counters[0]++;
            } else {
                counters[1]++;
                MixinFailureRegistry.recordProbeFailure(
                        expectation.mixinClass(), expectation.targetClass(), expectation.handler());
                missing.add(new MixinFailureRegistry.Failure("probe",
                        String.join(",", MixinFailureRegistry.ourConfigNames()), expectation.mixinClass(),
                        expectation.targetClass(), "MissingHandler", expectation.handler()));
            }
        }

        int ok = 0;
        int bad = 0;
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, int[]> entry : perTarget.entrySet()) {
            int present = entry.getValue()[0];
            int absent = entry.getValue()[1];
            ok += present;
            bad += absent;
            if (summary.length() > 0) {
                summary.append("，");
            }
            summary.append(simpleName(entry.getKey())).append(' ').append(present).append('/')
                    .append(present + absent);
        }

        if (bad == 0) {
            ModLog.log(ModLog.INIT, "Mixin 注入自检通过：handler " + ok + " 个全部在目标类里（"
                    + summary + "）；错误处理器标记=" + pluginMarker() + "；自报失败="
                    + MixinFailureRegistry.size() + " 条");
            return;
        }

        ModLog.log(ModLog.INIT, "Mixin 注入自检发现缺失：handler 在 " + ok + "，缺失 " + bad + "（"
                + summary + "）；错误处理器标记=" + pluginMarker() + "；自报失败="
                + MixinFailureRegistry.size() + " 条");
        for (MixinFailureRegistry.Failure failure : missing) {
            ModLog.log(ModLog.INIT, "缺失 handler：" + failure.targetClassName() + " 上的 " + failure.message()
                    + "（mixin=" + failure.mixinClassName() + "）");
        }
    }

    private static MixinFailureRegistry.Failure missing(Expectation expectation, String message) {
        MixinFailureRegistry.recordProbeFailure(
                expectation.mixinClass(), expectation.targetClass(), expectation.handler());
        return new MixinFailureRegistry.Failure("probe",
                String.join(",", MixinFailureRegistry.ourConfigNames()), expectation.mixinClass(),
                expectation.targetClass(), "MissingHandler", message);
    }

    /** 目标类上是否存在该方法（不触发初始化，只查已合并的方法表）。 */
    private static boolean hasHandler(Class<?> target, String handler) {
        try {
            for (Method method : target.getDeclaredMethods()) {
                String name = method.getName();
                if (name.equals(handler) || name.endsWith(handler) || name.contains(handler)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 错误处理器是否注册过（由 {@link StarSwordMixinConfigPlugin#onLoad} 写系统属性）。 */
    public static String pluginMarker() {
        try {
            String value = System.getProperty(PLUGIN_MARKER);
            return value == null ? "missing" : value;
        } catch (Throwable throwable) {
            return "unknown";
        }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
