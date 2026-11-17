package cn.autoforged.star_sword_mod_1785589476;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.item.ModCreativeTab;
import cn.autoforged.star_sword_mod_1785589476.item.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(StarSwordMod.MOD_ID)
public class StarSwordMod {
    public static final String MOD_ID = "star_sword_mod_1785589476";

    public StarSwordMod() {
        ModLog.init();
        // agent 不在这里附加：构造期还处于 mod 加载阶段（其它模组的 sided_setup 正在跑），
        // 此时注入/重放会打断它们的遍历，实测导致别的模组抛 ConcurrentModificationException。
        // 改到 FMLLoadCompleteEvent（ModBusEvents.onLoadComplete）再附加。
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.register(modEventBus);
        ModCreativeTab.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);
    }
}
