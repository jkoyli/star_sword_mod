package cn.autoforged.star_sword_mod_1785589476.client.render.cosmic;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.model.BakedModel;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 星辉剑"球面星空"着色器的注册与 uniform 更新。
 *
 * <p>做法照搬 Avaritia 的 cosmic 渲染（见 tools/ref/avaritia/）：
 * 把玩家的 yaw / pitch 传进片元着色器，片元里按球面坐标采样星空，
 * 于是"转动视角 = 看到天球的不同部分"，物品贴图只负责 alpha 遮罩。
 *
 * <p>三个开关：{@code -Dstar_sword.fx.cosmic=false} 关掉整条着色器链路，
 * 模型退回原版，贴图就是生成器烘出来的静态星空。
 */
@Mod.EventBusSubscriber(modid = StarSwordMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CosmicShaders {

    /**
     * 总开关。
     *
     * <p>{@code -Dstar_sword.fx.cosmic=false} 关掉整条着色器链路。
     */
    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("star_sword.fx.cosmic", "true"));

    /** 注册失败标记：着色器编译不出来时不要每次都重试，也不要让渲染层以为可画。 */
    private static volatile boolean failed;

    private static ShaderInstance cosmic;

    private CosmicShaders() {
    }

    /**
     * 注册着色器。
     *
     * <p><b>必须 try/catch</b>：{@link ShaderInstance} 的构造函数会当场读 json 并编译，
     * 任何一步失败都会抛 {@link java.io.IOException}；事件处理器里让它冒出去会被 Forge
     * 当成致命错误直接崩游戏（2.3.6 实机就崩在
     * {@code FileNotFoundException: minecraft:shaders/core/cosmic.json} 上）。
     * 这里退化成「没有星空效果」，模型自动回退原版 RenderType。
     *
     * <p>注意 json 的位置：{@code ShaderInstance} 的字符串构造函数把名字拼成
     * {@code minecraft:shaders/core/<name>.json}，所以 json 必须放在
     * {@code assets/minecraft/shaders/core/}；而 json 里的 {@code vertex}/{@code fragment}
     * 可以显式写我们的命名空间（现在是 {@code star_sword_mod_...:cosmic}），
     * 因此 vsh/fsh 仍然留在 {@code assets/star_sword_mod_.../shaders/core/} 下。
     */
    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        if (!ENABLED || failed) {
            return;
        }
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), "cosmic", DefaultVertexFormat.NEW_ENTITY),
                    shader -> cosmic = shader);
        } catch (Throwable throwable) {
            failed = true;
            cosmic = null;
            System.err.println("[star_sword] cosmic 着色器注册失败，已回退原版渲染："
                    + throwable);
        }
    }

    /** 拿到当前着色器；没编译成功（或开关关掉）时返回 {@code null}，调用方据此回退。 */
    public static ShaderInstance get() {
        return ENABLED ? cosmic : null;
    }

    public static boolean ready() {
        return get() != null;
    }

    /** 每帧把玩家朝向写进去。角度单位是弧度，与 Avaritia 一致。 */
    public static void updateUniforms(float yawRad, float pitchRad) {
        ShaderInstance shader = cosmic;
        if (shader == null) {
            return;
        }
        setFloat(shader, "Yaw", yawRad);
        setFloat(shader, "Pitch", pitchRad);
        setFloat(shader, "ExternalScale", 1.0F);
        setFloat(shader, "Opacity", 1.0F);
    }

    private static void setFloat(ShaderInstance shader, String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    /**
     * 烘焙完成后把星辉剑的模型包一层，让它用我们的 RenderType 画。
     *
     * <p>只换 RenderType、不动模型本身，所以所有 display 变换（手持、GUI、掉落）
     * 仍然是原版算的——这是这个方案比"接管物品渲染器"安全的地方。
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ENABLED) {
            return;
        }
        String namespace = StarSwordMod.MOD_ID;
        int wrapped = 0;
        StringBuilder seen = new StringBuilder();
        // 不猜模型键的写法（inventory / #inventory / item/…），
        // 直接扫一遍所有烘焙结果，按 namespace + path 匹配这把剑。
        for (java.util.Map.Entry<ResourceLocation, BakedModel> entry
                : new java.util.ArrayList<>(event.getModels().entrySet())) {
            ResourceLocation key = entry.getKey();
            boolean match = key.getNamespace().equals(namespace)
                    && key.getPath().contains("star_sword");
            if (!match) {
                if (key.getNamespace().equals(namespace) && seen.length() < 400) {
                    seen.append("\n  ").append(key);
                }
                continue;
            }
            event.getModels().put(key, new CosmicModel(entry.getValue()));
            wrapped++;
        }
        if (wrapped == 0) {
            System.err.println("[star_sword] cosmic: no baked model matched; mod models seen:" + seen);
        } else {
            System.out.println("[star_sword] cosmic: wrapped " + wrapped + " model(s), shader="
                    + (cosmic != null));
        }
    }
}
