package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.planner.SearchResult;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Read-only M4.0/1 contracts, before replacing the live single-step entry point. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4PlanningGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void logToPickaxeKeepsEveryBatchRemainder(GameTestHelper helper) {
        var player = player(helper);
        try {
            ItemStack log = new ItemStack(Items.OAK_LOG);
            var result = search(player, "diamond_pickaxe", 1, true, true, log, new ItemStack(Items.DIAMOND, 3));
            var plan = result.plan().orElseThrow(() -> new AssertionError(result));
            helper.assertFalse(plan.partial(), "Full pickaxe was only partial");
            helper.assertValueEqual(plan.steps().size(), 3, "recipe steps");
            helper.assertValueEqual(count(plan.primary(), Items.DIAMOND_PICKAXE), 1, "pickaxes");
            helper.assertValueEqual(count(plan.surplus(), Items.STICK), 2, "surplus sticks");
            helper.assertValueEqual(count(plan.surplus(), Items.OAK_PLANKS), 2, "surplus planks");
            helper.assertValueEqual(log.getCount(), 1, "input argument mutated");
            helper.assertTrue(player.getInventory().isEmpty(), "Preview wrote inventory");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void partialPreparationKeepsFourSticksAndDoesNotSpendDiamonds(GameTestHelper helper) {
        var player = player(helper);
        try {
            var result = search(player, "diamond_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 2));
            var plan = result.plan().orElseThrow(() -> new AssertionError(result));
            helper.assertTrue(plan.partial(), "Expected preparation");
            helper.assertValueEqual(count(plan.primary(), Items.STICK), 4, "primary sticks");
            helper.assertValueEqual(count(plan.surplus(), Items.OAK_PLANKS), 2, "surplus planks");
            helper.assertFalse(plan.extractions().stream().anyMatch(e -> e.expected().is(Items.DIAMOND)), "Partial spent diamonds");
            helper.assertValueEqual(plan.missing().stream().mapToInt(m -> m.count()).sum(), 1, "missing diamonds");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void existingFrontierCannotBePreparedAgain(GameTestHelper helper) {
        var player = player(helper);
        try {
            var result = search(player, "diamond_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.STICK, 4));
            helper.assertTrue(result.plan().isEmpty(), "Prepared redundant sticks");
            helper.assertValueEqual(result.code(), CraftingResultCode.MISSING_INGREDIENTS, "blocked reason");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void partialBatchesDoNotReserveOneStickTwice(GameTestHelper helper) {
        var player = player(helper);
        try {
            var result = search(player, "diamond_pickaxe", 4, true, true, new ItemStack(Items.OAK_LOG, 2));
            var plan = result.plan().orElseThrow(() -> new AssertionError(result));
            helper.assertValueEqual(count(plan.primary(), Items.STICK), 8, "sticks for four missing pickaxes");
            helper.assertValueEqual(plan.missing().stream().mapToInt(m -> m.count()).sum(), 12, "missing diamonds");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void seededConversionsWorkButEmptyCyclesDoNot(GameTestHelper helper) {
        var player = player(helper);
        try {
            var result = search(player, "iron_pickaxe", 1, true, true,
                    new ItemStack(Items.IRON_NUGGET, 27), new ItemStack(Items.STICK, 2));
            helper.assertTrue(result.plan().isPresent() && !result.plan().get().partial(), "Seeded nuggets did not work: " + result);
            var empty = search(player, "iron_ingot_from_nuggets", 1, false, true);
            helper.assertTrue(empty.plan().isEmpty(), "Unseeded cycle created ingots");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void reversibleMetalsCannotManufactureThemselvesOrExhaustToolSearch(GameTestHelper helper) {
        var player = player(helper);
        try {
            Item[] ingots = {Items.IRON_INGOT, Items.GOLD_INGOT};
            String[] metals = {"iron", "gold"};
            for (int i = 0; i < metals.length; i++) {
                var same = search(player, metals[i] + "_ingot_from_nuggets", 1, false, true, new ItemStack(ingots[i]));
                helper.assertTrue(same.plan().isEmpty(), "Ingot round trip became a craft: " + metals[i]);
                for (boolean partial : new boolean[]{false, true}) {
                    var tool = search(player, (i == 0 ? "iron" : "golden") + "_pickaxe", 1, partial, true,
                            new ItemStack(ingots[i]), new ItemStack(Items.STICK, 2));
                    helper.assertValueEqual(tool.code(), CraftingResultCode.MISSING_INGREDIENTS,
                            "One ingot must be a shortage, not a cycle/search limit: " + tool);
                    helper.assertTrue(tool.completeSearch(), "Shortage was not proven");
                    helper.assertValueEqual(search(player, (i == 0 ? "iron" : "golden") + "_pickaxe", 1, partial, true,
                            new ItemStack(ingots[i])).code(), CraftingResultCode.MISSING_INGREDIENTS, "one ingot alone");
                }
                var prepare = search(player, (i == 0 ? "iron" : "golden") + "_pickaxe", 1, true, true,
                        new ItemStack(ingots[i]), new ItemStack(Items.OAK_LOG));
                var plan = prepare.plan().orElseThrow(() -> new AssertionError(prepare));
                helper.assertValueEqual(count(plan.primary(), Items.STICK), 4, "prepare only missing sticks");
                Item ingot = ingots[i];
                helper.assertFalse(plan.extractions().stream().anyMatch(e -> e.expected().is(ingot)), "partial reboxed existing ingot");
            }
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void changedConversionYieldIsNotMistakenForLosslessInverse(GameTestHelper helper) {
        var player = player(helper);
        var manager = helper.getLevel().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            // A data pack may intentionally change the exchange rate. Only an
            // EXACT inverse may be removed, not every ingot/nugget relationship.
            var id = ResourceLocation.withDefaultNamespace("iron_nugget");
            var modified = new ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(original);
            modified.removeIf(holder -> holder.id().equals(id));
            modified.add(new net.minecraft.world.item.crafting.RecipeHolder<>(id,
                    new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                            new ItemStack(Items.IRON_NUGGET, 10), net.minecraft.core.NonNullList.of(
                                    net.minecraft.world.item.crafting.Ingredient.EMPTY, net.minecraft.world.item.crafting.Ingredient.of(Items.IRON_INGOT)))));
            manager.replaceRecipes(modified);
            var result = search(player, "iron_ingot_from_nuggets", 1, false, true, new ItemStack(Items.IRON_INGOT));
            var plan = result.plan().orElseThrow(() -> new AssertionError(result));
            helper.assertValueEqual(count(plan.surplus(), Items.IRON_NUGGET), 1, "changed yield's genuine surplus");
            helper.assertTrue(plan.hasMaterialChange(), "gaining conversion marked no-op");
        } finally { manager.replaceRecipes(original); remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void usefulMetalConversionsStillCombineExistingAndConvertedStock(GameTestHelper helper) {
        var player = player(helper);
        try {
            for (var stock : List.of(
                    List.of(new ItemStack(Items.IRON_NUGGET, 27), new ItemStack(Items.STICK, 2)),
                    List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_NUGGET, 18), new ItemStack(Items.STICK, 2)),
                    List.of(new ItemStack(Items.IRON_BLOCK), new ItemStack(Items.STICK, 2)))) {
                var result = search(player, "iron_pickaxe", 1, false, true, stock.toArray(ItemStack[]::new));
                helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "Useful conversion lost: " + result);
            }
            helper.assertValueEqual(search(player, "iron_nugget", 1, false, true,
                    new ItemStack(Items.IRON_INGOT)).code(), CraftingResultCode.CREATED, "ingot to nuggets");
            helper.assertValueEqual(search(player, "iron_ingot_from_nuggets", 1, false, true,
                    new ItemStack(Items.IRON_NUGGET, 9)).code(), CraftingResultCode.CREATED, "nuggets to ingot");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void metalDeficitsCountAllRootDemandsWithoutUnseededConversion(GameTestHelper helper) {
        var player = player(helper);
        try {
            Item[] metals = {Items.IRON_INGOT, Items.GOLD_INGOT};
            String[] targets = {"iron_pickaxe", "golden_pickaxe"};
            for (int metal = 0; metal < metals.length; metal++) {
                for (int available = 1; available < 3; available++) {
                    var result = search(player, targets[metal], 1, true, true,
                            new ItemStack(metals[metal], available), new ItemStack(Items.STICK, 2));
                    assertDeficit(helper, result, metals[metal], 3 - available);
                }
                assertDeficit(helper, search(player, targets[metal], 1, true, true,
                        new ItemStack(Items.STICK, 2)), metals[metal], 3);
            }
            assertDeficit(helper, search(player, "iron_pickaxe", 2, true, true,
                    new ItemStack(Items.IRON_INGOT), new ItemStack(Items.STICK, 4)), Items.IRON_INGOT, 5);
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void seededIncompleteProducerReportsItsRemainingInputs(GameTestHelper helper) {
        var player = player(helper);
        try {
            assertDeficit(helper, search(player, "iron_ingot_from_nuggets", 1, true, true,
                    new ItemStack(Items.IRON_NUGGET, 4)), Items.IRON_NUGGET, 5);
            var planks = search(player, "stick", 1, true, true, new ItemStack(Items.OAK_PLANKS));
            helper.assertTrue(planks.missing().size() == 1 && planks.missing().getFirst().count() == 1
                    && planks.missing().getFirst().alternatives().stream().allMatch(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)),
                    "missing one plank, with valid tag alternatives: " + planks.missing());
            var mixed = search(player, "iron_pickaxe", 1, true, true,
                    new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_NUGGET, 4), new ItemStack(Items.STICK, 2));
            helper.assertValueEqual(mixed.missing().size(), 2, "seeded route plus remaining ingot demand: " + mixed.missing());
            helper.assertTrue(mixed.missing().stream().anyMatch(m -> m.count() == 5
                    && m.alternatives().stream().allMatch(s -> s.is(Items.IRON_NUGGET))), "missing five nuggets: " + mixed.missing());
            helper.assertTrue(mixed.missing().stream().anyMatch(m -> m.count() == 1
                    && m.alternatives().stream().allMatch(s -> s.is(Items.IRON_INGOT))), "missing one other ingot: " + mixed.missing());
            helper.assertTrue(mixed.plan().isEmpty(), "diagnosis consumed incomplete inputs");
        } finally { remove(player); }
        helper.succeed();
    }

    private static void assertDeficit(GameTestHelper helper, SearchResult result, Item item, int count) {
        helper.assertValueEqual(result.code(), CraftingResultCode.MISSING_INGREDIENTS, "deficit result: " + result);
        helper.assertTrue(result.completeSearch() && result.plan().isEmpty(), "incomplete/no-op diagnosis: " + result);
        helper.assertValueEqual(result.missing().size(), 1, "one deficit kind: " + result.missing());
        var missing = result.missing().getFirst();
        helper.assertValueEqual(missing.count(), count, "whole demand quantity: " + result.missing());
        helper.assertTrue(!missing.alternatives().isEmpty() && missing.alternatives().stream().allMatch(s -> s.is(item)),
                "wrong missing item: " + result.missing());
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void rootWorkstationAndProtectedComponentsAreHardBoundaries(GameTestHelper helper) {
        var player = player(helper);
        try {
            helper.assertValueEqual(search(player, "diamond_pickaxe", 1, true, false,
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3)).code(),
                    CraftingResultCode.MISSING_WORKSTATION, "root station");
            ItemStack named = new ItemStack(Items.OAK_PLANKS, 2);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
            var result = search(player, "stick", 1, true, true, named);
            helper.assertTrue(result.plan().isEmpty(), "Consumed protected planks");
            helper.assertValueEqual(result.code(), CraftingResultCode.PROTECTED_INGREDIENTS, "protection diagnostic");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void vanillaDropConstructionCanBeCapturedBeforeWorldInsertion(GameTestHelper helper) {
        var player = player(helper);
        ItemEntity entity = null;
        try {
            var captured = new ArrayList<ItemEntity>();
            var previous = player.captureDrops(captured);
            try { entity = player.drop(new ItemStack(Items.STICK, 2), false, false); }
            finally { player.captureDrops(previous); }
            helper.assertTrue(entity != null && captured.size() == 1, "No captured vanilla drop");
            helper.assertTrue(player.serverLevel().getEntity(entity.getUUID()) == null, "Drop was prematurely published");
            helper.assertTrue(entity.hasPickUpDelay(), "Vanilla pickup delay lost");
            ItemEntity target = entity;
            Consumer<EntityJoinLevelEvent> cancel = event -> { if (event.getEntity() == target) event.setCanceled(true); };
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancel);
            try {
                helper.assertFalse(player.serverLevel().addFreshEntity(entity), "Canceled entity reported success");
                helper.assertTrue(player.serverLevel().getEntity(entity.getUUID()) == null, "Canceled entity entered world");
            } finally { NeoForge.EVENT_BUS.unregister(cancel); }
        } finally {
            if (entity != null) entity.discard();
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 200)
    public static void warmPlanningBenchmarkIsRecordedWithoutHidingTruncation(GameTestHelper helper) {
        var player = player(helper);
        try {
            for (int i = 0; i < 5; i++) search(player, "diamond_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
            long[] elapsed = new long[40];
            int limited = 0;
            for (int i = 0; i < elapsed.length; i++) {
                long start = System.nanoTime();
                var result = search(player, "diamond_pickaxe", 1, true, true,
                        new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
                elapsed[i] = System.nanoTime() - start;
                if (result.code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED) limited++;
            }
            java.util.Arrays.sort(elapsed);
            Craftable.LOGGER.warn("M4_BENCH warm_pickaxe40 p50={}ms p95={}ms max={}ms limited={}",
                    elapsed[19] / 1e6, elapsed[37] / 1e6, elapsed[39] / 1e6, limited);
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void mixedProducersShareOneLedgerAndCannotBypassSelectedItem(GameTestHelper helper) {
        var player = player(helper);
        try {
            var sources = List.of(new ResourceLedger.Source("fixture", 0, new ItemStack(Items.BIRCH_LOG)),
                    new ResourceLedger.Source("fixture", 1, new ItemStack(Items.OAK_LOG, 2)));
            var catalog = new CraftingRecipes(player, true);
            var request = new CraftRequest(ResourceLocation.withDefaultNamespace("stick"), 6, false, false,
                    CraftRequest.PartialPolicy.EXPLICIT_SAFE, Map.of());
            var mixed = new CraftSearch(catalog, request, sources, new SearchBudget(1_000_000_000L), p -> null).run();
            var plan = mixed.plan().orElseThrow();
            helper.assertValueEqual(count(plan.primary(), Items.STICK), 24, "six stick batches");
            helper.assertTrue(plan.steps().stream().anyMatch(s -> s.recipe().id().getPath().equals("oak_planks"))
                    && plan.steps().stream().anyMatch(s -> s.recipe().id().getPath().equals("birch_planks")), "No mixed route");
            var pinned = new CraftRequest(request.recipe(), 6, false, false, request.policy(),
                    Map.of("0.0", ResourceLocation.withDefaultNamespace("birch_planks")));
            var constrained = new CraftSearch(catalog, pinned, sources, new SearchBudget(1_000_000_000L), p -> null).run();
            helper.assertTrue(constrained.plan().isEmpty(), "Oak surplus bypassed selected birch input");
        } finally { remove(player); }
        helper.succeed();
    }

    static SearchResult search(ServerPlayer player, String id, int batches, boolean partial, boolean workbench, ItemStack... stacks) {
        var sources = new ArrayList<ResourceLedger.Source>();
        for (int i = 0; i < stacks.length; i++) sources.add(new ResourceLedger.Source("fixture", i, stacks[i]));
        var recipes = new CraftingRecipes(player, workbench);
        return new CraftSearch(recipes, new CraftRequest(ResourceLocation.withDefaultNamespace(id), batches, partial,
                false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, Map.of()), sources,
                new SearchBudget(1_000_000_000L), plan -> null).run();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void woodenPickaxeDeficitStaysPlanksBeforeAndAfterPreparation(GameTestHelper helper) {
        var player = player(helper);
        try {
            var first = search(player, "wooden_pickaxe", 1, true, true, new ItemStack(Items.OAK_LOG));
            var plan = first.plan().orElseThrow(() -> new AssertionError(first));
            helper.assertValueEqual(count(plan.primary(), Items.STICK), 4, "prepared sticks");
            helper.assertValueEqual(count(plan.primary(), Items.OAK_PLANKS) + count(plan.surplus(), Items.OAK_PLANKS), 2, "remaining planks");
            for (var result : List.of(first, search(player, "wooden_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_PLANKS, 2), new ItemStack(Items.STICK, 4)))) {
                helper.assertTrue(result.missing().size() == 1 && result.missing().getFirst().count() == 1
                        && result.missing().getFirst().alternatives().stream().allMatch(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)),
                        "Expected one plank, not an unrelated producer: " + result.missing());
            }
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void lockedUnseededAlternativesDoNotBlockUnlockedPartialTools(GameTestHelper helper) {
        var player = player(helper);
        var rule = player.level().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING);
        boolean before = rule.get();
        try {
            rule.set(true, player.getServer());
            var manager = player.serverLevel().getRecipeManager();
            for (String id : List.of("oak_planks", "stick", "stone_pickaxe", "diamond_pickaxe", "iron_pickaxe", "golden_pickaxe"))
                player.awardRecipes(List.of(manager.byKey(ResourceLocation.withDefaultNamespace(id)).orElseThrow()));
            for (String id : List.of("stone_pickaxe", "diamond_pickaxe", "iron_pickaxe", "golden_pickaxe")) {
                var result = search(player, id, 1, true, true, new ItemStack(Items.OAK_LOG));
                helper.assertValueEqual(result.code(), CraftingResultCode.PARTIAL_CREATED, id + " rejected: " + result);
                helper.assertValueEqual(count(result.plan().orElseThrow().primary(), Items.STICK), 4, "stick preparation");
            }
        } finally { rule.set(before, player.getServer()); remove(player); }
        helper.succeed();
    }

    static int count(List<ItemStack> stacks, Item item) {
        return stacks.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("removal")
    static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(0, 2, 0));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    static void remove(ServerPlayer player) {
        org.berusted.craftable.environment.EnvironmentSnapshotService.remove(player.getUUID());
        CraftingSessions.clear(player.getUUID());
        player.getServer().getPlayerList().remove(player);
    }
}
