package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        System.out.println("M4_GRAPH_PRESENTATION PASS batchTotals=3 siblingPaths=5 components/routes/references/remainders=preserved");
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
