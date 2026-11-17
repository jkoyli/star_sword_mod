package cn.autoforged.star_sword_mod_1785589476.mixinmon;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin 全局错误处理器：把 Mixin 自己报告的注入失败<b>抄送</b>给 {@link MixinFailureRegistry}。
 *
 * <p>移植自星-穹工程的 {@code StarDomeMixinErrorHandler}。注入点失败（{@code @At} 没命中、
 * 目标方法不存在、{@code @Inject} 校验不通过）只会以 WARN 形式出现一次，游戏照常启动；
 * 服主和开发者都看不见。本处理器把这条信号留下来，供 {@link MixinProbe} 与
 * {@code AgentStatus} 汇总展示。
 *
 * <p><b>注册与实例化</b>：Mixin 在 {@code MixinProcessor.getErrorHandlers(phase)} 里用
 * {@code getDeclaredConstructor().newInstance()} 反射实例化，因此本类必须是 {@code public}
 * 且带 {@code public} 无参构造。该方法<b>每次出错都新建一个实例</b>，所以状态只能放在静态的
 * {@link MixinFailureRegistry} 里。
 *
 * <p><b>只记本模组</b>：这是<b>全局</b>错误处理器——别的模组的失败也会回调进来。筛选放在
 * {@link MixinFailureRegistry#record} 的入口，本类只负责如实上报，不做筛选，这样「谁的失败该记」
 * 只有一处判据。
 *
 * <p><b>返回值刻意一律为 {@code null}</b>：{@code MixinProcessor.handleMixinError()} 的动作是
 * {@code config.isRequired() ? ERROR : WARN}；返回 {@code null} 表示<b>保持默认</b>——
 * 本模组配置是 {@code required=false}，于是默认动作是 WARN + 完整堆栈，游戏照常启动。
 * 本类<b>绝不</b>返回 {@code ErrorAction.ERROR}，绝不把 WARN 抬成崩游戏。
 *
 * <p><b>早期加载安全（硬要求）</b>：只允许引用 {@code java.*} 与 {@code org.spongepowered.asm.*}
 * （另加 {@link MixinFailureRegistry}，它只碰 {@code java.*} / {@code log4j}）。
 *
 * <p><b>异常契约（Never throws）</b>：{@code MixinProcessor} 调用回调时只 catch
 * {@code AbstractMethodError}，<b>其它任何 Throwable 都会直接逃逸并打断类变换</b>。
 * 因此两个回调内部逐字段 try/catch，最外层再兜一层，绝不抛出。
 */
public class StarSwordMixinErrorHandler implements IMixinErrorHandler {

    /** 供 {@link StarSwordMixinConfigPlugin} 注册用的全限定类名（两份各写一遍，避免相互引用）。 */
    public static final String CLASS_NAME =
            "cn.autoforged.star_sword_mod_1785589476.mixinmon.StarSwordMixinErrorHandler";

    /** Mixin 用 {@code getDeclaredConstructor().newInstance()} 实例化，必须有 public 无参构造。 */
    public StarSwordMixinErrorHandler() {
    }

    /**
     * 配置准备期失败（例如 mixin 类加载不出来、{@code @Mixin} 目标无法解析）。
     *
     * @return 一律 {@code null} = 保持 Mixin 的默认动作（{@code required=false} ⇒ WARN，不崩）
     */
    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        report("prepare", config, "", th, mixin);
        return null;
    }

    /**
     * 应用期失败（例如 {@code @At} / 目标方法没命中、{@code @Inject} 校验不通过）。
     *
     * @param targetClassName 出错时正在变换的目标类名
     * @return 一律 {@code null} = 保持 Mixin 的默认动作（{@code required=false} ⇒ WARN，不崩）
     */
    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        report("apply", null, targetClassName, th, mixin);
        return null;
    }

    /**
     * 把回调参数折算成 {@link MixinFailureRegistry} 的一条记录。
     *
     * <p>每个取值都单独 try/catch：{@code IMixinInfo}/{@code IMixinConfig} 的 getter 在失败路径上
     * 未必安全（例如 mixin 类根本没加载成功），任何一个 getter 抛异常都不能让整条记录丢掉，
     * 更不能让异常逃逸。
     */
    private static void report(String phase, IMixinConfig config, String targetClassName, Throwable th,
                               IMixinInfo mixin) {
        try {
            String configName = null;
            if (mixin != null) {
                try {
                    IMixinConfig mixinConfig = mixin.getConfig();
                    if (mixinConfig != null) {
                        configName = mixinConfig.getName();
                    }
                } catch (Throwable ignored) {
                    // 取不到就退回下面用回调参数里的 config
                }
            }
            if (configName == null && config != null) {
                try {
                    configName = config.getName();
                } catch (Throwable ignored) {
                    // 仍然取不到：留空，由 isOurs 的包前缀判据兜底
                }
            }
            String mixinClassName = null;
            if (mixin != null) {
                try {
                    mixinClassName = mixin.getClassName();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            MixinFailureRegistry.record(phase, configName, mixinClassName, targetClassName, th);
        } catch (Throwable throwable) {
            // 回调里抛异常会直接打断 Mixin 的变换流程 —— 这里必须彻底吞掉
            MixinFailureRegistry.logError("记录 mixin 失败时出错", throwable);
        }
    }
}
