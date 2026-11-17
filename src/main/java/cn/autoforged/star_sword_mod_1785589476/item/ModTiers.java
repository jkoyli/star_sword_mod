package cn.autoforged.star_sword_mod_1785589476.item;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.ForgeTier;

public class ModTiers {
    public static final ForgeTier STAR_SWORD_TIER = new ForgeTier(
            3,
            1561,
            8.0f,
            3.0f,
            15,
            BlockTags.create(new ResourceLocation(
                    StarSwordMod.MOD_ID, "incorrect_for_star_sword_tool")),
            () -> Ingredient.EMPTY
    );
}
