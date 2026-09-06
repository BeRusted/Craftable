package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.CommonHooks;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.workstation.WorkstationCapability;

/** Read-only construction of one executable crafting plan. */
final class DirectCraftingPlanner {
    private DirectCraftingPlanner() {}

    static DirectCraftingEvaluation evaluate(
            ServerPlayer player, ResourceLocation recipeId, EnvironmentSnapshot snapshot) {
        if (!hasValidContext(player)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.INVALID_CONTEXT);
        }

        ServerLevel level = player.serverLevel();
        RecipeHolder<?> rawHolder = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (rawHolder == null) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.RECIPE_NOT_FOUND);
        }
        if (!"minecraft".equals(recipeId.getNamespace()) || !(rawHolder.value() instanceof CraftingRecipe recipe)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }
        if (recipe.getSerializer() != RecipeSerializer.SHAPED_RECIPE
                && recipe.getSerializer() != RecipeSerializer.SHAPELESS_RECIPE) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }
        if (recipe.isSpecial() || recipe.isIncomplete() || recipe.getIngredients().isEmpty()) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }

        @SuppressWarnings("unchecked")
        RecipeHolder<CraftingRecipe> holder = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) rawHolder;
        if (level.getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(holder)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.RECIPE_LOCKED);
        }

        int gridSize = recipe.canCraftInDimensions(2, 2) ? 2 : 3;
        if (gridSize == 3 && !snapshot.supports(WorkstationCapability.CRAFTING_3X3)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.MISSING_WORKSTATION);
        }

        int patternWidth = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 0;
        AllocatedGrid allocation = allocate(
                recipe.getIngredients(), gridSize, patternWidth, recipe instanceof ShapedRecipe, snapshot.endpoints());
        if (allocation == null) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.MISSING_INGREDIENTS);
        }

        CraftingInput input = CraftingInput.of(gridSize, gridSize, allocation.gridItems());
        if (!recipe.matches(input, level)) {
            // Allocation and the recipe itself must agree. Treat disagreement as
            // unsupported instead of guessing at a different input layout.
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }

        try {
            ItemStack output = recipe.assemble(input, level.registryAccess()).copy();
            if (output.isEmpty()) {
                return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
            }

            NonNullList<ItemStack> remainders;
            CommonHooks.setCraftingPlayer(player);
            try {
                remainders = recipe.getRemainingItems(input);
            } finally {
                CommonHooks.setCraftingPlayer(null);
            }
            if (remainders.size() != input.size()) {
                return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
            }

            DirectCraftingPlan plan = new DirectCraftingPlan(
                    holder,
                    gridSize,
                    allocation.gridItems(),
                    output,
                    remainders,
                    allocation.extractions());
            if (!MainInventoryInsertion.canFitAfterExtractions(player.getInventory(), plan)) {
                return DirectCraftingEvaluation.blocked(CraftingResultCode.NO_OUTPUT_SPACE);
            }
            return DirectCraftingEvaluation.craftable(plan);
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error(
                    "Failed to construct direct crafting plan for {} and recipe {}",
                    player.getGameProfile().getName(),
                    recipeId,
                    exception);
            return DirectCraftingEvaluation.blocked(CraftingResultCode.INTERNAL_ERROR);
        }
    }

    static boolean hasValidContext(ServerPlayer player) {
        return !player.isSpectator()
                && !player.isDeadOrDying()
                && player.containerMenu == player.inventoryMenu
                && player.serverLevel().getServer().isSameThread();
    }

    /** Package-private allocation seam used by deterministic unit tests. */
    static AllocatedGrid allocate(
            List<Ingredient> ingredients,
            int gridSize,
            int patternWidth,
            boolean shaped,
            List<ContainerEndpoint> endpoints) {
        if (gridSize != 2 && gridSize != 3) {
            throw new IllegalArgumentException("Crafting grid must be 2x2 or 3x3");
        }
        if (shaped && patternWidth <= 0) {
            throw new IllegalArgumentException("Shaped allocation requires a pattern width");
        }

        List<SourceSlot> sources = new ArrayList<>();
        for (ContainerEndpoint endpoint : endpoints) {
            int lastSlot = endpoint.firstSlot() + endpoint.slotCount();
            for (int slot = endpoint.firstSlot(); slot < lastSlot; slot++) {
                ItemStack stack = endpoint.container().getItem(slot);
                if (!stack.isEmpty()) {
                    sources.add(new SourceSlot(endpoint, slot, stack.copy()));
                }
            }
        }

        List<Requirement> requirements = new ArrayList<>();
        int shapelessIndex = 0;
        for (int ingredientIndex = 0; ingredientIndex < ingredients.size(); ingredientIndex++) {
            Ingredient ingredient = ingredients.get(ingredientIndex);
            if (ingredient.isEmpty()) {
                continue;
            }
            int gridIndex = shaped
                    ? (ingredientIndex / patternWidth) * gridSize + ingredientIndex % patternWidth
                    : shapelessIndex++;
            if (gridIndex >= gridSize * gridSize) {
                return null;
            }
            List<Integer> candidates = new ArrayList<>();
            for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                if (ingredient.test(sources.get(sourceIndex).stack())) {
                    candidates.add(sourceIndex);
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            requirements.add(new Requirement(gridIndex, List.copyOf(candidates)));
        }
        if (requirements.isEmpty() || requirements.size() > gridSize * gridSize) {
            return null;
        }

        // Assign constrained ingredients first. With at most nine requirements,
        // this bounded backtracking avoids the broad-tag-before-exact-item greedy
        // failure without introducing the M4 recipe-graph planner.
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < requirements.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingInt(index -> requirements.get(index).candidates().size()));
        int[] available = sources.stream().mapToInt(source -> source.stack().getCount()).toArray();
        int[] chosenSource = new int[requirements.size()];
        java.util.Arrays.fill(chosenSource, -1);
        if (!assign(0, order, requirements, available, chosenSource)) {
            return null;
        }

        List<ItemStack> gridItems = new ArrayList<>(
                java.util.Collections.nCopies(gridSize * gridSize, ItemStack.EMPTY));
        int[] extractionCounts = new int[sources.size()];
        for (int requirementIndex = 0; requirementIndex < requirements.size(); requirementIndex++) {
            int sourceIndex = chosenSource[requirementIndex];
            SourceSlot source = sources.get(sourceIndex);
            gridItems.set(requirements.get(requirementIndex).gridIndex(), source.stack().copyWithCount(1));
            extractionCounts[sourceIndex]++;
        }

        List<DirectCraftingPlan.Extraction> extractions = new ArrayList<>();
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            int count = extractionCounts[sourceIndex];
            if (count > 0) {
                SourceSlot source = sources.get(sourceIndex);
                extractions.add(new DirectCraftingPlan.Extraction(
                        source.endpoint(), source.slot(), count, source.stack()));
            }
        }
        return new AllocatedGrid(gridItems, extractions);
    }

    private static boolean assign(
            int depth,
            List<Integer> order,
            List<Requirement> requirements,
            int[] available,
            int[] chosenSource) {
        if (depth == order.size()) {
            return true;
        }
        int requirementIndex = order.get(depth);
        for (int sourceIndex : requirements.get(requirementIndex).candidates()) {
            if (available[sourceIndex] <= 0) {
                continue;
            }
            available[sourceIndex]--;
            chosenSource[requirementIndex] = sourceIndex;
            if (assign(depth + 1, order, requirements, available, chosenSource)) {
                return true;
            }
            chosenSource[requirementIndex] = -1;
            available[sourceIndex]++;
        }
        return false;
    }

    record AllocatedGrid(List<ItemStack> gridItems, List<DirectCraftingPlan.Extraction> extractions) {
        AllocatedGrid {
            gridItems = gridItems.stream().map(ItemStack::copy).toList();
            extractions = List.copyOf(extractions);
        }

        @Override
        public List<ItemStack> gridItems() {
            return gridItems.stream().map(ItemStack::copy).toList();
        }
    }

    private record SourceSlot(ContainerEndpoint endpoint, int slot, ItemStack stack) {}

    private record Requirement(int gridIndex, List<Integer> candidates) {}
}
