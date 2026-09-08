package org.berusted.craftable.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.client.CraftableFeedback;
import org.berusted.craftable.client.recipebook.RecipeBookProjection;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.menu.AmbientInventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Shadow protected RecipeBookMenu<?, ?> menu;

    @ModifyVariable(method = "renderGhostRecipe", at = @At("HEAD"), argsOnly = true)
    private boolean craftable$compactResultGhost(boolean largeResultSlot) {
        // Grid dimensions and result-frame size are independent: our real 3x3
        // menu uses the inventory's compact output, not CraftingScreen's 24px
        // ghost highlight. Keep the incoming vanilla flag for every other menu.
        return !(menu instanceof AmbientInventoryMenu) && largeResultSlot;
    }

    @Redirect(method = "mouseClicked", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handlePlaceRecipe(ILnet/minecraft/world/item/crafting/RecipeHolder;Z)V"))
    private void craftable$validGhostGrid(MultiPlayerGameMode gameMode, int id, RecipeHolder<?> recipe, boolean shift) {
        // ALL may show 3x3 targets while the actual menu is 2x2. Never ask vanilla
        // to place that pattern into the wrong slot topology.
        if (RecipeBookProjection.active() && !recipe.value().canCraftInDimensions(menu.getGridWidth(), menu.getGridHeight())) {
            CraftableFeedback.showCreateResult(CraftingResultCode.MISSING_WORKSTATION, true);
            return;
        }
        gameMode.handlePlaceRecipe(id, recipe, shift);
    }
}
