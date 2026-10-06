package org.berusted.craftable.planner;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/** One immutable chain. Sources use stable IDs; no live container escapes into search state. */
public record CraftPlan(ResourceLocation target, int requestedBatches, int completedBatches,
        List<Step> steps, List<Extraction> extractions, List<ItemStack> primary,
        List<ItemStack> surplus, List<Missing> missing, boolean safePartial) {
    public CraftPlan {
        if (requestedBatches < 1 || completedBatches < 0 || completedBatches > requestedBatches
                || steps.isEmpty() || steps.size() > SearchBudget.MAX_STEPS) {
            throw new IllegalArgumentException("Invalid chain");
        }
        steps = List.copyOf(steps);
        for (int i = 0; i < steps.size(); i++) {
            for (int origin : steps.get(i).inputOrigins())
                if (origin < -1 || origin >= i) throw new IllegalArgumentException("Non-topological ingredient origin");
        }
        extractions = List.copyOf(extractions);
        primary = copies(primary);
        surplus = copies(surplus);
        missing = List.copyOf(missing);
    }

    public boolean partial() { return completedBatches < requestedBatches; }

    /** Reject identity transformations regardless of recipe IDs or source slot.
     * Compare actual extracted counts with ALL delivered items, including
     * surplus/remainders and components. Genuine compression is still a change.
     * Shared by search, untrusted witness replay and the commit boundary. */
    public boolean hasMaterialChange() {
        var delta = new java.util.HashMap<ResourceLedger.Key, Long>();
        for (var extraction : extractions)
            delta.merge(ResourceLedger.Key.of(extraction.expected()), -(long) extraction.count(), Long::sum);
        for (var stack : primary)
            if (!stack.isEmpty()) delta.merge(ResourceLedger.Key.of(stack), (long) stack.getCount(), Long::sum);
        for (var stack : surplus)
            if (!stack.isEmpty()) delta.merge(ResourceLedger.Key.of(stack), (long) stack.getCount(), Long::sum);
        return delta.values().stream().anyMatch(count -> count != 0);
    }

    /** Untrusted route only: no output, count of manufactured items, world
     * coordinates or container write instruction crosses this boundary. */
    public record Witness(java.util.UUID session, long recipes, long resources, List<WitnessStep> steps) {
        public Witness {
            java.util.Objects.requireNonNull(session);
            if (recipes < 0 || resources < 0 || steps.isEmpty() || steps.size() > SearchBudget.MAX_STEPS)
                throw new IllegalArgumentException("Invalid witness size/version");
            steps = List.copyOf(steps);
            for (int i = 0; i < steps.size(); i++) for (int origin : steps.get(i).origins())
                if (origin < -1 || origin >= i) throw new IllegalArgumentException("Non-topological witness");
        }

        public static Witness from(CraftPlan plan, org.berusted.craftable.environment.BrowsingSnapshot snapshot) {
            if (plan.partial()) throw new IllegalArgumentException("Partial witness");
            var ledger = new ResourceLedger(snapshot.sources());
            var steps = new java.util.ArrayList<WitnessStep>();
            for (var step : plan.steps()) {
                var refs = new java.util.ArrayList<java.util.UUID>();
                var inputs = step.inputs();
                for (int slot = 0; slot < inputs.size(); slot++) {
                    String ref = inputs.get(slot).isEmpty() ? null
                            : ledger.consume(inputs.get(slot), step.inputOrigins().get(slot), null).reference();
                    refs.add(ref == null ? new java.util.UUID(0, 0) : java.util.UUID.fromString(ref));
                }
                int origin = steps.size();
                ledger.produce(step.output(), step.path().equals("0"), false, step.path(), origin);
                for (var remainder : step.remainders()) ledger.produce(remainder, false, true, step.path(), origin);
                steps.add(new WitnessStep(step.recipe().id(), step.path(), inputs, step.inputOrigins(), refs));
            }
            return new Witness(snapshot.session(), snapshot.recipes(), snapshot.resources(), steps);
        }
    }

    public record WitnessStep(ResourceLocation recipe, String path, List<ItemStack> inputs,
            List<Integer> origins, List<java.util.UUID> references) {
        public WitnessStep {
            if (!path.matches("0(?:\\.[0-8]){0,12}") || (inputs.size() != 4 && inputs.size() != 9)
                    || origins.size() != inputs.size() || references.size() != inputs.size())
                throw new IllegalArgumentException("Invalid witness grid");
            inputs = copies(inputs); origins = List.copyOf(origins); references = List.copyOf(references);
            for (int i = 0; i < inputs.size(); i++) {
                boolean noRef = references.get(i).equals(new java.util.UUID(0, 0));
                if (inputs.get(i).isEmpty() ? origins.get(i) != -1 || !noRef
                        : inputs.get(i).getCount() != 1 || (origins.get(i) >= 0) != noRef)
                    throw new IllegalArgumentException("Invalid witness binding");
            }
        }
        @Override public List<ItemStack> inputs() { return copies(inputs); }
    }
    /** Exact value identity, not a collision-prone hash or retained executable plan. */
    public Object fingerprint() {
        return List.of(target, requestedBatches, completedBatches,
                steps.stream().map(s -> List.of(s.recipe().id(), s.path(), stackKeys(s.inputs()),
                        stackKey(s.output()), stackKeys(s.remainders()), s.inputOrigins())).toList(),
                extractions.stream().map(e -> List.of(e.endpointId(), e.slot(), e.count(), stackKey(e.expected()))).toList(),
                stackKeys(primary), stackKeys(surplus),
                missing.stream().map(m -> List.of(m.path(), m.count(), stackKeys(m.alternatives()))).toList(), safePartial);
    }

    public static List<Object> stackKeys(List<ItemStack> stacks) { return stacks.stream().map(CraftPlan::stackKey).toList(); }
    private static Object stackKey(ItemStack stack) {
        return List.of(stack.getItem(), stack.getComponentsPatch(), stack.getCount());
    }
    @Override public List<ItemStack> primary() { return copies(primary); }
    @Override public List<ItemStack> surplus() { return copies(surplus); }

    public static List<ItemStack> copies(List<ItemStack> values) {
        return values.stream().map(ItemStack::copy).toList();
    }

    public record Step(RecipeHolder<CraftingRecipe> recipe, String path, int gridSize,
            List<ItemStack> inputs, ItemStack output, List<ItemStack> remainders, List<Integer> inputOrigins) {
        public Step(RecipeHolder<CraftingRecipe> recipe, String path, int gridSize,
                List<ItemStack> inputs, ItemStack output, List<ItemStack> remainders) {
            this(recipe, path, gridSize, inputs, output, remainders,
                    java.util.Collections.nCopies(inputs.size(), -1));
        }
        public Step {
            if ((gridSize != 2 && gridSize != 3) || inputs.size() != gridSize * gridSize || output.isEmpty()
                    || inputOrigins.size() != inputs.size()) {
                throw new IllegalArgumentException("Invalid recipe step");
            }
            inputs = copies(inputs);
            output = output.copy();
            remainders = copies(remainders.stream().filter(s -> !s.isEmpty()).toList());
            inputOrigins = List.copyOf(inputOrigins);
        }
        @Override public List<ItemStack> inputs() { return copies(inputs); }
        @Override public ItemStack output() { return output.copy(); }
        @Override public List<ItemStack> remainders() { return copies(remainders); }
    }

    public record Extraction(String endpointId, int slot, int count, ItemStack expected) {
        public Extraction {
            if (slot < 0 || count < 1 || expected.isEmpty() || expected.getCount() < count) {
                throw new IllegalArgumentException("Invalid extraction");
            }
            expected = expected.copy();
        }
        @Override public ItemStack expected() { return expected.copy(); }
    }

    /** Missing alternatives are OR, not several independent deficits to add together. */
    public record Missing(String path, List<ItemStack> alternatives, int count) {
        public Missing {
            if (count <= 0) throw new IllegalArgumentException("Missing quantity must be positive");
            alternatives = copies(alternatives);
        }
        @Override public List<ItemStack> alternatives() { return copies(alternatives); }
    }
}
