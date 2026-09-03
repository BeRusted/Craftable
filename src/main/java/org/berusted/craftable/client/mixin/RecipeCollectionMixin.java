package org.berusted.craftable.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.stats.RecipeBook;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.client.recipebook.ClientWorkstationProbe;
import org.berusted.craftable.config.CraftableClientConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RecipeCollection.class)
public abstract class RecipeCollectionMixin {
    @Redirect(
            method = {"updateKnownRecipes", "canCraft"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/stats/RecipeBook;contains(Lnet/minecraft/world/item/crafting/RecipeHolder;)Z"))
    private boolean craftable$showWithoutMutatingUnlocks(RecipeBook book, RecipeHolder<?> recipe) {
        return CraftableClientConfig.recipeBookEnhancementsEnabled() || book.contains(recipe);
    }

    @ModifyVariable(method = "canCraft", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int craftable$effectiveGridWidth(int width) {
        return craftable$hasAmbientCraftingTable() ? 3 : width;
    }

    @ModifyVariable(method = "canCraft", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int craftable$effectiveGridHeight(int height) {
        return craftable$hasAmbientCraftingTable() ? 3 : height;
    }

    private static boolean craftable$hasAmbientCraftingTable() {
        return CraftableClientConfig.recipeBookEnhancementsEnabled()
                && Minecraft.getInstance().screen instanceof InventoryScreen
                && ClientWorkstationProbe.hasNearbyCraftingTable();
    }
}
