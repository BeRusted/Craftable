package org.berusted.craftable.execution;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.CraftingRecipes;

@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4AdmissionGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final CraftRequest STICKS = CraftRequest.one(ResourceLocation.withDefaultNamespace("stick"));

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void plainCreateKeepsProvenShortageWhenReadOnlyDiagnosisExhausts(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS));
            var proved = CraftingService.prepare(player, STICKS, EnvironmentSnapshotService.fresh(player), logical());
            helper.assertTrue(proved.fullMissing(), "fixture did not prove full exclusion");
            var unknown = CraftingService.prepare(player, STICKS, EnvironmentSnapshotService.fresh(player),
                    new SearchBudget(() -> 0, 1, 1));
            helper.assertTrue(unknown.fullMissing(), "diagnosis erased server-only full evidence");
            helper.assertValueEqual(unknown.result().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                    "unfinished details concealed uncertainty");
            var full = CraftingService.create(player, STICKS, false, new SearchBudget(() -> 0, 1, 1));
            helper.assertValueEqual(full.code(), CraftingResultCode.MISSING_INGREDIENTS, "plain C lost proven shortage");
            helper.assertTrue(full.plan() == null && full.missing().isEmpty(), "unfinished diagnosis became an executable plan/count");
            var partial = CraftingService.create(player, STICKS.withPartial(true), true, new SearchBudget(() -> 0, 1, 1));
            helper.assertValueEqual(partial.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                    "full exclusion authorized unfinished partial work");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 1, "shortage consumed stock");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "shortage manufactured output");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void activeEntriesDoNotRefillAdmissionAfterFreshDiscovery(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            long searches = CraftingService.activeFullSearches;
            var observed = new AtomicLong();
            long before = EnvironmentSnapshotService.fresh(player).generation();
            var preview = CraftingService.preview(player, STICKS, "", null, -1, exhaustedAfterScan(player, observed));
            helper.assertValueEqual(preview.view().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "preview refilled admission");
            helper.assertValueEqual(preview.token(), new UUID(0, 0), "exhausted preview issued an offer");
            helper.assertTrue(observed.get() > before, "preview did not fresh-scan before reading its remaining allowance");

            before = EnvironmentSnapshotService.fresh(player).generation();
            var attempt = CraftingService.attempt(player, STICKS.recipe(), 100, -1, false, STICKS.policy(),
                    exhaustedAfterScan(player, observed));
            helper.assertValueEqual(attempt.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "C refilled admission");
            helper.assertTrue(observed.get() > before, "C did not fresh-scan before reading its remaining allowance");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches, "exhausted preview/C began a search");

            var world = EnvironmentSnapshotService.fresh(player);
            var prepared = CraftingService.prepare(player, STICKS, world, logical());
            helper.assertTrue(prepared.result().plan().isPresent(), "confirmation fixture has no plan");
            var token = CraftingSessions.offer(player, prepared);
            searches = CraftingService.activeFullSearches;
            var confirmed = CraftingService.confirm(player, token, null, -1, exhaustedAfterScan(player, observed));
            helper.assertValueEqual(confirmed.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "confirmation refilled admission");
            helper.assertTrue(observed.get() > world.generation(), "confirmation did not fresh-scan");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches, "exhausted confirmation began a search");
            helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.CONFIRMATION_EXPIRED,
                    "exhausted confirmation token was replayable");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "exhausted entry consumed ingredients");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "exhausted entry delivered output");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void witnessValidationCannotStartAfterDiscoveryExhaustsAdmission(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            var browsing = CraftingSessions.refreshBrowsing(player);
            var plan = new CraftSearch(new CraftingRecipes(player, browsing.workbench()), STICKS, browsing.sources(),
                    logical(), ignored -> null).run().plan().orElseThrow();
            var witness = CraftPlan.Witness.from(plan, browsing);
            var world = EnvironmentSnapshotService.fresh(player);
            var prepared = CraftingService.prepareWitness(player, STICKS, world, witness, logical());
            var token = CraftingSessions.offer(player, prepared, true);
            long validations = CraftingService.witnessValidations;
            long searches = CraftingService.activeFullSearches;
            var observed = new AtomicLong();
            var result = CraftingService.confirm(player, token, witness, 100, exhaustedAfterScan(player, observed));
            helper.assertValueEqual(result.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "witness received a new validation deadline");
            helper.assertTrue(observed.get() > world.generation(), "witness confirmation did not fresh-scan");
            helper.assertValueEqual(CraftingService.witnessValidations, validations, "exhausted witness began validation");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches, "exhausted witness fell back to search");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "exhausted witness consumed ingredients");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "exhausted witness delivered output");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static LongSupplier exhaustedAfterScan(ServerPlayer player, AtomicLong observedGeneration) {
        var reads = new AtomicInteger();
        return () -> {
            if (reads.getAndIncrement() == 0) return 8_000_000L;
            observedGeneration.set(EnvironmentSnapshotService.preview(player).generation());
            return 0;
        };
    }

    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }
}
