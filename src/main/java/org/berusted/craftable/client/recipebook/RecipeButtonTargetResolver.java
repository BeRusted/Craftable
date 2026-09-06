package org.berusted.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.api.CraftingStatus;

/**
 * Gives one cycling vanilla recipe button one stable Craftable meaning.
 * The animated recipe remains visual only; it must not change what C executes.
 */
public final class RecipeButtonTargetResolver {
    private RecipeButtonTargetResolver() {}

    static List<RecipeHolder<?>> candidates(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<RecipeHolder<?>> candidates = new ArrayList<>();
        candidates.addAll(collection.getDisplayRecipes(true));
        candidates.addAll(collection.getDisplayRecipes(false));
        if (candidates.isEmpty()) {
            candidates.add(button.getRecipe());
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

    static RecipeHolder<?> preferredRecipe(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<RecipeHolder<?>> candidates = candidates(button);
        List<CraftingStatus> statuses = new ArrayList<>(candidates.size());
        List<Boolean> vanillaCraftable = new ArrayList<>(candidates.size());
        for (RecipeHolder<?> candidate : candidates) {
            boolean vanillaStatus = collection.isCraftable(candidate);
            vanillaCraftable.add(vanillaStatus);
            statuses.add(ClientRecipeStatusStore.get(candidate.id(), vanillaStatus));
        }
        int currentIndex = candidates.indexOf(button.getRecipe());
        return candidates.get(preferredIndex(statuses, vanillaCraftable, currentIndex));
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
        // Before the first server response, preserve vanilla's own positive
        // choice where possible; otherwise retain the currently shown variant.
        for (int index = 0; index < vanillaCraftable.size(); index++) {
            if (vanillaCraftable.get(index)) {
                return index;
            }
        }
        return currentIndex >= 0 && currentIndex < statuses.size() ? currentIndex : 0;
    }
}
