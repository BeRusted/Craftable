package org.berusted.craftable.client.mixin;

import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.stats.RecipeBook;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.client.recipebook.RecipeBookProjection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeCollection.class)
public abstract class RecipeCollectionMixin {
    @Shadow @Final private List<RecipeHolder<?>> recipes;
    @Shadow @Final private Set<RecipeHolder<?>> known;
    @Shadow @Final private Set<RecipeHolder<?>> fitsDimensions;
    @Shadow @Final private Set<RecipeHolder<?>> craftable;

    @Inject(method = "canCraft", at = @At("HEAD"), cancellable = true)
    private void craftable$project(StackedContents contents, int width, int height, RecipeBook book, CallbackInfo ci) {
        // These are display sets, never the player's unlocked recipe set. Vanilla
        // will recompute them normally when a different screen is active.
        if (!RecipeBookProjection.active()) {
            known.clear();
            for (var recipe : recipes) if (book.contains(recipe)) known.add(recipe);
            return;
        }
        known.clear();
        fitsDimensions.clear();
        craftable.clear();
        for (var recipe : recipes) {
            if (!RecipeBookProjection.visible(recipe)) continue;
            known.add(recipe);
            fitsDimensions.add(recipe);
            if (ClientRecipeStatusStore.get(recipe.id(), false) == CraftingStatus.CRAFTABLE) craftable.add(recipe);
        }
        ci.cancel();
    }
}
