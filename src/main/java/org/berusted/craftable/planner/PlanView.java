package org.berusted.craftable.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.recipe.CraftingRecipes;
import org.berusted.craftable.recipe.PlanningInput;

/**
 * Bounded display projection, not a second plan or an executable graph.
 * Paths aggregate repeated batches; mixed inputs remain separate stack entries.
 * The exact ordered operations accompany the graph so grouping cannot conceal
 * a batch's different recipe, costs, outputs or remainders.
 */
public record PlanView(CraftingResultCode code, boolean workbench, boolean complete,
        int completedBatches, List<Node> nodes, List<Operation> operations,
        List<ItemStack> consumed, List<ItemStack> primary, List<ItemStack> surplus,
        List<ItemStack> drops, List<CraftPlan.Missing> missing) {
    public static final int MAX_NODES = 256;
    public static final int MAX_MISSING = 32;

    public PlanView {
        nodes = List.copyOf(nodes);
        operations = List.copyOf(operations);
        consumed = CraftPlan.copies(consumed);
        primary = CraftPlan.copies(primary);
        surplus = CraftPlan.copies(surplus);
        drops = CraftPlan.copies(drops);
        missing = List.copyOf(missing);
        if (nodes.size() > MAX_NODES || operations.size() > SearchBudget.MAX_STEPS || missing.size() > MAX_MISSING)
            throw new IllegalArgumentException("Oversized plan view");
        for (int i = 0; i < operations.size(); i++)
            for (int origin : operations.get(i).inputOrigins())
                if (origin < -1 || origin >= i) throw new IllegalArgumentException("Invalid display dependency");
    }

    @Override public List<ItemStack> consumed() { return CraftPlan.copies(consumed); }
    @Override public List<ItemStack> primary() { return CraftPlan.copies(primary); }
    @Override public List<ItemStack> surplus() { return CraftPlan.copies(surplus); }
    @Override public List<ItemStack> drops() { return CraftPlan.copies(drops); }

    /** Exact reviewed values, independent of source UUIDs and server tokens.
     * A changed route/cost/remainder must be shown again before confirmation. */
    public Object reviewIdentity() {
        return List.of(code, workbench, complete, completedBatches,
                operations.stream().map(o -> List.of(o.recipe(), o.path(), CraftPlan.stackKeys(o.inputs()),
                        CraftPlan.stackKeys(List.of(o.output())), CraftPlan.stackKeys(o.remainders()), o.inputOrigins())).toList(),
                CraftPlan.stackKeys(consumed), CraftPlan.stackKeys(primary), CraftPlan.stackKeys(surplus),
                CraftPlan.stackKeys(drops), missing.stream().map(m -> List.of(m.path(), m.count(),
                        CraftPlan.stackKeys(m.alternatives()))).toList());
    }

    public record Candidate(ResourceLocation recipe, ItemStack output, int gridSize, CraftingResultCode rejection) {
        public Candidate {
            if (output.isEmpty() || gridSize < 2 || gridSize > 3) throw new IllegalArgumentException("Invalid recipe candidate");
            output = output.copy();
        }
        @Override public ItemStack output() { return output.copy(); }
    }
    public record Choices(List<Candidate> candidates, boolean truncated) {
        public Choices {
            if (candidates.size() > 16) throw new IllegalArgumentException("Too many recipe candidates");
            candidates = List.copyOf(candidates);
        }
    }

    /** Only a displayed demand can request candidates. A candidate's local
     * workstation/lock verdict is not a proof of global material availability;
     * selecting it must replan the whole request through CraftingService. */
    public Choices choices(CraftingRecipes catalog, CraftRequest request, String path) {
        return choices(catalog.planningInput(), request, path);
    }

    public Choices choices(PlanningInput catalog, CraftRequest request, String path) {
        var displayed = nodes.stream().filter(n -> n.path().equals(path)).findFirst().orElse(null);
        if (displayed == null) return new Choices(List.of(), false);
        var found = new java.util.TreeMap<ResourceLocation, CraftingRecipes.Entry>();
        if (path.equals("0")) {
            var root = catalog.find(request.recipe());
            if (root != null) for (var e : catalog.producing(net.minecraft.world.item.crafting.Ingredient.of(root.output())))
                if (ItemStack.isSameItemSameComponents(e.output(), root.output())) found.put(e.id(), e);
        } else {
            String parent = path.substring(0, path.lastIndexOf('.'));
            int slot = Integer.parseInt(path.substring(path.lastIndexOf('.') + 1));
            for (var node : nodes) if (node.path().equals(parent)) for (var id : node.recipes()) {
                var entry = catalog.find(id);
                if (entry == null) continue;
                for (var requirement : entry.requirements()) if (requirement.slot() == slot)
                    for (var e : catalog.producing(requirement.ingredient())) found.put(e.id(), e);
            }
        }
        return new Choices(found.values().stream().limit(SearchBudget.MAX_CANDIDATES)
                .map(e -> new Candidate(e.id(), e.output(), e.gridSize(), catalog.unavailable(e))).toList(),
                found.size() > SearchBudget.MAX_CANDIDATES);
    }

    /** needs are AND for actual inputs; alternatives=true is an explanatory OR. */
    public record Node(String path, List<ItemStack> needs, List<ItemStack> made,
            List<ResourceLocation> recipes, boolean explanation, boolean alternatives, String reference) {
        public Node {
            if (!path.matches("0(?:\\.[0-8]){0,12}") || needs.size() > 16 || made.size() > 16 || recipes.size() > 16)
                throw new IllegalArgumentException("Invalid display node");
            if (!reference.isEmpty() && (!reference.matches("0(?:\\.[0-8]){0,12}") || reference.equals(path)))
                throw new IllegalArgumentException("Invalid display reference");
            needs = CraftPlan.copies(needs);
            made = CraftPlan.copies(made);
            recipes = List.copyOf(recipes);
        }
        @Override public List<ItemStack> needs() { return CraftPlan.copies(needs); }
        @Override public List<ItemStack> made() { return CraftPlan.copies(made); }
    }

    public record Operation(ResourceLocation recipe, String path, List<ItemStack> inputs,
            ItemStack output, List<ItemStack> remainders, List<Integer> inputOrigins) {
        public Operation {
            if (inputs.size() > 9 || inputOrigins.size() != inputs.size() || remainders.size() > 9 || output.isEmpty()
                    || !path.matches("0(?:\\.[0-8]){0,12}"))
                throw new IllegalArgumentException("Invalid displayed operation");
            inputs = CraftPlan.copies(inputs);
            output = output.copy();
            remainders = CraftPlan.copies(remainders);
            inputOrigins = List.copyOf(inputOrigins);
        }
        @Override public List<ItemStack> inputs() { return CraftPlan.copies(inputs); }
        @Override public ItemStack output() { return output.copy(); }
        @Override public List<ItemStack> remainders() { return CraftPlan.copies(remainders); }
    }

    public static PlanView from(CraftingRecipes catalog, CraftRequest request, SearchResult result,
            List<ItemStack> drops) {
        return from(catalog, request, result, drops, List.of());
    }

    public static PlanView from(CraftingRecipes catalog, CraftRequest request, SearchResult result,
            List<ItemStack> drops, List<ItemStack> available) {
        return from(catalog.planningInput(), request, result, drops, available);
    }

    /** Display projection shares the same frozen catalog on either side.
     * It never creates execution authority or calls a live recipe hook. */
    public static PlanView from(PlanningInput catalog, CraftRequest request, SearchResult result,
            List<ItemStack> drops, List<ItemStack> available) {
        var nodes = new LinkedHashMap<String, MutableNode>();
        var operations = new ArrayList<Operation>();
        var consumed = new ArrayList<ItemStack>();
        CraftPlan plan = result.plan().orElse(null);
        if (plan != null) {
            for (var step : plan.steps()) {
                var node = node(nodes, step.path());
                if (node == null) return limited(catalog);
                node.actual = true;
                ResourceLedger.merge(node.made, step.output());
                ResourceLedger.merge(node.remaining, step.output());
                step.remainders().forEach(s -> ResourceLedger.merge(node.remaining, s));
                if (!node.recipes.contains(step.recipe().id())) node.recipes.add(step.recipe().id());
                var inputs = step.inputs();
                for (int slot = 0; slot < inputs.size(); slot++) {
                    if (inputs.get(slot).isEmpty()) continue;
                    var child = node(nodes, step.path() + "." + slot);
                    if (child == null) return limited(catalog);
                    child.actual = true;
                    ResourceLedger.merge(child.needs, inputs.get(slot));
                }
                operations.add(new Operation(step.recipe().id(), step.path(), inputs, step.output(), step.remainders(), step.inputOrigins()));
            }
            // Remaining generated quantities come from actual step references,
            // not a guess based on equal icons. Explanation can share this
            // frontier, but cannot invent extra copies or execution steps.
            for (var op : operations) for (int slot = 0; slot < op.inputs().size(); slot++) {
                int origin = op.inputOrigins().get(slot);
                if (origin >= 0) takeStock(nodes.get(operations.get(origin).path()).remaining,
                        net.minecraft.world.item.crafting.Ingredient.of(op.inputs().get(slot)), op.inputs().get(slot).getCount());
            }
            for (var extraction : plan.extractions())
                ResourceLedger.merge(consumed, extraction.expected().copyWithCount(extraction.count()));
        }
        var root = catalog.find(request.recipe());
        if (root != null && catalog.unavailable(root) != CraftingResultCode.UNSUPPORTED_RECIPE) {
            var node = node(nodes, "0");
            if (node == null) return limited(catalog);
            ResourceLedger.merge(node.needs, root.output().copyWithCount(root.output().getCount() * request.batches()));
            // A blocked root is still explainable. Do not multiply a made-up
            // chain into actual costs: only the ordered plan contributes those.
            if (!node.actual) {
                node.recipes.add(root.id());
                var stock = new ArrayList<ItemStack>();
                available.forEach(s -> ResourceLedger.merge(stock, s));
                consumed.forEach(s -> takeStock(stock, net.minecraft.world.item.crafting.Ingredient.of(s), s.getCount()));
                if (!explain(catalog, request, nodes, root, "0", new java.util.HashSet<>(), 0, stock, request.batches()))
                    return limited(catalog);
            }
        }
        if (result.missing().size() > MAX_MISSING || nodes.values().stream().anyMatch(n ->
                n.needs.size() > 16 || n.made.size() > 16 || n.recipes.size() > 16)) return limited(catalog);
        return new PlanView(result.code(), catalog.workbench(), true, plan == null ? 0 : plan.completedBatches(),
                nodes.entrySet().stream().map(e -> new Node(e.getKey(), e.getValue().needs, e.getValue().made,
                        e.getValue().recipes, !e.getValue().actual, e.getValue().alternatives, e.getValue().reference)).toList(),
                operations, consumed, plan == null ? List.of() : plan.primary(),
                plan == null ? List.of() : plan.surplus(), drops, result.missing());
    }

    private static boolean explain(PlanningInput catalog, CraftRequest request, Map<String, MutableNode> nodes,
            CraftingRecipes.Entry entry, String path, java.util.Set<ResourceLocation> active, int depth, List<ItemStack> stock, int batches) {
        if (depth >= SearchBudget.MAX_DEPTH || !active.add(entry.id())) return true; // Honest explanatory leaf.
        for (var requirement : entry.requirements()) {
            String childPath = path + "." + requirement.slot();
            var child = node(nodes, childPath);
            if (child == null) return false;
            if (child.actual) {
                // A partial frontier has a producing step, but no executed
                // root consumer yet. Supply its explanatory demand separately
                // without inventing an operation or adding to the net costs.
                if (child.needs.isEmpty()) child.made.stream().filter(requirement.ingredient()::test).findFirst()
                        .ifPresent(s -> child.needs.add(s.copyWithCount(batches)));
                takeStock(child.remaining, requirement.ingredient(), batches);
                continue;
            }
            child.alternatives = true;
            child.needs.addAll(java.util.Arrays.stream(requirement.ingredient().getItems()).limit(16)
                    .map(s -> s.copyWithCount(batches)).toList());
            boolean shared = false;
            for (var source : nodes.entrySet()) {
                if (!source.getValue().actual || source.getKey().equals(childPath)) continue;
                var supplied = takeStock(source.getValue().remaining, requirement.ingredient(), batches);
                if (!supplied.isEmpty()) {
                    child.needs.clear(); child.needs.addAll(supplied); child.alternatives = false;
                    child.reference = source.getKey(); shared = true; break;
                }
            }
            if (shared) continue;
            var existing = takeStock(stock, requirement.ingredient(), batches);
            if (!existing.isEmpty()) {
                child.needs.clear(); child.needs.addAll(existing); child.alternatives = false; child.actual = true;
                continue; // Explanation uses available stock; it does not manufacture it again.
            }
            var chosen = request.selections().get(childPath);
            var producers = catalog.producing(requirement.ingredient());
            var actualRecipes = nodes.values().stream().filter(n -> n.actual).flatMap(n -> n.recipes.stream()).toList();
            var producer = chosen == null ? producers.stream().filter(e -> !active.contains(e.id()))
                    .filter(e -> !conversionCycleWithoutStock(catalog, e, stock))
                    .min(java.util.Comparator.<CraftingRecipes.Entry, Boolean>comparing(e -> !actualRecipes.contains(e.id()))
                            .thenComparing(CraftingRecipes.Entry::id)).orElse(null)
                    : catalog.find(chosen);
            if (producer != null && catalog.unavailable(producer) != CraftingResultCode.UNSUPPORTED_RECIPE
                    && requirement.ingredient().test(producer.output()) && !active.contains(producer.id())) {
                child.recipes.add(producer.id());
                int upstreamBatches = (batches + producer.output().getCount() - 1) / producer.output().getCount();
                if (!explain(catalog, request, nodes, producer, childPath, active, depth + 1, stock, upstreamBatches)) return false;
            }
        }
        active.remove(entry.id());
        return true;
    }

    // This is only a display simplification, never a feasibility verdict. Do
    // not explain missing diamonds as nine more diamonds via a storage cycle.
    private static boolean conversionCycleWithoutStock(PlanningInput catalog, CraftingRecipes.Entry producer,
            List<ItemStack> stock) {
        return !producer.requirements().isEmpty() && producer.requirements().stream().allMatch(r ->
                stock.stream().noneMatch(s -> !s.isEmpty() && r.ingredient().test(s))
                && !catalog.producing(r.ingredient()).isEmpty()
                && catalog.producing(r.ingredient()).stream().allMatch(back -> !back.requirements().isEmpty()
                        && back.requirements().stream().allMatch(input -> input.ingredient().test(producer.output()))));
    }

    private static List<ItemStack> takeStock(List<ItemStack> stock, net.minecraft.world.item.crafting.Ingredient ingredient, int count) {
        if (stock.stream().filter(ingredient::test).mapToInt(ItemStack::getCount).sum() < count) return List.of();
        var used = new ArrayList<ItemStack>();
        for (var stack : stock) if (!stack.isEmpty() && ingredient.test(stack)) {
            int taken = Math.min(count, stack.getCount());
            if (taken == 0) break;
            ResourceLedger.merge(used, stack.copyWithCount(taken)); stack.shrink(taken); count -= taken;
        }
        return used;
    }

    public static PlanView failed(CraftingResultCode code) {
        return new PlanView(code, false, false, 0, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static PlanView limited(PlanningInput catalog) {
        return new PlanView(CraftingResultCode.SEARCH_BUDGET_EXCEEDED, catalog.workbench(), false, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static MutableNode node(Map<String, MutableNode> nodes, String path) {
        if (nodes.containsKey(path)) return nodes.get(path);
        if (nodes.size() >= MAX_NODES) return null;
        var result = new MutableNode();
        nodes.put(path, result);
        return result;
    }

    private static final class MutableNode {
        final List<ItemStack> needs = new ArrayList<>(), made = new ArrayList<>();
        final List<ItemStack> remaining = new ArrayList<>();
        final List<ResourceLocation> recipes = new ArrayList<>();
        boolean actual, alternatives;
        String reference = "";
    }
}
