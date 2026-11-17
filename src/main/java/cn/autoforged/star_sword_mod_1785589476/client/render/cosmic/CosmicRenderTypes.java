package cn.autoforged.star_sword_mod_1785589476.client.render.cosmic;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.lang.reflect.Field;

/**
 * 星辉剑专用的 {@link RenderType}：只把着色器换成 {@code cosmic}，其余状态照抄原版物品。
 *
 * <p>顶点格式用 {@code NEW_ENTITY}——1.20.1 里物品/实体走的就是它，用别的格式会和
 * {@code ItemRenderer} 写出的数据对不上。
 *
 * <p>状态常量（{@code TRANSLUCENT_TRANSPARENCY} 等）在原版里是 protected，
 * access transformer 对方法生效、对字段没生效，所以这里改用反射取，取不到就整体回退。
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
            } catch (Throwable t) {
                failed = true;
                LOGGER.error("[star_sword] cosmic RenderType disabled", t);
                return null;
            }
        }
        return cosmic;
    }

    private static RenderType create() throws Exception {
        RenderStateShard.TransparencyStateShard translucent = state(
                "TRANSLUCENT_TRANSPARENCY", RenderStateShard.TransparencyStateShard.class);
        RenderStateShard.LightmapStateShard lightmap = state(
                "LIGHTMAP", RenderStateShard.LightmapStateShard.class);

        return RenderType.create("star_sword_cosmic",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(CosmicShaders::get))
                        .setTextureState(new RenderStateShard.TextureStateShard(
                                TextureAtlas.LOCATION_BLOCKS, false, false))
                        .setTransparencyState(translucent)
                        .setLightmapState(lightmap)
                        .createCompositeState(true));
    }

    private static <T> T state(String name, Class<T> type) throws Exception {
        Field field = RenderStateShard.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(null));
    }
}
