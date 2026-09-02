package org.berusted.craftable.execution;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.environment.ContainerEndpoint;

/** A one-craft plan for the intentionally limited M0.2 direct-crafting slice. */
public record DirectCraftingPlan(
        ItemStack output,
        List<Extraction> extractions,
        List<ItemStack> consumedItems) {

    public DirectCraftingPlan {
        output = output.copy();
        extractions = List.copyOf(extractions);
        consumedItems = consumedItems.stream().map(ItemStack::copy).toList();
    }

    public record Extraction(ContainerEndpoint endpoint, int slot, int count, ItemStack expectedStack) {
        public Extraction {
            expectedStack = expectedStack.copy();
        }
    }
}
