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
                max = CraftingService.maximum(player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
                if (!max.pending()) break;
            }
            helper.assertTrue(max != null && max.proven(), "Simple pickaxe maximum stayed unknown: " + max);
            helper.assertValueEqual(max.lowerBound(), 1, "pickaxe maximum");
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            player.getInventory().setItem(0, new ItemStack(Items.BONE, 2));
            var bone = new CraftRequest(ResourceLocation.withDefaultNamespace("bone_meal"), 1, false, false,
                    CraftRequest.PartialPolicy.EXPLICIT_SAFE, Map.of());
            for (int i = 0; i < 100; i++) {
                max = CraftingService.maximum(player, bone);
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
