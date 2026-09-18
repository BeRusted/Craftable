package org.berusted.craftable.planner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Branch-local counts over immutable source fingerprints. Never mutates a container. */
public final class ResourceLedger {
    private final List<Source> sources;
    private final int[] available;
    private final int[] consumed;
    private final List<Lot> generated;

    public ResourceLedger(List<Source> sources) {
        this.sources = List.copyOf(sources);
        this.available = new int[sources.size()];
        this.consumed = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            if (!CraftingRecipes.protectedStack(sources.get(i).stack)) available[i] = sources.get(i).stack.getCount();
        }
        this.generated = new ArrayList<>();
    }

    private ResourceLedger(ResourceLedger from) {
        sources = from.sources;
        available = from.available.clone();
        consumed = from.consumed.clone();
        generated = new ArrayList<>(from.generated.size());
        for (Lot lot : from.generated) generated.add(lot.copy());
    }

    ResourceLedger copy() { return new ResourceLedger(this); }
    long retainedBytes() { return 128L + available.length * 8L + generated.size() * 256L; }
    long identityBytes() { return 128L + available.length * 32L + generated.size() * 384L; }

    List<Taken> choices(Ingredient ingredient) {
        List<Taken> result = new ArrayList<>();
        var seen = new LinkedHashSet<Key>();
        boolean singleDefaultItem = ingredient.isSimple() && ingredient.getItems().length == 1;
        // Reuse intermediate batch surplus before manufacturing/consuming more.
        for (int i = 0; i < generated.size(); i++) {
            Lot lot = generated.get(i);
            if (!lot.terminal && !CraftingRecipes.protectedStack(lot.stack) && lot.available > 0 && ingredient.test(lot.stack) && seen.add(Key.of(lot.stack))) {
                result.add(new Taken(-1, i, lot.stack.copyWithCount(1)));
                if (singleDefaultItem) return result;
            }
        }
        for (int i = 0; i < sources.size(); i++) {
            ItemStack stack = sources.get(i).stack;
            if (available[i] > 0 && ingredient.test(stack) && seen.add(Key.of(stack))) {
                result.add(new Taken(i, -1, stack.copyWithCount(1)));
                if (singleDefaultItem) return result;
            }
        }
        return result;
    }

    boolean has(Ingredient ingredient) {
        for (Lot lot : generated) if (!lot.terminal && !CraftingRecipes.protectedStack(lot.stack) && lot.available > 0 && ingredient.test(lot.stack)) return true;
        for (int i = 0; i < available.length; i++) {
            if (available[i] > 0 && ingredient.test(sources.get(i).stack)) return true;
        }
        return false;
    }

    boolean hasProtected(Ingredient ingredient) {
        return sources.stream().anyMatch(s -> CraftingRecipes.protectedStack(s.stack) && ingredient.test(s.stack));
    }

    java.util.Set<Item> availableItems() {
        var result = new java.util.HashSet<Item>();
        for (int i = 0; i < sources.size(); i++) if (available[i] > 0) result.add(sources.get(i).stack.getItem());
        return result;
    }

    void take(Taken taken) {
        if (taken.source >= 0) {
            if (--available[taken.source] < 0) throw new IllegalStateException("Double source reservation");
            consumed[taken.source]++;
        } else if (--generated.get(taken.lot).available < 0) {
            throw new IllegalStateException("Double generated reservation");
        }
    }

    void reserveFrontier(Taken taken) {
        // A blocked parent did not consume its inputs. Existing inputs stay in
        // their original slots, but remain reserved for this requested batch:
        // restoring availability would count the same two sticks for every
        // missing pickaxe in a multi-batch partial request.
        if (taken.source >= 0) {
            if (--consumed[taken.source] < 0) throw new IllegalStateException("Invalid frontier release");
        } else {
            Lot lot = generated.get(taken.lot);
            lot.reserved++;
            lot.primary = true;
        }
    }

    int origin(Taken taken) { return taken == null || taken.source >= 0 ? -1 : generated.get(taken.lot).origin; }

    int produce(ItemStack stack, boolean terminal, boolean remainder, String path, int origin) {
        if (stack.isEmpty()) return -1;
        if (generated.size() >= SearchBudget.MAX_STEPS * 10) throw new IllegalStateException("Generated lot limit");
        generated.add(new Lot(stack.copyWithCount(1), stack.getCount(), 0, terminal, terminal, remainder, path, origin));
        return generated.size() - 1;
    }

    Taken producedChoice(int lot, Ingredient ingredient) {
        if (lot < 0) return null;
        Lot entry = generated.get(lot);
        return !CraftingRecipes.protectedStack(entry.stack) && entry.available > 0 && ingredient.test(entry.stack)
                ? new Taken(-1, lot, entry.stack.copyWithCount(1)) : null;
    }

    List<CraftPlan.Extraction> extractions() {
        List<CraftPlan.Extraction> result = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (consumed[i] > 0) {
                Source source = sources.get(i);
                result.add(new CraftPlan.Extraction(source.endpointId, source.slot, consumed[i], source.stack));
            }
        }
        return List.copyOf(result);
    }

    List<ItemStack> delivery(boolean primary) {
        List<ItemStack> result = new ArrayList<>();
        for (Lot lot : generated) {
            int count = Math.addExact(lot.available, lot.reserved);
            if (count > 0 && lot.primary == primary) merge(result, lot.stack.copyWithCount(count));
        }
        return result;
    }

    static void merge(List<ItemStack> into, ItemStack stack) {
        for (ItemStack existing : into) {
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                existing.setCount(Math.addExact(existing.getCount(), stack.getCount()));
                return;
            }
        }
        into.add(stack.copy());
    }

    /** Exact, immutable state identity for failed-state memoization. Counts alone are insufficient. */
    Object identity() {
        var lots = generated.stream().map(l -> List.of(Key.of(l.stack), l.available, l.reserved,
                l.terminal, l.primary, l.remainder, l.path, l.origin)).toList();
        return List.of(java.util.Arrays.stream(available).boxed().toList(),
                java.util.Arrays.stream(consumed).boxed().toList(), lots);
    }

    /** Only valid between complete root batches, with no unfinished bindings.
     * Producer history no longer affects future feasibility there. Preserve
     * exact physical extraction counts, delivery order and every lot role;
     * the retained branch still owns its full, verifiable provenance. */
    Object batchBoundaryIdentity() {
        var lots = new java.util.LinkedHashMap<Object, Integer>();
        for (var lot : generated) if (lot.available + lot.reserved > 0) {
            Object key = List.of(Key.of(lot.stack), lot.terminal, lot.primary, lot.remainder);
            lots.merge(key, lot.available + lot.reserved, Math::addExact);
        }
        return List.of(java.util.Arrays.stream(available).boxed().toList(),
                java.util.Arrays.stream(consumed).boxed().toList(),
                lots.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue())).toList());
    }

    record Taken(int source, int lot, ItemStack stack) {}
    record Key(Item item, DataComponentPatch components) {
        static Key of(ItemStack stack) { return new Key(stack.getItem(), stack.getComponentsPatch()); }
    }

    public record Source(String endpointId, int slot, ItemStack stack) {
        public Source {
            if (slot < 0 || stack.isEmpty()) throw new IllegalArgumentException("Empty or invalid source");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    private static final class Lot {
        final ItemStack stack;
        int available;
        int reserved;
        final boolean terminal;
        boolean primary;
        final boolean remainder;
        final String path;
        final int origin;

        Lot(ItemStack stack, int available, int reserved, boolean terminal, boolean primary, boolean remainder, String path, int origin) {
            this.stack = stack;
            this.available = available;
            this.reserved = reserved;
            this.terminal = terminal;
            this.primary = primary;
            this.remainder = remainder;
            this.path = path;
            this.origin = origin;
        }
        Lot copy() { return new Lot(stack, available, reserved, terminal, primary, remainder, path, origin); }
    }
}
