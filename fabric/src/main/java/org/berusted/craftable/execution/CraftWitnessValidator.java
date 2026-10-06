package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Checks a supplied route, never discovers routes. Outputs and remainders are
 * reconstructed from authoritative recipes; all counts use the search ledger. */
final class CraftWitnessValidator {
    private CraftWitnessValidator() {}

    static CraftPlan validate(CraftingRecipes recipes, CraftRequest request, CraftPlan.Witness witness,
            List<ResourceLedger.Source> sources, SearchBudget budget) {
        require(!request.partial());
        var ledger = new ResourceLedger(sources);
        var supported = recipes.planningInput();
        var steps = new ArrayList<CraftPlan.Step>();
        var recipesByPath = new HashMap<String, Map<ResourceLocation, CraftingRecipes.Entry>>();
        int[] usedOutputs = new int[witness.steps().size()];
        boolean[] contributes = new boolean[usedOutputs.length];
        boolean[] anchored = new boolean[usedOutputs.length];
        int roots = 0;
        for (var claimed : witness.steps()) {
            require(budget.alive());
            var entry = recipes.find(claimed.recipe());
            require(recipes.unavailable(entry) == null);
            require(supported.unavailable(entry) == null);
            boolean root = claimed.path().equals("0");
            if (root) {
                require(claimed.recipe().equals(request.recipe()));
                roots++;
            } else if (request.selections().containsKey(claimed.path())) {
                require(request.selections().get(claimed.path()).equals(claimed.recipe()));
            }
            var grid = claimed.inputs();
            require(grid.size() == entry.gridSize() * entry.gridSize());
            boolean[] occupied = new boolean[grid.size()];
            for (var requirement : entry.requirements()) {
                occupied[requirement.slot()] = true;
                require(requirement.ingredient().test(grid.get(requirement.slot())));
            }
            for (int slot = 0; slot < grid.size(); slot++) {
                var input = grid.get(slot);
                require(occupied[slot] == !input.isEmpty());
                if (input.isEmpty()) continue;
                var pinned = request.selections().get(claimed.path() + "." + slot);
                if (pinned != null) {
                    var choice = recipes.find(pinned);
                    require(choice != null
                            && ItemStack.isSameItemSameComponents(choice.output(), input));
                }
                int origin = claimed.origins().get(slot);
                var binding = ledger.consume(input, origin, origin < 0 ? claimed.references().get(slot).toString() : null);
                if (origin >= 0 && !binding.remainder()) {
                    // A parent may reserve a producer's output before a child
                    // uses its surplus, but the parent executes AFTER that child.
                    // Anchor to an actual demand edge, not first replayed use.
                    if (steps.get(origin).path().equals(claimed.path() + "." + slot)) anchored[origin] = true;
                    usedOutputs[origin]++;
                }
            }
            var actual = recipes.assemble(entry, claimed.path(), grid);
            require(actual != null && budget.alive());
            int index = steps.size();
            steps.add(new CraftPlan.Step(actual.recipe(), actual.path(), actual.gridSize(), grid,
                    actual.output(), actual.remainders(), claimed.origins()));
            recipesByPath.computeIfAbsent(claimed.path(), ignored -> new HashMap<>()).putIfAbsent(entry.id(), entry);
            ledger.produce(actual.output(), root, false, actual.path(), index);
            for (var remainder : actual.remainders()) ledger.produce(remainder, false, true, actual.path(), index);
            contributes[index] = root;
        }
        require(roots == request.batches());
        // Reverse provenance, not client path labels, proves that no unrelated
        // processing is smuggled in alongside the requested root batches.
        for (int i = steps.size() - 1; i >= 0; i--) {
            require(contributes[i]);
            if (!steps.get(i).path().equals("0")) require(usedOutputs[i] > 0 && anchored[i]);
            for (int origin : steps.get(i).inputOrigins()) if (origin >= 0) contributes[origin] = true;
        }
        // Batch rounding is allowed; a whole removable batch of identical
        // intermediate output is not. This is bounded accounting, not a second
        // optimizer or a search for a cheaper alternative recipe.
        // Paths may contain several batches and several selected recipes. A
        // single entry per path would silently erase a forbidden ancestor.
        for (var path : recipesByPath.entrySet()) {
            int separator = path.getKey().lastIndexOf('.');
            boolean direct = true;
            while (separator >= 0) {
                require(budget.alive());
                String ancestorPath = path.getKey().substring(0, separator);
                var ancestors = recipesByPath.get(ancestorPath);
                if (ancestors != null) for (var descendant : path.getValue().values()) {
                    require(!ancestors.containsKey(descendant.id()));
                    if (direct) for (var ancestor : ancestors.values())
                        require(!supported.isExactInverse(ancestor, descendant) && budget.alive());
                }
                separator = ancestorPath.lastIndexOf('.');
                direct = false;
            }
        }
        validateIntermediateOutputs(steps, usedOutputs, budget);
        var plan = new CraftPlan(request.recipe(), request.batches(), roots, steps, ledger.extractions(),
                ledger.delivery(true), ledger.delivery(false), List.of(),
                steps.stream().allMatch(step -> recipes.find(step.recipe().id()).safePreparation()));
        require(plan.hasMaterialChange());
        return plan;
    }

    /** Final accounting only; the entry above still validates every source,
     * recipe, provenance edge and ancestor before applying this condition. */
    private static void validateIntermediateOutputs(List<CraftPlan.Step> steps, int[] usedOutputs, SearchBudget budget) {
        var groupsByItem = new HashMap<Item, List<OutputGroup>>();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            require(budget.alive());
            if (step.path().equals("0")) continue;
            var output = step.output();
            var groups = groupsByItem.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>());
            OutputGroup group = null;
            for (var candidate : groups) {
                require(budget.alive());
                if (ItemStack.isSameItemSameComponents(candidate.output, output)) { group = candidate; break; }
            }
            if (group == null) { group = new OutputGroup(output); groups.add(group); }
            group.spare = Math.addExact(group.spare, output.getCount() - usedOutputs[i]);
            group.minimum = Math.min(group.minimum, output.getCount());
        }
        for (var groups : groupsByItem.values()) for (var group : groups)
            require(group.spare < group.minimum && budget.alive());
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid or expired crafting witness");
    }

    private static final class OutputGroup {
        final ItemStack output;
        int spare, minimum = Integer.MAX_VALUE;
        OutputGroup(ItemStack output) { this.output = output; }
    }
}
