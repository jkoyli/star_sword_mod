package cn.autoforged.star_sword_mod_1785589476.client.render.cosmic;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 只改"用哪个 RenderType 画"的模型包装器。
 *
 * <p>四边形、变换、粒子图标全部原样透传；唯一的改动是 {@link #getRenderTypes}：
 * 把原版那个 {@code entity_translucent} 换成 {@link CosmicRenderTypes}。
 * 这样剑的模型和 display 变换完全不受影响——坏了也只是"颜色不对"，不会变成空手。
 *
 * <p>没实现的那些 Forge 扩展方法会走接口默认实现，对物品模型没有影响。
 */
public final class CosmicModel implements BakedModel {

    private final BakedModel delegate;

    public CosmicModel(BakedModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<RenderType> getRenderTypes(net.minecraft.world.item.ItemStack stack, boolean fabulous) {
        RenderType cosmic = CosmicRenderTypes.get();
        if (cosmic == null) {
            return delegate.getRenderTypes(stack, fabulous);
        }
        List<RenderType> list = new ArrayList<>(1);
        list.add(cosmic);
        return list;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
        return delegate.getQuads(state, side, rand);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return delegate.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return delegate.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return delegate.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return delegate.isCustomRenderer();
    }

    @SuppressWarnings("deprecation")
    @Override
    public TextureAtlasSprite getParticleIcon() {
        return delegate.getParticleIcon();
    }

    @SuppressWarnings("deprecation")
    @Override
    public ItemTransforms getTransforms() {
        return delegate.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return delegate.getOverrides();
    }
}
