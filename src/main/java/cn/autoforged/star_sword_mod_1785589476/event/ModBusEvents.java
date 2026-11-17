package cn.autoforged.star_sword_mod_1785589476.event;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import cn.autoforged.star_sword_mod_1785589476.agent.PocAgentBootstrap;
import cn.autoforged.star_sword_mod_1785589476.item.ModItems;
import cn.autoforged.star_sword_mod_1785589476.mixinmon.MixinProbe;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

@Mod.EventBusSubscriber(modid = StarSwordMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModBusEvents {

    @SubscribeEvent
    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.STAR_SWORD.get());
            event.accept(ModItems.COUNTERATTACK_TALISMAN.get());
            event.accept(ModItems.SPAWN_PREVENTION_TALISMAN.get());
            event.accept(ModItems.REFLECTION_TALISMAN.get());
        }
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModMessages::register);
    }

    /**
     * agent 在“加载全部完成之后”才附加。
     *
     * <p>原来在 {@code StarSwordMod} 构造期就 attach 并 retransform 核心类，而那时其它模组的
     * {@code sided_setup} 还在跑：我们注入的代码一旦被它们触发，会连带做静态初始化（网络注册等），
     * 打断它们正在遍历的集合——实测表现为别的模组抛 {@code ConcurrentModificationException}。
     *
     * <p>改到这里后，加载阶段完全没有我们的字节码，代价只是“进入世界前多几毫秒”才装上方法层。
     * 显式关闭仍然用 {@code -Dstar_sword.poc.agent=0} / {@code -PssAgent=0}。
     */
    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        // 先自检 Mixin 是否真的落到了目标类上（agent 之前做，量的是 Mixin 单独的结果）。
        event.enqueueWork(MixinProbe::run);
        event.enqueueWork(PocAgentBootstrap::installIfEnabled);
    }
}
