package cn.autoforged.star_sword_mod_1785589476.client.render.cosmic;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 每帧把玩家朝向喂给 {@code cosmic} 着色器。
 *
 * <p>用 {@link RenderLevelStageEvent} 而不是 tick：tick 只有 20Hz，
 * 转头时星空的球面会一跳一跳。这里拿 partialTick 做插值，跟得上。
 */
@Mod.EventBusSubscriber(modid = StarSwordMod.MOD_ID, value = Dist.CLIENT)
public final class CosmicEvents {

    private CosmicEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!CosmicShaders.ready()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        float partial = event.getPartialTick();
        CosmicShaders.updateUniforms(
                (float) Math.toRadians(player.getViewYRot(partial)),
                (float) -Math.toRadians(player.getViewXRot(partial)));
    }
}
