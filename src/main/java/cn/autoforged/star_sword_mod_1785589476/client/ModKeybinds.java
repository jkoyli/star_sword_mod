package cn.autoforged.star_sword_mod_1785589476.client;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 星辉剑命令面板的键位绑定：默认 H，可在游戏键位设置中更换。
 * 键位类型同时支持键盘与鼠标（如绑定为鼠标键，也能触发面板开关）。
 */
@Mod.EventBusSubscriber(modid = StarSwordMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModKeybinds {

    public static final KeyMapping OPEN_COMMAND_PANEL = new KeyMapping(
            "key.star_sword_mod_1785589476.command_panel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.categories.star_sword_mod_1785589476");

    /** 机制面板：默认 M，同样可在键位设置中更换，支持键盘键与鼠标键。 */
    public static final KeyMapping OPEN_MECHANIC_PANEL = new KeyMapping(
            "key.star_sword_mod_1785589476.mechanic_panel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.star_sword_mod_1785589476");

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_COMMAND_PANEL);
        event.register(OPEN_MECHANIC_PANEL);
    }
}
