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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Shadow protected RecipeBookMenu<?, ?> menu;
    @Unique private Object craftable$scope;
    @Unique private java.util.List<net.minecraft.client.gui.screens.recipebook.RecipeCollection> craftable$shown = java.util.List.of();

    @Inject(method = "initVisuals", at = @At("HEAD"))
    private void craftable$newLayout(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        craftable$scope = null; craftable$shown = java.util.List.of();
    }

    @Redirect(method = "updateCollections", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookPage;updateCollections(Ljava/util/List;Z)V"))
    private void craftable$stablePage(net.minecraft.client.gui.screens.recipebook.RecipeBookPage page,
            java.util.List<net.minecraft.client.gui.screens.recipebook.RecipeCollection> incoming, boolean reset) {
        if (!RecipeBookProjection.active()) {
            craftable$scope = null; craftable$shown = java.util.List.of();
            page.updateCollections(incoming, reset); return;
        }
        var access = (RecipeBookComponentAccessor) this;
        var key = java.util.List.of(access.craftable$getSelectedTab().getCategory(),
                access.craftable$getSearchBox().getValue(), page.getRecipeBook().isFiltering(menu));
        var next = incoming;
        if (!reset && key.equals(craftable$scope)) {
            // Resource refreshes append newly discovered results, rather than
            // inserting them before a button being repeatedly used. Scope
            // changes still get vanilla ordering. Real removals remain visible.
            var members = new java.util.LinkedHashSet<>(incoming);
            next = new java.util.ArrayList<>();
            for (var previous : craftable$shown) if (members.remove(previous)) next.add(previous);
            next.addAll(members);
            // Vanilla also updates on inventory packets, outside our handler.
            // Do not re-init every button if only evidence/colors changed.
            if (next.equals(craftable$shown)) return;
        }
        craftable$scope = key; craftable$shown = java.util.List.copyOf(next);
        page.updateCollections(next, reset);
    }

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
