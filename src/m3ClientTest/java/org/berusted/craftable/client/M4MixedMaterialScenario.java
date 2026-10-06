package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.PlanView;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.PlanningInput;
import static net.minecraft.world.item.Items.*;

/** Real mixed-batch plans through the existing frozen solver and graph. Only
 * presentation is inspected; paths/operations never become a new plan. */
final class M4MixedMaterialScenario {
    static void verify() throws Exception {
        var input = (PlanningInput) field(ClientBrowsePlanner.class, null, "input");
        verifyReal(input, "chest", 2, List.of(stack(OAK_LOG, 3), stack(JUNGLE_LOG, 1)),
                OAK_PLANKS, 12, OAK_LOG, 3, JUNGLE_PLANKS, 4, JUNGLE_LOG, 1);
        verifyReal(input, "stick", 6, List.of(stack(BIRCH_LOG, 1), stack(OAK_LOG, 2)),
                OAK_PLANKS, 8, OAK_LOG, 2, BIRCH_PLANKS, 4, BIRCH_LOG, 1);
        verifyComponentsAndSurplus();
        verifyConservativeShapesAndMixedRecipeBadge();
        verifyTrappedChest(input);
        verifyIndependentInventory();
        System.out.println("M4_MIXED_MATERIAL PASS chest=oak12+jungle4 stickMAX=oak8+birch4 trappedChest=inventory5 independent/generatedOrigins=preserved exactOrigins/tooltips/paths/components/surplus=preserved");
    }

    private static void verifyReal(PlanningInput input, String target, int batches, List<ItemStack> stock,
            Item boardsA, int amountA, Item logA, int logsA, Item boardsB, int amountB, Item logB, int logsB) throws Exception {
        var sources = new ArrayList<ResourceLedger.Source>();
        for (int i = 0; i < stock.size(); i++) sources.add(new ResourceLedger.Source("mixed-fixture", i, stock.get(i)));
        var request = new CraftRequest(id(target), batches, false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, Map.of());
        var result = new CraftSearch(input, request, sources, new SearchBudget(() -> 0L, 1_000_000_000L,
                SearchBudget.MAX_STATES), ignored -> null).run();
        require(result.code() == CraftingResultCode.CREATED, "Mixed fixture did not create " + target + ": " + result);
        var view = PlanView.from(input, request, result, List.of(), stock);
        Object reviewed = view.reviewIdentity();
        var selected = new ArrayList<String>();
        var graph = new PlanGraphWidget(0, 0, 480, 360, selected::addAll);
        graph.show(view, view.alternativeGroups(input));
        Object a = one(graph, boardsA), b = one(graph, boardsB);
        require(amount(a, "needs", boardsA) == amountA && amount(a, "made", boardsA) == amountA
                && amount(b, "needs", boardsB) == amountB && amount(b, "made", boardsB) == amountB,
                "Mixed path labelled one wood with another variant's quantity: " + target);
        require(amount(one(graph, logA), "needs", logA) == logsA && amount(one(graph, logB), "needs", logB) == logsB,
                "Shared batch copied or lost mixed upstream log quantities: " + target);
        verifyInputs(graph, a, logA, logsA); verifyInputs(graph, b, logB, logsB);
        verifyTooltip(graph, a, boardsA, amountA, logA, logsA, boardsB, logB);
        verifyTooltip(graph, b, boardsB, amountB, logB, logsB, boardsA, logA);
        verifyPaths(graph, a, view, boardsA, selected); verifyPaths(graph, b, view, boardsB, selected);
        require((boolean) value(a, "deviation") != (boolean) value(b, "deviation"),
                "All mixed materials were marked additional, or the additional branch lost its badge: " + target);
        require(view.reviewIdentity().equals(reviewed) && amount(view.consumed(), logA) == logsA
                && amount(view.consumed(), logB) == logsB, "Display changed material accounting/review: " + target);
        M4GraphPresentationScenario.capture(graph, target + "-mixed-materials");
        int pan = (int) field(PlanGraphWidget.class, graph, "panY");
        @SuppressWarnings("unchecked") var collapsed = (Set<String>) field(PlanGraphWidget.class, graph, "collapsed");
        collapsed.add((String) value(a, "id")); graph.show(view);
        require((int) field(PlanGraphWidget.class, graph, "panY") == pan && view.reviewIdentity().equals(reviewed),
                "Mixed collapse reset position or modified the reviewed plan");
        collapsed.clear(); graph.show(view);
        verifyInputs(graph, one(graph, boardsA), logA, logsA);
        verifyInputs(graph, one(graph, boardsB), logB, logsB);
    }

