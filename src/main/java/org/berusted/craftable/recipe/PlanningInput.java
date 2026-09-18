package org.berusted.craftable.recipe;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.CraftPlan;

/** Value-oriented access to one generation's ordinary recipe knowledge. Search
 * operations do not read players, Level, containers or crafting hooks.
 * The owning game thread may populate the index's bounded static relation cache;
 * this is not a promise of arbitrary concurrent access. */
public final class PlanningInput {
    private final CraftingRecipes.Index index;
    private final boolean workbench, limited;
    private final Set<ResourceLocation> unlocked;

    PlanningInput(CraftingRecipes.Index index, boolean workbench, boolean limited, Set<ResourceLocation> unlocked) {
        this.index = index;
        this.workbench = workbench;
        this.limited = limited;
        this.unlocked = Set.copyOf(unlocked);
    }

    /** Connection-scoped static knowledge, independent of open menus and
     * dynamic inventory versions. The client controller owns one instance;
     * calling bind is not a grant to execute or expose local verdicts. */
    public static final class Catalog {
        public enum State { EMPTY, BUILDING, READY, LIMITED }
        private Object connection, generation;
        private CraftingRecipes.Index.Builder building;
        private CraftingRecipes.Index index;
        private State state = State.EMPTY;
        private int builds;

        public void observe(Object connection, Object generation,
                java.util.Collection<net.minecraft.world.item.crafting.RecipeHolder<?>> recipes,
                net.minecraft.core.HolderLookup.Provider registries) {
            java.util.Objects.requireNonNull(connection);
            java.util.Objects.requireNonNull(generation);
            if (this.connection == connection && this.generation == generation) return;
            clear();
            this.connection = connection;
            this.generation = generation;
            // The original synchronized collection is stable within a recipe
            // generation. Reload/tag rebinding MUST supply a new generation.
            if (recipes.size() > 65_536) { state = State.LIMITED; return; }
            building = new CraftingRecipes.Index.Builder(generation, recipes, registries);
            builds++;
            state = State.BUILDING;
        }

        public void advance(long nanos) { advance(nanos, System::nanoTime); }

        // A deterministic clock verifies progress without wall-clock assertions.
        void advance(long nanos, java.util.function.LongSupplier clock) {
            if (state != State.BUILDING) return;
            try {
                if (!building.advance(nanos, clock)) return;
                index = building.result();
                building = null;
                state = State.READY;
            } catch (RuntimeException exception) {
                // Limits/unsupported normalization are unknown, never a
                // partially published catalog that could prove recipes absent.
                building.cancel();
                building = null;
                state = State.LIMITED;
                org.berusted.craftable.Craftable.LOGGER.warn("Local recipe catalog unavailable ({})",
                        exception.getClass().getSimpleName());
            }
        }

        public PlanningInput bind(boolean workbench, boolean limited, Set<ResourceLocation> unlocked) {
            if (state != State.READY) throw new IllegalStateException("Catalog is not ready");
            return new PlanningInput(index, workbench, limited, unlocked);
        }

        public State state() { return state; }
        public int builds() { return builds; }
        int processed() { return building == null ? 0 : building.processed(); }

        public void clear() {
            if (building != null) building.cancel();
            building = null;
            index = null;
            connection = generation = null;
            state = State.EMPTY;
        }
    }

    public CraftingRecipes.Entry find(ResourceLocation id) { return index.byId.get(id); }
    public List<CraftingRecipes.Entry> entries() { return index.valueOrdered; }
    public List<CraftingRecipes.Entry> producing(Ingredient ingredient) {
        return index.producing(ingredient).stream().filter(e -> index.valueRecipes.contains(e.id())).toList();
    }
    public boolean hasUnsupportedProducer(Ingredient ingredient) {
        return index.producing(ingredient).stream().anyMatch(e -> !index.valueRecipes.contains(e.id()));
    }
    public Object generation() { return index; }
    public String fingerprint() { return index.planningFingerprint; }
    public boolean fullySupported() { return index.valueRecipes.size() == index.ordered.size(); }
    public boolean workbench() { return workbench; }

    public CraftingResultCode unavailable(CraftingRecipes.Entry entry) {
        if (entry == null || !index.valueRecipes.contains(entry.id())) return CraftingResultCode.UNSUPPORTED_RECIPE;
        if (limited && !unlocked.contains(entry.id())) return CraftingResultCode.RECIPE_LOCKED;
        if (entry.gridSize() == 3 && !workbench) return CraftingResultCode.MISSING_WORKSTATION;
        return null;
    }

    public CraftPlan.Step assemble(CraftingRecipes.Entry entry, String path, List<ItemStack> grid) {
        if (unavailable(entry) != null || grid.size() != entry.gridSize() * entry.gridSize()) return null;
        // Search always binds a recipe's canonical grid. Mirrored/player grids
        // are a server matching concern, not a second local recipe matcher.
        var required = new boolean[grid.size()];
        for (var demand : entry.requirements()) {
            var stack = grid.get(demand.slot());
            if (stack.getCount() != 1 || CraftingRecipes.protectedStack(stack) || !demand.ingredient().test(stack)) return null;
            required[demand.slot()] = true;
        }
        var remainders = new java.util.ArrayList<ItemStack>();
        for (int slot = 0; slot < grid.size(); slot++) {
            var stack = grid.get(slot);
            if (!required[slot] && !stack.isEmpty()) return null;
            if (!stack.isEmpty()) {
                var remainder = index.remainders.get(stack.getItem());
                if (remainder == null) return null; // Unknown behavior is not an empty remainder.
                if (!remainder.isEmpty()) remainders.add(remainder.copy());
            }
        }
        return new CraftPlan.Step(entry.holder(), path, entry.gridSize(), grid, entry.output(), remainders);
    }
}
