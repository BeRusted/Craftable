package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EndpointKind;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.planner.SearchBudget;

@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4PreviewGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fullFailureUpgradesToPartialWithoutAnotherFullSearch(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG));
        var snapshot = table(fixture(player, source));
        var id = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
        try {
            var full = CraftingService.previews(player, snapshot);
            helper.assertValueEqual(full.evaluate(id, logicalBudget(), false).code(), CraftingResultCode.MISSING_INGREDIENTS, "full-only failure");
            helper.assertValueEqual(full.stats().partialSearches(), 0L, "filter computed a partial plan");
            helper.assertValueEqual(full.stats().closureBuilds(), 1, "initial closure");
            long searches = full.stats().fullSearches();
            var rescanned = new EnvironmentSnapshot(snapshot.dimension(), snapshot.origin(), snapshot.scanSettings(),
                    snapshot.capturedGameTime() + 20, snapshot.generation() + 1, snapshot.endpoints(), snapshot.workstations());
            var diagnostic = CraftingService.previews(player, rescanned);
            helper.assertValueEqual(diagnostic.evaluate(id, logicalBudget(), true).status(), CraftingStatus.PARTIAL, "diagnostic lost useful sticks");
            helper.assertValueEqual(diagnostic.stats().fullSearches(), searches, "diagnostic repeated full search");
            helper.assertValueEqual(diagnostic.stats().partialSearches(), 1L, "diagnostic did not enter partial phase");
            helper.assertValueEqual(diagnostic.stats().closureBuilds(), 1, "scan timestamp rebuilt closure");
            helper.assertValueEqual(diagnostic.evaluate(id, logicalBudget(), false).code(), CraftingResultCode.MISSING_INGREDIENTS, "filter reused partial as full");
            helper.assertValueEqual(diagnostic.stats().fullSearches(), searches, "filter after diagnostic re-searched");
            helper.assertValueEqual(source.getItem(0).getCount(), 1, "preview consumed log");
            source.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            var changed = CraftingService.previews(player, rescanned);
            helper.assertValueEqual(changed.stats().results(), 0, "changed resources kept proof");
            changed.evaluate(id, logicalBudget(), true);
            helper.assertValueEqual(changed.stats().fullSearches(), 1L, "changed resources skipped full search");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void incompleteFullSearchCannotAuthorizePartialUpgrade(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var snapshot = table(fixture(player, new SimpleContainer(new ItemStack(Items.OAK_LOG))));
        var id = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
        try {
            var batch = CraftingService.previews(player, snapshot);
            helper.assertValueEqual(batch.evaluate(id, new SearchBudget(() -> 0L, 1, 1), false).code(),
                    CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "truncated full search");
            long count = batch.stats().fullSearches();
            batch.evaluate(id, logicalBudget(), true);
            helper.assertValueEqual(batch.stats().fullSearches(), count + 1, "unknown was used as negative proof");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 200)
    public static void wholeScopeOver256RetainsCompletedEvidenceAndOneClosure(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var snapshot = table(fixture(player, new SimpleContainer(1)));
        try {
            var ids = new org.berusted.craftable.recipe.CraftingRecipes(player, true).entries().stream()
                    .map(e -> e.id()).limit(320).toList();
            helper.assertTrue(ids.size() > 256, "scope fixture too small");
            var completed = new ArrayList<ResourceLocation>();
            var batch = CraftingService.previews(player, snapshot);
            long start = System.nanoTime();
            for (var id : ids) {
                var verdict = batch.evaluate(id, logicalBudget(), false);
                if (verdict.code() != CraftingResultCode.SEARCH_BUDGET_EXCEEDED) completed.add(id);
            }
            helper.assertTrue(completed.size() > 256, "not enough completed scope proofs");
            long initialNanos = System.nanoTime() - start;
            long searches = batch.stats().fullSearches();
            long hits = batch.stats().hits();
            start = System.nanoTime();
            for (int round = 0; round < 10; round++) {
                var next = CraftingService.previews(player, snapshot);
                for (var id : completed) next.evaluate(id, new SearchBudget(() -> 0L, 1, 1), false);
                helper.assertValueEqual(next.stats().fullSearches(), searches, "completed scope evicted/researched");
                helper.assertValueEqual(next.stats().closureBuilds(), 1, "scope rebuilt closure");
                helper.assertValueEqual(next.stats().closureAttempts(), 1, "scope restarted closure exploration");
            }
            helper.assertValueEqual(batch.stats().hits() - hits, (long) completed.size() * 10, "scope hits");
            Craftable.LOGGER.warn("M48_SCOPE recipes={} completed={} coldMs={} repeat10Ms={} fullSearches={} partialSearches={} closureBuilds={} closureAttempts={} closureMs={}",
                    ids.size(), completed.size(), initialNanos / 1e6, (System.nanoTime() - start) / 1e6,
                    searches, batch.stats().partialSearches(), batch.stats().closureBuilds(), batch.stats().closureAttempts(), batch.stats().closureNanos() / 1e6);
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void previewCapacityIsCapturedAndIncludedEvenWithoutPlayerSource(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 2));
        var snapshot = M4TransactionGameTests.snapshot(player,
                new ContainerEndpoint("source", EndpointKind.ENDER_CHEST, null, source, 0, 1));
        var id = ResourceLocation.withDefaultNamespace("stick");
        try {
            var before = CraftingService.previews(player, snapshot);
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            helper.assertValueEqual(before.evaluate(id, logicalBudget(), false).status(), CraftingStatus.CRAFTABLE,
                    "one batch observed later capacity instead of captured values");
            var after = CraftingService.previews(player, snapshot);
            helper.assertValueEqual(after.evaluate(id, logicalBudget(), false).code(), CraftingResultCode.NO_OUTPUT_SPACE,
                    "capacity omitted from identity when no player input endpoint");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void changedMenuAndPermissionsCannotReusePreviewEvidence(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 2));
        var snapshot = table(fixture(player, source));
        var id = ResourceLocation.withDefaultNamespace("stick");
        var limited = player.serverLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING);
        boolean original = limited.get();
        try {
            CraftingService.previews(player, snapshot).evaluate(id, logicalBudget(), false);
            player.containerMenu = new net.minecraft.world.inventory.CraftingMenu(10, player.getInventory());
            helper.assertValueEqual(CraftingService.previews(player, snapshot).stats().results(), 0, "new menu kept evidence");
            limited.set(true, player.getServer());
            helper.assertValueEqual(CraftingService.previews(player, snapshot).evaluate(id, logicalBudget(), false).code(),
                    CraftingResultCode.RECIPE_LOCKED, "permissions not in scope identity");
        } finally {
            limited.set(original, player.getServer());
            player.containerMenu = player.inventoryMenu;
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    private static SearchBudget logicalBudget() { return new SearchBudget(() -> 0L, 1, SearchBudget.MAX_STATES); }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fullEvidenceRequiresExactIntentAndScope(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var recipes = new org.berusted.craftable.recipe.CraftingRecipes(player, true);
            var scope = new org.berusted.craftable.planner.CraftSearch.Reachability();
            var request = org.berusted.craftable.planner.CraftRequest.one(ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
            var first = new org.berusted.craftable.planner.CraftSearch(recipes, request, List.of(), logicalBudget(), p -> null)
                    .withReachability(scope);
            helper.assertValueEqual(first.run().code(), CraftingResultCode.MISSING_INGREDIENTS, "full fixture not negative");
            helper.assertTrue(first.fullEvidence() != null, "completed full failure lost evidence");
            var altered = List.of(request.withBatches(2),
                    new org.berusted.craftable.planner.CraftRequest(request.recipe(), 1, true, true, request.policy(), java.util.Map.of()),
                    new org.berusted.craftable.planner.CraftRequest(request.recipe(), 1, true, false, request.policy(),
                            java.util.Map.of("0.4", ResourceLocation.withDefaultNamespace("stick_from_bamboo_item"))),
                    org.berusted.craftable.planner.CraftRequest.one(ResourceLocation.withDefaultNamespace("wooden_pickaxe")));
            for (var intent : altered) {
                var search = new org.berusted.craftable.planner.CraftSearch(recipes, intent, List.of(), logicalBudget(), p -> null)
                        .withReachability(scope).withFullEvidence(first.fullEvidence());
                search.run();
                helper.assertValueEqual(search.fullSearches(), 1, "mismatched intent skipped complete search: " + intent);
            }
            var newScope = new org.berusted.craftable.planner.CraftSearch(recipes, request.withPartial(true), List.of(), logicalBudget(), p -> null)
                    .withReachability(new org.berusted.craftable.planner.CraftSearch.Reachability()).withFullEvidence(first.fullEvidence());
            newScope.run();
            helper.assertValueEqual(newScope.fullSearches(), 1, "old scope authorized a new resource view");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static EnvironmentSnapshot table(EnvironmentSnapshot snapshot) {
        return new EnvironmentSnapshot(snapshot.dimension(), snapshot.origin(), snapshot.scanSettings(),
                snapshot.capturedGameTime(), snapshot.generation(), snapshot.endpoints(),
                List.of(new org.berusted.craftable.workstation.WorkstationEndpoint("table",
                        org.berusted.craftable.workstation.WorkstationCapability.CRAFTING_3X3, null)));
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void progressiveMaximumStopsWithinItsWholeQuerySliceLimit(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.BIRCH_LOG));
            player.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 2));
            var request = org.berusted.craftable.planner.CraftRequest.one(ResourceLocation.withDefaultNamespace("stick"));
            CraftingService.Maximum result = null;
            for (int i = 0; i <= SearchBudget.MAX_MAXIMUM_SLICES; i++) {
                result = CraftingService.maximum(player, request);
                if (!result.pending()) break;
            }
            helper.assertTrue(result != null && !result.pending(), "MAX exceeded whole-query bound");
            helper.assertTrue(result.lowerBound() <= 6, "MAX double-counted mixed stock");
            if (result.proven()) helper.assertValueEqual(result.lowerBound(), 6, "incorrect proven MAX");
            else helper.assertTrue(result.limited(), "unproven MAX concealed truncation");
            helper.assertValueEqual(player.getInventory().countItem(Items.BIRCH_LOG), 1, "MAX consumed birch");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 2, "MAX consumed oak");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void coldRetryBorrowsOnlyRemainingPageAllowance(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 2));
        var snapshot = fixture(player, source);
        var id = ResourceLocation.withDefaultNamespace("stick");
        try {
            var batch = CraftingService.previews(player, snapshot);
            helper.assertValueEqual(batch.allowance(id, 8_000_000L), 1_000_000L, "initial slice");
            var clock = new java.util.concurrent.atomic.AtomicLong();
            batch.evaluate(id, new SearchBudget(clock::getAndIncrement, 1, 2048));
            helper.assertValueEqual(batch.allowance(id, 8_000_000L), 1L, "backoff should only read cached metadata");
            source.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
            helper.assertValueEqual(CraftingService.previews(player, snapshot).allowance(id, 8_000_000L),
                    1_000_000L, "changed identity retained retry penalty");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void sameGenerationChangedStacksAndCapacityInvalidateVerdicts(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 2));
        var snapshot = fixture(player, source);
        var id = ResourceLocation.withDefaultNamespace("stick");
        try {
            var first = CraftingService.previews(player, snapshot).evaluate(id, new SearchBudget(1_000_000_000L));
            helper.assertValueEqual(first.status(), CraftingStatus.CRAFTABLE, "initial verdict");
            source.setItem(0, ItemStack.EMPTY);
            var second = CraftingService.previews(player, snapshot).evaluate(id, new SearchBudget(1_000_000_000L));
            helper.assertValueEqual(second.status(), CraftingStatus.BLOCKED, "changed contents with identical generation");
            source.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            var third = CraftingService.previews(player, snapshot).evaluate(id, new SearchBudget(1_000_000_000L));
            helper.assertValueEqual(third.code(), CraftingResultCode.NO_OUTPUT_SPACE, "capacity absent from cache identity");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            var fourth = CraftingService.previews(player, snapshot).evaluate(id, new SearchBudget(1_000_000_000L));
            helper.assertValueEqual(fourth.status(), CraftingStatus.CRAFTABLE, "new free slot did not invalidate cache");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 300)
    public static void boundedPageCostIsRecordedForOneSixteenAndSixtyFourContainers(GameTestHelper helper) {
        var players = List.of(M4PlanningGameTests.player(helper), M4PlanningGameTests.player(helper));
        var ids = List.of("stick", "oak_planks", "birch_planks", "diamond_pickaxe", "diamond_sword", "diamond_axe",
                "diamond_hoe", "diamond_shovel", "iron_pickaxe", "iron_sword", "iron_axe", "iron_hoe", "iron_shovel",
                "golden_pickaxe", "golden_sword", "golden_axe", "golden_hoe", "golden_shovel", "wooden_pickaxe",
                "wooden_sword", "wooden_axe", "wooden_hoe", "wooden_shovel", "chest", "crafting_table",
                "oak_door", "oak_trapdoor", "oak_fence", "oak_fence_gate", "ladder", "bowl", "barrel")
                .stream().map(ResourceLocation::withDefaultNamespace).toList();
        try {
            for (int containers : new int[]{1, 16, 64}) {
                var storage = new ArrayList<ContainerEndpoint>();
                for (int i = 0; i < containers; i++) {
                    var box = new SimpleContainer(27);
                    for (int slot = 0; slot < 27; slot++) {
                        var item = switch (slot % 6) {
                            case 0 -> Items.OAK_LOG; case 1 -> Items.DIAMOND; case 2 -> Items.IRON_INGOT;
                            case 3 -> Items.GOLD_INGOT; case 4 -> Items.OAK_PLANKS; default -> Items.STICK;
                        };
                        box.setItem(slot, new ItemStack(item, 64));
                    }
                    storage.add(new ContainerEndpoint("bench:" + i, EndpointKind.ENDER_CHEST, null, box, 0, 27));
                }
                for (int users = 1; users <= 2; users++) {
                    players.forEach(p -> CraftingSessions.clear(p.getUUID()));
                    long[] samples = new long[30];
                    int attempted = 0, known = 0, omitted = 0;
                    for (int sample = 0; sample < samples.length; sample++) {
                        long frameStart = System.nanoTime();
                        for (int u = 0; u < users; u++) {
                            var player = players.get(u);
                            var endpoints = new ArrayList<>(storage);
                            endpoints.addFirst(new ContainerEndpoint("player", EndpointKind.PLAYER, null, player.getInventory(), 0, 36));
                            var snapshot = M4TransactionGameTests.snapshot(player, endpoints.toArray(ContainerEndpoint[]::new));
                            snapshot = new EnvironmentSnapshot(snapshot.dimension(), snapshot.origin(), snapshot.scanSettings(),
                                    snapshot.capturedGameTime(), snapshot.generation(), snapshot.endpoints(),
                                    List.of(new org.berusted.craftable.workstation.WorkstationEndpoint("table",
                                            org.berusted.craftable.workstation.WorkstationCapability.CRAFTING_3X3, null)));
                            long deadline = System.nanoTime() + 8_000_000L;
                            var batch = CraftingService.previews(player, snapshot);
                            for (int index = 0; index < ids.size(); index++) {
                                long remaining = deadline - System.nanoTime();
                                if (remaining <= 0) { omitted += ids.size() - index; break; }
                                var result = batch.evaluate(ids.get(index), new SearchBudget(batch.allowance(ids.get(index), remaining)));
                                attempted++;
                                if (result.code() != CraftingResultCode.SEARCH_BUDGET_EXCEEDED) known++;
                            }
                        }
                        samples[sample] = System.nanoTime() - frameStart;
                    }
                    java.util.Arrays.sort(samples);
                    Craftable.LOGGER.warn("M4_PAGE containers={} users={} samples=30 p50={}ms p95={}ms max={}ms known={}/{} omitted={}",
                            containers, users, samples[14]/1e6, samples[28]/1e6, samples[29]/1e6, known, attempted, omitted);
                }
            }
        } finally { players.forEach(M4PlanningGameTests::remove); }
        helper.succeed();
    }

    private static EnvironmentSnapshot fixture(ServerPlayer player, SimpleContainer source) {
        return M4TransactionGameTests.snapshot(player,
                new ContainerEndpoint("player", EndpointKind.PLAYER, null, player.getInventory(), 0, 36),
                new ContainerEndpoint("source", EndpointKind.ENDER_CHEST, null, source, 0, source.getContainerSize()));
    }
}
