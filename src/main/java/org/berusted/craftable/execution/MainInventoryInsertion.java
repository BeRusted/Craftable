package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.environment.EndpointKind;

/** Exact, all-or-nothing simulation and insertion for the player's 36 main slots. */
final class MainInventoryInsertion {
    private static final int MAIN_SLOT_COUNT = 36;

    private MainInventoryInsertion() {}

    static boolean canFitAfterExtractions(Inventory inventory, DirectCraftingPlan plan) {
        List<ItemStack> simulated = copyMainInventory(inventory);
        for (DirectCraftingPlan.Extraction extraction : plan.extractions()) {
            if (extraction.endpoint().kind() != EndpointKind.PLAYER) {
                continue;
            }
            ItemStack stack = simulated.get(extraction.slot());
            stack.shrink(extraction.count());
            if (stack.isEmpty()) {
                simulated.set(extraction.slot(), ItemStack.EMPTY);
            }
        }
        return insertAll(simulated, inventory.getMaxStackSize(), plan.producedItems());
    }

    static boolean insertAll(Inventory inventory, List<ItemStack> producedItems) {
        List<ItemStack> finalState = copyMainInventory(inventory);
        if (!insertAll(finalState, inventory.getMaxStackSize(), producedItems)) {
            return false;
        }
        // Only publish the simulated state after every output fits. This keeps a
        // late insertion mismatch from leaving a half-inserted result.
        for (int slot = 0; slot < MAIN_SLOT_COUNT; slot++) {
            inventory.setItem(slot, finalState.get(slot));
        }
        return true;
    }

    /** Mutates {@code slots} only when every produced stack can be inserted. */
    static boolean insertAll(List<ItemStack> slots, int containerMaxStackSize, List<ItemStack> producedItems) {
        List<ItemStack> working = slots.stream().map(ItemStack::copy).toList();
        working = new ArrayList<>(working);
        for (ItemStack produced : producedItems) {
            ItemStack remaining = produced.copy();
            mergeIntoExisting(working, containerMaxStackSize, remaining);
            fillEmptySlots(working, containerMaxStackSize, remaining);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        for (int slot = 0; slot < slots.size(); slot++) {
            slots.set(slot, working.get(slot));
        }
        return true;
    }

    private static void mergeIntoExisting(
            List<ItemStack> slots, int containerMaxStackSize, ItemStack remaining) {
        for (ItemStack existing : slots) {
            if (remaining.isEmpty()) {
                return;
            }
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int maximum = Math.min(containerMaxStackSize, existing.getMaxStackSize());
            int moved = Math.min(Math.max(0, maximum - existing.getCount()), remaining.getCount());
            existing.grow(moved);
            remaining.shrink(moved);
        }
    }

    private static void fillEmptySlots(
            List<ItemStack> slots, int containerMaxStackSize, ItemStack remaining) {
        for (int slot = 0; slot < slots.size() && !remaining.isEmpty(); slot++) {
            if (!slots.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(
                    Math.min(containerMaxStackSize, remaining.getMaxStackSize()), remaining.getCount());
            slots.set(slot, remaining.split(moved));
        }
    }

    private static List<ItemStack> copyMainInventory(Inventory inventory) {
        List<ItemStack> copy = new ArrayList<>(MAIN_SLOT_COUNT);
        for (int slot = 0; slot < MAIN_SLOT_COUNT; slot++) {
            copy.add(inventory.getItem(slot).copy());
        }
        return copy;
    }
}
