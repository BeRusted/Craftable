package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.PlanView;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.planner.SearchResult;
import org.berusted.craftable.recipe.PlanningInput;

/** User-reported routing shapes, exercised by the existing client planning
 * entry. Frozen recipes, normal search/projection and the production widget
 * are reused; these cases neither grant resources nor implement a renderer. */
final class M4GraphRoutingScenario {
    static void verify() throws Exception {
        var frozen = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.class.getDeclaredField("input");
        frozen.setAccessible(true);
        var input = (PlanningInput) frozen.get(null);
        verifyLectern(input);
        verifySharedRedstone(input, "repeater");
        verifySharedRedstone(input, "crafter");
        verifySmoker(input);
        System.out.println("M4_GRAPH_ROUTING PASS lectern/repeater/crafter missing+real smoker/fullOR/mixedWood unrelatedJunctions=0 singleInput=straight consumerPort=unique paths/authority/pan/collapse=preserved");
    }

    private static void verifyLectern(PlanningInput input) throws Exception {
        var pins = new TreeMap<String, ResourceLocation>();
        var slabs = paths(input, "0", "lectern", Items.ACACIA_SLAB);
        // Pin the chosen slab output, not every deterministic acacia-plank
        // input below it. Ordinary material/producer matching supplies that
        // route without filling the bounded player-selection map.
        for (String slab : slabs) pins.put(slab, id("acacia_slab"));
        String shelf = paths(input, "0", "lectern", Items.BOOKSHELF).getFirst();
        pins.put(shelf, id("bookshelf"));
        var boards = paths(input, shelf, "bookshelf", Items.OAK_PLANKS);
        require(boards.size() == 6, "Lectern fixture no longer has six shelf boards");
        for (String board : boards.subList(3, 6)) pins.put(board, id("acacia_planks"));
        var leather = producer(input, Items.LEATHER, Items.RABBIT_HIDE);
        for (String book : paths(input, shelf, "bookshelf", Items.BOOK)) {
            pins.put(book, id("book"));
            // Paper has a unique ordinary producer in the frozen vanilla
            // catalog. The actual cane cost and straight chain remain checked.
            pinInputs(input, pins, book, "book", Items.LEATHER, leather);
        }
        require(pins.size() <= CraftRequest.MAX_SELECTIONS, "Lectern fixture over-specified deterministic routes");
        var request = request("lectern", pins);
        var stock = List.of(stack(Items.OAK_PLANKS, 3), stack(Items.SUGAR_CANE, 9), stack(Items.RABBIT_HIDE, 12));
        var missing = PlanView.from(input, request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), List.of(), stock);
        verifyView(missing, "lectern-missing", true);
        var realStock = new ArrayList<>(stock);
        realStock.add(stack(Items.ACACIA_LOG, 2));
        var real = searched(input, request, realStock);
        require(amount(real.consumed(), Items.SUGAR_CANE) == 9 && amount(real.consumed(), Items.RABBIT_HIDE) == 12
                && amount(real.consumed(), Items.OAK_PLANKS) == 3 && amount(real.consumed(), Items.ACACIA_LOG) == 2,
                "Lectern real fixture lost authoritative material quantities");
        verifyView(real, "lectern-real", true);
    }

    private static void verifySharedRedstone(PlanningInput input, String target) throws Exception {
        var pins = new TreeMap<String, ResourceLocation>();
        var split = producer(input, Items.REDSTONE, Items.REDSTONE_BLOCK);
        pinInputs(input, pins, "0", target, Items.REDSTONE, split);
        String branchRecipe = target.equals("repeater") ? "redstone_torch" : "dropper";
        Item branchItem = target.equals("repeater") ? Items.REDSTONE_TORCH : Items.DROPPER;
        for (String branch : paths(input, "0", target, branchItem)) {
            pins.put(branch, id(branchRecipe));
            pinInputs(input, pins, branch, branchRecipe, Items.REDSTONE, split);
        }
        var request = request(target, pins);
        var stock = target.equals("repeater") ? List.of(stack(Items.STICK, 2), stack(Items.STONE, 3))
                : List.of(stack(Items.IRON_INGOT, 5), stack(Items.CRAFTING_TABLE, 1), stack(Items.COBBLESTONE, 7));
        var missing = PlanView.from(input, request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), List.of(), stock);
        verifyView(missing, target + "-missing", false);
        verifyDust(missing, target);
        var realStock = new ArrayList<>(stock);
        realStock.add(stack(Items.REDSTONE_BLOCK, 1));
        var real = searched(input, request, realStock);
        require(amount(real.consumed(), Items.REDSTONE_BLOCK) == 1 && amount(real.surplus(), Items.REDSTONE) == 6,
                "Shared dust routing changed one block's cost or six-dust surplus");
        verifyView(real, target + "-real", false);
        verifyDust(real, target);
    }

    private static PlanView searched(PlanningInput input, CraftRequest request, List<ItemStack> stock) {
        var sources = new ArrayList<ResourceLedger.Source>();
        for (int i = 0; i < stock.size(); i++) sources.add(new ResourceLedger.Source("routing-fixture", i, stock.get(i)));
        var result = new CraftSearch(input, request, sources, new SearchBudget(1_000_000_000L), ignored -> null).run();
        require(result.code() == CraftingResultCode.CREATED, "Routing fixture cannot craft " + request.recipe() + ": " + result);
        return PlanView.from(input, request, result, List.of(), stock);
    }

    private static void verifyView(PlanView view, String name, boolean singleInputChains) throws Exception {
        verifyView(view, name, singleInputChains, Map.of());
    }

    private static void verifyView(PlanView view, String name, boolean singleInputChains, Map<String, Object> groups) throws Exception {
        var identity = view.reviewIdentity();
        var selected = new ArrayList<String>();
        var graph = new PlanGraphWidget(0, 0, 640, 600, selected::addAll);
        graph.show(view, groups);
        M4GraphPresentationScenario.capture(graph, "routing-" + name);
        verifyGeometry(graph, name);
        if (name.startsWith("repeater")) verifyConsumerPorts(graph, name);
        @SuppressWarnings("unchecked") var routed = (List<PlanGraphRouting.Link>) field(graph, "connections");
        var geometry = new ArrayList<PlanGraphRouting.Node>();
        for (var cell : cells(graph)) geometry.add(new PlanGraphRouting.Node((String) field(cell, "id"),
                number(cell, "x"), number(cell, "y")));
        require(PlanGraphRouting.score(geometry, routed).outside() == 0, name + " unnecessarily routed around the whole scene");
        var represented = new HashSet<String>();
        for (var cell : cells(graph)) {
            @SuppressWarnings("unchecked") var paths = (List<String>) field(cell, "paths");
            represented.addAll(paths);
            selected.clear();
            graph.onClick(number(cell, "x") + number(graph, "panX") + 8, number(cell, "y") + number(graph, "panY") + 8);
            require(selected.equals(paths), name + " lost a selectable demand path");
            if (singleInputChains && (amount(cell, Items.PAPER) > 0 || amount(cell, Items.LEATHER) > 0)) {
                var children = (List<?>) field(cell, "children");
                require(children.size() == 1, name + " did not merge the homogeneous single-input chain");
                var child = cell(graph, (String) children.getFirst());
                require(number(cell, "y") == number(child, "y"), name + " gave paper/leather an unnecessary elbow");
            }
        }
        require(represented.equals(new HashSet<>(view.nodes().stream().map(PlanView.Node::path).toList())), name + " lost projection paths");
        int count = cells(graph).size();
        graph.onDrag(0, 0, -23, 17);
        int panX = number(graph, "panX"), panY = number(graph, "panY");
        graph.show(view);
        require(cells(graph).size() == count && number(graph, "panX") == panX && number(graph, "panY") == panY,
                name + " refresh changed count or reset pan");
        @SuppressWarnings("unchecked") var collapsed = (java.util.Set<String>) field(graph, "collapsed");
        String branch = null;
        for (var cell : cells(graph)) if (!field(cell, "id").equals("0") && !((List<?>) field(cell, "children")).isEmpty()) {
            branch = (String) field(cell, "id"); break;
        }
        if (branch != null) {
            collapsed.add(branch); graph.show(view);
            verifyGeometry(graph, name + "-collapsed");
            collapsed.clear(); graph.show(view);
            require(cells(graph).size() == count && number(graph, "panX") == panX && number(graph, "panY") == panY,
                    name + " expansion changed count or reset pan");
        }
        require(identity.equals(view.reviewIdentity()), name + " mutated reviewed operations/costs");
    }

    private static void verifySmoker(PlanningInput input) throws Exception {
        var request = request("smoker", Map.of());
        var logPaths = paths(input, "0", "smoker", Items.OAK_LOG);
        require(logPaths.size() == 4, "Smoker fixture lost four homogeneous log demands");
        var stock = List.of(stack(Items.FURNACE, 1));
        var missing = PlanView.from(input, request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), List.of(), stock);
        verifyView(missing, "smoker-missing-four", false, missing.alternativeGroups(input));
        verifySmokerLogs(input, missing, logPaths, 4, 0, 0);
        stock = List.of(stack(Items.FURNACE, 1), stack(Items.BIRCH_LOG, 1), stack(Items.OAK_LOG, 1));
        missing = PlanView.from(input, request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), List.of(), stock);
        verifyView(missing, "smoker-missing-two", false, missing.alternativeGroups(input));
        verifySmokerLogs(input, missing, logPaths, 2, 1, 1);
        var real = searched(input, request, List.of(stack(Items.FURNACE, 1), stack(Items.BIRCH_LOG, 2), stack(Items.OAK_LOG, 2)));
        require(amount(real.consumed(), Items.FURNACE) == 1 && amount(real.consumed(), Items.BIRCH_LOG) == 2
                && amount(real.consumed(), Items.OAK_LOG) == 2, "Smoker projection changed real mixed-material costs");
        verifyView(real, "smoker-real-mixed", false);
        verifySmokerLogs(input, real, logPaths, 0, 2, 2);
        System.out.println("M4_SMOKER_OR PASS missing4/missing2 fullSetMerge realBirchOak=separate selectionPaths=4 authority=preserved");
    }

    private static void verifySmokerLogs(PlanningInput input, PlanView view, List<String> logPaths,
            int missing, int oak, int birch) throws Exception {
        var selected = new ArrayList<String>();
        var graph = new PlanGraphWidget(0, 0, 640, 600, selected::addAll);
        var identity = view.reviewIdentity();
        graph.show(view, view.alternativeGroups(input));
        int orCells = 0, knownCells = 0, displayedOak = 0, displayedBirch = 0;
        var represented = new HashSet<String>();
        for (var c : cells(graph)) {
            @SuppressWarnings("unchecked") var paths = (List<String>) field(c, "paths");
            if (paths.stream().noneMatch(logPaths::contains)) continue;
            require(logPaths.containsAll(paths), "Smoker log grouping crossed a parent/other material");
            for (String path : paths) require(represented.add(path), "Smoker log path was counted twice");
            if ((boolean) field(c, "alternatives")) {
                orCells++;
                @SuppressWarnings("unchecked") var options = (List<ItemStack>) field(c, "needs");
                require(options.size() == 16 && options.stream().allMatch(s -> s.getCount() == missing)
                        && paths.size() == missing, "Complete smoker OR did not keep one independent choice per missing log");
            } else {
                knownCells++;
                require(((List<?>) field(c, "needs")).size() == 1, "Real oak/birch were turned into an alternative icon");
                displayedOak += amount(c, Items.OAK_LOG); displayedBirch += amount(c, Items.BIRCH_LOG);
            }
            selected.clear();
            graph.onClick(number(c, "x") + number(graph, "panX") + 8, number(c, "y") + number(graph, "panY") + 8);
            require(selected.equals(paths), "Smoker merged click lost real slot constraints");
        }
        require(orCells == (missing == 0 ? 0 : 1) && knownCells == (oak > 0 ? 1 : 0) + (birch > 0 ? 1 : 0)
                && displayedOak == oak && displayedBirch == birch && represented.equals(new HashSet<>(logPaths)),
                "Smoker failed complete-set OR aggregation or merged distinct actual wood");
        require(identity.equals(view.reviewIdentity()), "Smoker OR aggregation changed executable authority");
    }

    private static void verifyConsumerPorts(PlanGraphWidget graph, String name) throws Exception {
        for (var consumer : cells(graph)) {
            var incoming = new ArrayList<Object>();
            for (var link : (List<?>) field(graph, "connections")) if (field(link, "target").equals(field(consumer, "id"))) incoming.add(link);
            if (incoming.size() < 2) continue;
            int x = number(consumer, "x"), y = number(consumer, "y") + 13;
            for (var link : incoming) {
                var ports = new HashSet<Integer>();
                for (var s : (List<?>) field(link, "segments")) if (number(s, "y1") == number(s, "y2")
                        && Math.min(number(s, "x1"), number(s, "x2")) < x
                        && Math.max(number(s, "x1"), number(s, "x2")) >= x) ports.add(number(s, "y1"));
                require(ports.equals(java.util.Set.of(y)), name + " gave consumer " + field(consumer, "id")
                        + " external input ports " + ports + " instead of " + y);
                require(strokeContains(link, x - 1, y) && strokeContains(link, x + 1, y),
                        name + " broke a legitimate consumer inlet in the rendered strokes");
            }
        }
    }

    private static void verifyDust(PlanView view, String name) throws Exception {
        var graph = new PlanGraphWidget(0, 0, 640, 600, ignored -> {});
        graph.show(view);
        var dust = new ArrayList<Object>();
        int blocks = 0;
        for (var cell : cells(graph)) {
            if (amount(cell, Items.REDSTONE) > 0) dust.add(cell);
            blocks += amount(cell, Items.REDSTONE_BLOCK);
        }
        require(dust.size() == 2 && blocks == 1, name + " merged independent dust demands or copied the block");
        require(amount(dust.getFirst(), Items.REDSTONE) + amount(dust.getLast(), Items.REDSTONE) == 3,
                name + " changed dust demand quantities");
        require(number(dust.getFirst(), "x") == number(dust.getLast(), "x")
                && Math.abs(number(dust.getFirst(), "y") - number(dust.getLast(), "y")) == 40,
                name + " shared dust is not adjacent in one column");
    }

    private static void verifyGeometry(PlanGraphWidget graph, String name) throws Exception {
        var cells = cells(graph);
        var links = (List<?>) field(graph, "connections");
        for (var link : links) {
            var source = cell(graph, (String) field(link, "source"));
            var target = cell(graph, (String) field(link, "target"));
            require(number(source, "x") < number(target, "x"), name + " points backwards");
            require(((List<?>) field(target, "children")).contains(field(source, "id")), name + " connected unrelated nodes");
            for (var s : (List<?>) field(link, "segments")) {
                int x1 = number(s, "x1"), y1 = number(s, "y1"), x2 = number(s, "x2"), y2 = number(s, "y2");
                require(x1 == x2 || y1 == y2, name + " has a non-orthogonal wire");
                for (var c : cells) {
                    if (c == source || c == target) continue;
                    int x = number(c, "x"), y = number(c, "y");
                    require(Math.max(x1, x2) < x || Math.min(x1, x2) > x + 25
                            || Math.max(y1, y2) < y || Math.min(y1, y2) > y + 25,
                            name + " wire crosses unrelated node " + field(c, "id"));
                }
            }
        }
        for (int i = 0; i < links.size(); i++) for (int j = i + 1; j < links.size(); j++) {
            var a = links.get(i); var b = links.get(j);
            if (sharesEndpoint(a, b)) continue; // A real supply/consumer bus is not a false junction.
            for (var x : (List<?>) field(a, "segments")) for (var y : (List<?>) field(b, "segments")) {
                require(!falseJunction(x, y), name + " joined unrelated wires " + field(a, "source") + "→"
                        + field(a, "target") + " / " + field(b, "source") + "→" + field(b, "target"));
                verifyCrossingGap(a, x, b, y, name);
            }
        }
        for (int i = 0; i < cells.size(); i++) for (int j = i + 1; j < cells.size(); j++)
            require(Math.abs(number(cells.get(i), "x") - number(cells.get(j), "x")) >= 28
                    || Math.abs(number(cells.get(i), "y") - number(cells.get(j), "y")) >= 28, name + " overlaps nodes");
    }

    private static boolean sharesEndpoint(Object a, Object b) throws Exception {
        return field(a, "source").equals(field(b, "source")) || field(a, "target").equals(field(b, "target"))
                || field(a, "source").equals(field(b, "target")) || field(a, "target").equals(field(b, "source"));
    }

    private static boolean falseJunction(Object a, Object b) throws Exception {
        int ax1 = number(a, "x1"), ax2 = number(a, "x2"), ay1 = number(a, "y1"), ay2 = number(a, "y2");
        int bx1 = number(b, "x1"), bx2 = number(b, "x2"), by1 = number(b, "y1"), by2 = number(b, "y2");
        if (ax1 == ax2 && ay1 == ay2 || bx1 == bx2 && by1 == by2) return false;
        if (ax1 == ax2 && bx1 == bx2)
            return ax1 == bx1 && Math.min(Math.max(ay1, ay2), Math.max(by1, by2)) > Math.max(Math.min(ay1, ay2), Math.min(by1, by2));
        if (ay1 == ay2 && by1 == by2)
            return ay1 == by1 && Math.min(Math.max(ax1, ax2), Math.max(bx1, bx2)) > Math.max(Math.min(ax1, ax2), Math.min(bx1, bx2));
        // Orthogonal contact, including a raw segment endpoint, is only safe
        // when the rendered strokes leave an explicit gap (checked below).
        return false;
    }

    private static void verifyCrossingGap(Object a, Object x, Object b, Object y, String name) throws Exception {
        int ax1 = number(x, "x1"), ax2 = number(x, "x2"), ay1 = number(x, "y1"), ay2 = number(x, "y2");
        int bx1 = number(y, "x1"), bx2 = number(y, "x2"), by1 = number(y, "y1"), by2 = number(y, "y2");
        if (ax1 == ax2 && ay1 == ay2 || bx1 == bx2 && by1 == by2) return;
        if (ax1 == ax2 && bx1 == bx2 && ax1 == bx1) {
            int contact = Math.max(Math.min(ay1, ay2), Math.min(by1, by2));
            if (contact == Math.min(Math.max(ay1, ay2), Math.max(by1, by2)))
                require(!strokeContains(a, ax1, contact) || !strokeContains(b, ax1, contact), name + " joined unrelated collinear endpoints");
            return;
        }
        if (ay1 == ay2 && by1 == by2 && ay1 == by1) {
            int contact = Math.max(Math.min(ax1, ax2), Math.min(bx1, bx2));
            if (contact == Math.min(Math.max(ax1, ax2), Math.max(bx1, bx2)))
                require(!strokeContains(a, contact, ay1) || !strokeContains(b, contact, ay1), name + " joined unrelated collinear endpoints");
            return;
        }
        if (ax1 == ax2 && by1 == by2) { verifyCrossingGap(b, y, a, x, name); return; }
        if (ay1 != ay2 || bx1 != bx2 || bx1 < Math.min(ax1, ax2) || bx1 > Math.max(ax1, ax2)
                || ay1 < Math.min(by1, by2) || ay1 > Math.max(by1, by2)) return;
        for (var stroke : (List<?>) field(b, "strokes"))
            require(number(stroke, "x1") != bx1 || number(stroke, "x2") != bx1
                    || ay1 + 1 < Math.min(number(stroke, "y1"), number(stroke, "y2"))
                    || ay1 - 1 > Math.max(number(stroke, "y1"), number(stroke, "y2")),
                    name + " rendered an unrelated crossing as a connected junction");
    }

    private static boolean strokeContains(Object link, int x, int y) throws Exception {
        for (var s : (List<?>) field(link, "strokes"))
            if (x >= Math.min(number(s, "x1"), number(s, "x2")) && x <= Math.max(number(s, "x1"), number(s, "x2"))
                    && y >= Math.min(number(s, "y1"), number(s, "y2")) && y <= Math.max(number(s, "y1"), number(s, "y2"))) return true;
        return false;
    }

    private static List<String> paths(PlanningInput input, String path, String recipe, Item item) {
        var entry = input.find(id(recipe));
        require(entry != null, "Missing routing fixture recipe " + recipe);
        return entry.requirements().stream().filter(r -> r.ingredient().test(stack(item, 1)))
                .map(r -> path + "." + r.slot()).toList();
    }
    private static void pinInputs(PlanningInput input, Map<String, ResourceLocation> pins, String path,
            String recipe, Item item, ResourceLocation producer) {
        for (String child : paths(input, path, recipe, item)) pins.put(child, producer);
    }
    private static ResourceLocation producer(PlanningInput input, Item output, Item material) {
        return input.producing(Ingredient.of(output)).stream().filter(e -> e.requirements().stream()
                .allMatch(r -> r.ingredient().test(stack(material, 1)))).findFirst().orElseThrow().id();
    }
    private static CraftRequest request(String recipe, Map<String, ResourceLocation> pins) {
        return new CraftRequest(id(recipe), 1, false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
    }
    private static int amount(List<ItemStack> stacks, Item item) { return stacks.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    @SuppressWarnings("unchecked") private static int amount(Object cell, Item item) throws Exception { return amount((List<ItemStack>) field(cell, "needs"), item); }
    private static List<?> cells(PlanGraphWidget graph) throws Exception { return (List<?>) field(graph, "cells"); }
    private static Object cell(PlanGraphWidget graph, String id) throws Exception {
        for (var cell : cells(graph)) if (field(cell, "id").equals(id)) return cell;
        throw new AssertionError("Missing routing cell " + id);
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static int number(Object object, String name) throws Exception { return (int) field(object, name); }
    private static ItemStack stack(Item item, int count) { return new ItemStack(item, count); }
    private static ResourceLocation id(String name) { return ResourceLocation.withDefaultNamespace(name); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
