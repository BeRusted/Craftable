package org.berusted.craftable.environment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Authorized browsing values only. There are no world positions, container
 * names, live handles or external slot numbers in this model. A snapshot is
 * not execution authority; lease and connection validation belong to its owner. */
public record BrowsingSnapshot(UUID session, long recipes, long resources, boolean workbench,
        boolean limitedCrafting, Set<ResourceLocation> unlocked, boolean allowDrops,
        CraftRequest.PartialPolicy partialPolicy, int maxBatches, int inventoryMaximum,
        List<ItemStack> inventory, List<Input> inputs) {
    public static final int MAX_INPUTS = 4096;
    public static final int MAX_UNLOCKED = 16_384;

    public BrowsingSnapshot {
        java.util.Objects.requireNonNull(session);
        java.util.Objects.requireNonNull(partialPolicy);
        if (recipes < 0 || resources < 0 || maxBatches < 1 || maxBatches > CraftRequest.MAX_BATCHES
                || inventoryMaximum < 1 || inventoryMaximum > 99 || inventory.size() != 36
                || inputs.size() > MAX_INPUTS || unlocked.size() > MAX_UNLOCKED)
            throw new IllegalArgumentException("Invalid browsing snapshot");
        unlocked = Set.copyOf(unlocked);
        inventory = CraftPlan.copies(inventory);
        inputs = List.copyOf(inputs);
        var references = new HashSet<UUID>();
        var inventorySlots = new HashSet<Integer>();
        for (var input : inputs) {
            if (!references.add(input.reference())) throw new IllegalArgumentException("Duplicate source reference");
            if (input.inventorySlot() >= 0 && (!inventorySlots.add(input.inventorySlot())
                    || !ItemStack.matches(input.stack(), inventory.get(input.inventorySlot()))))
                throw new IllegalArgumentException("Source does not match inventory capacity");
        }
        // Own inventory already reaches this player through vanilla slot sync.
        // Retain its exact components for capacity; replacing a protected stack
        // with an ordinary-item placeholder would invent merge space. Protected
        // external stacks never enter inputs and expose no component data.
    }

    @Override public List<ItemStack> inventory() { return CraftPlan.copies(inventory); }

    public List<ResourceLedger.Source> sources() {
        return inputs.stream().map(input -> new ResourceLedger.Source(input.reference().toString(),
                Math.max(0, input.inventorySlot()), input.stack())).toList();
    }

    public Set<String> playerReferences() {
        return inputs.stream().filter(input -> input.inventorySlot() >= 0).map(input -> input.reference().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> references() {
        return inputs.stream().map(input -> input.reference().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Exact normalized content, excluding resource numbering and lease/scan
     * metadata. Renewal cannot invalidate math just by changing a timestamp.
     * Server authorization also checks its private source bindings separately. */
    public Object contentIdentity() {
        return List.of(recipes, workbench, limitedCrafting, unlocked, allowDrops, partialPolicy,
                maxBatches, inventoryMaximum, CraftPlan.stackKeys(inventory), inputs.stream()
                        .map(input -> List.of(input.reference(), input.inventorySlot(),
                                CraftPlan.stackKeys(List.of(input.stack())))).toList());
    }

    public record Input(UUID reference, int inventorySlot, ItemStack stack) {
        public Input {
            java.util.Objects.requireNonNull(reference);
            if (inventorySlot < -1 || inventorySlot >= 36 || stack.isEmpty()
                    || stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()
                    || CraftingRecipes.protectedStack(stack))
                throw new IllegalArgumentException("Invalid authorized source");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }
}
