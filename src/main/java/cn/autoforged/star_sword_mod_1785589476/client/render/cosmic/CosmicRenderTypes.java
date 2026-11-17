package cn.autoforged.star_sword_mod_1785589476.client.render.cosmic;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 星辉剑专用的 {@link RenderType}：只把着色器换成 {@code cosmic}，其余状态照抄原版物品。
 *
 * <p><b>为什么这么绕：</b>原版把 {@code TRANSLUCENT_TRANSPARENCY} / {@code LIGHTMAP}
 * 这些状态常量声明成 {@code protected static}，而它们在**生产环境里是 SRG 名**。
 * 日志实测：{@code NoSuchFieldException: TRANSLUCENT_TRANSPARENCY}，
 * 连 {@code ObfuscationReflectionHelper.findField} 都找不到（该表在生产环境里是空的）。
 *
 * <p>所以这里改成**按类型**取值，一个字段名都不依赖：
 * 先拿一个现成的 {@code RenderType.entityTranslucent(...)}，把它内部的
 * {@code CompositeState} 反射出来，再按 {@code instanceof} 分派回 builder 的各个 setter，
 * 只把 shader 那一项换成我们的。字段名在 reobf 下会变，类型不会。
 */
public final class CosmicRenderTypes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static RenderType cosmic;
    private static boolean failed;

    private CosmicRenderTypes() {
    }

    /** 着色器没起来或状态取不到时返回 {@code null}，调用方回退到原版 RenderType。 */
    public static RenderType get() {
        if (failed || !CosmicShaders.ready()) {
            return null;
        }
        if (cosmic == null) {
            try {
                cosmic = create();
                LOGGER.info("[star_sword] cosmic RenderType created");
            } catch (Throwable t) {
                failed = true;
                LOGGER.error("[star_sword] cosmic RenderType disabled", t);
                return null;
            }
        }
        return cosmic;
    }

    private static RenderType create() throws Exception {
        // 模板：原版物品用的就是它（NEW_ENTITY 格式 + 方块图集 + 半透明 + 光照贴图）
        RenderType template = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);

        Object composite = firstValueOfType(RenderType.class, template, RenderType.CompositeState.class);
        if (composite == null) {
            throw new IllegalStateException("RenderType.CompositeState not found on template");
        }

        RenderType.CompositeState.CompositeStateBuilder builder = RenderType.CompositeState.builder();
        boolean shaderSet = false;
        for (Object state : allValues(RenderType.CompositeState.class, composite)) {
            if (state == null) {
                continue;
            }
            if (state instanceof RenderStateShard.ShaderStateShard) {
                builder.setShaderState(new RenderStateShard.ShaderStateShard(CosmicShaders::get));
                shaderSet = true;
            } else if (state instanceof RenderStateShard.TransparencyStateShard s) {
                builder.setTransparencyState(s);
            } else if (state instanceof RenderStateShard.DepthTestStateShard s) {
                builder.setDepthTestState(s);
            } else if (state instanceof RenderStateShard.CullStateShard s) {
                builder.setCullState(s);
            } else if (state instanceof RenderStateShard.LightmapStateShard s) {
                builder.setLightmapState(s);
            } else if (state instanceof RenderStateShard.OverlayStateShard s) {
                builder.setOverlayState(s);
            } else if (state instanceof RenderStateShard.WriteMaskStateShard s) {
                builder.setWriteMaskState(s);
            } else if (state instanceof RenderStateShard.OutputStateShard s) {
                builder.setOutputState(s);
            } else if (state instanceof RenderStateShard.TexturingStateShard s) {
                builder.setTexturingState(s);
            } else if (state instanceof RenderStateShard.LayeringStateShard s) {
                builder.setLayeringState(s);
            } else if (state instanceof RenderStateShard.EmptyTextureStateShard s) {
                builder.setTextureState(s);
            }
        }
        if (!shaderSet) {
            throw new IllegalStateException("template has no ShaderStateShard");
        }

        return RenderType.create("star_sword_cosmic",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                builder.createCompositeState(true));
    }

    /**
     * 沿继承链找第一个类型为 {@code type} 的字段并读出值。
     *
     * <p>必须走整条继承链：{@code RenderType.entityTranslucent(...)} 实际返回的是子类
     * {@code CompositeRenderType}，{@code state} 字段声明在子类上，
     * 只扫 {@code RenderType} 本类会找不到（实测报 "CompositeState not found on template"）。
     */
    private static Object firstValueOfType(Class<?> stopAt, Object instance, Class<?> type) throws Exception {
        for (Class<?> c = instance.getClass(); c != null && stopAt.isAssignableFrom(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == type) {
                    field.setAccessible(true);
                    return field.get(instance);
                }
            }
        }
        return null;
    }

    /** 读出 {@code instance} 里所有字段的值（不看名字，按 instanceof 分派）。 */
    private static List<Object> allValues(Class<?> owner, Object instance) throws Exception {
        List<Object> out = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) {
            field.setAccessible(true);
            out.add(field.get(instance));
        }
        return out;
    }
}
