package org.berusted.craftable.execution;

import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
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
import org.berusted.craftable.network.CraftingDetailPayloads;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.PlanView;
import org.berusted.craftable.planner.SearchBudget;

/** Deterministic deadline/accounting checks, not wall-clock performance claims.
 * Exact ordinary recipe classes preserve the unsupported-subclass boundary;
 * the logical clock advances at budget checkpoints, never by sleeping or
 * altering production callbacks, resources, authority or the 8 ms cap. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreM5WitnessReviewGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final long ALLOWANCE_NANOS = 8_000_000L;
    private static final CraftRequest PICK = CraftRequest.one(id("pickaxe"));

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void threeStepWitnessReviewKeepsExactAccountingWithinSharedDeadline(GameTestHelper helper) {
        try (var environment = new Environment(helper)) {
            var fixture = environment.fixture(helper);
            var player = environment.player;
            var beforeInventory = inventoryIdentity(player);
            long searches = CraftingService.activeFullSearches;
            long validations = CraftingService.witnessValidations;
            var clock = new CheckpointClock(30_000L);
            var budget = new SearchBudget(clock, ALLOWANCE_NANOS, SearchBudget.MAX_STATES);
            var replay = CraftingService.prepareWitness(player, PICK, EnvironmentSnapshotService.fresh(player), fixture.witness(), budget);
            helper.assertValueEqual(replay.result().code(), CraftingResultCode.CREATED, "three-step witness did not replay");
            helper.assertValueEqual(replay.result().plan().orElseThrow().fingerprint(), fixture.plan().fingerprint(),
                    "fresh witness replay changed source/provenance/output/surplus accounting");
            helper.assertTrue(budget.alive() && clock.elapsed() < ALLOWANCE_NANOS,
                    "small logical replay exhausted or expanded the original deadline");
            helper.assertValueEqual(budget.states(), 0, "witness validation entered solver states");

            // A separate request receives one original 8 ms deadline shared
            // with its admission supplier. It performs real fresh discovery.
            var reviewClock = new CheckpointClock(30_000L);
            var reviewBudget = new SearchBudget(reviewClock, ALLOWANCE_NANOS, SearchBudget.MAX_STATES);
            long generation = EnvironmentSnapshotService.preview(player).generation();
            var draft = CraftingService.preview(player, PICK, "", fixture.witness(), 100, reviewBudget, reviewClock::remaining);
            helper.assertValueEqual(draft.view().code(), CraftingResultCode.CREATED, "shared 8 ms review failed");
            helper.assertTrue(draft.view().complete() && !draft.token().equals(CraftingDetailPayloads.NO_TOKEN),
                    "complete low-cost review lacked an authoritative offer");
            helper.assertTrue(EnvironmentSnapshotService.preview(player).generation() > generation,
                    "review reused a stale world snapshot instead of fresh discovery");
            helper.assertValueEqual(draft.view().operations().size(), 3, "review fabricated or omitted a step");
            helper.assertValueEqual(M4PlanningGameTests.count(draft.view().primary(), Items.DIAMOND_PICKAXE), 1, "review output");
            helper.assertValueEqual(M4PlanningGameTests.count(draft.view().surplus(), Items.STICK), 2, "review stick surplus");
            helper.assertValueEqual(M4PlanningGameTests.count(draft.view().surplus(), Items.OAK_PLANKS), 2, "review plank surplus");
            helper.assertValueEqual(M4PlanningGameTests.count(draft.view().consumed(), Items.OAK_LOG), 1, "review log extraction");
            helper.assertValueEqual(M4PlanningGameTests.count(draft.view().consumed(), Items.DIAMOND), 3, "review diamond extraction");
            helper.assertTrue(reviewBudget.alive() && reviewClock.remaining() > 0, "review manufactured a fresh deadline");
            helper.assertValueEqual(reviewBudget.states(), 0, "authoritative review searched alternative routes");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches, "witness review re-searched");
            helper.assertValueEqual(CraftingService.witnessValidations, validations + 2, "replay/review validation count");
            helper.assertValueEqual(inventoryIdentity(player), beforeInventory, "read-only witness review mutated inventory");
            Craftable.LOGGER.warn("PRE_M5_WITNESS_DEADLINE logicalCheckpointClock=true wallClockPerformance=false"
                    + " steps=3 deadlineNanos={} replayElapsedNanos={} reviewElapsedNanos={} fresh=true fullSearches=0",
                    ALLOWANCE_NANOS, clock.elapsed(), reviewClock.elapsed());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void threeStepWitnessReviewCannotRefillAnExpiredSharedDeadline(GameTestHelper helper) {
        try (var environment = new Environment(helper)) {
            var fixture = environment.fixture(helper);
            var player = environment.player;
            var beforeInventory = inventoryIdentity(player);
            long searches = CraftingService.activeFullSearches;
            long validations = CraftingService.witnessValidations;
            var clock = new CheckpointClock(3_000_000L);
            var budget = new SearchBudget(clock, ALLOWANCE_NANOS, SearchBudget.MAX_STATES);
            long generation = EnvironmentSnapshotService.preview(player).generation();
            var draft = CraftingService.preview(player, PICK, "", fixture.witness(), 100, budget, clock::remaining);
            helper.assertValueEqual(draft.view().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                    "expired witness validation became a success or material absence proof");
            helper.assertValueEqual(draft.token(), CraftingDetailPayloads.NO_TOKEN, "expired review issued an offer");
            helper.assertTrue(budget.exhausted() && clock.elapsed() >= ALLOWANCE_NANOS && clock.remaining() == 0,
                    "review replenished its original 8 ms shared deadline");
            helper.assertTrue(EnvironmentSnapshotService.preview(player).generation() > generation,
                    "exhausted review skipped the fresh authoritative scan");
            helper.assertValueEqual(CraftingService.witnessValidations, validations + 1,
                    "fixture did not exhaust during actual witness validation");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches, "exhausted witness fell back to search");
            helper.assertValueEqual(budget.states(), 0, "exhausted witness entered solver states");
            helper.assertTrue(draft.view().primary().isEmpty(), "expired review exposed executable output");
            helper.assertValueEqual(inventoryIdentity(player), beforeInventory, "expired witness review consumed or delivered stock");
            Craftable.LOGGER.warn("PRE_M5_WITNESS_DEADLINE logicalCheckpointClock=true wallClockPerformance=false"
                    + " steps=3 deadlineNanos={} elapsedNanos={} result=SEARCH_BUDGET_EXCEEDED token=false fresh=true fullSearches=0",
                    ALLOWANCE_NANOS, clock.elapsed());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void projectedOperationsKeepExactValuesCopiesAndDisplayPathBounds(GameTestHelper helper) {
        try (var environment = new Environment(helper)) {
            var fixture = environment.fixture(helper);
            var prepared = fixture.prepared();
            var view = PlanView.from(prepared.recipes(), prepared.request(), prepared.result(), prepared.delivery().drops());
            helper.assertTrue(view.complete() && view.code() == CraftingResultCode.CREATED && view.operations().size() == 3,
                    "three-step projection lost its complete result");
            exactOperationsAndCopies(helper, fixture.plan(), view);
            var emptyChoices = view.choices(prepared.recipes(), PICK, "");
            var emptyValueChoices = view.choices(prepared.recipes().planningInput(), PICK, "");
            helper.assertTrue(emptyChoices.candidates().isEmpty() && !emptyChoices.truncated()
                            && emptyValueChoices.candidates().isEmpty() && !emptyValueChoices.truncated(),
                    "empty display path offered recipe candidates");
            var rootChoices = view.choices(prepared.recipes(), PICK, "0");
            helper.assertTrue(rootChoices.candidates().size() == 1 && rootChoices.candidates().getFirst().recipe().equals(PICK.recipe()),
                    "empty-path fast return changed real root candidates");

            var operation = view.operations().getFirst();
            String deepest = "0" + ".8".repeat(12);
            for (String path : List.of("0", "0.0", "0.8", deepest)) {
                new PlanView.Node(path, List.of(new ItemStack(Items.OAK_LOG)), List.of(), List.of(), false, false, "");
                new PlanView.Operation(operation.recipe(), path, operation.inputs(), operation.output(),
                        operation.remainders(), operation.inputOrigins());
            }
            new PlanView.Node("0", List.of(), List.of(), List.of(), false, false, deepest);
            var viewIdentity = view.reviewIdentity();
            var planIdentity = fixture.plan().fingerprint();
            for (String path : List.of("", "1", "0.9", "0.00", "0.0suffix", "0.0.", "0\n", "0" + ".0".repeat(13))) {
                rejected(() -> new PlanView.Node(path, List.of(), List.of(), List.of(), false, false, ""),
                        "invalid node path was accepted: " + path);
                rejected(() -> new PlanView.Operation(operation.recipe(), path, operation.inputs(), operation.output(),
                        operation.remainders(), operation.inputOrigins()), "invalid operation path was accepted: " + path);
                if (!path.isEmpty()) rejected(() -> new PlanView.Node("0.0", List.of(), List.of(), List.of(), false, false, path),
                        "invalid display reference was accepted: " + path);
                var choices = view.choices(prepared.recipes(), PICK, path);
                helper.assertTrue(choices.candidates().isEmpty() && !choices.truncated(), "undisplayed invalid path offered choices");
            }
            rejected(() -> new PlanView.Node("0.0", List.of(), List.of(), List.of(), false, false, "0.0"),
                    "self display reference was accepted");
            helper.assertValueEqual(view.reviewIdentity(), viewIdentity, "path/candidate checks changed review identity");
            helper.assertValueEqual(fixture.plan().fingerprint(), planIdentity, "display checks changed actual plan identity");
        }
        // The three-step chain has no returned container. This second genuine
        // ordinary recipe checks a nonempty remainder copy, rather than adding
        // a fictitious returned stack to the successful three-step projection.
        try (var environment = new Environment(helper)) {
            var player = environment.player;
            player.getServer().getRecipeManager().replaceRecipes(List.of(recipe("returned", Items.COOKED_BEEF, 1, Items.MILK_BUCKET)));
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.MILK_BUCKET));
            var beforeInventory = inventoryIdentity(player);
            var request = CraftRequest.one(id("returned"));
            var prepared = CraftingService.prepare(player, request, EnvironmentSnapshotService.fresh(player), new SearchBudget(1_000_000_000L));
            var plan = prepared.result().plan().orElseThrow(() -> new AssertionError("returned-container fixture failed: " + prepared.result().code()));
            var view = PlanView.from(prepared.recipes(), prepared.request(), prepared.result(), prepared.delivery().drops());
            helper.assertValueEqual(M4PlanningGameTests.count(view.operations().getFirst().remainders(), Items.BUCKET), 1,
                    "actual returned bucket missing from projected operation");
            exactOperationsAndCopies(helper, plan, view);
            helper.assertValueEqual(inventoryIdentity(player), beforeInventory, "returned-stack display test mutated actual stock");
        }
        helper.succeed();
    }

    private static void exactOperationsAndCopies(GameTestHelper helper, CraftPlan plan, PlanView view) {
        helper.assertValueEqual(view.operations().size(), plan.steps().size(), "projection operation count");
        var planIdentity = plan.fingerprint();
        var viewIdentity = view.reviewIdentity();
        var nodeIdentity = nodeValues(view);
        for (int index = 0; index < plan.steps().size(); index++) {
            var step = plan.steps().get(index);
            var operation = view.operations().get(index);
            helper.assertValueEqual(operation.recipe(), step.recipe().id(), "operation recipe " + index);
            helper.assertValueEqual(operation.path(), step.path(), "operation path " + index);
            helper.assertValueEqual(CraftPlan.stackKeys(operation.inputs()), CraftPlan.stackKeys(step.inputs()), "operation inputs " + index);
            helper.assertValueEqual(CraftPlan.stackKeys(List.of(operation.output())), CraftPlan.stackKeys(List.of(step.output())), "operation output " + index);
            helper.assertValueEqual(CraftPlan.stackKeys(operation.remainders()), CraftPlan.stackKeys(step.remainders()), "operation remainders " + index);
            helper.assertValueEqual(operation.inputOrigins(), step.inputOrigins(), "operation origins " + index);
            mutateCopies(operation.inputs()); mutateCopies(operation.remainders()); operation.output().setCount(99);
            mutateCopies(step.inputs()); mutateCopies(step.remainders()); step.output().setCount(99);
            mutateOriginView(operation.inputOrigins()); mutateOriginView(step.inputOrigins());
        }
        mutateCopies(view.consumed()); mutateCopies(view.primary()); mutateCopies(view.surplus()); mutateCopies(view.drops());
        mutateCopies(plan.primary()); mutateCopies(plan.surplus());
        for (var node : view.nodes()) { mutateCopies(node.needs()); mutateCopies(node.made()); }
        helper.assertValueEqual(plan.fingerprint(), planIdentity, "getter stack mutation changed actual plan fingerprint");
        helper.assertValueEqual(view.reviewIdentity(), viewIdentity, "getter stack mutation changed displayed review identity");
        helper.assertValueEqual(nodeValues(view), nodeIdentity, "node getter mutation changed projected needs/made");
    }

    private static Object nodeValues(PlanView view) {
        return view.nodes().stream().map(node -> List.of(node.path(), CraftPlan.stackKeys(node.needs()),
                CraftPlan.stackKeys(node.made()), node.recipes(), node.explanation(), node.alternatives(), node.reference())).toList();
    }
    private static void mutateCopies(List<ItemStack> stacks) {
        for (var stack : stacks) if (!stack.isEmpty()) stack.setCount(99);
    }
    private static void mutateOriginView(List<Integer> origins) {
        if (origins.isEmpty()) return;
        try { origins.set(0, origins.getFirst() == -1 ? 0 : -1); }
        catch (UnsupportedOperationException immutable) { /* An immutable value list also protects the owner. */ }
    }
    private static void rejected(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static ResourceLocation id(String name) { return ResourceLocation.withDefaultNamespace("pre_m5_probe_" + name); }
    private static Object inventoryIdentity(ServerPlayer player) {
        return CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory()));
    }
    private static RecipeHolder<CraftingRecipe> recipe(String name, Item output, int count, Item... inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        for (var item : inputs) ingredients.add(Ingredient.of(item));
        return new RecipeHolder<>(id(name), new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output, count), ingredients));
    }
    private record Fixture(CraftPlan plan, CraftPlan.Witness witness, CraftingService.Prepared prepared) {}

    private static final class CheckpointClock implements LongSupplier {
        private final long checkpointCost;
        private long elapsed;
        CheckpointClock(long checkpointCost) { this.checkpointCost = checkpointCost; }
        @Override public long getAsLong() {
            long previous = elapsed;
            elapsed += checkpointCost;
            return previous;
        }
        long elapsed() { return elapsed; }
        long remaining() { return Math.max(0, ALLOWANCE_NANOS - elapsed); }
    }

    private static final class Environment implements AutoCloseable {
        final ServerPlayer player;
        final List<RecipeHolder<?>> original;
        final BlockPos table;
        final BlockState previousTable;
        Environment(GameTestHelper helper) {
            player = M4PlanningGameTests.player(helper);
            original = List.copyOf(player.getServer().getRecipeManager().getRecipes());
            table = player.blockPosition().north();
            previousTable = player.serverLevel().getBlockState(table);
            player.serverLevel().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));
        }
        Fixture fixture(GameTestHelper helper) {
            player.getServer().getRecipeManager().replaceRecipes(List.of(
                    recipe("planks", Items.OAK_PLANKS, 4, Items.OAK_LOG),
                    recipe("stick", Items.STICK, 4, Items.OAK_PLANKS, Items.OAK_PLANKS),
                    recipe("pickaxe", Items.DIAMOND_PICKAXE, 1, Items.DIAMOND, Items.DIAMOND, Items.DIAMOND, Items.STICK, Items.STICK),
                    recipe("unrelated", Items.TORCH, 4, Items.COAL, Items.STICK)));
            var prepared = CraftingService.prepare(player, PICK, EnvironmentSnapshotService.fresh(player), new SearchBudget(1_000_000_000L));
            var plan = prepared.result().plan().orElseThrow(() -> new AssertionError("three-step fixture failed: " + prepared.result().code()));
            helper.assertTrue(!plan.partial() && plan.steps().size() == 3, "fixture is not a complete three-step plan");
            helper.assertValueEqual(plan.steps().stream().map(s -> s.recipe().id()).collect(java.util.stream.Collectors.toSet()),
                    Set.of(id("planks"), id("stick"), id("pickaxe")), "fixture touched an unrelated recipe");
            var snapshot = CraftingSessions.refreshBrowsing(player);
            return new Fixture(plan, CraftPlan.Witness.from(plan, snapshot), prepared);
        }
        @Override public void close() {
            try { player.getServer().getRecipeManager().replaceRecipes(original); }
            finally {
                player.serverLevel().setBlock(table, previousTable, 2);
                M4PlanningGameTests.remove(player);
            }
        }
    }
}
