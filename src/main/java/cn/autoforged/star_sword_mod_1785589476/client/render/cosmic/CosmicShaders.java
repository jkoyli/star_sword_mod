package cn.autoforged.star_sword_mod_1785589476.client.render.cosmic;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

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

    /** 总开关。 */
    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("star_sword.fx.cosmic", "true"));

    private static ShaderInstance cosmic;

    private CosmicShaders() {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), "cosmic", DefaultVertexFormat.NEW_ENTITY),
                shader -> cosmic = shader);
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
        ModelResourceLocation key = new ModelResourceLocation(
                new ResourceLocation(StarSwordMod.MOD_ID, "star_sword"), "inventory");
        BakedModel original = event.getModels().get(key);
        if (original != null) {
            event.getModels().put(key, new CosmicModel(original));
        } else {
            // 键名对不上就什么都不做：宁可没效果，也不能把物品画坏
            System.err.println("[star_sword] cosmic: model not found for " + key);
        }
    }
}
