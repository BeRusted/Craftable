package org.berusted.craftable.execution;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.network.CraftingDetailPayloads;
import org.berusted.craftable.network.CraftingWire;
import org.berusted.craftable.planner.*;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Configured world limits through the real snapshot/review/transaction path.
 * Frozen deadlines prove accounting; recorded timings are observations only. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreM5RulesGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void configured256StepsAllowA192StepWitnessAndAtomicCommit(GameTestHelper helper) {
        var limit = stepLimit(); int old = limit.get();
        try (var fixture = new World(helper)) {
            limit.set(256);
            var player = fixture.player;
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            player.getInventory().setItem(1, new ItemStack(Items.BIRCH_LOG, 64));
            var snapshot = CraftingSessions.refreshBrowsing(player);
            helper.assertValueEqual(snapshot.maxPlanSteps(), 256, "world rule absent from browsing values");
            var encoded = CraftingWire.snapshot(snapshot, player.registryAccess());
            var decoded = CraftingWire.snapshot(encoded, player.registryAccess());
            helper.assertValueEqual(decoded.contentIdentity(), snapshot.contentIdentity(), "step rule lost on snapshot wire");
            var recipes = new CraftingRecipes(player, true);
            var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("chest")).withBatches(64);
            long started = System.nanoTime();
            var result = new CraftSearch(recipes, request, snapshot.sources(), logical(), snapshot.maxPlanSteps(),
                    plan -> MainInventoryInsertion.simulate(snapshot.inventory(), snapshot.inventoryMaximum(), plan,
                            snapshot.playerReferences(), snapshot.references(), false).failure()).run();
            long searchNanos = System.nanoTime() - started;
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "256 rule failed a real 192-step route");
            var plan = result.plan().orElseThrow();
            helper.assertValueEqual(plan.steps().size(), 192, "bulk route compressed actual operations");
            var witness = CraftPlan.Witness.from(plan, decoded);
            var message = new CraftingDetailPayloads.PreviewRequest(player.containerMenu.containerId, 1, request, "", witness);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
            int witnessBytes;
            try {
                CraftingDetailPayloads.PreviewRequest.CODEC.encode(buffer, message);
                witnessBytes = buffer.readableBytes();
                helper.assertTrue(witnessBytes <= 65_536, "ordinary 192-step witness exceeded unchanged frame bound");
                witness = CraftingDetailPayloads.PreviewRequest.CODEC.decode(buffer).witness();
            } finally { buffer.release(); }
            long validations = CraftingService.witnessValidations, fullSearches = CraftingService.activeFullSearches;
            started = System.nanoTime();
            var draft = CraftingService.preview(player, request, "", witness, 1, logical(), null);
            long reviewNanos = System.nanoTime() - started;
            helper.assertValueEqual(draft.view().code(), CraftingResultCode.CREATED, "configured bulk review failed");
            helper.assertTrue(draft.view().complete() && !draft.token().equals(CraftingDetailPayloads.NO_TOKEN), "incomplete bulk review authorized");
            helper.assertTrue(CraftingDetailPayloads.boundedLocal(draft, player.registryAccess()) == draft,
                    "bulk complete display exceeded unchanged 64KiB bound");
            started = System.nanoTime();
            var outcome = CraftingService.confirm(player, draft.token(), witness, 2, logical(), null);
            long confirmNanos = System.nanoTime() - started;
            helper.assertValueEqual(outcome.code(), CraftingResultCode.CREATED, "192-step atomic witness commit failed");
            helper.assertValueEqual(CraftingService.activeFullSearches, fullSearches, "bulk witness initiated alternative search");
            helper.assertValueEqual(CraftingService.witnessValidations, validations + 2, "review and confirm did not freshly revalidate");
            helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 64, "bulk output quantity");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG) + player.getInventory().countItem(Items.BIRCH_LOG), 0, "bulk duplicated input");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS) + player.getInventory().countItem(Items.BIRCH_PLANKS), 0, "bulk leaked intermediate output");
            helper.assertValueEqual(CraftingService.confirm(player, draft.token(), witness, 3, logical(), null).code(),
                    CraftingResultCode.CONFIRMATION_EXPIRED, "192-step confirmation replayed");
            Craftable.LOGGER.info("PreM5 configured bulk: steps={} states={} witnessBytes={} searchNanos={} reviewNanos={} confirmNanos={} (functional frozen deadline; confirmation includes commit/notifications)",
                    plan.steps().size(), result.visitedStates(), witnessBytes, searchNanos, reviewNanos, confirmNanos);
        } finally { limit.set(old); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void tighteningAndRaisingRulesInvalidateDynamicGrantsButNotStaticRecipes(GameTestHelper helper) {
        var limit = stepLimit(); int old = limit.get();
        try (var fixture = new World(helper)) {
            var player = fixture.player;
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            limit.set(128);
            var first = CraftingSessions.refreshBrowsing(player);
            var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("chest"));
            var recipes = new CraftingRecipes(player, true);
            var result = new CraftSearch(recipes, request, first.sources(), logical(), first.maxPlanSteps(), plan -> null).run();
            var witness = CraftPlan.Witness.from(result.plan().orElseThrow(), first);
            var prepared = CraftingService.prepareWitness(player, request, EnvironmentSnapshotService.fresh(player), witness, logical());
            var token = CraftingSessions.offer(player, prepared, true);
            var before = CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory()));
            limit.set(1);
            helper.assertValueEqual(CraftingService.confirm(player, token, witness, 1, logical(), null).code(),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "old offer survived a tighter rule");
            var smaller = CraftingSessions.refreshBrowsing(player);
            helper.assertTrue(smaller.resources() > first.resources() && smaller.recipes() == first.recipes(),
                    "rule reload did not isolate dynamic evidence from static generation");
            boolean rejected = false;
            try { CraftingService.prepareWitness(player, request, EnvironmentSnapshotService.fresh(player), witness, logical()); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "old grant/witness survived step-rule change");
            var unknown = CraftingService.create(player, request.withPartial(true), false, logical());
            helper.assertValueEqual(unknown.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "step limit became partial authorization");
            helper.assertTrue(unknown.plan() == null, "truncated world plan was executable");
            limit.set(256);
            var larger = CraftingSessions.refreshBrowsing(player);
            helper.assertTrue(larger.resources() > smaller.resources() && larger.recipes() == first.recipes(), "raising rule reused old mathematics");
            helper.assertValueEqual(larger.inputs().getFirst().reference(), first.inputs().getFirst().reference(), "rule change renumbered real source binding");
            helper.assertValueEqual(CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory())), before, "rule test consumed inputs");
        } finally { limit.set(old); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void validatorUsesEffectiveLimitBeforeAnyReplayStateOrConsumption(GameTestHelper helper) {
        try (var fixture = new World(helper)) {
            var player = fixture.player;
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var recipes = new CraftingRecipes(player, true);
            var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("chest"));
            var result = new CraftSearch(recipes, request, snapshot.sources(), logical(), plan -> null).run();
            var witness = CraftPlan.Witness.from(result.plan().orElseThrow(), snapshot);
            var budget = logical(); boolean rejected = false;
            try { CraftWitnessValidator.validate(recipes, request, witness, snapshot.sources(), budget, 1); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected && budget.states() == 0, "hard-ceiling witness escaped a smaller effective rule");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 2, "rejected witness consumed inputs");
            helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 0, "rejected witness manufactured output");
        }
        helper.succeed();
    }

    private static ModConfigSpec.IntValue stepLimit() { return CraftableServerConfig.SPEC.getValues().get("crafting.maxPlanSteps"); }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }

    private static final class World implements AutoCloseable {
        final ServerPlayer player;
        final BlockPos table;
        final BlockState previous;
        World(GameTestHelper helper) {
            player = M4PlanningGameTests.player(helper); table = player.blockPosition().north();
            previous = player.serverLevel().getBlockState(table);
            player.serverLevel().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
        }
        @Override public void close() {
            player.serverLevel().setBlock(table, previous, 2); M4PlanningGameTests.remove(player);
        }
    }
}
