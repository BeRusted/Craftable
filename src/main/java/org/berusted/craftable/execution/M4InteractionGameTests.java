package org.berusted.craftable.execution;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.SearchBudget;

@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4InteractionGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void witnessUsesOriginalTransactionWithoutResearchAndCannotReplay(GameTestHelper helper) {
        var player = setup(helper, 3);
        try {
            var request = request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            var witness = witness(player, request);
            int uses = workbenchUses(player);
            var prepared = CraftingService.prepareWitness(player, request, EnvironmentSnapshotService.fresh(player), witness,
                    new SearchBudget(1_000_000_000L));
            helper.assertTrue(prepared.result().plan().isPresent(), "valid witness rejected");
            long before = CraftingService.activeFullSearches;
            var draft = CraftingService.preview(player, request, "", witness, 100);
            helper.assertFalse(draft.token().equals(new UUID(0, 0)), "no witness token: " + draft.view().code());
            helper.assertValueEqual(workbenchUses(player), uses, "preview counted workbench use");
            var result = CraftingService.confirm(player, draft.token(), witness, 101);
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "witness commit");
            helper.assertValueEqual(CraftingService.activeFullSearches, before, "witness re-searched");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "witness output");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 2, "stick surplus");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "plank surplus");
            helper.assertValueEqual(workbenchUses(player), uses + 1, "Shift+C workbench use");
            helper.assertValueEqual(CraftingService.confirm(player, draft.token(), witness, 102).code(),
                    CraftingResultCode.CONFIRMATION_EXPIRED, "token replay");
            helper.assertValueEqual(workbenchUses(player), uses + 1, "replayed token counted workbench use");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void witnessRejectsStaleForeignAlteredAndDowngradedAuthority(GameTestHelper helper) {
        var player = setup(helper, 3);
        var other = setup(helper, 3);
        try {
            var request = request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            var witness = witness(player, request);
            long before = CraftingService.activeFullSearches;
            helper.assertValueEqual(CraftingService.preview(other, request, "", witness, 100).token(), new UUID(0,0), "cross-player witness");
            var prepared = CraftingService.prepareWitness(player, request, EnvironmentSnapshotService.fresh(player), witness,
                    new SearchBudget(1_000_000_000L));
            var token = CraftingSessions.offer(player, prepared, true);
            helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.ENVIRONMENT_CHANGED,
                    "witness token downgraded to server search");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertValueEqual(CraftingService.preview(player, request, "", witness, 101).token(), new UUID(0,0), "stale resources");
            helper.assertValueEqual(CraftingService.activeFullSearches, before, "rejected witness triggered search");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 0, "rejection changed output");
        } finally { M4PlanningGameTests.remove(player); M4PlanningGameTests.remove(other); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void witnessRejectsExtraProcessingQuantityPinsAndUnfundedInputs(GameTestHelper helper) {
        var player = setup(helper, 3);
        try {
            var request = request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            var witness = witness(player, request);
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var recipes = new org.berusted.craftable.recipe.CraftingRecipes(player, true);
            var steps = new java.util.ArrayList<>(witness.steps());
            var planks = steps.getFirst();
            steps.add(new org.berusted.craftable.planner.CraftPlan.WitnessStep(planks.recipe(), "0.8", planks.inputs(), planks.origins(), planks.references()));
            var extra = new org.berusted.craftable.planner.CraftPlan.Witness(witness.session(), witness.recipes(), witness.resources(), steps);
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, request, extra, snapshot.sources(), new SearchBudget(1_000_000_000L)));
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, request.withBatches(2), witness, snapshot.sources(), new SearchBudget(1_000_000_000L)));
            var stickStep = witness.steps().stream().filter(s -> s.recipe().getPath().equals("stick")).findFirst().orElseThrow();
            var pinned = new CraftRequest(request.recipe(), 1, false, request.allowDrops(), request.policy(),
                    Map.of(stickStep.path(), ResourceLocation.withDefaultNamespace("stick_from_bamboo_item")));
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, pinned, witness, snapshot.sources(), new SearchBudget(1_000_000_000L)));
            var insufficient = snapshot.sources().stream().map(s -> s.stack().is(Items.DIAMOND)
                    ? new org.berusted.craftable.planner.ResourceLedger.Source(s.endpointId(), s.slot(), s.stack().copyWithCount(2)) : s).toList();
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, request, witness, insufficient, new SearchBudget(1_000_000_000L)));
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, request.withPartial(true), witness, snapshot.sources(), new SearchBudget(1_000_000_000L)));
            rejected(helper, () -> CraftWitnessValidator.validate(new org.berusted.craftable.recipe.CraftingRecipes(player, false),
                    request, witness, snapshot.sources(), new SearchBudget(1_000_000_000L)));
            var badRefs = new java.util.ArrayList<>(planks.references());
            badRefs.set(0, UUID.randomUUID());
            var unknown = new java.util.ArrayList<>(witness.steps());
            unknown.set(0, new org.berusted.craftable.planner.CraftPlan.WitnessStep(planks.recipe(), planks.path(), planks.inputs(), planks.origins(), badRefs));
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, request,
                    new org.berusted.craftable.planner.CraftPlan.Witness(witness.session(), witness.recipes(), witness.resources(), unknown),
                    snapshot.sources(), new SearchBudget(1_000_000_000L)));
            rejected(helper, () -> new org.berusted.craftable.planner.CraftPlan.Witness(witness.session(), witness.recipes(), witness.resources(),
                    java.util.Collections.nCopies(129, planks)));
            var clock = new java.util.concurrent.atomic.AtomicLong();
            var exhausted = CraftingService.prepareWitness(player, request, EnvironmentSnapshotService.fresh(player), witness,
                    new SearchBudget(clock::getAndIncrement, 1, 2048));
            helper.assertValueEqual(exhausted.result().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "witness budget became absence proof");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 2, "validation wrote inputs");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void serverNegativeEvidenceSkipsFullButCapacityChangeInvalidates(GameTestHelper helper) {
        var player = setup(helper, 0);
        try {
            var request = request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            prepare(player, request);
            long before = CraftingService.activeFullSearches;
            var repeated = prepare(player, request.withPartial(true));
            helper.assertValueEqual(CraftingService.activeFullSearches, before, "full failure re-searched");
            helper.assertTrue(repeated.result().plan().orElseThrow().partial(), "partial not analyzed");
            player.getInventory().setItem(30, new ItemStack(Items.BEDROCK));
            prepare(player, request.withPartial(true));
            helper.assertTrue(CraftingService.activeFullSearches > before, "capacity mutation reused negative proof");
            CraftingSessions.clear(player.getUUID());
            var clock = new java.util.concurrent.atomic.AtomicLong();
            CraftingService.prepare(player, request, EnvironmentSnapshotService.fresh(player), new SearchBudget(clock::getAndIncrement, 1, 2048));
            before = CraftingService.activeFullSearches;
            prepare(player, request.withPartial(true));
            helper.assertTrue(CraftingService.activeFullSearches > before, "truncated search became negative proof");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static org.berusted.craftable.planner.CraftPlan.Witness witness(ServerPlayer player, CraftRequest request) {
        var snapshot = CraftingSessions.refreshBrowsing(player);
        var recipes = new org.berusted.craftable.recipe.CraftingRecipes(player, true);
        var result = new org.berusted.craftable.planner.CraftSearch(recipes, request, snapshot.sources(),
                new SearchBudget(1_000_000_000L), plan -> null).run();
        return org.berusted.craftable.planner.CraftPlan.Witness.from(result.plan().orElseThrow(), snapshot);
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void locallyFabricatedRoundTripWitnessCannotAwardItemsOrStatistics(GameTestHelper helper) {
        var player = setup(helper, 0);
        try {
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT));
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var source = snapshot.sources().stream().filter(s -> s.stack().is(Items.IRON_INGOT)).findFirst().orElseThrow();
            var recipes = new org.berusted.craftable.recipe.CraftingRecipes(player, true);
            var split = recipes.assemble(recipes.find(ResourceLocation.withDefaultNamespace("iron_nugget")), "0.0",
                    java.util.List.of(new ItemStack(Items.IRON_INGOT), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
            var rootId = ResourceLocation.withDefaultNamespace("iron_ingot_from_nuggets");
            var join = recipes.assemble(recipes.find(rootId), "0",
                    java.util.Collections.nCopies(9, new ItemStack(Items.IRON_NUGGET)));
            join = new org.berusted.craftable.planner.CraftPlan.Step(join.recipe(), join.path(), join.gridSize(),
                    join.inputs(), join.output(), join.remainders(), java.util.Collections.nCopies(9, 0));
            var plan = new org.berusted.craftable.planner.CraftPlan(rootId, 1, 1, java.util.List.of(split, join),
                    java.util.List.of(new org.berusted.craftable.planner.CraftPlan.Extraction(source.endpointId(), source.slot(), 1, source.stack())),
                    java.util.List.of(new ItemStack(Items.IRON_INGOT)), java.util.List.of(), java.util.List.of(), true);
            helper.assertFalse(plan.hasMaterialChange(), "No-op ledger delta was not detected");
            var witness = org.berusted.craftable.planner.CraftPlan.Witness.from(plan, snapshot);
            int before = workbenchUses(player);
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, CraftRequest.one(rootId), witness,
                    snapshot.sources(), new SearchBudget(1_000_000_000L)));
            // A real shovel at the end must not legitimize an intermediate
            // ingot -> nuggets -> ingot detour or its extra crafted statistics.
            player.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
            var productiveSnapshot = CraftingSessions.refreshBrowsing(player);
            var nestedSplit = new org.berusted.craftable.planner.CraftPlan.Step(split.recipe(), "0.0.0", split.gridSize(),
                    split.inputs(), split.output(), split.remainders(), split.inputOrigins());
            var nestedJoin = new org.berusted.craftable.planner.CraftPlan.Step(join.recipe(), "0.0", join.gridSize(),
                    join.inputs(), join.output(), join.remainders(), join.inputOrigins());
            var shovelId = ResourceLocation.withDefaultNamespace("iron_shovel");
            var grid = new java.util.ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
            grid.set(0, new ItemStack(Items.IRON_INGOT));
            grid.set(3, new ItemStack(Items.STICK)); grid.set(6, new ItemStack(Items.STICK));
            var shovel = recipes.assemble(recipes.find(shovelId), "0", grid);
            shovel = new org.berusted.craftable.planner.CraftPlan.Step(shovel.recipe(), shovel.path(), shovel.gridSize(),
                    shovel.inputs(), shovel.output(), shovel.remainders(), java.util.List.of(1, -1, -1, -1, -1, -1, -1, -1, -1));
            var productive = new org.berusted.craftable.planner.CraftPlan(shovelId, 1, 1, java.util.List.of(nestedSplit, nestedJoin, shovel),
                    productiveSnapshot.sources().stream().map(s -> new org.berusted.craftable.planner.CraftPlan.Extraction(
                            s.endpointId(), s.slot(), s.stack().getCount(), s.stack())).toList(),
                    java.util.List.of(new ItemStack(Items.IRON_SHOVEL)), java.util.List.of(), java.util.List.of(), true);
            helper.assertTrue(productive.hasMaterialChange(), "fixture must have a real final product");
            var nestedWitness = org.berusted.craftable.planner.CraftPlan.Witness.from(productive, productiveSnapshot);
            rejected(helper, () -> CraftWitnessValidator.validate(recipes, CraftRequest.one(shovelId), nestedWitness,
                    productiveSnapshot.sources(), new SearchBudget(1_000_000_000L)));
            helper.assertValueEqual(player.getInventory().countItem(Items.IRON_INGOT), 1, "rejected loop changed inventory");
            helper.assertValueEqual(workbenchUses(player), before, "rejected loop counted workbench use");
            helper.assertValueEqual(player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.IRON_INGOT)), 0,
                    "rejected loop counted crafted ingots");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void witnessDifferentialRoundTripAndRepresentativeCosts(GameTestHelper helper) {
        var player = setup(helper, 0);
        long[] validation = new long[100], searches = new long[100], captures = new long[100], rejections = new long[100];
        int bytes = 0;
        try {
            String[] targets = {"oak_planks", "diamond_pickaxe", "stick", "cake", "wooden_pickaxe"};
            ItemStack[][] stock = {
                {new ItemStack(Items.OAK_LOG)},
                {new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3)},
                {new ItemStack(Items.OAK_LOG, 2), new ItemStack(Items.BIRCH_LOG)},
                {new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                    new ItemStack(Items.SUGAR, 2), new ItemStack(Items.EGG), new ItemStack(Items.WHEAT, 3)},
                {new ItemStack(Items.OAK_LOG, 2)}
            };
            for (int scenario = 0; scenario < targets.length; scenario++) {
                player.getInventory().clearContent();
                for (int slot = 0; slot < stock[scenario].length; slot++) player.getInventory().setItem(slot, stock[scenario][slot].copy());
                var request = CraftRequest.one(ResourceLocation.withDefaultNamespace(targets[scenario])).withBatches(scenario == 2 ? 6 : 1);
                var witness = witness(player, request);
                var packet = new org.berusted.craftable.network.CraftingDetailPayloads.PreviewRequest(0, 200, request, "", witness);
                var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
                try {
                    org.berusted.craftable.network.CraftingDetailPayloads.PreviewRequest.CODEC.encode(wire, packet);
                    bytes = Math.max(bytes, wire.readableBytes());
                    witness = org.berusted.craftable.network.CraftingDetailPayloads.PreviewRequest.CODEC.decode(wire).witness();
                    helper.assertFalse(wire.isReadable(), "witness trailing bytes");
                } finally { wire.release(); }
                for (int sample = -3; sample < 20; sample++) {
                    long start = System.nanoTime();
                    var world = EnvironmentSnapshotService.fresh(player);
                    long capture = System.nanoTime() - start;
                    start = System.nanoTime();
                    var expected = CraftingService.prepare(player, request, world, new SearchBudget(1_000_000_000L));
                    long search = System.nanoTime() - start;
                    long before = CraftingService.activeFullSearches;
                    start = System.nanoTime();
                    var actual = CraftingService.prepareWitness(player, request, world, witness, new SearchBudget(1_000_000_000L));
                    long valid = System.nanoTime() - start;
                    helper.assertValueEqual(CraftingService.activeFullSearches, before, "validator used solver");
                    helper.assertValueEqual(actual.result().plan().orElseThrow().fingerprint(),
                            expected.result().plan().orElseThrow().fingerprint(), "search/witness accounting mismatch " + targets[scenario]);
                    var wrong = request.withBatches(request.batches() + 1);
                    var submitted = witness;
                    start = System.nanoTime();
                    rejected(helper, () -> CraftingService.prepareWitness(player, wrong, world, submitted, new SearchBudget(1_000_000_000L)));
                    long rejectedNanos = System.nanoTime() - start;
                    if (sample >= 0) {
                        int index = scenario * 20 + sample;
                        validation[index] = valid; searches[index] = search; captures[index] = capture; rejections[index] = rejectedNanos;
                    }
                }
            }
            Craftable.LOGGER.warn("M49_WITNESS samples=100 searchUs={} validationUs={} captureUs={} rejectUs={} maxWireBytes={} (p50,p95,p99,max; same-JVM ordinary fixtures, no network latency/commit timing)",
                    percentiles(searches), percentiles(validation), percentiles(captures), percentiles(rejections), bytes);
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static java.util.List<Double> percentiles(long[] values) {
        java.util.Arrays.sort(values);
        return java.util.List.of(values[49] / 1000.0, values[94] / 1000.0, values[98] / 1000.0, values[99] / 1000.0);
    }

    private static void rejected(GameTestHelper helper, Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        helper.fail("Invalid witness accepted");
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 260)
    public static void confirmationExpiresAfterTwoHundredTicks(GameTestHelper helper) {
        var player = setup(helper, 3);
        UUID token;
        try { token = CraftingSessions.offer(player, prepare(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE))); }
        catch (RuntimeException error) { M4PlanningGameTests.remove(player); throw error; }
        helper.runAfterDelay(201, () -> {
            try {
                helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.CONFIRMATION_EXPIRED,
                        "expired draft executed");
                helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "expired draft spent log");
                helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 3, "expired draft spent diamonds");
                helper.succeed();
            } finally { M4PlanningGameTests.remove(player); }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void worldPolicyTighteningAndNewPreviewInvalidateOldOffer(GameTestHelper helper) {
        var player = setup(helper, 0);
        net.neoforged.neoforge.common.ModConfigSpec.EnumValue<CraftRequest.PartialPolicy> setting =
                org.berusted.craftable.config.CraftableServerConfig.SPEC.getValues().get(java.util.List.of("crafting", "partialExecution"));
        var original = setting.get();
        try {
            var req = request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            UUID token = CraftingSessions.offer(player, prepare(player, req));
            setting.set(CraftRequest.PartialPolicy.NEVER);
            helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.ENVIRONMENT_CHANGED,
                    "world policy change ignored");
            helper.assertValueEqual(create(player, req).code(), CraftingResultCode.MISSING_INGREDIENTS,
                    "client widened world NEVER policy");
            helper.assertValueEqual(CraftingService.preview(player, req).token(), new UUID(0, 0),
                    "world NEVER issued partial token");
            setting.set(original);
            token = CraftingSessions.offer(player, prepare(player, req));
            CraftingService.preview(player, CraftRequest.one(ResourceLocation.withDefaultNamespace("missing_recipe")));
            helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.CONFIRMATION_EXPIRED,
                    "blocked new preview left old token active");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "read-only policy test spent resources");
        } finally { setting.set(original); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void expiredSearchCannotCommitFullOrPartialInputs(GameTestHelper helper) {
        var player = setup(helper, 3);
        try {
            for (boolean partial : new boolean[]{false, true}) {
                var clock = new java.util.concurrent.atomic.AtomicLong();
                var outcome = CraftingService.create(player, request(partial, CraftRequest.PartialPolicy.EXPLICIT_SAFE),
                        false, new SearchBudget(clock::getAndIncrement, 1, 2048));
                helper.assertValueEqual(outcome.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "expired budget outcome");
                helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "expired search spent log");
                helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 3, "expired search spent diamond");
                helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 0, "expired search published output");
            }
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void limitedCraftingRequiresAllIntermediatesUnlockedInitially(GameTestHelper helper) {
        var player = setup(helper, 3);
        var rule = player.level().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING);
        boolean before = rule.get();
        var manager = player.serverLevel().getRecipeManager();
        var root = manager.byKey(PICK).orElseThrow();
        var planks = manager.byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
        var sticks = manager.byKey(ResourceLocation.withDefaultNamespace("stick")).orElseThrow();
        try {
            rule.set(true, player.getServer());
            player.awardRecipes(java.util.List.of(root));
            var request = request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            helper.assertValueEqual(prepare(player, request).result().code(), CraftingResultCode.RECIPE_LOCKED,
                    "locked intermediates accepted");
            player.awardRecipes(java.util.List.of(planks));
            helper.assertValueEqual(prepare(player, request).result().code(), CraftingResultCode.RECIPE_LOCKED,
                    "locked sticks accepted");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "read-only lock check changed input");
            player.awardRecipes(java.util.List.of(sticks));
            helper.assertTrue(prepare(player, request).result().plan().isPresent(), "unlocked chain refused");
        } finally {
            rule.set(before, player.getServer());
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void reloadAndModeChangesInvalidateConfirmation(GameTestHelper helper) {
        var player = setup(helper, 3);
        var manager = player.serverLevel().getRecipeManager();
        var original = java.util.List.copyOf(manager.getRecipes());
        try {
            UUID token = CraftingSessions.offer(player, prepare(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)));
            manager.replaceRecipes(original);
            helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.ENVIRONMENT_CHANGED,
                    "old recipe generation accepted");
            token = CraftingSessions.offer(player, prepare(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)));
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.CONFIRMATION_EXPIRED,
                    "creative confirmed chain");
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "invalid confirmation consumed input");
        } finally {
            manager.replaceRecipes(original);
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void observerReentryAndExceptionCannotRefundOrSuppressLaterSteps(GameTestHelper helper) {
        var player = setup(helper, 3);
        var seen = new java.util.ArrayList<net.minecraft.world.item.Item>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerEvent.ItemCraftedEvent> listener = event -> {
            if (event.getEntity() != player) return;
            seen.add(event.getCrafting().getItem());
            helper.assertValueEqual(CraftingService.createOne(player, PICK), CraftingResultCode.REQUEST_THROTTLED,
                    "observer reentered transaction");
            if (seen.size() == 1) throw new IllegalStateException("M4 expected observer fault");
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.entity.player.PlayerEvent.ItemCraftedEvent.class, listener);
        try {
            helper.assertValueEqual(create(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.CREATED, "post-commit exception changed outcome");
            helper.assertValueEqual(seen, java.util.List.of(Items.OAK_PLANKS, Items.STICK, Items.DIAMOND_PICKAXE),
                    "later step notification suppressed");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "missing primary");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 0, "input refunded");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 0, "diamond refunded");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void completeOnlyAndClientStricterPoliciesNeverImplicitlyPrepare(GameTestHelper helper) {
        var player = setup(helper, 0);
        try {
            helper.assertValueEqual(create(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "single C");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "single C spent log");
            helper.assertValueEqual(create(player, request(true, CraftRequest.PartialPolicy.NEVER)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "NEVER");
            helper.assertValueEqual(create(player, request(true, CraftRequest.PartialPolicy.CONFIRM)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "confirmation guard");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "unconfirmed preparation spent log");
            helper.assertValueEqual(create(player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.PARTIAL_CREATED, "explicit safe");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 4, "prepared sticks");
            helper.assertValueEqual(create(player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "second preparation");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "repeated preparation changed surplus");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void doublePressLinksOnlyFailedFullServerAttempt(GameTestHelper helper) {
        var player = setup(helper, 0);
        try {
            helper.assertTrue(CraftingSessions.acceptSequence(player, 1), "first sequence");
            helper.assertFalse(CraftingSessions.acceptSequence(player, 1), "replay accepted");
            CraftingSessions.recordAttempt(player, PICK, 1, CraftingResultCode.CREATED, false);
            helper.assertFalse(CraftingSessions.partialGesture(player, PICK, 1), "successful first press armed partial");
            CraftingSessions.recordAttempt(player, PICK, 2, CraftingResultCode.MISSING_INGREDIENTS, false);
            helper.assertTrue(CraftingSessions.partialGesture(player, PICK, 2), "second press before client reply lost intent");
            helper.assertFalse(CraftingSessions.partialGesture(player, ResourceLocation.withDefaultNamespace("stick"), 2),
                    "different target inherited intent");
            helper.assertFalse(CraftingSessions.partialGesture(player, PICK, 1), "old request inherited intent");
            CraftingSessions.recordAttempt(player, PICK, 3, CraftingResultCode.PARTIAL_CREATED, true);
            helper.assertFalse(CraftingSessions.partialGesture(player, PICK, 3), "partial result armed another preparation");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void confirmationIsOneShotAndBoundToDisplayedInputs(GameTestHelper helper) {
        var player = setup(helper, 3);
        try {
            var prepared = prepare(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            UUID token = CraftingSessions.offer(player, prepared);
            player.getInventory().getItem(1).shrink(1);
            helper.assertValueEqual(CraftingService.confirm(player, token).code(),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "stale displayed costs");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "stale confirm spent input");
            helper.assertValueEqual(CraftingService.confirm(player, token).code(),
                    CraftingResultCode.CONFIRMATION_EXPIRED, "replayed stale confirmation");
            player.getInventory().getItem(1).grow(1);
            prepared = prepare(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            token = CraftingSessions.offer(player, prepared);
            helper.assertValueEqual(CraftingService.confirm(player, token).code(), CraftingResultCode.CREATED, "fresh confirmation");
            helper.assertValueEqual(CraftingService.confirm(player, token).code(),
                    CraftingResultCode.CONFIRMATION_EXPIRED, "replayed successful confirmation");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "duplicate output");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void otherPlayerCannotConsumeOwnersConfirmation(GameTestHelper helper) {
        var owner = setup(helper, 3);
        var other = M4PlanningGameTests.player(helper);
        try {
            UUID token = CraftingSessions.offer(owner, prepare(owner, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)));
            helper.assertValueEqual(CraftingService.confirm(other, token).code(),
                    CraftingResultCode.CONFIRMATION_EXPIRED, "cross-player token");
            helper.assertValueEqual(owner.getInventory().countItem(Items.OAK_LOG), 1, "owner inputs changed");
            helper.assertValueEqual(CraftingService.confirm(owner, token).code(), CraftingResultCode.CREATED, "owner token was stolen");
        } finally { M4PlanningGameTests.remove(owner); M4PlanningGameTests.remove(other); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 200)
    public static void maximumProvesSharedResourcesWithoutAssumingMonotoneCapacity(GameTestHelper helper) {
        var player = setup(helper, 3);
        try {
            CraftingService.Maximum max = null;
            for (int i = 0; i < 100; i++) {
                max = CraftingService.maximum(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE), new SearchBudget(1_000_000_000L));
                if (!max.pending()) break;
            }
            helper.assertTrue(max != null && max.proven(), "Simple pickaxe maximum stayed unknown: " + max);
            helper.assertValueEqual(max.lowerBound(), 1, "pickaxe maximum");
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            player.getInventory().setItem(0, new ItemStack(Items.BONE, 2));
            var bone = new CraftRequest(ResourceLocation.withDefaultNamespace("bone_meal"), 1, false, false,
                    CraftRequest.PartialPolicy.EXPLICIT_SAFE, Map.of());
            for (int i = 0; i < 100; i++) {
                max = CraftingService.maximum(player, bone, new SearchBudget(1_000_000_000L));
                if (!max.pending()) break;
            }
            helper.assertTrue(max != null && max.proven(), "Bone maximum stayed unknown: " + max);
            helper.assertValueEqual(max.lowerBound(), 2, "capacity false for one, true for two batches");
            helper.assertValueEqual(player.getInventory().countItem(Items.BONE), 2, "MAX mutated inputs");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static ServerPlayer setup(GameTestHelper helper, int diamonds) {
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.CRAFTING_TABLE);
        var player = M4PlanningGameTests.player(helper);
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
        if (diamonds > 0) player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, diamonds));
        return player;
    }

    private static int workbenchUses(ServerPlayer player) {
        return player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE));
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fullCreateAndPreviewReportWholeDeficitsWithoutConsumingAssignedInputs(GameTestHelper helper) {
        var player = setup(helper, 0);
        try {
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT));
            player.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
            var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("iron_pickaxe"));
            var result = prepare(player, request).result();
            helper.assertValueEqual(result.missing().size(), 1, "whole root demand");
            var deficit = result.missing().getFirst();
            helper.assertTrue(deficit.count() == 2 && deficit.alternatives().stream().allMatch(s -> s.is(Items.IRON_INGOT)),
                    "expected two ingots: " + result.missing());
            // The second query reuses server negative evidence; diagnosis must
            // still rebuild exact counts without replaying the full search.
            long searches = CraftingService.activeFullSearches;
            var failed = create(player, request);
            helper.assertValueEqual(failed.code(), CraftingResultCode.MISSING_INGREDIENTS, "C failed result");
            helper.assertValueEqual(missingIdentity(failed.missing()), missingIdentity(result.missing()), "C versus read-only diagnostic");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches, "diagnosis repeated complete failure search");
            helper.assertValueEqual(missingIdentity(CraftingService.preview(player, request).view().missing()),
                    missingIdentity(result.missing()), "Shift+C deficit");
            helper.assertValueEqual(player.getInventory().countItem(Items.IRON_INGOT), 1, "failed diagnosis spent ingot");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 2, "failed diagnosis spent sticks");
            helper.assertValueEqual(workbenchUses(player), 0, "failed diagnosis counted workbench use");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static Object missingIdentity(java.util.List<org.berusted.craftable.planner.CraftPlan.Missing> missing) {
        // ItemStack uses object equality; compare the actual item/components,
        // not the fresh defensive copies held by the two result records.
        return missing.stream().map(m -> java.util.List.of(m.path(), m.count(),
                org.berusted.craftable.planner.CraftPlan.stackKeys(m.alternatives()))).toList();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void directCraftCountsOneWorkbenchUseButPreparationAndFailuresDoNot(GameTestHelper helper) {
        var player = setup(helper, 3);
        try {
            int before = workbenchUses(player);
            var req = request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            helper.assertValueEqual(create(player, req).code(), CraftingResultCode.CREATED, "direct C chain");
            helper.assertValueEqual(workbenchUses(player), before + 1, "C workbench use");
            helper.assertValueEqual(player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.DIAMOND_PICKAXE)), 1, "crafted pickaxe statistic");
            helper.assertValueEqual(player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.STICK)), 4, "intermediate craft statistic");
            helper.assertValueEqual(create(player, req).code(), CraftingResultCode.MISSING_INGREDIENTS, "failed second pickaxe");
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            helper.assertValueEqual(create(player, req.withPartial(true)).code(), CraftingResultCode.PARTIAL_CREATED, "2x2-only preparation");
            helper.assertValueEqual(workbenchUses(player), before + 1, "failure/preparation counted workbench use");
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
            helper.assertValueEqual(create(player, CraftRequest.one(ResourceLocation.withDefaultNamespace("oak_planks"))).code(),
                    CraftingResultCode.CREATED, "full 2x2 craft");
            helper.assertValueEqual(workbenchUses(player), before + 1, "full 2x2 craft counted table use");
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 6));
            helper.assertValueEqual(create(player, req.withBatches(2)).code(), CraftingResultCode.CREATED, "two-batch craft");
            helper.assertValueEqual(workbenchUses(player), before + 2, "multi-batch action counted twice");
            helper.assertValueEqual(player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.DIAMOND_PICKAXE)),
                    3, "multi-batch crafted quantity");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static CraftRequest request(boolean partial, CraftRequest.PartialPolicy policy) {
        return new CraftRequest(PICK, 1, partial, false, policy, Map.of());
    }

    private static CraftingService.Prepared prepare(ServerPlayer player, CraftRequest request) {
        return CraftingService.prepare(player, request, EnvironmentSnapshotService.fresh(player), new SearchBudget(1_000_000_000L));
    }

    private static CraftingService.Outcome create(ServerPlayer player, CraftRequest request) {
        return CraftingService.create(player, request, false, new SearchBudget(1_000_000_000L));
    }
}
