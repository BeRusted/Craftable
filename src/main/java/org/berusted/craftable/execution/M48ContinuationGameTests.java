package org.berusted.craftable.execution;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.*;
import org.berusted.craftable.recipe.CraftingRecipes;

@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M48ContinuationGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static CraftRequest pick() { return CraftRequest.one(ResourceLocation.withDefaultNamespace("diamond_pickaxe")); }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void closureExclusionFeedsDiagnosticWithoutRepeatingFullSearch(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var catalog = new CraftingRecipes(player, true).planningInput();
            var sources = List.of(new ResourceLedger.Source("fixture", 0, new ItemStack(Items.OAK_LOG)));
            var closure = new CraftSearch.Reachability();
            new CraftSearch(catalog, pick(), sources, logical(), plan -> null).withReachability(closure).run();
            var proof = closure.excludes(catalog, pick());
            helper.assertTrue(proof != null, "missing diamond must be excluded by a complete ordinary closure");
            var diagnostic = new CraftSearch(catalog, pick().withPartial(true), sources, logical(), plan -> null)
                    .withReachability(closure).withFullEvidence(proof).withDiagnosticBudget(logical());
            var result = diagnostic.run();
            helper.assertTrue(result.plan().isPresent() && result.plan().orElseThrow().partial(), "lost partial preparation");
            helper.assertValueEqual(diagnostic.fullSearches(), 0, "diagnostic repeated full exclusion");
            helper.assertValueEqual(closure.builds(), 1, "diagnostic rebuilt closure");
            // Presence is only an exclusion tool: a log reaches planks/sticks,
            // but does not prove that one log suffices for a wooden pickaxe.
            var wooden = CraftRequest.one(ResourceLocation.withDefaultNamespace("wooden_pickaxe"));
            helper.assertTrue(closure.excludes(catalog, wooden) == null, "quantity claim escaped coarse closure");
            var exact = new CraftSearch(catalog, wooden, sources, logical(), plan -> null).withReachability(closure).run();
            helper.assertTrue(exact.plan().isEmpty(), "one log was counted twice");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void freshAuthorizationDoesNotRenumberUnchangedResources(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            var first = CraftingSessions.refreshBrowsing(player);
            for (int check = 0; check < 100; check++) {
                var renewed = CraftingSessions.refreshBrowsing(player);
                helper.assertTrue(first == renewed, "unchanged authorization rebuilt resource snapshot");
                helper.assertValueEqual(CraftingSessions.browsingLease(player), player.level().getGameTime() + 10,
                        "authorization did not renew independently");
            }
            var reference = first.inputs().stream().filter(input -> input.inventorySlot() == 0).findFirst().orElseThrow().reference();
            player.getInventory().getItem(0).grow(1);
            var changed = CraftingSessions.refreshBrowsing(player);
            helper.assertTrue(changed.resources() > first.resources(), "quantity change did not update version");
            helper.assertValueEqual(changed.inputs().stream().filter(input -> input.inventorySlot() == 0).findFirst().orElseThrow().reference(),
                    reference, "same slot changed reference after quantity change");
            player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 64));
            helper.assertTrue(CraftingSessions.refreshBrowsing(player).resources() > changed.resources(), "capacity change lost");
            CraftingSessions.closeBrowsing(player);
            var reopened = CraftingSessions.refreshBrowsing(player);
            helper.assertFalse(reopened.session().equals(first.session()), "menu reopening reused revoked session");
            helper.assertValueEqual(reopened.recipes(), first.recipes(), "menu reopening changed the static recipe generation");
            helper.assertFalse(reopened.inputs().stream().anyMatch(input -> input.reference().equals(reference)), "revoked reference reused");
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            boolean rejected = false;
            try { CraftingSessions.refreshBrowsing(player); }
            catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected, "creative mode captured browsing resources");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void doubleChestWrappersKeepReferencesButReplacementAndOcclusionRevoke(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var level = player.serverLevel();
        var left = player.blockPosition().east();
        var right = left.east();
        var previousLeft = level.getBlockState(left);
        var previousRight = level.getBlockState(right);
        var previousAbove = level.getBlockState(left.above());
        var leftState = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH)
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        var rightState = leftState.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
        try {
            level.setBlock(left, leftState, 2);
            level.setBlock(right, rightState, 2);
            level.setBlock(left.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            ((net.minecraft.world.Container) level.getBlockEntity(left)).setItem(0, new ItemStack(Items.OAK_LOG));
            var first = CraftingSessions.refreshBrowsing(player);
            var reference = first.inputs().stream().filter(input -> input.inventorySlot() == -1 && input.stack().is(Items.OAK_LOG))
                    .findFirst().orElseThrow().reference();
            for (int check = 0; check < 10; check++) helper.assertTrue(first == CraftingSessions.refreshBrowsing(player),
                    "new CompoundContainer wrapper changed stable identity");
            level.setBlock(left, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(left, leftState, 2);
            ((net.minecraft.world.Container) level.getBlockEntity(left)).setItem(0, new ItemStack(Items.OAK_LOG));
            var replaced = CraftingSessions.refreshBrowsing(player);
            helper.assertTrue(replaced.resources() > first.resources(), "same contents in replacement endpoint did not revoke");
            helper.assertFalse(replaced.inputs().stream().anyMatch(input -> input.reference().equals(reference)), "replaced chest kept reference");
            level.setBlock(left.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            var occluded = CraftingSessions.refreshBrowsing(player);
            helper.assertTrue(occluded.resources() > replaced.resources(), "occlusion did not change resources");
            helper.assertFalse(occluded.inputs().stream().anyMatch(input -> input.stack().is(Items.OAK_LOG)), "occluded chest leaked resource");
            level.setBlock(left.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            var restored = CraftingSessions.refreshBrowsing(player);
            var revoked = replaced.inputs().stream().map(org.berusted.craftable.environment.BrowsingSnapshot.Input::reference).toList();
            helper.assertFalse(restored.inputs().stream().anyMatch(input -> revoked.contains(input.reference()) && input.inventorySlot() == -1),
                    "regrant reused revoked external references");
        } finally {
            level.setBlock(left, previousLeft, 2); level.setBlock(right, previousRight, 2);
            level.setBlock(left.above(), previousAbove, 2);
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void browsingValuesRoundTripAndRenewalPreservesContent(GameTestHelper helper) {
        var inventory = new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(36, ItemStack.EMPTY));
        inventory.set(0, new ItemStack(Items.OAK_LOG));
        var named = new ItemStack(Items.DIAMOND, 3);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Own protected stack"));
        inventory.set(1, named);
        var sources = List.of(new org.berusted.craftable.environment.BrowsingSnapshot.Input(java.util.UUID.randomUUID(), 0, inventory.get(0)),
                new org.berusted.craftable.environment.BrowsingSnapshot.Input(java.util.UUID.randomUUID(), -1, new ItemStack(Items.DIAMOND, 3)));
        var value = browsing(inventory, sources, 1);
        var bytes = org.berusted.craftable.network.CraftingWire.snapshot(value, helper.getLevel().registryAccess());
        var decoded = org.berusted.craftable.network.CraftingWire.snapshot(bytes, helper.getLevel().registryAccess());
        helper.assertValueEqual(decoded.contentIdentity(), value.contentIdentity(), "snapshot codec changed values");
        helper.assertValueEqual(decoded.session(), value.session(), "snapshot session changed");
        try (var sliced = new org.berusted.craftable.network.CraftingWire.SnapshotDecoder(bytes, helper.getLevel().registryAccess())) {
            int calls = 0;
            while (!sliced.advance(1) && ++calls < 5000) { }
            helper.assertValueEqual(sliced.result().contentIdentity(), value.contentIdentity(), "sliced decode changed values");
            helper.assertTrue(calls > 1 && calls < 5000, "decoder did not retain bounded progress");
        }
        helper.assertValueEqual(browsing(inventory, sources, 2).contentIdentity(), value.contentIdentity(),
                "transport/version metadata changed mathematical content");
        inventory.get(0).setCount(5);
        named.setCount(1);
        decoded.inventory().get(0).setCount(7);
        decoded.inputs().getFirst().stack().setCount(9);
        helper.assertValueEqual(decoded.contentIdentity(), value.contentIdentity(), "snapshot leaked mutable stacks");
        boolean protectedRejected = false;
        try { new org.berusted.craftable.environment.BrowsingSnapshot.Input(java.util.UUID.randomUUID(), -1, named); }
        catch (IllegalArgumentException expected) { protectedRejected = true; }
        helper.assertTrue(protectedRejected, "protected external components entered source projection");
        boolean trailingRejected = false;
        try { org.berusted.craftable.network.CraftingWire.snapshot(java.util.Arrays.copyOf(bytes, bytes.length + 1), helper.getLevel().registryAccess()); }
        catch (IllegalArgumentException expected) { trailingRejected = true; }
        helper.assertTrue(trailingRejected, "trailing snapshot bytes accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void browsingCapacityUsesTheSamePlayerVersusExternalArithmetic(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var inventory = new java.util.ArrayList<ItemStack>();
            for (int slot = 0; slot < 36; slot++) inventory.add(new ItemStack(Items.COBBLESTONE, 64));
            inventory.set(0, new ItemStack(Items.OAK_PLANKS, 2));
            var recipe = new CraftingRecipes(player, true);
            for (int sourceSlot : new int[]{0, -1}) {
                var values = browsing(inventory, List.of(new org.berusted.craftable.environment.BrowsingSnapshot.Input(
                        java.util.UUID.randomUUID(), sourceSlot, new ItemStack(Items.OAK_PLANKS, 2))), 1);
                var result = new CraftSearch(recipe.planningInput(), CraftRequest.one(ResourceLocation.withDefaultNamespace("stick")),
                        values.sources(), logical(), plan -> MainInventoryInsertion.simulate(values.inventory(), values.inventoryMaximum(),
                                plan, values.playerReferences(), values.references(), false).failure()).run();
                helper.assertTrue(sourceSlot == 0 ? result.code() == CraftingResultCode.CREATED
                        : result.code() == CraftingResultCode.NO_OUTPUT_SPACE, "source provenance changed delivery capacity");
            }
        } finally { player.getServer().getPlayerList().remove(player); }
        helper.succeed();
    }

    private static org.berusted.craftable.environment.BrowsingSnapshot browsing(List<ItemStack> inventory,
            List<org.berusted.craftable.environment.BrowsingSnapshot.Input> inputs, long version) {
        return new org.berusted.craftable.environment.BrowsingSnapshot(new java.util.UUID(1, 2), 1, version, true,
                false, java.util.Set.of(), false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, 64, 64, inventory, inputs);
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void manySlicesKeepOneRootAndExactlyTheSynchronousPlan(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var recipes = new CraftingRecipes(player, true);
            var sources = List.of(new ResourceLedger.Source("a", 0, new ItemStack(Items.OAK_LOG)),
                    new ResourceLedger.Source("a", 1, new ItemStack(Items.DIAMOND, 3)));
            var expected = new CraftSearch(recipes, pick(), sources, logical(), p -> null).run();
            var clock = new AtomicLong();
            var allowance = SearchBudget.resumable(clock::getAndIncrement, 1_000_000, 2048);
            var scope = new CraftSearch.Reachability();
            var sliced = new CraftSearch(recipes.planningInput(), pick(), sources, allowance, p -> null).withReachability(scope);
            java.util.Optional<SearchResult> actual = java.util.Optional.empty();
            int slices = 0, lastStates = 0;
            long lastRemaining = allowance.remainingNanos();
            while (actual.isEmpty() && slices++ < 10_000) {
                actual = sliced.advance(64);
                helper.assertTrue(allowance.states() >= lastStates && allowance.remainingNanos() <= lastRemaining, "slice reset allowance");
                lastStates = allowance.states(); lastRemaining = allowance.remainingNanos();
                clock.addAndGet(10_000_000); // Long idle intervals are not active CPU.
            }
            helper.assertTrue(slices > 10 && actual.isPresent(), "continuation fixture did not slice/finish");
            helper.assertValueEqual(actual.get().plan().orElseThrow().fingerprint(), expected.plan().orElseThrow().fingerprint(), "sliced plan drift");
            helper.assertValueEqual(actual.get().visitedStates(), expected.visitedStates(), "replayed state exploration");
            helper.assertValueEqual(sliced.initializations(), 1, "root restarted");
            helper.assertValueEqual(scope.attempts(), 1, "closure restarted after pause");
            helper.assertTrue(recipes.validate(actual.get().plan().orElseThrow()), "pure plan failed authoritative recipe replay");
            Craftable.LOGGER.info("M48_CONTINUATION slices={} states={} rootInitializations=1 closureAttempts=1", slices, actual.get().visitedStates());
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void unsupportedProducerCannotBecomeNegativeEvidence(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = player.getServer().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            // Same serializer does not establish ordinary semantics: a subclass
            // may add arbitrary world-dependent matching or output behavior.
            var opaque = new net.minecraft.world.item.crafting.ShapelessRecipe("",
                    net.minecraft.world.item.crafting.CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                    net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,
                            net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_LOG))) {};
            var all = new java.util.ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(original);
            all.add(new net.minecraft.world.item.crafting.RecipeHolder<>(ResourceLocation.withDefaultNamespace("m48_opaque_diamond"), opaque));
            manager.replaceRecipes(all);
            var search = new CraftSearch(new CraftingRecipes(player, true), pick().withPartial(true),
                    List.of(new ResourceLedger.Source("a", 0, new ItemStack(Items.OAK_LOG))), logical(), p -> null);
            var result = search.run();
            helper.assertValueEqual(result.code(), CraftingResultCode.UNSUPPORTED_RECIPE, "omitted producer misreported as material deficit");
            helper.assertTrue(!result.completeSearch() && result.plan().isEmpty() && search.fullEvidence() == null,
                    "unsupported production authorized partial preparation");
        } finally { manager.replaceRecipes(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void largeRetainedStateStopsWithoutPartialAuthorization(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var sources = new java.util.ArrayList<ResourceLedger.Source>();
            for (int i = 0; i < 16383; i++) sources.add(new ResourceLedger.Source("a", i, new ItemStack(Items.OAK_LOG)));
            sources.add(new ResourceLedger.Source("a", 16383, new ItemStack(Items.DIAMOND, 3)));
            var search = new CraftSearch(new CraftingRecipes(player, true), pick().withPartial(true), sources, logical(), p -> null);
            var result = search.run();
            helper.assertValueEqual(result.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "retained-state bound not enforced");
            helper.assertTrue(!result.completeSearch() && result.plan().isEmpty() && search.fullEvidence() == null,
                    "memory truncation authorized partial crafting");
            helper.assertValueEqual(search.partialSearches(), 0, "memory exhaustion entered partial phase");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void frozenOrdinaryCatalogMatchesAuthoritativeAssembly(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var recipes = new CraftingRecipes(player, true);
            var input = recipes.planningInput();
            int checked = 0;
            for (var entry : input.entries()) {
                if (input.unavailable(entry) != null) continue;
                var grid = new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(entry.gridSize() * entry.gridSize(), ItemStack.EMPTY));
                for (var requirement : entry.requirements())
                    grid.set(requirement.slot(), requirement.ingredient().getItems()[0].copyWithCount(1));
                var predicted = input.assemble(entry, "0", grid);
                var actual = recipes.assemble(entry, "0", grid);
                helper.assertTrue(predicted != null && actual != null, "ordinary assembly rejected: " + entry.id());
                helper.assertTrue(ItemStack.matches(predicted.output(), actual.output()), "output mismatch: " + entry.id());
                helper.assertValueEqual(CraftPlan.stackKeys(predicted.remainders()), CraftPlan.stackKeys(actual.remainders()), "remainder mismatch: " + entry.id());
                checked++;
            }
            helper.assertTrue(checked > 800, "catalog differential was not broad enough");
            Craftable.LOGGER.info("M48_VALUE_CATALOG checked={} output/remainder agreement", checked);
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void exhaustionAndCancellationNeverRestartOrBorrowDiagnosis(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var recipes = new CraftingRecipes(player, true);
            var full = SearchBudget.resumable(() -> 0, 100, 1);
            var diagnostic = SearchBudget.resumable(() -> 0, 100, 2048);
            var search = new CraftSearch(recipes, pick().withPartial(true), List.of(), full, p -> null).withDiagnosticBudget(diagnostic);
            var result = search.run();
            helper.assertValueEqual(result.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "exhaustion fabricated absence");
            for (int i = 0; i < 100; i++) helper.assertValueEqual(search.advance(1).orElseThrow(), result, "completed exhaustion restarted");
            helper.assertValueEqual(search.initializations(), 1, "unknown retried its root");
            helper.assertValueEqual(search.partialSearches(), 0, "diagnosis borrowed for full search");
            helper.assertValueEqual(diagnostic.states(), 0, "diagnosis budget was spent");
            helper.assertTrue(search.fullEvidence() == null, "unknown issued negative evidence");
            var canceled = new CraftSearch(recipes, pick(), List.of(), logical(), p -> null);
            canceled.cancel();
            helper.assertValueEqual(canceled.run().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "canceled work restarted");
            helper.assertValueEqual(canceled.initializations(), 0, "canceled task initialized a root");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void diagnosticExhaustionPreservesFullFailureEvidence(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var recipes = new CraftingRecipes(player, true);
            var source = List.of(new ResourceLedger.Source("a", 0, new ItemStack(Items.OAK_LOG)));
            var scope = new CraftSearch.Reachability();
            var diagnostic = SearchBudget.resumable(() -> 0, 100, 1);
            var search = new CraftSearch(recipes, pick().withPartial(true), source, logical(), p -> null)
                    .withReachability(scope).withDiagnosticBudget(diagnostic);
            helper.assertValueEqual(search.run().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "partial exhaustion hidden");
            helper.assertTrue(search.fullEvidence() != null, "diagnostic exhaustion erased full failure");
            helper.assertValueEqual(search.fullSearches(), 1, "diagnostic restarted full search");
            helper.assertValueEqual(search.partialSearches(), 1, "diagnostic stage not reached");
            var filter = new CraftSearch(recipes, pick(), source, logical(), p -> null)
                    .withReachability(scope).withFullEvidence(search.fullEvidence());
            helper.assertValueEqual(filter.run().code(), CraftingResultCode.MISSING_INGREDIENTS, "lost full conclusion");
            helper.assertValueEqual(filter.fullSearches(), 0, "filter re-searched completed full failure");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }
}
