package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.PlanView;

/** Display-only fixtures driven by the existing planning entry. No world or
 * network authority is manufactured and no second test runner is registered. */
final class M4GraphPresentationScenario {
    static void verify() throws Exception {
        var pick = id("diamond_pickaxe");
        var nodes = List.of(node("0", stack(Items.DIAMOND_PICKAXE, 3), stack(Items.DIAMOND_PICKAXE, 3), pick),
                node("0.0", stack(Items.DIAMOND, 9), ItemStack.EMPTY, null),
                node("0.1", stack(Items.STICK, 6), ItemStack.EMPTY, null));
        var operations = new ArrayList<PlanView.Operation>();
        for (int i = 0; i < 3; i++) operations.add(op(pick, "0",
                List.of(stack(Items.DIAMOND, 3), stack(Items.STICK, 2)), stack(Items.DIAMOND_PICKAXE, 1), List.of()));
        var view = view(nodes, operations);
        Object identity = view.reviewIdentity();
        var graph = new PlanGraphWidget(0, 0, 400, 240, ignored -> {});
        graph.show(view);
        var root = cell(graph, "0");
        var tooltip = tooltip(graph, root);
        require(tooltip.stream().filter(s -> s.equals(text("recipe_inputs"))).count() == 1, "Repeated input heading");
        require(tooltip.stream().filter(s -> s.equals(text("recipe_outputs"))).count() == 1, "Repeated output heading");
        require(tooltip.contains(amount(9, Items.DIAMOND)) && tooltip.contains(amount(6, Items.STICK))
                && tooltip.contains(amount(3, Items.DIAMOND_PICKAXE)), "Three pickaxes lost aggregate costs/output");
        require(tooltip.size() < 14 && tooltip.stream().noneMatch(s -> s.contains("minecraft:")), "Verbose or technical tooltip");
        require(view.reviewIdentity().equals(identity) && view.operations().size() == 3, "Presentation mutated the plan");

        // Five independent but equivalent plank subtrees: one displayed chain,
        // all five production paths and their full quantities are retained.
        var boatNodes = new ArrayList<PlanView.Node>();
        boatNodes.add(node("0", stack(Items.OAK_BOAT, 4), stack(Items.OAK_BOAT, 4), id("oak_boat")));
        var boatOps = new ArrayList<PlanView.Operation>();
        for (int i = 0; i < 5; i++) {
            boatNodes.add(node("0." + i, stack(Items.OAK_PLANKS, 4), stack(Items.OAK_PLANKS, 4), id("oak_planks")));
            boatNodes.add(node("0." + i + ".0", stack(Items.OAK_LOG, 1), ItemStack.EMPTY, null));
            boatOps.add(op(id("oak_planks"), "0." + i, List.of(stack(Items.OAK_LOG, 1)), stack(Items.OAK_PLANKS, 4), List.of()));
        }
        var selected = new ArrayList<String>();
        graph = new PlanGraphWidget(0, 0, 400, 240, selected::addAll);
        var boat = view(boatNodes, boatOps);
        identity = boat.reviewIdentity(); graph.show(boat);
        require(cells(graph).size() == 3, "Equivalent sibling subtrees were not merged");
        var planks = cell(graph, "0.0");
        require(((List<?>) field(planks, "paths")).size() == 5, "Merged production lost demand paths");
        require(count(planks, "needs", Items.OAK_PLANKS) == 20 && count(planks, "made", Items.OAK_PLANKS) == 20
                && count(cell(graph, "0.0.0"), "needs", Items.OAK_LOG) == 5, "Merged quantities are not additive");
        tooltip = tooltip(graph, planks);
        require(tooltip.contains(amount(5, Items.OAK_LOG)) && tooltip.contains(amount(20, Items.OAK_PLANKS)), "Merged costs lost batches");
        require(tooltip.contains(text("shared", 5)) && tooltip.stream().noneMatch(s -> s.contains("不会重复") || s.contains("not counted")),
                "Shared-node tooltip retained old explanatory prose");
        graph.onClick((int) field(planks, "x") + (int) field(graph, "panX") + 8,
                (int) field(planks, "y") + (int) field(graph, "panY") + 8);
        require(selected.equals(List.of("0.0", "0.1", "0.2", "0.3", "0.4")), "Click lost represented constraints");
        require(boat.reviewIdentity().equals(identity), "Merging modified reviewed operations");

        // Equal icons alone are insufficient: different underlying materials,
        // recipe selections, components, and explanatory ORs stay distinct.
        var distinct = new ArrayList<>(boatNodes);
        distinct.set(4, node("0.1.0", stack(Items.BIRCH_LOG, 1), ItemStack.EMPTY, null));
        graph.show(view(distinct, boatOps));
        require(cells(graph).size() == 5, "Different input routes merged");
        distinct = new ArrayList<>(boatNodes);
        distinct.set(3, node("0.1", stack(Items.OAK_PLANKS, 4), stack(Items.OAK_PLANKS, 4), id("alternate_planks")));
        graph.show(view(distinct, boatOps));
        require(cells(graph).size() == 5, "Different selected recipes merged");
        var named = stack(Items.DIAMOND, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct component"));
        graph.show(view(List.of(nodes.getFirst(), node("0.0", stack(Items.DIAMOND, 1), ItemStack.EMPTY, null),
                node("0.1", named, ItemStack.EMPTY, null)), List.of()));
        require(cells(graph).size() == 3, "Distinct components merged");
        graph.show(view(List.of(nodes.getFirst(), node("0.0", stack(Items.DIAMOND, 1), ItemStack.EMPTY, null),
                new PlanView.Node("0.1", List.of(stack(Items.DIAMOND, 1)), List.of(), List.of(), true, true, "")), List.of()));
        require(cells(graph).size() == 3, "Explanatory OR merged with actual input");

        var stick = id("stick");
        graph.show(view(List.of(nodes.getFirst(), node("0.0", stack(Items.STICK, 2), stack(Items.STICK, 4), stick),
                node("0.0.0", stack(Items.OAK_PLANKS, 2), ItemStack.EMPTY, null),
                new PlanView.Node("0.1", List.of(stack(Items.STICK, 2)), List.of(), List.of(), false, false, "0.0")),
                List.of(op(stick, "0.0", List.of(stack(Items.OAK_PLANKS, 2)), stack(Items.STICK, 4), List.of()),
                        new PlanView.Operation(pick, "0", List.of(stack(Items.STICK, 2), stack(Items.STICK, 2)),
                                stack(Items.DIAMOND_PICKAXE, 1), List.of(), List.of(0, 0)))));
        require(cells(graph).size() == 3 && count(cell(graph, "0.0"), "made", Items.STICK) == 4,
                "Same-batch reference duplicated production");

        var bucketOps = List.of(op(id("cake"), "0", List.of(stack(Items.MILK_BUCKET, 3)), stack(Items.CAKE, 1),
                List.of(stack(Items.BUCKET, 3))), op(id("cake"), "0", List.of(stack(Items.MILK_BUCKET, 3)),
                stack(Items.CAKE, 1), List.of(stack(Items.BUCKET, 3))));
        graph.show(view(List.of(node("0", stack(Items.CAKE, 2), stack(Items.CAKE, 2), id("cake"))), bucketOps));
        tooltip = tooltip(graph, cell(graph, "0"));
        require(tooltip.contains(amount(6, Items.MILK_BUCKET)) && tooltip.contains(amount(2, Items.CAKE))
                && tooltip.contains(amount(6, Items.BUCKET)), "Aggregate remainders lost quantities");
        verifyMissingLeaves();
        verifyCandidateTooltips();
        verifyExplanationBranches();
        System.out.println("M4_GRAPH_PRESENTATION PASS batchTotals=3 siblingPaths=5 missingGold=3 candidateInputs=3+2 tooltipRows/noLF/chestOR=8 selectedWood/rawLeaf/siblingExplanation=preserved");
    }

    private static void verifyMissingLeaves() throws Exception {
        var root = node("0", stack(Items.GOLDEN_PICKAXE, 1), ItemStack.EMPTY, id("golden_pickaxe"));
        var nodes = List.of(root, missing("0.0", Items.GOLD_INGOT), missing("0.1", Items.GOLD_INGOT),
                missing("0.2", Items.GOLD_INGOT), node("0.3", stack(Items.STICK, 2), ItemStack.EMPTY, null));
        var view = view(nodes, List.of());
        var identity = view.reviewIdentity();
        var selected = new ArrayList<String>();
        var graph = new PlanGraphWidget(0, 0, 400, 240, selected::addAll);
        graph.show(view);
        var gold = cell(graph, "0.0");
        require(cells(graph).size() == 3 && count(gold, "needs", Items.GOLD_INGOT) == 3,
                "Three identical missing ingots did not merge into count 3");
        require(field(gold, "paths").equals(List.of("0.0", "0.1", "0.2")), "Missing leaf merge lost real demand paths");
        require(tooltip(graph, gold).contains(text("need", 3, new ItemStack(Items.GOLD_INGOT).getHoverName()))
                && !tooltip(graph, gold).contains(text("or")), "Fixed missing leaf tooltip is still presented as an OR");
        graph.onClick((int) field(gold, "x") + (int) field(graph, "panX") + 8,
                (int) field(gold, "y") + (int) field(graph, "panY") + 8);
        require(selected.equals(List.of("0.0", "0.1", "0.2")), "Missing leaf click lost constraints");
        require(view.reviewIdentity().equals(identity) && view.nodes().size() == 5
                && view.nodes().get(1).needs().getFirst().getCount() == 1, "Display merge rewrote true requirements");

        var or = List.of(stack(Items.GOLD_INGOT, 1), stack(Items.IRON_INGOT, 1));
        graph.show(view(List.of(root, new PlanView.Node("0.0", or, List.of(), List.of(), true, true, ""),
                new PlanView.Node("0.1", or, List.of(), List.of(), true, true, "")), List.of()));
        require(cells(graph).size() == 2 && count(cell(graph, "0.0"), "needs", Items.GOLD_INGOT) == 2
                && count(cell(graph, "0.0"), "needs", Items.IRON_INGOT) == 2
                && tooltip(graph, cell(graph, "0.0")).contains(text("or")), "Identical OR slots lost quantity or alternative semantics");
        graph.show(view(List.of(root, new PlanView.Node("0.0", or, List.of(), List.of(), true, true, ""),
                new PlanView.Node("0.1", List.of(stack(Items.GOLD_INGOT, 1), stack(Items.COPPER_INGOT, 1)),
                        List.of(), List.of(), true, true, "")), List.of()));
        require(cells(graph).size() == 3, "Different OR sets were merged");
        graph.show(view(List.of(root,
                new PlanView.Node("0.0", List.of(stack(Items.GOLD_INGOT, 1)), List.of(), List.of(id("gold_ingot_from_nuggets")), true, true, ""),
                new PlanView.Node("0.1", List.of(stack(Items.GOLD_INGOT, 1)), List.of(), List.of(id("gold_ingot_from_nuggets")), true, true, ""),
                missing("0.0.0", Items.GOLD_NUGGET), missing("0.1.0", Items.GOLD_NUGGET)), List.of()));
        require(cells(graph).size() == 5, "Explanation subrecipes or leaves under different parents were merged");
        graph.show(view(List.of(root, missing("0.0", Items.GOLD_INGOT), missing("0.1", Items.GOLD_INGOT),
                new PlanView.Node("0.2", List.of(stack(Items.GOLD_INGOT, 1)), List.of(), List.of(), true, true, "0.0")), List.of()));
        require(cells(graph).size() == 3 && ((List<?>) field(cell(graph, "0.1"), "paths")).size() == 1,
                "Reference source was structurally merged with an independent missing leaf");
        var named = stack(Items.GOLD_INGOT, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct missing component"));
        graph.show(view(List.of(root, missing("0.0", Items.GOLD_INGOT),
                new PlanView.Node("0.1", List.of(named), List.of(), List.of(), true, true, "")), List.of()));
        require(cells(graph).size() == 3, "Missing items with different components were merged");
    }

    @SuppressWarnings("unchecked")
    private static void verifyCandidateTooltips() throws Exception {
        var field = CraftingPlanOverlay.class.getDeclaredField("current");
        field.setAccessible(true);
        var overlay = field.get(null);
        require(overlay != null, "No live detail overlay for candidate tooltip fixture");
        var candidate = new PlanView.Candidate(id("golden_pickaxe"), stack(Items.GOLDEN_PICKAXE, 1), 3, null);
        var labelMethod = CraftingPlanOverlay.class.getDeclaredMethod("candidateLabel", PlanView.Candidate.class);
        labelMethod.setAccessible(true);
        String label = ((Component) labelMethod.invoke(overlay, candidate)).getString();
        require(label.equals(new ItemStack(Items.GOLDEN_PICKAXE).getHoverName().getString() + " ×1"),
                "Candidate title retained status prefix or recipe suffix");
        var tooltipMethod = CraftingPlanOverlay.class.getDeclaredMethod("candidateTooltip", PlanView.Candidate.class);
        tooltipMethod.setAccessible(true);
        var lines = ((List<Component>) tooltipMethod.invoke(overlay, candidate)).stream().map(Component::getString).toList();
        require(lines.size() == 4 && lines.getFirst().equals(label) && lines.get(1).equals(text("candidate_grid", 3, 3))
                && lines.contains(text("recipe_inputs") + " " + amount(3, Items.GOLD_INGOT))
                && lines.contains(text("recipe_inputs") + " " + amount(2, Items.STICK))
                && lines.stream().noneMatch(s -> s.contains("\n") || s.contains("minecraft:") || s.contains("golden_pickaxe")),
                "Golden pickaxe tooltip is not separate, clean rows: " + lines);
        var inputMethod = CraftingPlanOverlay.class.getDeclaredMethod("candidateInputLines", List.class);
        inputMethod.setAccessible(true);
        var named = stack(Items.GOLD_INGOT, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct ingredient"));
        var inputs = List.of(Ingredient.of(Items.GOLD_INGOT), Ingredient.of(Items.GOLD_INGOT), Ingredient.of(named),
                Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Ingredient.of(Items.BIRCH_PLANKS, Items.OAK_PLANKS));
        var rows = ((List<Component>) inputMethod.invoke(null, inputs)).stream().map(Component::getString).toList();
        require(rows.size() == 3 && rows.getFirst().equals(text("recipe_inputs") + " " + amount(2, Items.GOLD_INGOT))
                && rows.get(1).contains(named.getHoverName().getString())
                && rows.get(2).contains(text("input_options", 2, Component.literal(new ItemStack(Items.OAK_PLANKS).getHoverName().getString()
                        + ", " + new ItemStack(Items.BIRCH_PLANKS).getHoverName().getString()))),
                "Card merged different components or counted OR alternatives as fixed input: " + rows);
        var rejected = new PlanView.Candidate(candidate.recipe(), candidate.output(), 3, CraftingResultCode.MISSING_WORKSTATION);
        require(((List<Component>) tooltipMethod.invoke(overlay, rejected)).getLast().getString().equals(
                Component.translatable("reason.craftable.missing_workstation").getString()), "Candidate rejection reason is not its own row");
        var helmet = new PlanView.Candidate(id("turtle_helmet"), stack(Items.TURTLE_HELMET, 1), 3, null);
        var helmetRows = ((List<Component>) tooltipMethod.invoke(overlay, helmet)).stream().map(Component::getString).toList();
        require(helmetRows.size() == 3 && helmetRows.get(2).equals(text("recipe_inputs") + " " + amount(5, Items.TURTLE_SCUTE))
                && helmetRows.stream().noneMatch(s -> s.contains("\n")), "Single-material tooltip retained an LF glyph");
        var chest = new PlanView.Candidate(id("chest"), stack(Items.CHEST, 1), 3, null);
        var chestRows = ((List<Component>) tooltipMethod.invoke(overlay, chest)).stream().map(Component::getString).toList();
        require(chestRows.size() == 3 && chestRows.get(2).contains("8 ×") && chestRows.get(2).contains("…")
                && chestRows.stream().noneMatch(s -> s.contains("\n")), "Eight identical chest OR slots were not grouped");
        var a = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS, Items.JUNGLE_PLANKS);
        var b = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS, Items.ACACIA_PLANKS);
        require(((List<?>) inputMethod.invoke(null, List.of(a, b))).size() == 2,
                "OR sets sharing only their displayed first three options were merged");
    }

    private static void verifyExplanationBranches() throws Exception {
        var inputField = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.class.getDeclaredField("input");
        inputField.setAccessible(true);
        var input = (org.berusted.craftable.recipe.PlanningInput) inputField.get(null);
        var blocked = org.berusted.craftable.planner.SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS);
        var boat = PlanView.from(input, org.berusted.craftable.planner.CraftRequest.one(id("oak_boat")), blocked, List.of(), List.of());
        require(boat.nodes().stream().noneMatch(n -> n.recipes().contains(id("oak_wood")))
                && boat.nodes().stream().anyMatch(n -> n.needs().stream().anyMatch(s -> s.is(Items.OAK_LOG))),
                "Blocked boat explanation needlessly processed logs to wood");
        var selected = new ArrayList<String>();
        var graph = new PlanGraphWidget(0, 0, 400, 240, selected::addAll);
        var identity = boat.reviewIdentity();
        graph.show(boat);
        require(cells(graph).size() == 3 && count(cell(graph, "0.0"), "needs", Items.OAK_PLANKS) == 5,
                "Identical selected explanation branches were not merged");
        var planks = cell(graph, "0.0");
        graph.onClick((int) field(planks, "x") + (int) field(graph, "panX") + 8,
                (int) field(planks, "y") + (int) field(graph, "panY") + 8);
        require(selected.size() == 5 && boat.reviewIdentity().equals(identity) && boat.operations().isEmpty()
                && boat.consumed().isEmpty(), "Explanation aliases lost paths or fabricated operations/cost");
        // Paths use the normalizer's canonical grid size (stick is 2x2),
        // not adjacent indices in the chooser's visual 3x3 ingredient list.
        var pins = new java.util.TreeMap<String, ResourceLocation>();
        input.find(id("stick")).requirements().forEach(r -> pins.put("0." + r.slot(), id("birch_planks")));
        var pinned = new org.berusted.craftable.planner.CraftRequest(id("stick"), 1, false, false,
                org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                pins);
        var birch = PlanView.from(input, pinned, blocked, List.of(), List.of());
        require(birch.nodes().stream().filter(n -> pins.containsKey(n.path())).count() == 2
                && birch.nodes().stream().filter(n -> pins.containsKey(n.path()))
                .allMatch(n -> !n.alternatives() && n.needs().size() == 1 && n.needs().getFirst().is(Items.BIRCH_PLANKS)),
                "Pinned birch recipe retained the first oak candidate icon");
        graph.show(birch);
        require(cells(graph).size() == 3 && count(cell(graph, "0.0"), "needs", Items.BIRCH_PLANKS) == 2,
                "Equivalent pinned birch explanation subtrees did not merge");
        // Leave the live detail untouched: fixtures consume its immutable
        // normalized catalog, not a second index or a manufactured grant.
    }

