package cn.autoforged.star_sword_mod_1785589476.client.render;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 预先注册黑洞用到的 5 张 GUI 贴图。
 *
 * <p>为什么不依赖 {@code TextureManager.getTexture} 的按需加载：那套行为随版本变过，
 * 而且失败时只会画成"紫黑方格"，不会抛异常——是最难查的那类 bug。
 * 这里在客户端重载阶段显式 register，等于把"贴图一定在"这件事钉死。
 *
 * <p>注册在 {@link RegisterClientReloadListenersEvent} 里而不是 setup 里：
 * 此时 {@code TextureManager} 与资源管理器都已就绪，且该事件只在客户端触发，
 * 不会把客户端类带进专用服务端。
 */
@Mod.EventBusSubscriber(modid = StarSwordMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FxTextures {

    private static final String[] NAMES = {
            "blackhole_disk",
            "blackhole_halo",
            "blackhole_ring",
            "blackhole_core",
            "blackhole_streak",
            "blackhole_stars",
    };

    private FxTextures() {
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        for (String name : NAMES) {
            ResourceLocation location = new ResourceLocation(StarSwordMod.MOD_ID,
                    "textures/gui/" + name + ".png");
            mc.getTextureManager().register(location, new SimpleTexture(location));
        }
    }
}
