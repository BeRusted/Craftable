package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
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
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.planner.SearchResult;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Core regressions use ordinary recipes and deterministic clocks, not a second planner. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M410SearchRegressionGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fishingRodQuantityShortageCompletesAndReusesItsFullEvidence(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var catalog = new CraftingRecipes(player, true);
            var sources = rodSources();
            var before = sourceKeys(sources);
            var scope = new CraftSearch.Reachability();
            var full = new CraftSearch(catalog, rod(), sources, logical(), p -> null).withReachability(scope);
            assertStringShortage(helper, full.run());
            helper.assertTrue(full.fullEvidence() != null, "one present string prevented complete quantity exclusion");
            helper.assertValueEqual(full.partialSearches(), 0, "FULL_ONLY entered preparation");

            var diagnostic = new CraftSearch(catalog, rod().withPartial(true), sources, logical(), p -> null)
                    .withReachability(scope).withFullEvidence(full.fullEvidence()).withDiagnosticBudget(logical());
            assertStringShortage(helper, diagnostic.run());
            helper.assertValueEqual(diagnostic.fullSearches(), 0, "diagnosis repeated completed full exclusion");
            helper.assertValueEqual(diagnostic.partialSearches(), 1, "whole-demand diagnosis was skipped");
            helper.assertValueEqual(sourceKeys(sources), before, "shortage preview mutated source counts/components");
            helper.assertTrue(player.getInventory().isEmpty(), "shortage preview wrote inventory");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fishingRodShortageHasTheSameResultAcrossSmallResumableSlices(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var catalog = new CraftingRecipes(player, true);
            var sources = rodSources();
            var expected = new CraftSearch(catalog, rod(), sources, logical(), p -> null).run();
            var clock = new AtomicLong();
            var allowance = SearchBudget.resumable(clock::getAndIncrement, 1_000_000, SearchBudget.MAX_STATES);
            var scope = new CraftSearch.Reachability();
            var sliced = new CraftSearch(catalog, rod(), sources, allowance, p -> null).withReachability(scope);
            Optional<SearchResult> actual = Optional.empty();
            int slices = 0;
            while (actual.isEmpty() && slices++ < 10_000) {
                actual = sliced.advance(32);
                clock.addAndGet(10_000_000); // Waiting is not active CPU and must not restart the task.
            }
            helper.assertTrue(actual.isPresent() && slices > 10, "small-slice shortage did not retain/finish its work");
            assertStringShortage(helper, actual.orElseThrow());
            helper.assertValueEqual(actual.orElseThrow().visitedStates(), expected.visitedStates(), "sliced quantity search replayed states");
            helper.assertTrue(sliced.fullEvidence() != null, "sliced exclusion lost its evidence");
            helper.assertValueEqual(sliced.initializations(), 1, "sliced shortage restarted its root");
            helper.assertValueEqual(scope.attempts(), 1, "sliced shortage restarted the closure");
            helper.assertValueEqual(sliced.partialSearches(), 0, "sliced FULL_ONLY entered diagnosis");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void structurallyOmittedBranchesKeepAFullWitnessAcrossSliceBoundaries(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            manager.replaceRecipes(candidateFixture(false));
            var catalog = new CraftingRecipes(player, true);
            var clock = new AtomicLong();
            var allowance = SearchBudget.resumable(clock::getAndIncrement, 1_000_000, SearchBudget.MAX_STATES);
            // Existing stock now defers producer alternatives until needed.
            // Force actual production from the 17 ordinary candidates while
            // keeping enough stone for the preferred stick AND string route.
            var sources = List.of(source(0, Items.COBBLESTONE, 2));
            var search = new CraftSearch(catalog, request("m410_candidate_root", false), sources, allowance, p -> null);
            Optional<SearchResult> result = Optional.empty();
            boolean pausedAfterOmission = false;
            int slices = 0;
            while (result.isEmpty() && slices++ < 10_000) {
                result = search.advance(12);
                pausedAfterOmission |= allowance.truncated() && result.isEmpty();
            }
            helper.assertTrue(pausedAfterOmission, "fixture did not put the retained witness after a truncated slice");
            helper.assertTrue(result.isPresent(), "retained witness never completed");
            helper.assertValueEqual(result.orElseThrow().code(), CraftingResultCode.CREATED, "structural omission canceled a retained full witness");
            helper.assertTrue(result.orElseThrow().completeSearch() && !result.orElseThrow().plan().orElseThrow().partial(),
                    "valid full witness was reported as incomplete/preparation");
            helper.assertTrue(allowance.truncated() && !allowance.exhausted(), "fixture exhausted CPU/states instead of omitting candidates");
            helper.assertTrue(catalog.validate(result.orElseThrow().plan().orElseThrow()), "retained witness failed authoritative recipe replay");
            helper.assertValueEqual(search.initializations(), 1, "structurally limited witness restarted");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void structurallyOmittedBranchesCannotProveAbsenceOrAuthorizePreparation(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            manager.replaceRecipes(candidateFixture(true));
            var clock = new AtomicLong();
            var allowance = SearchBudget.resumable(clock::getAndIncrement, 1_000_000, SearchBudget.MAX_STATES);
            var sources = List.of(source(0, Items.STICK, 1), source(1, Items.COBBLESTONE, 1));
            var before = sourceKeys(sources);
            var diagnostic = SearchBudget.resumable(() -> 0, 1_000_000, SearchBudget.MAX_STATES);
            var search = new CraftSearch(new CraftingRecipes(player, true), request("m410_candidate_root", true), sources,
                    allowance, p -> null).withDiagnosticBudget(diagnostic);
            Optional<SearchResult> result = Optional.empty();
            int slices = 0;
            while (result.isEmpty() && slices++ < 10_000) result = search.advance(12);
            helper.assertTrue(result.isPresent(), "structurally incomplete search never terminated");
            helper.assertValueEqual(result.orElseThrow().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                    "omitted alternatives became a definite shortage");
            helper.assertTrue(!result.orElseThrow().completeSearch() && result.orElseThrow().plan().isEmpty()
                    && search.fullEvidence() == null, "incomplete full search authorized partial consumption");
            helper.assertTrue(allowance.truncated() && !allowance.exhausted(), "fixture reached a hard allowance instead of structural omission");
            helper.assertValueEqual(search.partialSearches(), 0, "incomplete full search entered preparation");
            helper.assertValueEqual(diagnostic.states(), 0, "incomplete full search borrowed diagnosis allowance");
            helper.assertValueEqual(sourceKeys(sources), before, "structural failure mutated resources");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void producerlessInputMayBeSuppliedByAnEarlierCraftingRemainder(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            // No recipe outputs a bucket. The first root input must be crafted
            // before its milk-bucket remainder can satisfy the second input.
            manager.replaceRecipes(List.of(
                    recipe("m410_remainder_root", Items.DIAMOND, Items.COOKED_BEEF, Items.BUCKET),
                    recipe("m410_remainder_producer", Items.COOKED_BEEF, Items.MILK_BUCKET)));
            var catalog = new CraftingRecipes(player, true);
            helper.assertTrue(catalog.planningInput().producing(Ingredient.of(Items.BUCKET)).isEmpty(), "fixture has a direct bucket producer");
            var scope = new CraftSearch.Reachability();
            var result = new CraftSearch(catalog, request("m410_remainder_root", false),
                    List.of(source(0, Items.MILK_BUCKET, 1)), logical(), p -> null).withReachability(scope).run();
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "producerless ordering rejected a useful remainder");
            var plan = result.plan().orElseThrow();
            helper.assertTrue(catalog.validate(plan), "remainder-origin witness failed replay");
            helper.assertValueEqual(plan.steps().size(), 2, "remainder was manufactured in an extra recipe");
            helper.assertValueEqual(M4PlanningGameTests.count(plan.primary(), Items.DIAMOND), 1, "remainder-enabled output");
            helper.assertValueEqual(plan.extractions().size(), 1, "remainder was counted as an original source");
            helper.assertTrue(plan.extractions().getFirst().expected().is(Items.MILK_BUCKET), "wrong remainder seed consumed");
            helper.assertValueEqual(M4PlanningGameTests.count(plan.surplus(), Items.BUCKET), 0, "consumed bucket was delivered again");
            helper.assertTrue(scope.excludes(catalog.planningInput(), request("m410_remainder_root", false)) == null,
                    "presence closure disproved the full witness by forgetting its remainder");

            // The same remainder must also keep a nested producer reachable,
            // not only an existing/generated direct root input.
            manager.replaceRecipes(List.of(
                    recipe("m410_remainder_root", Items.DIAMOND, Items.COOKED_BEEF, Items.IRON_INGOT),
                    recipe("m410_remainder_producer", Items.COOKED_BEEF, Items.MILK_BUCKET),
                    recipe("m410_bucket_consumer", Items.IRON_INGOT, Items.BUCKET)));
            var nestedCatalog = new CraftingRecipes(player, true);
            var nested = new CraftSearch(nestedCatalog, request("m410_remainder_root", false),
                    List.of(source(0, Items.MILK_BUCKET, 1)), logical(), p -> null).run();
            helper.assertValueEqual(nested.code(), CraftingResultCode.CREATED, "coarse reachability pruned a remainder-fed producer");
            helper.assertTrue(nestedCatalog.validate(nested.plan().orElseThrow()), "nested remainder witness failed replay");
            helper.assertValueEqual(nested.plan().orElseThrow().steps().size(), 3, "nested remainder producer count");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void missingStockMayStillBeProducedAndUsefulWoodConversionSurvives(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            var fishing = manager.byKey(ResourceLocation.withDefaultNamespace("fishing_rod")).orElseThrow();
            manager.replaceRecipes(List.of(fishing, recipe("m410_string_producer", Items.STRING, Items.COBBLESTONE)));
            var catalog = new CraftingRecipes(player, true);
            var result = new CraftSearch(catalog, rod(), List.of(source(0, Items.STICK, 3), source(1, Items.STRING, 1),
                    source(2, Items.COBBLESTONE, 1)), logical(), p -> null).run();
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "stock-only quantity precheck ignored a valid producer");
            helper.assertTrue(catalog.validate(result.plan().orElseThrow()), "produced string witness failed replay");
            helper.assertValueEqual(M4PlanningGameTests.count(result.plan().orElseThrow().primary(), Items.FISHING_ROD), 1, "produced fishing rod");

            manager.replaceRecipes(original);
            var wood = new CraftSearch(new CraftingRecipes(player, true), request("oak_boat", false),
                    List.of(source(0, Items.OAK_LOG, 2)), logical(), p -> null).run();
            helper.assertValueEqual(wood.code(), CraftingResultCode.CREATED, "ordinary log/plank conversion was pruned");
            var woodPlan = wood.plan().orElseThrow();
            helper.assertFalse(woodPlan.steps().stream().anyMatch(step -> step.recipe().id().getPath().equals("oak_wood")),
                    "planks unnecessarily reboxed their log source into wood");
            helper.assertValueEqual(woodPlan.extractions().stream().filter(e -> e.expected().is(Items.OAK_LOG)).mapToInt(CraftPlan.Extraction::count).sum(),
                    2, "two logs supplied five planks without double spending");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static CraftRequest rod() { return request("fishing_rod", false); }
    private static CraftRequest request(String id, boolean partial) {
        return CraftRequest.one(ResourceLocation.withDefaultNamespace(id)).withPartial(partial);
    }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }
    private static ResourceLedger.Source source(int slot, Item item, int count) {
        return new ResourceLedger.Source("fixture", slot, new ItemStack(item, count));
    }
    private static List<ResourceLedger.Source> rodSources() {
        var result = new ArrayList<ResourceLedger.Source>();
        result.add(source(0, Items.STICK, 3));
        result.add(source(1, Items.STRING, 1));
        // These unrelated but reachable wood routes made a simple shortage
        // branch into repeated stick production before the second string.
        Item[] wood = {Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
                Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.CRIMSON_STEM, Items.WARPED_STEM,
                Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS, Items.JUNGLE_PLANKS,
                Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
                Items.CRIMSON_PLANKS, Items.WARPED_PLANKS, Items.BAMBOO};
        for (Item item : wood) result.add(source(result.size(), item, 2));
        return List.copyOf(result);
    }
    private static List<RecipeHolder<?>> candidateFixture(boolean missing) {
        var result = new ArrayList<RecipeHolder<?>>();
        result.add(recipe("m410_candidate_root", Items.DIAMOND, Items.STICK, Items.STRING));
        for (int candidate = 0; candidate <= SearchBudget.MAX_CANDIDATES; candidate++)
            result.add(recipe("m410_stick_" + candidate, Items.STICK, Items.COBBLESTONE));
        result.add(missing ? recipe("m410_string", Items.STRING, Items.COBBLESTONE, Items.COBBLESTONE)
                : recipe("m410_string", Items.STRING, Items.COBBLESTONE));
        return List.copyOf(result);
    }
    private static RecipeHolder<?> recipe(String id, Item output, Item... inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        for (Item input : inputs) ingredients.add(Ingredient.of(input));
        return new RecipeHolder<>(ResourceLocation.withDefaultNamespace(id),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output), ingredients));
    }
    private static List<Object> sourceKeys(List<ResourceLedger.Source> sources) {
        return CraftPlan.stackKeys(sources.stream().map(ResourceLedger.Source::stack).toList());
    }
    private static void assertStringShortage(GameTestHelper helper, SearchResult result) {
        helper.assertValueEqual(result.code(), CraftingResultCode.MISSING_INGREDIENTS, "fishing-rod quantity shortage: " + result);
        helper.assertTrue(result.completeSearch() && result.plan().isEmpty(), "quantity shortage remained unknown or prepared redundant stock");
        helper.assertValueEqual(result.missing().size(), 1, "one missing material kind: " + result.missing());
        var missing = result.missing().getFirst();
        helper.assertValueEqual(missing.count(), 1, "the existing string was lost or counted twice");
        helper.assertTrue(!missing.alternatives().isEmpty() && missing.alternatives().stream().allMatch(stack -> stack.is(Items.STRING)),
                "wrong missing material: " + result.missing());
    }
}
