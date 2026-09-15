package org.berusted.craftable.execution;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.environment.ContainerEndpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record DirectCraftingPlan(
        RecipeHolder<CraftingRecipe> recipe,
        int gridSize,
        List<ItemStack> gridItems,
        ItemStack output,
        List<ItemStack> remainingItems,
        List<Extraction> extractions) {

    public DirectCraftingPlan {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(output, "output");
        if (gridSize != 2 && gridSize != 3) {
            throw new IllegalArgumentException("Crafting grid must be 2x2 or 3x3");
        }
        if (gridItems.size() != gridSize * gridSize) {
            throw new IllegalArgumentException("Crafting grid item count does not match its dimensions");
        }
        if (output.isEmpty()) {
            throw new IllegalArgumentException("Crafting output must not be empty");
        }
        gridItems = copyStacks(gridItems);
        output = output.copy();
        remainingItems = copyStacks(remainingItems.stream().filter(stack -> !stack.isEmpty()).toList());
        extractions = List.copyOf(extractions);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }

    @Override
    public List<ItemStack> gridItems() {
        return copyStacks(gridItems);
    }

    @Override
    public ItemStack output() {
        return output.copy();
    }

    @Override
    public List<ItemStack> remainingItems() {
        return copyStacks(remainingItems);
    }

    public List<ItemStack> producedItems() {
        List<ItemStack> produced = new ArrayList<>(1 + remainingItems.size());
        produced.add(output.copy());
        remainingItems.forEach(stack -> produced.add(stack.copy()));
        return List.copyOf(produced);
    }

    public record Extraction(ContainerEndpoint endpoint, int slot, int count, ItemStack expectedStack) {
        public Extraction {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(expectedStack, "expectedStack");
            if (!endpoint.containsSlot(slot) || count <= 0 || expectedStack.isEmpty() || expectedStack.getCount() < count) {
                throw new IllegalArgumentException("Invalid crafting extraction");
            }
            expectedStack = expectedStack.copy();
        }

        @Override
        public ItemStack expectedStack() {
            return expectedStack.copy();
        }
    }
}
