package org.berusted.craftable.execution;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.PlanView;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.planner.SearchResult;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Core limit semantics use real ordinary recipe entries and a frozen CPU
 * clock. World reload, wire admission and actual transactions have separate
 * fixtures; these read-only searches must never mutate their real sources. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreM5StepLimitGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final ResourceLocation TARGET = id("target");

    private PreM5StepLimitGameTests() {}

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void oneStepLimitAcceptsDirectInputsButNotRecursiveOrExtraRootBatches(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
            var catalog = new CraftingRecipes(player, true);
            var sources = inventorySources(player);
            var before = inventoryIdentity(player);
            var sticks = CraftRequest.one(ResourceLocation.withDefaultNamespace("stick"));
            var direct = search(catalog, sticks, sources, logical(), 1).run();
            helper.assertValueEqual(direct.code(), CraftingResultCode.CREATED, "one direct operation was excluded");
            helper.assertValueEqual(direct.plan().orElseThrow().steps().size(), 1, "direct route invented an intermediate");
            var tooManyRoots = search(catalog, sticks.withBatches(2).withPartial(true), sources, logical(), 1);
            assertUnknown(helper, tooManyRoots.run(), tooManyRoots, "root count exceeded the effective step cap");
            helper.assertValueEqual(tooManyRoots.visitedStates(), 0, "root lower bound waited for branch expansion");
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            var recursive = search(catalog, sticks.withPartial(true), inventorySources(player), logical(), 1)
                    .withDiagnosticBudget(logical());
            assertUnknown(helper, recursive.run(), recursive, "recursive preparation bypassed the one-step cap");
            helper.assertValueEqual(recursive.partialSearches(), 0, "structural exclusion authorized diagnosis");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "limited search consumed real logs");
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
            helper.assertValueEqual(inventoryIdentity(player), before, "read-only direct planning mutated its source");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void defaultAndHardLimitsPermitExact128And256OperationRoutes(GameTestHelper helper) {
        try (var fixture = new RecipesFixture(helper)) {
            fixture.player.getInventory().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 64));
            var catalog = fixture.catalog();
            var sources = inventorySources(fixture.player);
            var before = inventoryIdentity(fixture.player);
            // Four ordinary one-input operations per root, with no item-specific
            // step arithmetic in production and no zero-cost shortcut in tests.
            var baseline = new CraftSearch(catalog, request(32), sources, logical(), plan -> null).run();
            assertFull(helper, catalog, baseline, 32, 128);
            var limited = search(catalog, request(33).withPartial(true), sources, logical(), 128);
            assertUnknown(helper, limited.run(), limited, "129th represented operation escaped the default cap");
            helper.assertValueEqual(limited.partialSearches(), 0, "default-cap truncation authorized partial work");
            var hard = search(catalog, request(64), sources, logical(), 256).run();
            assertFull(helper, catalog, hard, 64, 256);
            var view = PlanView.from(catalog, request(64), hard, List.of());
            helper.assertTrue(view.complete() && view.code() == CraftingResultCode.CREATED,
                    "fixed projection bound still used the old runtime default");
            helper.assertValueEqual(view.operations().size(), 256, "projection silently truncated represented operations");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "large core search consumed source inventory");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void continuationFreezesStepLimitWithoutReplenishingItsBudget(GameTestHelper helper) {
        try (var fixture = new RecipesFixture(helper)) {
            fixture.player.getInventory().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 64));
            var catalog = fixture.catalog();
            var sources = inventorySources(fixture.player);
            var before = inventoryIdentity(fixture.player);
            var clock = new AtomicLong();
            var budget = SearchBudget.resumable(clock::getAndIncrement, 1_000_000, SearchBudget.MAX_STATES);
            int configuredLimit = 128;
            var continuation = search(catalog, request(33), sources, budget, configuredLimit);
            java.util.Optional<SearchResult> result = continuation.advance(25);
            helper.assertTrue(result.isEmpty(), "fixture did not reach an actual continuation boundary");
            configuredLimit = 256;
            long previousRemaining = budget.remainingNanos();
            int previousStates = continuation.visitedStates();
            while (result.isEmpty()) {
                clock.addAndGet(1_000_000); // Idle time is not new CPU allowance.
                result = continuation.advance(25);
                helper.assertTrue(budget.remainingNanos() <= previousRemaining, "slice refilled the CPU allowance");
                helper.assertTrue(continuation.visitedStates() >= previousStates, "slice reset visited states");
                previousRemaining = budget.remainingNanos();
                previousStates = continuation.visitedStates();
            }
            assertUnknown(helper, result.orElseThrow(), continuation, "resumed search adopted a later caller limit");
            helper.assertValueEqual(continuation.initializations(), 1, "continuation restarted its root");
            assertFull(helper, catalog, search(catalog, request(33), sources, logical(), configuredLimit).run(), 33, 132);
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "continuation mutated its real source");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void searchedNegativeEvidenceIsSpecificToItsFrozenStepLimit(GameTestHelper helper) {
        try (var fixture = new RecipesFixture(helper)) {
            var catalog = fixture.catalog();
            var scope = new CraftSearch.Reachability();
            var first = search(catalog, request(1), List.of(), logical(), 128).withReachability(scope);
            helper.assertValueEqual(first.run().code(), CraftingResultCode.MISSING_INGREDIENTS, "empty route did not prove absence");
            var evidence = first.fullEvidence();
            helper.assertTrue(evidence != null, "complete full search did not retain negative evidence");
            var same = search(catalog, request(1).withPartial(true), List.of(), logical(), 128)
                    .withReachability(scope).withFullEvidence(evidence);
            same.run();
            helper.assertValueEqual(same.fullSearches(), 0, "identical-cap negative proof was not reused");
            var higher = search(catalog, request(1).withPartial(true), List.of(), logical(), 256)
                    .withReachability(scope).withFullEvidence(evidence);
            higher.run();
            helper.assertValueEqual(higher.fullSearches(), 1, "different higher cap skipped complete full search");
            var lower = search(catalog, request(1).withPartial(true), List.of(), logical(), 1)
                    .withReachability(scope).withFullEvidence(evidence);
            assertUnknown(helper, lower.run(), lower, "different lower cap reused a searched proof");
            helper.assertValueEqual(lower.fullSearches(), 1, "lower cap skipped the full entry");
            helper.assertValueEqual(lower.partialSearches(), 0, "different-cap evidence authorized preparation");
            var presence = scope.excludes(catalog.planningInput(), request(1));
            helper.assertTrue(presence != null, "fixture lacked a genuine presence-only exclusion");
            var independent = search(catalog, request(1).withPartial(true), List.of(), logical(), 256)
                    .withReachability(scope).withFullEvidence(presence);
            independent.run();
            helper.assertValueEqual(independent.fullSearches(), 0, "presence absence incorrectly depended on step allowance");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void separateDiagnosticAllowancePreservesTheFrozenStepCapAndIncompleteMarker(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            var catalog = new CraftingRecipes(player, true);
            var sources = inventorySources(player);
            var before = inventoryIdentity(player);
            var pick = CraftRequest.one(ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
            var scope = new CraftSearch.Reachability();
            search(catalog, pick, sources, logical(), 128).withReachability(scope).run();
            var presence = scope.excludes(catalog.planningInput(), pick);
            helper.assertTrue(presence != null, "missing diamond was not excluded by frozen presence data");
            var diagnostic = search(catalog, pick.withPartial(true), sources, logical(), 1)
                    .withReachability(scope).withFullEvidence(presence).withDiagnosticBudget(logical());
            var result = diagnostic.run();
            // Diagnosis may retain a concrete bounded prefix for display. The
            // incomplete marker, rather than absence of that prefix, prevents
            // service execution and confirmation/witness authorization.
            helper.assertTrue(!result.completeSearch(), "truncated diagnostic prefix lost its incomplete marker");
            helper.assertTrue(result.plan().isEmpty() || result.plan().orElseThrow().partial()
                    && result.plan().orElseThrow().steps().size() <= 1, "diagnostic allowance bypassed its frozen operation cap");
            helper.assertValueEqual(diagnostic.fullSearches(), 0, "presence proof needlessly searched full again");
            helper.assertValueEqual(diagnostic.partialSearches(), 1, "fixture never entered actual preparation diagnosis");
            helper.assertValueEqual(inventoryIdentity(player), before, "diagnostic allowance consumed the real source");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void representationLimitsRejectInvalidCapsAndExcessGeneratedLots(GameTestHelper helper) {
        try (var fixture = new RecipesFixture(helper)) {
            for (int limit : List.of(0, 257, Integer.MAX_VALUE))
                rejected(helper, () -> search(fixture.catalog(), request(1), List.of(), logical(), limit),
                        "invalid world step cap was accepted");
            var ledger = new ResourceLedger(List.of());
            for (int i = 0; i < SearchBudget.HARD_MAX_STEPS * 10; i++)
                ledger.produce(new ItemStack(Items.BUCKET), false, true, "0", i / 10);
            boolean rejected = false;
            try { ledger.produce(new ItemStack(Items.BUCKET), false, true, "0", 255); }
            catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected, "generated-lot representation grew without a bound");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void projectionOverflowStaysIncompleteInsteadOfWrappingCounts(GameTestHelper helper) {
        try (var fixture = new RecipesFixture(helper)) {
            var oversized = fixture.recipes(Integer.MAX_VALUE);
            helper.getLevel().getRecipeManager().replaceRecipes(oversized);
            var result = SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS);
            var view = PlanView.from(fixture.catalog(), request(2), result, List.of());
            helper.assertTrue(view.code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                    && !view.complete() && view.operations().isEmpty(), "root output multiplication wrapped into complete review");
            helper.getLevel().getRecipeManager().replaceRecipes(fixture.recipes(1));
            var aggregated = PlanView.from(fixture.catalog(), request(1), result, List.of(),
                    List.of(new ItemStack(Items.AMETHYST_SHARD, Integer.MAX_VALUE),
                            new ItemStack(Items.AMETHYST_SHARD, Integer.MAX_VALUE)));
            helper.assertTrue(aggregated.code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                    && !aggregated.complete() && aggregated.operations().isEmpty(), "source aggregation escaped as a complete review");
        }
        helper.succeed();
    }

    private static CraftSearch search(CraftingRecipes catalog, CraftRequest request,
            List<ResourceLedger.Source> sources, SearchBudget budget, int stepLimit) {
        return new CraftSearch(catalog, request, sources, budget, stepLimit, plan -> null);
    }

    private static void assertUnknown(GameTestHelper helper, SearchResult result, CraftSearch search, String message) {
        helper.assertTrue(result.code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                && !result.completeSearch() && result.plan().isEmpty() && search.fullEvidence() == null, message);
    }

    private static void assertFull(GameTestHelper helper, CraftingRecipes catalog, SearchResult result, int batches, int steps) {
        helper.assertTrue(result.code() == CraftingResultCode.CREATED && result.completeSearch()
                && result.plan().isPresent() && !result.plan().orElseThrow().partial(), "reachable bounded chain did not complete");
        var plan = result.plan().orElseThrow();
        helper.assertValueEqual(plan.completedBatches(), batches, "wrong completed root count");
        helper.assertValueEqual(plan.steps().size(), steps, "wrong exact operation count");
        helper.assertValueEqual(plan.extractions().stream().mapToInt(CraftPlan.Extraction::count).sum(), batches,
                "chain duplicated or discarded initial shards");
        helper.assertTrue(catalog.validate(plan) && plan.surplus().isEmpty(), "chain failed actual ordinary recipe replay");
    }

    private static List<ResourceLedger.Source> inventorySources(ServerPlayer player) {
        var result = new java.util.ArrayList<ResourceLedger.Source>();
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) result.add(new ResourceLedger.Source("fixture", slot, stack));
        }
        return List.copyOf(result);
    }

    private static Object inventoryIdentity(ServerPlayer player) {
        return CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory()));
    }

    private static void rejected(GameTestHelper helper, Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, message);
    }

    private static CraftRequest request(int batches) { return CraftRequest.one(TARGET).withBatches(batches); }
    private static ResourceLocation id(String name) { return ResourceLocation.withDefaultNamespace("pre_m5_steps_" + name); }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }

    private static RecipeHolder<?> recipe(String name, Item output, int count, Item input) {
        return new RecipeHolder<>(id(name), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(output, count), NonNullList.of(Ingredient.EMPTY, Ingredient.of(input))));
    }

    private static final class RecipesFixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerPlayer player;
        final List<RecipeHolder<?>> original;

        RecipesFixture(GameTestHelper helper) {
            this.helper = helper;
            player = M4PlanningGameTests.player(helper);
            original = List.copyOf(helper.getLevel().getRecipeManager().getRecipes());
            helper.getLevel().getRecipeManager().replaceRecipes(recipes(1));
        }

        List<RecipeHolder<?>> recipes(int rootOutput) {
            return List.of(recipe("a", Items.IRON_NUGGET, 1, Items.AMETHYST_SHARD),
                    recipe("b", Items.COPPER_INGOT, 1, Items.IRON_NUGGET),
                    recipe("c", Items.IRON_INGOT, 1, Items.COPPER_INGOT),
                    recipe("target", Items.GOLD_INGOT, rootOutput, Items.IRON_INGOT));
        }

        CraftingRecipes catalog() { return new CraftingRecipes(player, true); }

        @Override public void close() {
            helper.getLevel().getRecipeManager().replaceRecipes(original);
            M4PlanningGameTests.remove(player);
        }
    }
}
