package cn.autoforged.star_sword_mod_1785589476.mixinmon;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mixin 配置插件：唯一职责是把 {@link StarSwordMixinErrorHandler} 注册进 Mixin 的全局错误处理器表。
 *
 * <p>移植自星-穹工程的 {@code StarDomeMixinConfigPlugin}。本模组只有一份 mixin 配置
 * （{@code star_sword_mod_1785589476.mixins.json}），它写了
 * {@code "plugin": "cn.autoforged.star_sword_mod_1785589476.mixinmon.StarSwordMixinConfigPlugin"}，
 * Mixin 在 {@code MixinConfig.onSelect()} 里实例化并回调 {@link #onLoad(String)}，这一步发生在该配置的
 * mixin 被 prepare / apply <b>之前</b>，所以错误处理器能在真正需要它之前装好。
 *
 * <p><b>为什么用 plugin 而不是更简单的方式</b>：{@code Mixins.registerErrorHandlerClass} 是静态方法，
 * 必须在 Mixin 开始变换目标类之前调用；模组自己的 {@code @Mod} 构造器太晚（MC 类那时已被变换），
 * 而 mixin 配置的 plugin 是官方提供的「早期启动」入口。重复注册是幂等的，这里仍无条件调用
 * （万一某次注册抛异常，下一次还有机会），只用静态标志把<b>日志</b>去重。
 *
 * <p><b>其余方法一律取接口的默认语义</b>：不筛 mixin、不追加 mixin、不改字节码——本插件
 * <b>不改变任何注入行为</b>。
 *
 * <p><b>早期加载安全（硬要求）</b>：只允许引用 {@code java.*}、{@code org.spongepowered.asm.*}、
 * {@code org.objectweb.asm.*}（接口签名要求 {@link ClassNode}）与 {@link MixinFailureRegistry}。
 * <b>禁止</b>任何 {@code net.minecraft.*} / {@code net.minecraftforge.*} / {@code ModLog}。
 *
 * <p><b>异常契约（Never throws）</b>：每个方法都吞 {@link Throwable}——注册失败只记一条 ERROR，
 * 机制降级而不是崩游戏。
 */
public class StarSwordMixinConfigPlugin implements IMixinConfigPlugin {

    /**
     * 要注册的错误处理器全限定类名。
     *
     * <p>刻意写字面量、不写 {@code StarSwordMixinErrorHandler.class.getName()}：那会强制在本插件
     * 加载时解析错误处理器类；字面量则让 Mixin 自己在用的时候再去 {@code findClass}。
     */
    private static final String ERROR_HANDLER_CLASS =
            "cn.autoforged.star_sword_mod_1785589476.mixinmon.StarSwordMixinErrorHandler";

    /** 多份配置（未来可能增加）会各回调一次 onLoad：注册幂等，日志只打一次。 */
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    /** Mixin 用 {@code getDeclaredConstructor().newInstance()} 实例化，必须有 public 无参构造。 */
    public StarSwordMixinConfigPlugin() {
    }

    /** 插件装载回调（配置被选中时调用，早于该配置的 mixin 应用）：注册全局错误处理器。 */
    @Override
    public void onLoad(String mixinPackage) {
        try {
            Mixins.registerErrorHandlerClass(ERROR_HANDLER_CLASS);
            // 跨类加载器转达「插件确实跑过」：Mixin 可能用另一个加载器装载本类，模组侧读静态字段
            // 未必看得到同一个 Class，系统属性是 JVM 全局的。字面量与 MixinProbe.PLUGIN_MARKER 一致
            // （本类不允许引用 MixinProbe：它会拉进 MC 的 ModLog）。
            try {
                System.setProperty("star_sword.mixin.plugin", "ok");
            } catch (Throwable ignored) {
                // 系统属性写不进去不影响注册
            }
            if (LOGGED.compareAndSet(false, true)) {
                MixinFailureRegistry.logInfo("mixin 错误处理器已注册：" + ERROR_HANDLER_CLASS
                        + "（本行只打一次；配置=" + mixinPackage + "）");
            }
        } catch (Throwable throwable) {
            MixinFailureRegistry.logError("注册 mixin 错误处理器失败：" + ERROR_HANDLER_CLASS, throwable);
        }
    }

    /** 返回 {@code null} = 沿用配置里的 {@code refmap} 字段。 */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /** 一律放行：本插件不参与「哪些 mixin 该应用」的决策。 */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    /** 空实现：不需要观察其它配置的目标类。 */
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 刻意留空
    }

    /** 返回 {@code null} = 不追加任何 mixin。 */
    @Override
    public List<String> getMixins() {
        return null;
    }

    /** 空实现：不需要在应用前改字节码。 */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
        // 刻意留空
    }

    /** 空实现：不需要在应用后改字节码。 */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        // 刻意留空
    }
}