    private static void verifyComponentsAndSurplus() throws Exception {
        var plain = stack(OAK_PLANKS, 1);
        var named = stack(OAK_PLANKS, 1); named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct wood"));
        var nodes = List.of(node("0", List.of(stack(STICK, 8)), List.of(stack(STICK, 8)), List.of(id("stick"))),
                node("0.0", List.of(plain, named), List.of(plain.copyWithCount(4), named.copyWithCount(4)),
                        List.of(id("oak_planks"), id("birch_planks"))),
                node("0.1", List.of(named, plain), List.of(), List.of()),
                node("0.0.0", List.of(stack(OAK_LOG, 1), stack(BIRCH_LOG, 1)), List.of(), List.of()));
        var operations = List.of(new PlanView.Operation(id("oak_planks"), "0.0", List.of(stack(OAK_LOG, 1)),
                        plain.copyWithCount(4), List.of(), List.of(-1)),
                new PlanView.Operation(id("birch_planks"), "0.0", List.of(stack(BIRCH_LOG, 1)),
                        named.copyWithCount(4), List.of(), List.of(-1)),
                new PlanView.Operation(id("stick"), "0", List.of(plain, named), stack(STICK, 4), List.of(), List.of(0, 1)),
                new PlanView.Operation(id("stick"), "0", List.of(named, plain), stack(STICK, 4), List.of(), List.of(1, 0)));
        var view = new PlanView(CraftingResultCode.CREATED, true, true, 2, nodes, operations,
                List.of(stack(OAK_LOG, 1), stack(BIRCH_LOG, 1)), List.of(stack(STICK, 8)),
                List.of(plain.copyWithCount(2), named.copyWithCount(2)), List.of(), List.of());
        Object identity = view.reviewIdentity();
        var graph = new PlanGraphWidget(0, 0, 480, 320, ignored -> {}); graph.show(view);
        var wood = cells(graph).stream().filter(c -> {
            try { return amount(c, "needs", OAK_PLANKS) > 0; }
            catch (Exception failure) { throw new AssertionError(failure); }
        }).toList();
        require(wood.size() == 2, "Different output components merged into one mixed-production icon");
        int additional = 0;
        for (var cell : wood) {
            if ((boolean) value(cell, "deviation")) additional++;
            @SuppressWarnings("unchecked") var needs = (List<ItemStack>) value(cell, "needs");
            @SuppressWarnings("unchecked") var made = (List<ItemStack>) value(cell, "made");
            require(needs.size() == 1 && needs.getFirst().getCount() == 2 && made.size() == 1
                    && made.getFirst().getCount() == 4 && ItemStack.isSameItemSameComponents(needs.getFirst(), made.getFirst()),
                    "Demand/production/surplus were conflated for a mixed output component");
            var log = needs.getFirst().has(DataComponents.CUSTOM_NAME) ? BIRCH_LOG : OAK_LOG;
            verifyInputs(graph, cell, log, 1);
            require(((List<?>) value(cell, "operations")).size() == 1, "Variant tooltip included another component's operation");
        }
        require(additional == 1, "Opposite consumer input order marked both actual output variants additional");
        require(view.reviewIdentity().equals(identity) && view.operations().size() == 4 && view.surplus().size() == 2,
                "Component projection changed reviewed surplus or ordered steps");
    }

