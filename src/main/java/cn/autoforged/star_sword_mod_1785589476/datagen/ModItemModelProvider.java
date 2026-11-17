package cn.autoforged.star_sword_mod_1785589476.datagen;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import cn.autoforged.star_sword_mod_1785589476.item.ModItems;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.registries.RegistryObject;

import net.minecraft.world.item.Item;

public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, StarSwordMod.MOD_ID, helper);
    }

    @Override
    protected void registerModels() {
        handheldItem(ModItems.STAR_SWORD);
        basicItem(ModItems.COUNTERATTACK_TALISMAN.get());
        basicItem(ModItems.SPAWN_PREVENTION_TALISMAN.get());
        basicItem(ModItems.REFLECTION_TALISMAN.get());
    }

    private void handheldItem(RegistryObject<Item> item) {
        withExistingParent(item.getId().getPath(),
                        new ResourceLocation("minecraft", "item/diamond_sword"))
                .texture("layer0",
                        new ResourceLocation(StarSwordMod.MOD_ID,
                                "item/" + item.getId().getPath()));
    }
}
