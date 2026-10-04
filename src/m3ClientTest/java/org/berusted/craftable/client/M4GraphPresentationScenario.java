package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.PlanView;
import org.berusted.craftable.recipe.PlanningInput;

/** Display-only fixtures driven by the existing planning entry. No world or
 * network authority is manufactured and no second test runner is registered. */
final class M4GraphPresentationScenario {
    static void verify() throws Exception {
        verifyPlayerWording();
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
        require(cells(graph).size() == 3 && count(cell(graph, "0.0"), "made", Items.STICK) == 4
                && count(cell(graph, "0.0"), "needs", Items.STICK) == 4
                && field(cell(graph, "0.0"), "paths").equals(List.of("0.0", "0.1")),
                "Same-batch reference duplicated production");

        var bucketOps = List.of(op(id("cake"), "0", List.of(stack(Items.MILK_BUCKET, 3)), stack(Items.CAKE, 1),
                List.of(stack(Items.BUCKET, 3))), op(id("cake"), "0", List.of(stack(Items.MILK_BUCKET, 3)),
                stack(Items.CAKE, 1), List.of(stack(Items.BUCKET, 3))));
        graph.show(view(List.of(node("0", stack(Items.CAKE, 2), stack(Items.CAKE, 2), id("cake"))), bucketOps));
        tooltip = tooltip(graph, cell(graph, "0"));
        require(tooltip.contains(amount(6, Items.MILK_BUCKET)) && tooltip.contains(amount(2, Items.CAKE))
                && tooltip.contains(amount(6, Items.BUCKET)), "Aggregate remainders lost quantities");
        verifyMissingLeaves();
        verifyAlternativeEvidenceBudget();
        verifyCandidateTooltips();
        verifyExplanationBranches();
        verifySharedBatches();
        verifyAlignmentBoundaries();
        M4GraphRoutingScenario.verify();
        System.out.println("M4_GRAPH_PRESENTATION PASS batchTotals=3 siblingPaths=5 missingGold=3 candidateInputs=3+2 tooltipRows/noLF/chestOR=8 selectedWood/rawLeaf/siblingExplanation=preserved");
    }