    private static void verifyInputs(PlanGraphWidget graph, Object cell, Item log, int expected) throws Exception {
        @SuppressWarnings("unchecked") var byId = (Map<String, ?>) field(PlanGraphWidget.class, graph, "byId");
        @SuppressWarnings("unchecked") var children = (List<String>) value(cell, "children");
        require(children.size() == 1 && byId.containsKey(children.getFirst())
                && amount(byId.get(children.getFirst()), "needs", log) == expected,
                "Mixed producer connected to another wood's source, or repeated its extraction");
    }
    private static void verifyTrappedChest(PlanningInput input) throws Exception {
        var pins = new java.util.TreeMap<String, ResourceLocation>();
        String chest = inputPath(input, "0", "trapped_chest", CHEST);
        String hook = inputPath(input, "0", "trapped_chest", TRIPWIRE_HOOK);
        pins.put(chest, id("chest")); pins.put(hook, id("tripwire_hook"));
        for (var requirement : input.find(id("chest")).requirements())
            pins.put(chest + "." + requirement.slot(), id("birch_planks"));
        pins.put(inputPath(input, hook, "tripwire_hook", BIRCH_PLANKS), id("birch_planks"));
        var request = new CraftRequest(id("trapped_chest"), 2, false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
        var stock = List.of(stack(BIRCH_LOG, 5), stack(STICK, 1), stack(IRON_INGOT, 1));
        var sources = new ArrayList<ResourceLedger.Source>();
        for (int i = 0; i < stock.size(); i++) sources.add(new ResourceLedger.Source("trapped-fixture", i, stock.get(i)));
        var result = new CraftSearch(input, request, sources, new SearchBudget(() -> 0L, 1_000_000_000L,
                SearchBudget.MAX_STATES), ignored -> null).run();
        require(result.code() == CraftingResultCode.CREATED, "Trapped chest fixture did not create: " + result);
        var view = PlanView.from(input, request, result, List.of(), stock);
        Object reviewed = view.reviewIdentity();
        var selected = new ArrayList<String>();
        var graph = new PlanGraphWidget(0, 0, 640, 480, selected::addAll); graph.show(view, view.alternativeGroups(input));
        Object logs = one(graph, BIRCH_LOG);
        require(amount(logs, "needs", BIRCH_LOG) == 5 && ((List<?>) value(logs, "made")).isEmpty()
                && ((List<?>) value(logs, "recipes")).isEmpty() && !(boolean) value(logs, "sharedBatch"),
                "Trapped chest inventory was duplicated or labelled as a generated batch");
        verifyPaths(graph, logs, view, BIRCH_LOG, selected);
        int demand = 0, produced = 0, branches = 0;
        for (var cell : cells(graph)) if (amount(cell, "needs", BIRCH_PLANKS) > 0) {
            branches++; demand += amount(cell, "needs", BIRCH_PLANKS); produced += amount(cell, "made", BIRCH_PLANKS);
            verifyInputs(graph, cell, BIRCH_LOG, 5);
        }
        require(branches == 2 && demand == 17 && produced == 20 && amount(view.consumed(), BIRCH_LOG) == 5
                && amount(view.surplus(), BIRCH_PLANKS) == 3 && view.reviewIdentity().equals(reviewed),
                "Pooling inventory changed distinct chest/hook demands, production, leftovers or reviewed costs");
        verifyGeometry(graph, "trapped-chest-shared-inventory");
        M4GraphPresentationScenario.capture(graph, "trapped-chest-shared-inventory");
        int pan = (int) field(PlanGraphWidget.class, graph, "panY");
        @SuppressWarnings("unchecked") var collapsed = (Set<String>) field(PlanGraphWidget.class, graph, "collapsed");
        collapsed.add(chest); graph.show(view); collapsed.clear(); graph.show(view);
        require(amount(one(graph, BIRCH_LOG), "needs", BIRCH_LOG) == 5
                && (int) field(PlanGraphWidget.class, graph, "panY") == pan && view.reviewIdentity().equals(reviewed),
                "Shared inventory collapse/refresh duplicated material or reset position");
        // The same resource name is not proof of an inventory origin. Reuse
        // the real inter-batch plank references, but replace the log extractions
        // with explicit, distinct generated batches in a read-only view.
        verifyGeneratedSources(view);
    }

    private static void verifyGeneratedSources(PlanView original) throws Exception {
        var logs = original.nodes().stream().filter(n -> n.needs().stream().anyMatch(s -> s.is(BIRCH_LOG))).toList();
        require(logs.size() >= 2 && logs.stream().mapToInt(n -> amount(n.needs(), BIRCH_LOG)).sum() == 5,
                "Trapped fixture lost its distinct actual extraction paths");
        var nodes = new ArrayList<PlanView.Node>();
        var operations = new ArrayList<PlanView.Operation>();
        var origins = new java.util.HashMap<String, Integer>();
        for (var log : logs) {
            int count = amount(log.needs(), BIRCH_LOG);
            origins.put(log.path(), operations.size());
            operations.add(new PlanView.Operation(id("generated_birch_log"), log.path(), List.of(stack(BIRCH_WOOD, count)),
                    stack(BIRCH_LOG, count), List.of(), List.of(-1)));
        }
        for (var node : original.nodes()) if (origins.containsKey(node.path())) {
            int count = amount(node.needs(), BIRCH_LOG);
            nodes.add(node(node.path(), node.needs(), List.of(stack(BIRCH_LOG, count)), List.of(id("generated_birch_log"))));
            nodes.add(node(node.path() + ".0", List.of(stack(BIRCH_WOOD, count)), List.of(), List.of()));
        } else nodes.add(node);
        for (var operation : original.operations()) {
            var inputOrigins = new ArrayList<Integer>();
            for (int slot = 0; slot < operation.inputs().size(); slot++) {
                int old = operation.inputOrigins().get(slot);
                Integer generated = origins.get(operation.path() + "." + slot);
                inputOrigins.add(old >= 0 ? old + logs.size() : generated == null ? -1 : generated);
            }
            operations.add(new PlanView.Operation(operation.recipe(), operation.path(), operation.inputs(), operation.output(),
                    operation.remainders(), inputOrigins));
        }
        var view = new PlanView(CraftingResultCode.CREATED, true, true, original.completedBatches(), nodes, operations,
                List.of(stack(BIRCH_WOOD, 5)), original.primary(), original.surplus(), List.of(), List.of());
        Object identity = view.reviewIdentity();
        var graph = new PlanGraphWidget(0, 0, 640, 480, ignored -> {}); graph.show(view);
        var generated = new ArrayList<Object>();
        for (var cell : cells(graph)) if (amount(cell, "needs", BIRCH_LOG) > 0) generated.add(cell);
        require(generated.size() == 2 && generated.stream().allMatch(cell -> {
            try { return !((List<?>) value(cell, "made")).isEmpty() && !((List<?>) value(cell, "recipes")).isEmpty(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }) && view.reviewIdentity().equals(identity), "Shared generated batches were incorrectly pooled as inventory");
    }

    private static void verifyIndependentInventory() throws Exception {
        var nodes = List.of(node("0", List.of(stack(TRAPPED_CHEST, 2)), List.of(stack(TRAPPED_CHEST, 2)), List.of(id("trapped_chest"))),
                node("0.0", List.of(stack(CHEST, 2)), List.of(stack(CHEST, 2)), List.of(id("chest"))),
                node("0.0.0", List.of(stack(BIRCH_PLANKS, 16)), List.of(stack(BIRCH_PLANKS, 16)), List.of(id("birch_planks"))),
                node("0.0.0.0", List.of(stack(BIRCH_LOG, 4)), List.of(), List.of()),
                node("0.1", List.of(stack(TRIPWIRE_HOOK, 2)), List.of(stack(TRIPWIRE_HOOK, 2)), List.of(id("tripwire_hook"))),
                node("0.1.0", List.of(stack(BIRCH_PLANKS, 1)), List.of(stack(BIRCH_PLANKS, 4)), List.of(id("birch_planks"))),
                node("0.1.0.0", List.of(stack(BIRCH_LOG, 1)), List.of(), List.of()));
        var operations = List.of(new PlanView.Operation(id("birch_planks"), "0.0.0", List.of(stack(BIRCH_LOG, 4)),
                        stack(BIRCH_PLANKS, 16), List.of(), List.of(-1)),
                new PlanView.Operation(id("birch_planks"), "0.1.0", List.of(stack(BIRCH_LOG, 1)),
                        stack(BIRCH_PLANKS, 4), List.of(), List.of(-1)),
                new PlanView.Operation(id("chest"), "0.0", List.of(stack(BIRCH_PLANKS, 16)), stack(CHEST, 2), List.of(), List.of(0)),
                new PlanView.Operation(id("tripwire_hook"), "0.1", List.of(stack(BIRCH_PLANKS, 1)), stack(TRIPWIRE_HOOK, 2), List.of(), List.of(1)),
                new PlanView.Operation(id("trapped_chest"), "0", List.of(stack(CHEST, 2), stack(TRIPWIRE_HOOK, 2)),
                        stack(TRAPPED_CHEST, 2), List.of(), List.of(2, 3)));
        var view = new PlanView(CraftingResultCode.CREATED, true, true, 2, nodes, operations, List.of(stack(BIRCH_LOG, 5)),
                List.of(stack(TRAPPED_CHEST, 2)), List.of(stack(BIRCH_PLANKS, 3)), List.of(), List.of());
        Object identity = view.reviewIdentity();
        var graph = new PlanGraphWidget(0, 0, 640, 480, ignored -> {}); graph.show(view);
        int sources = 0;
        for (var cell : cells(graph)) if (amount(cell, "needs", BIRCH_LOG) > 0) {
            sources++;
            require(((Set<?>) value(cell, "parents")).size() == 1, "Independent inventory origins acquired an unrelated consumer");
        }
        require(sources == 2, "Unrelated production routes were pooled merely because their inventory icons match");
        for (var cell : cells(graph)) if (amount(cell, "needs", BIRCH_PLANKS) > 0)
            verifyInputs(graph, cell, BIRCH_LOG, amount(cell, "needs", BIRCH_PLANKS) == 16 ? 4 : 1);
        verifyGeometry(graph, "trapped-chest-independent-inventory");
        M4GraphPresentationScenario.capture(graph, "trapped-chest-independent-inventory");
        require(view.reviewIdentity().equals(identity), "Independent routing changed reviewed material costs");
    }

    private static String inputPath(PlanningInput input, String parent, String recipe, Item item) {
        return parent + "." + input.find(id(recipe)).requirements().stream()
                .filter(r -> r.ingredient().test(stack(item, 1))).findFirst().orElseThrow().slot();
    }
    private static void verifyGeometry(PlanGraphWidget graph, String name) throws Exception {
        var method = M4GraphRoutingScenario.class.getDeclaredMethod("verifyGeometry", PlanGraphWidget.class, String.class);
        method.setAccessible(true); method.invoke(null, graph, name);
    }
    private static void verifyConservativeShapesAndMixedRecipeBadge() throws Exception {
        // The optional duplicate-shape normalization is not used for an
        // explanation merely because equal icons/recipe names are present.
        var root = node("0", List.of(stack(STICK, 4)), List.of(stack(STICK, 4)), List.of(id("stick")));
        var explanation = List.of(root,
                new PlanView.Node("0.0", List.of(stack(OAK_PLANKS, 1)), List.of(), List.of(id("oak_planks")), true, false, ""),
                new PlanView.Node("0.1", List.of(stack(OAK_PLANKS, 1)), List.of(), List.of(id("oak_planks")), true, false, ""),
                node("0.0.0", List.of(stack(OAK_LOG, 1)), List.of(), List.of()),
                node("0.1.0", List.of(stack(OAK_LOG, 1)), List.of(), List.of()),
                node("0.1.1", List.of(stack(OAK_LOG, 1)), List.of(), List.of()));
        var view = new PlanView(CraftingResultCode.MISSING_INGREDIENTS, true, true, 0, explanation, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        var graph = new PlanGraphWidget(0, 0, 480, 320, ignored -> {}); graph.show(view);
        var project = PlanGraphWidget.class.getDeclaredMethod("projectMaterials", Map.class); project.setAccessible(true);
        var raw = new java.util.LinkedHashMap<String, PlanView.Node>(); explanation.forEach(n -> raw.put(n.path(), n));
        @SuppressWarnings("unchecked") var projected = (Map<String, ?>) project.invoke(graph, raw);
        var shape = PlanGraphWidget.class.getDeclaredMethod("demandShape", String.class, Map.class, Map.class, Set.class);
        shape.setAccessible(true);
        var one = shape.invoke(null, "0.0", projected, new java.util.HashMap<>(), new java.util.HashSet<>());
        var two = shape.invoke(null, "0.1", projected, new java.util.HashMap<>(), new java.util.HashSet<>());
        require(!one.equals(two), "Unproved explanation discarded different repeated-input structure");
        var nodes = List.of(root,
                node("0.0", List.of(stack(OAK_PLANKS, 1)), List.of(stack(OAK_PLANKS, 8)),
                        List.of(id("oak_planks"), id("alternate_oak_planks"))),
                node("0.1", List.of(stack(OAK_PLANKS, 1)), List.of(), List.of()),
                node("0.0.0", List.of(stack(OAK_LOG, 1), stack(BIRCH_LOG, 1)), List.of(), List.of()));
        var operations = List.of(new PlanView.Operation(id("oak_planks"), "0.0", List.of(stack(OAK_LOG, 1)),
                        stack(OAK_PLANKS, 4), List.of(), List.of(-1)),
                new PlanView.Operation(id("alternate_oak_planks"), "0.0", List.of(stack(BIRCH_LOG, 1)),
                        stack(OAK_PLANKS, 4), List.of(), List.of(-1)),
                new PlanView.Operation(id("stick"), "0", List.of(stack(OAK_PLANKS, 1), stack(OAK_PLANKS, 1)),
                        stack(STICK, 4), List.of(), List.of(0, 1)));
        view = new PlanView(CraftingResultCode.CREATED, true, true, 1, nodes, operations, List.of(),
                List.of(stack(STICK, 4)), List.of(stack(OAK_PLANKS, 6)), List.of(), List.of());
        graph.show(view);
        var boards = one(graph, OAK_PLANKS);
        var badge = PlanGraphWidget.class.getDeclaredMethod("additionalMaterial", boards.getClass()); badge.setAccessible(true);
        require((boolean) badge.invoke(null, boards) && ((List<?>) value(boards, "recipes")).size() == 2,
                "Same-output mixed producer recipes lost their additional-route warning");
        require(!(boolean) badge.invoke(null, one(graph, STICK)), "Root result received a mixed-route warning");
    }
    private static void verifyPaths(PlanGraphWidget graph, Object cell, PlanView view, Item item, List<String> selected) throws Exception {
        var expected = view.nodes().stream().filter(n -> n.needs().stream().anyMatch(s -> s.is(item)))
                .map(PlanView.Node::path).collect(java.util.stream.Collectors.toSet());
        @SuppressWarnings("unchecked") var paths = (List<String>) value(cell, "paths");
        require(Set.copyOf(paths).equals(expected), "Mixed display lost original demand selection paths");
        selected.clear();
        graph.onClick((int) value(cell, "x") + (int) field(PlanGraphWidget.class, graph, "panX") + 8,
                (int) value(cell, "y") + (int) field(PlanGraphWidget.class, graph, "panY") + 8);
        require(Set.copyOf(selected).equals(expected), "Mixed display click did not preserve independent path choices");
    }
    private static void verifyTooltip(PlanGraphWidget graph, Object cell, Item boards, int boardCount, Item log, int logCount,
            Item otherBoards, Item otherLog) throws Exception {
        var method = PlanGraphWidget.class.getDeclaredMethod("tooltip", cell.getClass()); method.setAccessible(true);
        @SuppressWarnings("unchecked") var lines = ((List<Component>) method.invoke(graph, cell)).stream().map(Component::getString).toList();
        require(lines.contains(amount(boardCount, boards)) && lines.contains(amount(logCount, log))
                && lines.stream().noneMatch(s -> s.contains(stack(otherBoards, 1).getHoverName().getString())
                        || s.contains(stack(otherLog, 1).getHoverName().getString())),
                "Variant tooltip summed operations solely by shared path: " + lines);
    }
    private static PlanView.Node node(String path, List<ItemStack> needs, List<ItemStack> made, List<ResourceLocation> recipes) {
        return new PlanView.Node(path, needs, made, recipes, false, false, "");
    }
    private static Object one(PlanGraphWidget graph, Item item) throws Exception {
        var found = new ArrayList<Object>();
        for (var cell : cells(graph)) if (amount(cell, "needs", item) > 0) found.add(cell);
        require(found.size() == 1, "Expected one exact aggregate of " + item + ", got " + found.size());
        return found.getFirst();
    }
    private static List<?> cells(PlanGraphWidget graph) throws Exception { return (List<?>) field(PlanGraphWidget.class, graph, "cells"); }
    private static int amount(Object cell, String name, Item item) throws Exception {
        @SuppressWarnings("unchecked") var stacks = (List<ItemStack>) value(cell, name); return amount(stacks, item);
    }
    private static int amount(List<ItemStack> stacks, Item item) { return stacks.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static String amount(int count, Item item) {
        return Component.translatable("screen.craftable.plan.item_count", count, stack(item, 1).getHoverName()).getString();
    }
    private static Object value(Object cell, String name) throws Exception { return field(cell.getClass(), cell, name); }
    private static Object field(Class<?> type, Object instance, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private static ResourceLocation id(String name) { return ResourceLocation.withDefaultNamespace(name); }
    private static ItemStack stack(Item item, int count) { return new ItemStack(item, count); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
