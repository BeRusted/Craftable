package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
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
        var outputs = steps.stream().map(CraftPlan.Step::output).toList();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            int spare = 0, smallest = Integer.MAX_VALUE;
            for (int j = 0; j < steps.size(); j++) {
                var other = steps.get(j);
                if (other.path().startsWith(step.path() + "."))
                    require(!step.recipe().id().equals(other.recipe().id()));
                if (!other.path().equals("0") && ItemStack.isSameItemSameComponents(outputs.get(i), outputs.get(j))) {
                    spare = Math.addExact(spare, outputs.get(j).getCount() - usedOutputs[j]);
                    smallest = Math.min(smallest, outputs.get(j).getCount());
                }
            }
            require((step.path().equals("0") || spare < smallest) && budget.alive());
        }
        return new CraftPlan(request.recipe(), request.batches(), roots, steps, ledger.extractions(),
                ledger.delivery(true), ledger.delivery(false), List.of(),
                steps.stream().allMatch(step -> recipes.find(step.recipe().id()).safePreparation()));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid or expired crafting witness");
    }
}
