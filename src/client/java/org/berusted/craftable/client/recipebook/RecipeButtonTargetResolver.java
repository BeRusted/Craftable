package org.berusted.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.api.CraftingResultCode;

/**
 * Equivalent recipes share a best route, but different outputs retain the
 * vanilla displayed choice. Animation must not select another boat material.
 */
public final class RecipeButtonTargetResolver {
    private RecipeButtonTargetResolver() {}

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
        for (RecipeHolder<?> candidate : equivalentCandidates(button)) {
            statuses.add(ClientRecipeStatusStore.display(
                    candidate.id(), collection.isCraftable(candidate)));
        }
        return strongestStatus(statuses);
    }

    @org.jetbrains.annotations.Nullable
    public static RecipeHolder<?> preferredRecipe(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<RecipeHolder<?>> candidates = equivalentCandidates(button);
        if (candidates.isEmpty()) return null;
        List<CraftingStatus> statuses = new ArrayList<>(candidates.size());
        List<Boolean> vanillaCraftable = new ArrayList<>(candidates.size());
        List<CraftingResultCode> reasons = new ArrayList<>(candidates.size());
        for (RecipeHolder<?> candidate : candidates) {
            boolean vanillaStatus = collection.isCraftable(candidate);
            vanillaCraftable.add(vanillaStatus);
            statuses.add(ClientRecipeStatusStore.display(candidate.id(), vanillaStatus));
            reasons.add(ClientRecipeStatusStore.reason(candidate.id()));
        }
        // Only equal item+components may substitute for the displayed result.
        // Yield differences are legitimate alternative routes, not materials.
        return candidates.get(preferredIndex(statuses, vanillaCraftable, reasons, 0));
    }

    private static List<RecipeHolder<?>> equivalentCandidates(RecipeButton button) {
        var shown = button.getRecipe(); // Existing mixin bounds-checks stale indices.
        if (shown == null) return List.of();
        var registries = button.getCollection().registryAccess();
        var output = shown.value().getResultItem(registries);
        return candidates(button).stream().filter(r -> ItemStack.isSameItemSameComponents(
                output, r.value().getResultItem(registries))).toList();
    }

    /** Bounded UI context only; never a grant to use another output recipe. */
    public static List<net.minecraft.resources.ResourceLocation> outputVariants(RecipeButton button) {
        // Include hidden/unavailable variants so the root chooser can explain
        // them. The extra entry signals truncation at the existing 16-card cap.
        var shown = button.getRecipe();
        return java.util.stream.Stream.concat(shown == null ? java.util.stream.Stream.empty()
                : java.util.stream.Stream.of(shown.id()), candidates(button).stream().map(RecipeHolder::id))
                .distinct().limit(17).toList();
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
        var recipes = equivalentCandidates(button);
        var target = preferredRecipe(button);
        if (target == null) return ClientRecipeStatusStore.Lifecycle.UNKNOWN;
        if (ClientRecipeStatusStore.display(target.id(), false) == CraftingStatus.CRAFTABLE) {
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
        return preferredIndex(statuses, vanillaCraftable, java.util.Collections.nCopies(statuses.size(), null), currentIndex);
    }

    static int preferredIndex(List<CraftingStatus> statuses, List<Boolean> vanillaCraftable,
            List<CraftingResultCode> reasons, int currentIndex) {
        if (statuses.size() != vanillaCraftable.size() || statuses.size() != reasons.size() || statuses.isEmpty()) {
            throw new IllegalArgumentException("Recipe variant state must be non-empty and aligned");
        }
        for (int index = 0; index < statuses.size(); index++) {
            if (statuses.get(index) == CraftingStatus.CRAFTABLE) {
                return index;
            }
        }
        for (int index = 0; index < statuses.size(); index++) {
            if (statuses.get(index) == CraftingStatus.PARTIAL) return index;
        }
        // A proven material route blocked only by delivery is more useful
        // than an arbitrary unseeded alternative (bones, not missing bone
        // blocks). This is target selection within the same output group,
        // never a positive craftability verdict or execution authority.
        for (int index = 0; index < reasons.size(); index++) {
            if (reasons.get(index) == CraftingResultCode.NO_OUTPUT_SPACE) return index;
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
