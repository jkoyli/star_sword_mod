package cn.autoforged.star_sword_mod_1785589476.item;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTab {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StarSwordMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> STAR_SWORD_TAB = CREATIVE_MODE_TABS.register("star_sword_tab",
            () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.STAR_SWORD.get()))
                    .title(Component.translatable("creativetab.star_sword_mod_1785589476.star_sword_tab"))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.STAR_SWORD.get());
                        output.accept(ModItems.COUNTERATTACK_TALISMAN.get());
                        output.accept(ModItems.SPAWN_PREVENTION_TALISMAN.get());
                        output.accept(ModItems.REFLECTION_TALISMAN.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