    private static PlanView.Node missing(String path, net.minecraft.world.item.Item item) {
        return new PlanView.Node(path, List.of(stack(item, 1)), List.of(), List.of(), true, true, "");
    }

    private static PlanView view(List<PlanView.Node> nodes, List<PlanView.Operation> ops) {
        return new PlanView(CraftingResultCode.CREATED, true, true, 1, nodes, ops, List.of(), List.of(), List.of(), List.of(), List.of());
    }
    private static PlanView.Node node(String path, ItemStack need, ItemStack made, ResourceLocation recipe) {
        return new PlanView.Node(path, List.of(need), made.isEmpty() ? List.of() : List.of(made),
                recipe == null ? List.of() : List.of(recipe), false, false, "");
    }
    private static PlanView.Operation op(ResourceLocation recipe, String path, List<ItemStack> inputs, ItemStack output,
            List<ItemStack> remainders) {
        return new PlanView.Operation(recipe, path, inputs, output, remainders, java.util.Collections.nCopies(inputs.size(), -1));
    }
    private static ItemStack stack(net.minecraft.world.item.Item item, int count) { return new ItemStack(item, count); }
    private static ResourceLocation id(String id) { return ResourceLocation.withDefaultNamespace(id); }
    private static String text(String key, Object... args) { return Component.translatable("screen.craftable.plan." + key, args).getString(); }
    private static String amount(int count, net.minecraft.world.item.Item item) { return text("item_count", count, new ItemStack(item).getHoverName()); }
    private static List<?> cells(PlanGraphWidget graph) throws Exception { return (List<?>) field(graph, "cells"); }
    private static Object cell(PlanGraphWidget graph, String id) throws Exception {
        for (var cell : cells(graph)) if (field(cell, "id").equals(id)) return cell;
        throw new AssertionError("Missing graph cell " + id);
    }
    @SuppressWarnings("unchecked")
    private static int count(Object cell, String list, net.minecraft.world.item.Item item) throws Exception {
        return ((List<ItemStack>) field(cell, list)).stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    @SuppressWarnings("unchecked")
    private static List<String> tooltip(PlanGraphWidget graph, Object cell) throws Exception {
        var method = PlanGraphWidget.class.getDeclaredMethod("tooltip", cell.getClass());
        method.setAccessible(true);
        return ((List<Component>) method.invoke(graph, cell)).stream().map(Component::getString).toList();
    }
    private static Object field(Object obj, String name) throws Exception {
        var field = obj.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(obj);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
