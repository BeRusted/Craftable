package org.berusted.craftable.execution;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Material deficits and delivery capacity are independent server decisions. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M411CapacityDiagnosticGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final CraftRequest PICK = CraftRequest.one(ResourceLocation.withDefaultNamespace("diamond_pickaxe"));

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void externalLogShortageDoesNotChangeWithOneOrTwoFreeSlots(GameTestHelper helper) {
        try (var environment = new LogEnvironment(helper)) {
            var player = environment.player;
            fill(player, 1);
            var before = inventoryIdentity(player);
            var tight = CraftingService.prepare(player, PICK, EnvironmentSnapshotService.fresh(player), logical());
            helper.assertValueEqual(tight.result().code(), CraftingResultCode.NO_OUTPUT_SPACE, "tight preparation ignored surplus capacity");
            helper.assertTrue(tight.result().plan().isEmpty() && tight.fullMissing(), "capacity failure became an executable preparation");
            assertDiamonds(helper, tight.result().missing(), 3);
            var failed = CraftingService.create(player, PICK, false, logical());
            helper.assertValueEqual(failed.code(), CraftingResultCode.MISSING_INGREDIENTS, "plain C replaced real shortage with preparation capacity");
            assertDiamonds(helper, failed.missing(), 3);
            helper.assertTrue(failed.plan() == null, "plain C authorized preparation");
            helper.assertValueEqual(inventoryIdentity(player), before, "plain C changed tight inventory");
            helper.assertValueEqual(environment.container().getItem(0).getCount(), 1, "plain C consumed external log");

            player.getInventory().setItem(34, ItemStack.EMPTY);
            var roomy = CraftingService.prepare(player, PICK, EnvironmentSnapshotService.fresh(player), logical());
            helper.assertTrue(roomy.result().plan().isPresent() && roomy.result().plan().orElseThrow().partial(),
                    "two free slots did not admit the real preparation");
            assertDiamonds(helper, roomy.result().missing(), 3);
            helper.assertValueEqual(missingIdentity(tight.result().missing()), missingIdentity(roomy.result().missing()),
                    "capacity invented a plank/stick ingredient deficit");
            var roomyFailure = CraftingService.create(player, PICK, false, logical());
            helper.assertValueEqual(roomyFailure.code(), CraftingResultCode.MISSING_INGREDIENTS, "roomy plain C lost shortage");
            assertDiamonds(helper, roomyFailure.missing(), 3);
            helper.assertValueEqual(environment.container().getItem(0).getCount(), 1, "roomy plain C consumed external log");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "plain C manufactured preparation");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void capacityBlockedPreparationKeepsAssignedGridAndCursorInputs(GameTestHelper helper) {
        try (var environment = new LogEnvironment(helper)) {
            var player = environment.player;
            fill(player, 1);
            player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.DIAMOND, 2));
            player.containerMenu.setCarried(new ItemStack(Items.BLAZE_ROD));
            var before = inventoryIdentity(player);
            var partial = CraftingService.create(player, PICK.withPartial(true), false, logical());
            helper.assertValueEqual(partial.code(), CraftingResultCode.NO_OUTPUT_SPACE, "partial fell back to a route with invented deficits");
            helper.assertTrue(partial.plan() == null, "blocked partial retained an executable plan");
            assertDiamonds(helper, partial.missing(), 1);
            helper.assertValueEqual(inventoryIdentity(player), before, "capacity refusal changed main inventory");
            helper.assertValueEqual(player.inventoryMenu.getCraftSlots().getItem(0).getCount(), 2, "capacity refusal consumed assigned diamonds");
            helper.assertTrue(player.inventoryMenu.getCraftSlots().getItem(0).is(Items.DIAMOND), "capacity refusal changed grid material");
            helper.assertTrue(player.containerMenu.getCarried().is(Items.BLAZE_ROD)
                    && player.containerMenu.getCarried().getCount() == 1, "capacity refusal consumed cursor input");
            helper.assertValueEqual(environment.container().getItem(0).getCount(), 1, "blocked partial consumed external log");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "blocked partial delivered output");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void equalDeficitMayUseAProducerWhoseRemainderFits(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            manager.replaceRecipes(List.of(
                    recipe("m411_root", Items.DIAMOND, Items.STICK, Items.COBBLESTONE),
                    recipe("m411_a_bucket_producer", Items.STICK, Items.MILK_BUCKET),
                    recipe("m411_b_log_producer", Items.STICK, Items.OAK_LOG)));
            fill(player, 1);
            var slots = MainInventoryInsertion.copyMainInventory(player.getInventory());
            var sources = List.of(new ResourceLedger.Source("fixture", 0, new ItemStack(Items.MILK_BUCKET)),
                    new ResourceLedger.Source("fixture", 1, new ItemStack(Items.OAK_LOG)));
            var rejected = new AtomicInteger();
            var catalog = new CraftingRecipes(player, true);
            var result = new CraftSearch(catalog, CraftRequest.one(ResourceLocation.withDefaultNamespace("m411_root")).withPartial(true),
                    sources, logical(), plan -> {
                        var failure = MainInventoryInsertion.simulate(slots, 64, plan, Set.of(), Set.of("fixture"), false).failure();
                        if (failure == CraftingResultCode.NO_OUTPUT_SPACE) rejected.incrementAndGet();
                        return failure;
                    }).run();
            helper.assertTrue(rejected.get() > 0, "fixture did not reject the bucket remainder before testing its alternative");
            helper.assertValueEqual(result.code(), CraftingResultCode.PARTIAL_CREATED, "capacity rejected a legitimate equivalent preparation");
            var plan = result.plan().orElseThrow();
            helper.assertTrue(catalog.validate(plan), "alternative preparation did not replay authoritatively");
            helper.assertValueEqual(plan.steps().getFirst().recipe().id(), ResourceLocation.withDefaultNamespace("m411_b_log_producer"),
                    "capacity did not select the less bulky producer");
            helper.assertTrue(plan.missing().size() == 1 && plan.missing().getFirst().count() == 1
                    && plan.missing().getFirst().alternatives().stream().allMatch(stack -> stack.is(Items.COBBLESTONE)),
                    "capacity changed the alternative's real deficit");
            helper.assertValueEqual(inventoryIdentity(player), CraftPlan.stackKeys(slots), "read-only alternative wrote inventory");
            helper.assertValueEqual(sources.get(0).stack().getCount(), 1, "alternative consumed source fixture");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void boneMealOneBatchIsCapacityBlockedButTwoBatchesFreeTheBoneSlot(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            fill(player, 0);
            player.getInventory().setItem(0, new ItemStack(Items.BONE, 2));
            var bone = CraftRequest.one(ResourceLocation.withDefaultNamespace("bone_meal"));
            var before = inventoryIdentity(player);
            var one = CraftingService.create(player, bone, false, logical());
            helper.assertValueEqual(one.code(), CraftingResultCode.NO_OUTPUT_SPACE, "one bone batch became a material shortage");
            helper.assertTrue(one.missing().isEmpty() && one.plan() == null, "capacity invented a bone/block deficit");
            helper.assertValueEqual(inventoryIdentity(player), before, "one blocked batch consumed bone");
            var two = CraftingService.create(player, bone.withBatches(2), false, logical());
            helper.assertValueEqual(two.code(), CraftingResultCode.CREATED, "two batches failed to free their source slot");
            helper.assertValueEqual(player.getInventory().countItem(Items.BONE), 0, "two batches did not consume both bones");
            helper.assertValueEqual(player.getInventory().countItem(Items.BONE_MEAL), 6, "two batches delivered wrong bone meal count");
            helper.assertValueEqual(player.getInventory().countItem(Items.BEDROCK), 35 * 64, "bone capacity route consumed unrelated items");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void structurallyTruncatedPreparationCannotConsumeInputs(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            manager.replaceRecipes(crowdedProducerRecipes());
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            player.containerMenu.setCarried(new ItemStack(Items.BLAZE_ROD));
            var intent = CraftRequest.one(ResourceLocation.withDefaultNamespace("m411_truncated_root"));
            var before = inventoryIdentity(player);
            var budget = logical();
            var prepared = CraftingService.prepare(player, intent.withPartial(true), EnvironmentSnapshotService.fresh(player), budget);
            helper.assertTrue(prepared.fullMissing(), "fixture did not prove the full cobblestone shortage");
            helper.assertTrue(budget.truncated() && !prepared.result().completeSearch(), "fixture did not omit a diagnostic producer");
            helper.assertValueEqual(prepared.result().code(), CraftingResultCode.PARTIAL_CREATED, "kept producer did not reach a partial frontier");
            helper.assertTrue(prepared.result().plan().isPresent() && prepared.result().plan().orElseThrow().partial(),
                    "fixture has no reachable but structurally incomplete preparation");
            helper.assertTrue(prepared.recipes().validate(prepared.result().plan().orElseThrow()), "fixture partial plan did not replay");

            var partial = CraftingService.create(player, intent.withPartial(true), false, logical());
            helper.assertValueEqual(partial.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "incomplete diagnostic authorized direct partial consumption");
            helper.assertTrue(partial.plan() == null && partial.missing().isEmpty() && partial.drops().isEmpty(),
                    "incomplete diagnostic escaped as an executable or authoritative outcome");
            helper.assertValueEqual(inventoryIdentity(player), before, "incomplete partial consumed the original log");
            helper.assertTrue(player.containerMenu.getCarried().is(Items.BLAZE_ROD)
                    && player.containerMenu.getCarried().getCount() == 1, "incomplete partial changed the cursor input");
            var full = CraftingService.create(player, intent, false, logical());
            helper.assertValueEqual(full.code(), CraftingResultCode.MISSING_INGREDIENTS, "plain C lost its proved full exclusion");
            helper.assertTrue(full.plan() == null && full.missing().isEmpty(), "plain C exposed unfinished diagnostic quantities");
            helper.assertValueEqual(inventoryIdentity(player), before, "plain C consumed structurally incomplete preparation");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void structurallyTruncatedAlternativesDoNotRejectACompleteWitness(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            manager.replaceRecipes(crowdedProducerRecipes());
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE));
            var intent = CraftRequest.one(ResourceLocation.withDefaultNamespace("m411_truncated_root"));
            var budget = logical();
            var prepared = CraftingService.prepare(player, intent, EnvironmentSnapshotService.fresh(player), budget);
            helper.assertTrue(budget.truncated(), "fixture did not omit full-route alternatives");
            helper.assertTrue(prepared.result().completeSearch() && prepared.result().plan().isPresent()
                    && !prepared.result().plan().orElseThrow().partial(), "omitted alternatives invalidated a complete concrete witness");
            var outcome = CraftingService.create(player, intent, false, logical());
            helper.assertValueEqual(outcome.code(), CraftingResultCode.CREATED, "partial completeness gate rejected a proved full craft");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 1, "complete fixture output was not delivered");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 0, "complete fixture did not consume its log");
            helper.assertValueEqual(player.getInventory().countItem(Items.COBBLESTONE), 0, "complete fixture did not consume cobblestone");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "complete fixture leaked its intermediate");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static List<RecipeHolder<?>> crowdedProducerRecipes() {
        var recipes = new java.util.ArrayList<RecipeHolder<?>>();
        recipes.add(recipe("m411_truncated_root", Items.DIAMOND, Items.STICK, Items.COBBLESTONE));
        for (int index = 0; index <= SearchBudget.MAX_CANDIDATES; index++)
            recipes.add(recipe("m411_truncated_stick_" + index, Items.STICK, Items.OAK_LOG));
        return recipes;
    }

    private static void fill(ServerPlayer player, int freeSlots) {
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot,
                slot >= 36 - freeSlots ? ItemStack.EMPTY : new ItemStack(Items.BEDROCK, 64));
    }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }
    private static Object inventoryIdentity(ServerPlayer player) {
        return CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory()));
    }
    private static Object missingIdentity(List<CraftPlan.Missing> missing) {
        return missing.stream().map(item -> List.of(item.count(), CraftPlan.stackKeys(item.alternatives()))).toList();
    }
    private static void assertDiamonds(GameTestHelper helper, List<CraftPlan.Missing> missing, int count) {
        helper.assertTrue(missing.size() == 1 && missing.getFirst().count() == count
                && missing.getFirst().alternatives().stream().allMatch(stack -> stack.is(Items.DIAMOND)),
                "expected only " + count + " missing diamonds, got " + missing);
    }
    private static RecipeHolder<?> recipe(String id, Item output, Item... inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        for (Item input : inputs) ingredients.add(Ingredient.of(input));
        return new RecipeHolder<>(ResourceLocation.withDefaultNamespace(id),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output), ingredients));
    }

    private static final class LogEnvironment implements AutoCloseable {
        final ServerPlayer player;
        final BlockPos chest, above, table;
        final BlockState previousChest, previousAbove, previousTable;

        LogEnvironment(GameTestHelper helper) {
            player = M4PlanningGameTests.player(helper);
            chest = player.blockPosition().east();
            above = chest.above();
            table = player.blockPosition().north();
            var level = player.serverLevel();
            previousChest = level.getBlockState(chest);
            previousAbove = level.getBlockState(above);
            previousTable = level.getBlockState(table);
            level.setBlock(above, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
            level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
            container().setItem(0, new ItemStack(Items.OAK_LOG));
        }
        Container container() { return (Container) player.serverLevel().getBlockEntity(chest); }
        @Override public void close() {
            var level = player.serverLevel();
            level.setBlock(chest, previousChest, 2);
            level.setBlock(above, previousAbove, 2);
            level.setBlock(table, previousTable, 2);
            M4PlanningGameTests.remove(player);
        }
    }
}
