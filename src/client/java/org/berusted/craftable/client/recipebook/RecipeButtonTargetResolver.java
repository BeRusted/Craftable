package org.berusted.craftable.client.recipebook;

import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.api.CraftingStatus;

import java.util.ArrayList;
import java.util.List;

public final class RecipeButtonTargetResolver {
    private RecipeButtonTargetResolver() {
    }

    static List<RecipeHolder<?>> candidates(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<RecipeHolder<?>> candidates = new ArrayList<>();
        candidates.addAll(collection.getDisplayRecipes(true));
        candidates.addAll(collection.getDisplayRecipes(false));
        if (candidates.isEmpty()) {
            candidates.addAll(RecipeBookProjection.candidates(collection));
        }
        return List.copyOf(candidates);
    }

    public static CraftingStatus status(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<CraftingStatus> statuses = new ArrayList<>();
        for (RecipeHolder<?> candidate : candidates(button)) {
            statuses.add(ClientRecipeStatusStore.get(
                    candidate.id(), collection.isCraftable(candidate)));
        }
        return strongestStatus(statuses);
    }

    @org.jetbrains.annotations.Nullable
    public static RecipeHolder<?> preferredRecipe(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<RecipeHolder<?>> candidates = candidates(button);
        if (candidates.isEmpty()) return null;
        List<CraftingStatus> statuses = new ArrayList<>(candidates.size());
        List<Boolean> vanillaCraftable = new ArrayList<>(candidates.size());
        for (RecipeHolder<?> candidate : candidates) {
            boolean vanillaStatus = collection.isCraftable(candidate);
            vanillaCraftable.add(vanillaStatus);
            statuses.add(ClientRecipeStatusStore.get(candidate.id(), vanillaStatus));
        }

        return candidates.get(preferredIndex(statuses, vanillaCraftable, 0));
    }

    static CraftingStatus strongestStatus(List<CraftingStatus> statuses) {
        CraftingStatus best = CraftingStatus.BLOCKED;
        for (CraftingStatus status : statuses) {
            if (status == CraftingStatus.CRAFTABLE) {
                return status;
            }
            if (status == CraftingStatus.PARTIAL) {
                best = status;
            }
        }
        return best;
    }

    public static ClientRecipeStatusStore.Lifecycle lifecycle(RecipeButton button) {
        var recipes = candidates(button);
        var target = preferredRecipe(button);
        if (target == null) return ClientRecipeStatusStore.Lifecycle.UNKNOWN;
        if (ClientRecipeStatusStore.get(target.id(), false) == CraftingStatus.CRAFTABLE) {
            return ClientRecipeStatusStore.lifecycle(target.id());
        }
        boolean unknown = false;
        for (var recipe : recipes) {
            var state = ClientRecipeStatusStore.lifecycle(recipe.id());
            if (state == ClientRecipeStatusStore.Lifecycle.PENDING) return state;
            if (state == ClientRecipeStatusStore.Lifecycle.UNKNOWN) unknown = true;
        }
        return unknown ? ClientRecipeStatusStore.Lifecycle.UNKNOWN : ClientRecipeStatusStore.Lifecycle.KNOWN;
    }

    static int preferredIndex(
            List<CraftingStatus> statuses, List<Boolean> vanillaCraftable, int currentIndex) {
        if (statuses.size() != vanillaCraftable.size() || statuses.isEmpty()) {
            throw new IllegalArgumentException("Recipe variant state must be non-empty and aligned");
        }
        for (int index = 0; index < statuses.size(); index++) {
            if (statuses.get(index) == CraftingStatus.CRAFTABLE) {
                return index;
            }
        }
        for (int index = 0; index < vanillaCraftable.size(); index++) {
            if (vanillaCraftable.get(index)) {
                return index;
            }
        }
        return currentIndex >= 0 && currentIndex < statuses.size() ? currentIndex : 0;
    }
}
