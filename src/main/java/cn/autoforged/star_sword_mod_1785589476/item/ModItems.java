package cn.autoforged.star_sword_mod_1785589476.item;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, StarSwordMod.MOD_ID);

    public static final RegistryObject<Item> STAR_SWORD = ITEMS.register("star_sword",
            () -> new StarSwordItem(ModTiers.STAR_SWORD_TIER, 3, -2.4f,
                    new Item.Properties()
                            .rarity(Rarity.COMMON)
                            .fireResistant()));

    public static final RegistryObject<Item> COUNTERATTACK_TALISMAN = ITEMS.register("counterattack_talisman",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.COMMON)));

    public static final RegistryObject<Item> SPAWN_PREVENTION_TALISMAN = ITEMS.register("spawn_prevention_talisman",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.COMMON)));

    public static final RegistryObject<Item> REFLECTION_TALISMAN = ITEMS.register("reflection_talisman",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.COMMON)));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
