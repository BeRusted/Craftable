package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.environment.EndpointKind;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.api.CraftingResultCode;

/** Exact, all-or-nothing simulation and insertion for the player's 36 main slots. */
public final class MainInventoryInsertion {
    private static final int MAIN_SLOT_COUNT = 36;

    private MainInventoryInsertion() {}

    static Delivery simulate(Inventory inventory, CraftPlan plan, EnvironmentSnapshot snapshot, boolean allowDrops) {
        return simulate(copyMainInventory(inventory), inventory.getMaxStackSize(), plan,
                snapshot.endpoints().stream().filter(e -> e.kind() == EndpointKind.PLAYER)
                        .map(e -> e.id()).collect(java.util.stream.Collectors.toSet()),
                snapshot.endpoints().stream().map(e -> e.id()).collect(java.util.stream.Collectors.toSet()), allowDrops);
    }

    /** Preview and commit share the same capacity arithmetic. The preview
     * passes copied values, so one batch cannot observe two inventory states. */
    public static Delivery simulate(List<ItemStack> mainSlots, int maximum, CraftPlan plan,
            java.util.Set<String> playerEndpoints, java.util.Set<String> endpoints, boolean allowDrops) {
        List<ItemStack> simulated = new ArrayList<>(CraftPlan.copies(mainSlots));
        for (var extraction : plan.extractions()) {
            if (!endpoints.contains(extraction.endpointId())) return Delivery.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
            if (playerEndpoints.contains(extraction.endpointId())) {
                if (extraction.slot() < 0 || extraction.slot() >= simulated.size())
                    return Delivery.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
                var stack = simulated.get(extraction.slot());
                if (stack.getCount() < extraction.count()) return Delivery.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
                stack.shrink(extraction.count());
            }
        }
        return fit(simulated, maximum, plan.primary(), plan.surplus(), allowDrops);
    }

    static Delivery fit(List<ItemStack> slots, int maximum, List<ItemStack> primary,
            List<ItemStack> surplus, boolean allowDrops) {
        var working = new ArrayList<>(CraftPlan.copies(slots));
        // Primary is provenance-based: the four sticks being prepared are the
        // purpose of a partial request, not disposable "surplus" of a pickaxe.
        if (!insertAll(working, maximum, primary)) return Delivery.failed(CraftingResultCode.NO_OUTPUT_SPACE);
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack stack : surplus) {
            ItemStack remaining = stack.copy();
            mergeIntoExisting(working, maximum, remaining);
            fillEmptySlots(working, maximum, remaining);
            if (!remaining.isEmpty() && !allowDrops) return Delivery.failed(CraftingResultCode.NO_OUTPUT_SPACE);
            while (!remaining.isEmpty()) {
                if (drops.size() >= 32) return Delivery.failed(CraftingResultCode.DROP_LIMIT_EXCEEDED);
                drops.add(remaining.split(Math.min(remaining.getMaxStackSize(), remaining.getCount())));
            }
        }
        return new Delivery(null, working, drops);
    }

    static void publish(Inventory inventory, Delivery delivery) {
        if (delivery.failure() != null) throw new IllegalArgumentException("Cannot publish failed delivery");
        var slots = delivery.slots();
        for (int slot = 0; slot < MAIN_SLOT_COUNT; slot++) inventory.setItem(slot, slots.get(slot));
    }

    public record Delivery(CraftingResultCode failure, List<ItemStack> slots, List<ItemStack> drops) {
        public Delivery {
            slots = CraftPlan.copies(slots);
            drops = CraftPlan.copies(drops);
        }
        @Override public List<ItemStack> slots() { return CraftPlan.copies(slots); }
        @Override public List<ItemStack> drops() { return CraftPlan.copies(drops); }
        static Delivery failed(CraftingResultCode code) { return new Delivery(code, List.of(), List.of()); }
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

    static List<ItemStack> copyMainInventory(Inventory inventory) {
        List<ItemStack> copy = new ArrayList<>(MAIN_SLOT_COUNT);
        for (int slot = 0; slot < MAIN_SLOT_COUNT; slot++) {
            copy.add(inventory.getItem(slot).copy());
        }
        return copy;
    }
}