    static void verifyPlayerWording() {
        var language = net.minecraft.client.Minecraft.getInstance().options.languageCode;
        if (language.equals("zh_cn") || language.equals("en_us")) {
            var expected = language.equals("zh_cn")
                    ? List.of("所需材料", "消耗材料", "合成物品", "返还物品", "剩余物品")
                    : List.of("Ingredients", "Materials used", "Crafted items", "Returned items", "Leftover items");
            var keys = List.of("recipe_inputs", "inputs", "outputs", "remainders", "surplus");
            for (int i = 0; i < keys.size(); i++) require(text(keys.get(i)).equals(expected.get(i)), "Player wording not loaded: " + keys.get(i));
        }
        var summary = org.berusted.craftable.client.menu.AmbientInventoryEvents.ruleSummary();
        require(summary.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents,
                "World settings lost translated text");
        var contents = (net.minecraft.network.chat.contents.TranslatableContents) summary.getContents();
        require(contents.getKey().equals("tooltip.craftable.rules"), "World settings unavailable in active fixture");
        var arguments = contents.getArgs();
        require(arguments.length == 4 && arguments[3] instanceof Component, "Ender chest setting still uses a raw boolean");
        var enabled = ((Component) arguments[3]).getString();
        require(enabled.equals(Component.translatable("options.on").getString())
                || enabled.equals(Component.translatable("options.off").getString()), "Ender chest setting lost vanilla On/Off");
        System.out.println("M4_PLAYER_WORDING PASS language=" + language + " headings/localizedRules");
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
        require(cells(graph).size() == 2 && count(cell(graph, "0.0"), "needs", Items.GOLD_INGOT) == 3
                && field(cell(graph, "0.0"), "paths").equals(List.of("0.0", "0.1", "0.2")),
                "Equivalent sibling leaves lost quantities or paths");
        var named = stack(Items.GOLD_INGOT, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct missing component"));
        graph.show(view(List.of(root, missing("0.0", Items.GOLD_INGOT),
                new PlanView.Node("0.1", List.of(named), List.of(), List.of(), true, true, "")), List.of()));
        require(cells(graph).size() == 3, "Missing items with different components were merged");

        // Equal displayed prefixes are not proof of equal complete Ingredient
        // sets. Legacy/manual projections with no full-set identity must keep
        // the truncation guard even after real smoker log ORs can aggregate.
        var truncated = List.of(Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
                Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
                Items.OAK_WOOD, Items.SPRUCE_WOOD, Items.BIRCH_WOOD, Items.JUNGLE_WOOD,
                Items.ACACIA_WOOD, Items.DARK_OAK_WOOD, Items.MANGROVE_WOOD, Items.CHERRY_WOOD)
                .stream().map(item -> stack(item, 1)).toList();
        var prefixOnly = view(List.of(root, new PlanView.Node("0.0", truncated, List.of(), List.of(), true, true, ""),
                new PlanView.Node("0.1", truncated, List.of(), List.of(), true, true, "")), List.of());
        graph.show(prefixOnly);
        require(cells(graph).size() == 3, "Equal truncated prefixes invented complete OR equality");
        var fullOak = new ArrayList<>(truncated); fullOak.add(stack(Items.STRIPPED_OAK_LOG, 1));
        var fullBirch = new ArrayList<>(truncated); fullBirch.add(stack(Items.STRIPPED_BIRCH_LOG, 1));
        var oakIdentity = java.util.Set.copyOf(org.berusted.craftable.planner.CraftPlan.stackKeys(fullOak));
        var birchIdentity = java.util.Set.copyOf(org.berusted.craftable.planner.CraftPlan.stackKeys(fullBirch));
        graph.show(prefixOnly, java.util.Map.of("0.0", oakIdentity, "0.1", birchIdentity));
        require(cells(graph).size() == 3, "Different complete OR identities merged because their first sixteen icons matched");
        graph.show(prefixOnly, java.util.Map.of("0.0", oakIdentity, "0.1", oakIdentity));
        require(cells(graph).size() == 2 && ((List<?>) field(cell(graph, "0.0"), "paths")).size() == 2
                && ((List<?>) field(cell(graph, "0.0"), "needs")).stream().allMatch(s -> ((ItemStack) s).getCount() == 2),
                "Equal complete OR evidence did not retain two independent demands");
    }

    private static void verifyAlternativeEvidenceBudget() {
        // Build an isolated ordinary catalog through the existing normalizer;
        // do not replace the world's recipes or manufacture a custom Ingredient.
        // Distinct components make all 4096 resolved options genuinely distinct,
        // while identical first-sixteen prefixes cannot prove full-set equality.
        var recipes = new ArrayList<RecipeHolder<?>>();
        for (int recipe = 0; recipe < 5; recipe++) {
            var options = new ItemStack[4096];
            for (int option = 0; option < options.length; option++) {
                options[option] = stack(Items.STONE, 1);
                options[option].set(DataComponents.CUSTOM_NAME, Component.literal(option < 16
                        ? "Alternative prefix " + option : "Alternative " + recipe + "/" + option));
            }
            recipes.add(new RecipeHolder<>(id("m4_alternative_budget_" + recipe),
                    new ShapelessRecipe("", CraftingBookCategory.MISC, stack(Items.PAPER, 1),
                            NonNullList.of(Ingredient.EMPTY, Ingredient.of(options)))));
        }
        var catalog = new PlanningInput.Catalog();
        var mc = net.minecraft.client.Minecraft.getInstance();
        catalog.observe(new Object(), new Object(), recipes, mc.level.registryAccess());
        catalog.advance(Long.MAX_VALUE / 2);
        require(catalog.state() == PlanningInput.Catalog.State.READY, "Alternative budget fixture catalog did not finish");
        var input = catalog.bind(true, false, java.util.Set.of());

        // The same frozen recipe/slot is reached twice before the budget is
        // full and once after it. Such paths reuse one Ingredient proof, while
        // the fifth independent Ingredient must conservatively get no proof.
        int[] route = {0, 0, 1, 2, 3, 4, 0};
        var nodes = new ArrayList<PlanView.Node>();
        nodes.add(node("0", stack(Items.BOOK, 1), ItemStack.EMPTY, null));
        for (int path = 0; path < route.length; path++) {
            var entry = input.find(recipes.get(route[path]).id());
            require(entry != null && input.unavailable(entry) == null, "Budget fixture is not ordinary frozen knowledge");
            var options = entry.requirements().getFirst().ingredient().getItems();
            require(options.length == 4096, "Fixture's component alternatives collapsed during normalization");
            var parent = "0." + path;
            nodes.add(node(parent, stack(Items.PAPER, 1), stack(Items.PAPER, 1), entry.id()));
            nodes.add(new PlanView.Node(parent + ".0", java.util.Arrays.stream(options).limit(16)
                    .map(s -> s.copyWithCount(1)).toList(), List.of(), List.of(), true, true, ""));
        }
        var view = view(nodes, List.of());
        var reviewed = view.reviewIdentity();
        var groups = view.alternativeGroups(input);
        require(groups.size() == 6 && !groups.containsKey("0.5.0"),
                "Total alternative evidence cap failed to leave the over-budget leaf without proof");
        for (int path : new int[]{0, 1, 2, 3, 4, 6}) {
            var options = input.find(recipes.get(route[path]).id()).requirements().getFirst().ingredient().getItems();
            var complete = java.util.Set.copyOf(CraftPlan.stackKeys(java.util.Arrays.stream(options)
                    .map(s -> s.copyWithCount(1)).toList()));
            require(complete.size() == 4096 && complete.equals(groups.get("0." + path + ".0")),
                    "Budget handling truncated or changed a retained full Ingredient identity");
        }
        require(groups.get("0.0.0") == groups.get("0.1.0") && groups.get("0.0.0") == groups.get("0.6.0"),
                "Repeated paths charged new evidence or lost the cached proof after budget exhaustion");
        require(!groups.get("0.0.0").equals(groups.get("0.2.0")),
                "Identical sixteen-option prefixes erased different full component sets");
        require(reviewed.equals(view.reviewIdentity()) && view.nodes().size() == 15,
                "Optional evidence budget changed displayed demands or review authority");
        catalog.clear();
        System.out.println("M4_ALTERNATIVE_EVIDENCE_BUDGET PASS uniqueOptions=16384 fullKeys=4096 overBudget=conservative repeatedPaths=reused");
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
        require(cells(graph).size() == 3 && count(cell(graph, "0.0"), "needs", Items.OAK_PLANKS) == 5
                && count(cell(graph, "0.0.0"), "needs", Items.OAK_LOG) == 2,
                "Merged plank demands lost quantity or duplicated logs");
        var planks = cell(graph, "0.0");
        graph.onClick((int) field(planks, "x") + (int) field(graph, "panX") + 8,
                (int) field(planks, "y") + (int) field(graph, "panY") + 8);
        require(selected.equals(boat.nodes().stream().filter(n -> n.needs().size() == 1
                        && n.needs().getFirst().is(Items.OAK_PLANKS)).map(PlanView.Node::path).toList())
                && selected.size() == 5 && boat.reviewIdentity().equals(identity) && boat.operations().isEmpty()
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
        require(cells(graph).size() == 3 && count(cell(graph, "0.0"), "needs", Items.BIRCH_PLANKS) == 2
                && ((List<?>) field(cell(graph, "0.0"), "paths")).size() == 2
                && count(cell(graph, "0.0.0"), "needs", Items.BIRCH_LOG) == 1,
                "Equivalent pinned birch demands lost selection paths or rounded the log twice");
        // Leave the live detail untouched: fixtures consume its immutable
        // normalized catalog, not a second index or a manufactured grant.
    }

    private static void verifySharedBatches() throws Exception {
        var inputField = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.class.getDeclaredField("input");
        inputField.setAccessible(true);
        var input = (org.berusted.craftable.recipe.PlanningInput) inputField.get(null);
        for (String target : List.of("diamond_pickaxe", "repeater")) {
            var item = target.equals("repeater") ? Items.REDSTONE : Items.DIAMOND;
            var block = target.equals("repeater") ? Items.REDSTONE_BLOCK : Items.DIAMOND_BLOCK;
            var split = input.producing(Ingredient.of(item)).stream()
                    .filter(e -> e.requirements().size() == 1 && e.requirements().getFirst().ingredient().test(stack(block, 1)))
                    .findFirst().orElseThrow();
            var pins = new java.util.TreeMap<String, ResourceLocation>();
            for (var r : input.find(id(target)).requirements()) {
                String path = "0." + r.slot();
                if (r.ingredient().test(stack(item, 1))) pins.put(path, split.id());
                else if (r.ingredient().test(stack(Items.REDSTONE_TORCH, 1))) {
                    var torch = input.find(id("redstone_torch"));
                    pins.put(path, torch.id());
                    torch.requirements().stream().filter(t -> t.ingredient().test(stack(item, 1)))
                            .forEach(t -> pins.put(path + "." + t.slot(), split.id()));
                }
            }
            var req = new org.berusted.craftable.planner.CraftRequest(id(target), 1, false, false,
                    org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
            var missing = PlanView.from(input, req, org.berusted.craftable.planner.SearchResult.blocked(
                    CraftingResultCode.MISSING_INGREDIENTS), List.of(), List.of(stack(Items.STICK, 2), stack(Items.STONE, 3)));
            var selected = new ArrayList<String>();
            var graph = new PlanGraphWidget(0, 0, 480, 400, selected::addAll);
            var identity = missing.reviewIdentity();
            graph.show(missing);
            verifySharedGeometry(graph, missing, item, block, selected);
            capture(graph, target + "-missing");
            require(missing.operations().isEmpty() && missing.consumed().isEmpty() && missing.reviewIdentity().equals(identity),
                    "Shared geometry mutated explanation authority");
            // Run the same frozen solver, then project its real producing batch.
            var sources = List.of(new org.berusted.craftable.planner.ResourceLedger.Source("fixture", 0, stack(block, 1)),
                    new org.berusted.craftable.planner.ResourceLedger.Source("fixture", 1, stack(Items.STICK, 2)),
                    new org.berusted.craftable.planner.ResourceLedger.Source("fixture", 2, stack(Items.STONE, 3)));
            var result = new org.berusted.craftable.planner.CraftSearch(input, req, sources,
                    new org.berusted.craftable.planner.SearchBudget(1_000_000_000L), ignored -> null).run();
            require(result.code() == CraftingResultCode.CREATED, "Shared real fixture cannot craft: " + result);
            var real = PlanView.from(input, req, result, List.of(), sources.stream().map(s -> s.stack()).toList());
            var realIdentity = real.reviewIdentity();
            graph.show(real); selected.clear();
            verifySharedGeometry(graph, real, item, block, selected);
            capture(graph, target + "-real");
            require(real.consumed().stream().filter(s -> s.is(block)).mapToInt(ItemStack::getCount).sum() == 1
                    && real.surplus().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum() == 6
                    && real.reviewIdentity().equals(realIdentity),
                    "Shared real geometry hid the block cost or surplus");
            if (item == Items.REDSTONE) {
                @SuppressWarnings("unchecked") var collapsed = (java.util.Set<String>) field(graph, "collapsed");
                String torch = real.nodes().stream().filter(n -> n.needs().stream().anyMatch(s -> s.is(Items.REDSTONE_TORCH)))
                        .findFirst().orElseThrow().path();
                int pan = (int) field(graph, "panY");
                collapsed.add(torch); graph.show(real);
                int dust = 0;
                for (var c : cells(graph)) if (count(c, "needs", item) > 0) dust++;
                require(dust == 1 && (int) field(graph, "panY") == pan, "Collapse lost visible dust or reset the view");
                collapsed.clear(); graph.show(real); selected.clear();
                verifySharedGeometry(graph, real, item, block, selected);
            }
        }
        // Different explicitly chosen output variants must split the supply.
        var pins = new java.util.TreeMap<String, ResourceLocation>();
        var slots = input.find(id("stick")).requirements();
        pins.put("0." + slots.getFirst().slot(), id("oak_planks"));
        pins.put("0." + slots.getLast().slot(), id("birch_planks"));
        var req = new org.berusted.craftable.planner.CraftRequest(id("stick"), 1, false, false,
                org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
        var view = PlanView.from(input, req, org.berusted.craftable.planner.SearchResult.blocked(
                CraftingResultCode.MISSING_INGREDIENTS), List.of(), List.of());
        require(view.nodes().stream().filter(n -> pins.containsKey(n.path())).allMatch(n -> n.reference().isEmpty()),
                "Different pinned recipes shared a hypothetical batch");
        var graph = new PlanGraphWidget(0, 0, 480, 400, ignored -> {});
        graph.show(view);
        require(cells(graph).stream().filter(c -> {
            try { return count(c, "needs", Items.OAK_PLANKS) + count(c, "needs", Items.BIRCH_PLANKS) > 0; }
            catch (Exception e) { throw new RuntimeException(e); }
        }).count() == 2, "Different pinned routes merged in display");
        System.out.println("M4_SHARED_BATCHES PASS diamond/repeater missing+real block=1 surplus=6 siblingMerge/crossParent/softAlignment/collapse pinSplit");
    }

    private static void verifySharedGeometry(PlanGraphWidget graph, PlanView view, net.minecraft.world.item.Item item,
            net.minecraft.world.item.Item block, List<String> selected) throws Exception {
        var demands = view.nodes().stream().filter(n -> n.needs().size() == 1 && n.needs().getFirst().is(item)).toList();
        require(demands.size() == 3, "Three independent demands disappeared");
        var displayed = new ArrayList<Object>();
        for (var c : cells(graph)) if (count(c, "needs", item) > 0) displayed.add(c);
        require(displayed.size() == (item == Items.DIAMOND ? 1 : 2), "Wrong same-parent demand aggregation");
        var paths = new java.util.HashSet<String>();
        int quantity = 0;
        for (var c : displayed) {
            var represented = (List<?>) field(c, "paths");
            var owners = new java.util.HashSet<String>();
            for (var p : represented) {
                String path = (String) p;
                require(paths.add(path), "Demand path represented twice");
                owners.add(path.substring(0, path.lastIndexOf('.')));
            }
            // Redstone demands become siblings only after their two torch
            // parents merge. The direct root demand must never join them.
            require(owners.size() == 1 || item == Items.REDSTONE && owners.stream().noneMatch("0"::equals),
                    "Merged unrelated parents");
            quantity += count(c, "needs", item);
            int before = selected.size();
            graph.onClick((int) field(c, "x") + (int) field(graph, "panX") + 8,
                    (int) field(c, "y") + (int) field(graph, "panY") + 8);
            require(selected.subList(before, selected.size()).equals(represented), "Merged click lost original constraints");
        }
        require(quantity == 3 && paths.equals(new java.util.HashSet<>(demands.stream().map(PlanView.Node::path).toList())),
                "Display aggregation lost true demands");
        if (item == Items.REDSTONE) {
            var two = count(displayed.get(0), "needs", item) == 2 ? displayed.get(0) : displayed.get(1);
            var one = two == displayed.get(0) ? displayed.get(1) : displayed.get(0);
            require(field(two, "x").equals(field(one, "x")) && (int) field(one, "y") - (int) field(two, "y") == 40,
                    "Shared dust was not adjacent/aligned: " + field(two, "x") + "," + field(two, "y")
                            + " / " + field(one, "x") + "," + field(one, "y"));
            for (var c : cells(graph)) if (count(c, "needs", Items.STICK) > 0)
                require(field(c, "x").equals(field(two, "x")) && (int) field(c, "y") < (int) field(two, "y"),
                        "Stick did not move above the adjacent dust demands");
            int torches = 0;
            for (var c : cells(graph)) if (count(c, "needs", Items.REDSTONE_TORCH) > 0) {
                torches++;
                require(count(c, "needs", Items.REDSTONE_TORCH) == 2 && ((List<?>) field(c, "paths")).size() == 2,
                        "Two torches were not merged with all selection paths");
            }
            require(torches == 1, "Duplicate torch nodes");
        }
        int total = 0;
        for (var cell : cells(graph)) {
            total += count(cell, "needs", block);
            for (var child : (List<?>) field(cell, "children"))
                require((int) field(cell(graph, (String) child), "x") < (int) field(cell, "x"), "Shared edge points backwards");
        }
        require(total == 1, "Block source was drawn more than once");
        var all = cells(graph);
        for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++) {
            var a = all.get(i); var b = all.get(j);
            if (field(a, "x").equals(field(b, "x")))
                require(Math.abs((int) field(a, "y") - (int) field(b, "y")) >= 26, "Shared source overlaps another material");
        }
        for (var link : (List<?>) field(graph, "connections")) for (var segment : (List<?>) field(link, "segments")) {
            int x1 = (int) field(segment, "x1"), x2 = (int) field(segment, "x2");
            int y1 = (int) field(segment, "y1"), y2 = (int) field(segment, "y2");
            require(x1 == x2 || y1 == y2, "Non-orthogonal connection");
            for (var c : all) {
                if (field(c, "id").equals(field(link, "source")) || field(c, "id").equals(field(link, "target"))) continue;
                int x = (int) field(c, "x"), y = (int) field(c, "y");
                require(Math.max(x1, x2) < x || Math.min(x1, x2) > x + 25
                        || Math.max(y1, y2) < y || Math.min(y1, y2) > y + 25,
                        "Connection crosses unrelated ingredient " + field(c, "id"));
            }
        }
    }

    private static void verifyAlignmentBoundaries() throws Exception {
        // A tiny immutable projection reuses the real widget. No extra index,
        // solver, graph renderer or client entry is introduced by these cases.
        var nodes = new ArrayList<>(List.of(
                node("0", stack(Items.REPEATER, 1), ItemStack.EMPTY, id("repeater")),
                node("0.0", stack(Items.REDSTONE_TORCH, 2), ItemStack.EMPTY, id("redstone_torch")),
                node("0.0.0", stack(Items.REDSTONE, 2), stack(Items.REDSTONE, 9), id("redstone_from_block")),
                node("0.0.0.0", stack(Items.REDSTONE_BLOCK, 1), ItemStack.EMPTY, null),
                node("0.0.1", stack(Items.STICK, 2), ItemStack.EMPTY, null),
                new PlanView.Node("0.1", List.of(stack(Items.REDSTONE, 1)), List.of(), List.of(), false, false, "0.0.0"),
                node("0.2", stack(Items.STONE, 3), ItemStack.EMPTY, null)));
        var graph = new PlanGraphWidget(0, 0, 480, 400, ignored -> {});
        graph.show(view(nodes, List.of()));
        require(field(cell(graph, "0.0.0"), "x").equals(field(cell(graph, "0.1"), "x")), "Simple fixture did not align");
        var distinct = new ArrayList<>(nodes);
        distinct.set(5, new PlanView.Node("0.1", List.of(stack(Items.REDSTONE, 1)), List.of(),
                List.of(id("other_redstone")), false, false, "0.0.0"));
        graph.show(view(distinct, List.of()));
        require(!field(cell(graph, "0.0.0"), "x").equals(field(cell(graph, "0.1"), "x")), "Different recipes were soft-aligned");
        var named = stack(Items.REDSTONE, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct dust"));
        distinct.set(5, new PlanView.Node("0.1", List.of(named), List.of(), List.of(), false, false, "0.0.0"));
        graph.show(view(distinct, List.of()));
        require(!field(cell(graph, "0.0.0"), "x").equals(field(cell(graph, "0.1"), "x")), "Different components were soft-aligned");
        distinct.set(5, node("0.1", stack(Items.REDSTONE, 1), stack(Items.REDSTONE, 9), id("redstone_from_block")));
        distinct.add(node("0.1.0", stack(Items.REDSTONE_BLOCK, 1), ItemStack.EMPTY, null));
        graph.show(view(distinct, List.of()));
        require(!field(cell(graph, "0.0.0"), "x").equals(field(cell(graph, "0.1"), "x")), "Equal icons invented shared supply");

        var multiple = new ArrayList<>(nodes);
        multiple.add(node("0.3", stack(Items.DIAMOND_PICKAXE, 1), ItemStack.EMPTY, id("diamond_pickaxe")));
        multiple.add(node("0.3.0", stack(Items.DIAMOND, 2), stack(Items.DIAMOND, 9), id("diamond_from_block")));
        multiple.add(node("0.3.0.0", stack(Items.DIAMOND_BLOCK, 1), ItemStack.EMPTY, null));
        multiple.add(new PlanView.Node("0.4", List.of(stack(Items.DIAMOND, 1)), List.of(), List.of(), false, false, "0.3.0"));
        graph.show(view(multiple, List.of()));
        verifyRoutingSafety(graph);
        require(count(cell(graph, "0.0.0"), "needs", Items.REDSTONE) == 2
                && count(cell(graph, "0.1"), "needs", Items.REDSTONE) == 1
                && count(cell(graph, "0.3.0"), "needs", Items.DIAMOND) == 2
                && count(cell(graph, "0.4"), "needs", Items.DIAMOND) == 1,
                "Multi-group layout changed independent quantities");

        // An unrelated source shares the same drawing column. The unified
        // layout must keep it visible, not preserve a bad baseline's crossing.
        nodes.add(node("0.2.0", stack(Items.COBBLESTONE, 3), ItemStack.EMPTY, id("fixture_stone")));
        nodes.add(node("0.2.0.0", stack(Items.COAL, 1), ItemStack.EMPTY, null));
        var view = view(nodes, List.of());
        var identity = view.reviewIdentity();
        graph.show(view);
        verifyRoutingSafety(graph);
        require(identity.equals(view.reviewIdentity()), "Layout changed authority");
        System.out.println("M4_GRAPH_LAYOUT_BOUNDARIES PASS recipe/component/source isolation multiGroup/obstacle paths/authority=preserved");
    }

    @SuppressWarnings("unchecked")
    private static void verifyRoutingSafety(PlanGraphWidget graph) throws Exception {
        var nodes = new ArrayList<PlanGraphRouting.Node>();
        for (var c : cells(graph)) nodes.add(new PlanGraphRouting.Node((String) field(c, "id"),
                (int) field(c, "x"), (int) field(c, "y")));
        var score = PlanGraphRouting.score(nodes, (List<PlanGraphRouting.Link>) field(graph, "connections"));
        require(score.hits() == 0 && score.overlaps() == 0 && score.contacts() == 0,
                "Routing retained an ambiguous/obstructed baseline: " + score);
    }

    private static PlanView.Node missing(String path, net.minecraft.world.item.Item item) {
        return new PlanView.Node(path, List.of(stack(item, 1)), List.of(), List.of(), true, true, "");
    }

    static void capture(PlanGraphWidget graph, String name) throws Exception {
        // Render the existing widget offscreen: no alternate renderer, live
        // overlay mutation, world stock, network request or extra test entry.
        var mc = net.minecraft.client.Minecraft.getInstance();
        var window = mc.getWindow();
        var rendered = new PlanGraphWidget(0, 0, window.getGuiScaledWidth(), window.getGuiScaledHeight(), ignored -> {});
        @SuppressWarnings("unchecked") var groups = (java.util.Map<String, Object>) field(graph, "alternativeGroups");
        rendered.show((PlanView) field(graph, "view"), groups);
        var projection = new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
        var sorting = com.mojang.blaze3d.systems.RenderSystem.getVertexSorting();
        var target = new com.mojang.blaze3d.pipeline.TextureTarget(window.getWidth(), window.getHeight(), true,
                net.minecraft.client.Minecraft.ON_OSX);
        try {
            target.bindWrite(true);
            com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(new org.joml.Matrix4f()
                    .setOrtho(0, window.getGuiScaledWidth(), window.getGuiScaledHeight(), 0, 1000, 21000),
                    com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
            var graphics = new net.minecraft.client.gui.GuiGraphics(mc, mc.renderBuffers().bufferSource());
            graphics.pose().translate(0, 0, -11000);
            rendered.render(graphics, -1, -1, 0);
            graphics.flush();
            var directory = mc.gameDirectory.toPath().resolve("screenshots");
            java.nio.file.Files.createDirectories(directory);
            try (var pixels = net.minecraft.client.Screenshot.takeScreenshot(target)) {
                pixels.writeToFile(directory.resolve("m4-sibling-" + name + ".png"));
            }
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(projection, sorting);
            target.destroyBuffers(); mc.getMainRenderTarget().bindWrite(true);
        }
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
