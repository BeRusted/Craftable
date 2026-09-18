package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EndpointKind;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.planner.CraftPlan;

@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4TransactionGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void joinObserverChangingDropRejectsEntireDelivery(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var drops = new ArrayList<ItemEntity>();
        Consumer<EntityJoinLevelEvent> listener = event -> {
            if (event.getEntity() instanceof ItemEntity entity && entity.getOwner() == player) {
                drops.add(entity);
                entity.setItem(new ItemStack(Items.DIRT));
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, listener);
        try {
            fill(player);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "changed drop accepted");
            helper.assertValueEqual(source.countItem(Items.OAK_LOG), 1, "log not restored");
            helper.assertValueEqual(source.countItem(Items.DIAMOND), 3, "diamonds not restored");
            helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "primary escaped rollback");
            helper.assertTrue(!drops.isEmpty() && drops.stream().allMatch(ItemEntity::isRemoved), "changed entity leaked");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            drops.forEach(ItemEntity::discard);
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void completeChainPublishesOnceAndReportsAllSteps(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var events = new ArrayList<net.minecraft.world.item.Item>();
        Consumer<PlayerEvent.ItemCraftedEvent> listener = e -> {
            if (e.getEntity() == player) events.add(e.getCrafting().getItem());
        };
        NeoForge.EVENT_BUS.addListener(PlayerEvent.ItemCraftedEvent.class, listener);
        try {
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.CREATED, "chain commit");
            helper.assertTrue(source.isEmpty(), "Inputs not consumed");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "pickaxe");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 2, "surplus sticks");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "surplus planks");
            helper.assertValueEqual(events, List.of(Items.OAK_PLANKS, Items.STICK, Items.DIAMOND_PICKAXE), "step events");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void oneFreeSlotAllowsOnlyPrimaryWhenDropsAreEnabled(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var drops = new ArrayList<ItemEntity>();
        Consumer<EntityJoinLevelEvent> listener = e -> {
            if (e.getEntity() instanceof ItemEntity item && item.getOwner() == player) drops.add(item);
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, listener);
        try {
            fill(player);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            helper.assertValueEqual(MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false).failure(),
                    CraftingResultCode.NO_OUTPUT_SPACE, "strict capacity");
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true);
            helper.assertValueEqual(delivery.drops().size(), 2, "two surplus types");
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery), CraftingResultCode.CREATED, "drop commit");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "primary delivered");
            // setThrower uses owner for pickup provenance in the pinned vanilla API.
            helper.assertValueEqual(drops.size(), 2, "published drops");
            helper.assertTrue(drops.stream().allMatch(ItemEntity::hasPickUpDelay), "Missing vanilla pickup delay");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            drops.forEach(ItemEntity::discard);
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void secondDropRejectionRemovesFirstAndRestoresEveryInput(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var created = new ArrayList<ItemEntity>();
        Consumer<EntityJoinLevelEvent> cancel = e -> {
            if (e.getEntity() instanceof ItemEntity item && item.getOwner() == player) {
                created.add(item);
                if (created.size() == 2) e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancel);
        try {
            fill(player);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "rejected drop");
            helper.assertValueEqual(source.countItem(Items.OAK_LOG), 1, "restored log");
            helper.assertValueEqual(source.countItem(Items.DIAMOND), 3, "restored diamonds");
            helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "Primary escaped rollback");
            helper.assertValueEqual(created.size(), 2, "second drop reached cancellation");
            helper.assertTrue(created.stream().allMatch(ItemEntity::isRemoved), "Transaction entity survived rollback");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
            created.forEach(ItemEntity::discard);
            M4PlanningGameTests.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void lateExtractionMismatchRestoresEarlierChainSources(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3)) {
            @Override public ItemStack removeItem(int slot, int count) {
                if (slot == 1) { super.removeItem(slot, 1); return ItemStack.EMPTY; }
                return super.removeItem(slot, count);
            }
        };
        try {
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "extraction mismatch");
            helper.assertValueEqual(source.countItem(Items.OAK_LOG), 1, "restored first removal");
            helper.assertValueEqual(source.countItem(Items.DIAMOND), 3, "restored partially removed source");
            helper.assertTrue(player.getInventory().isEmpty(), "Output escaped failure");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void preparationPrimaryCannotBeDroppedOrPreparedTwice(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG));
        try {
            var plan = plan(player, source, true);
            var snapshot = snapshot(player, source);
            fill(player);
            helper.assertValueEqual(MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true).failure(),
                    CraftingResultCode.NO_OUTPUT_SPACE, "partial primary capacity");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            player.getInventory().setItem(1, ItemStack.EMPTY);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.PARTIAL_CREATED, "preparation commit");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 4, "four primary sticks");
            var again = M4PlanningGameTests.search(player, "diamond_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.STICK, 4));
            helper.assertTrue(again.plan().isEmpty(), "Redundant preparation");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void finalCapacityDoesNotRequireIntermediateSlots(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            fill(player);
            player.getInventory().setItem(0, new ItemStack(Items.IRON_NUGGET, 27));
            player.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
            var result = M4PlanningGameTests.search(player, "iron_pickaxe", 1, false, true,
                    player.getInventory().getItem(0), player.getInventory().getItem(1));
            var plan = result.plan().orElseThrow();
            var endpoint = new ContainerEndpoint("fixture", EndpointKind.PLAYER, null, player.getInventory(), 0, 36);
            var snapshot = snapshot(player, endpoint);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertTrue(delivery.failure() == null, "Escrow wrongly needs intermediate slots");
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery), CraftingResultCode.CREATED, "escrow commit");
            helper.assertValueEqual(player.getInventory().countItem(Items.IRON_PICKAXE), 1, "final output");
            helper.assertValueEqual(player.getInventory().countItem(Items.IRON_INGOT), 0, "intermediate leak");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static CraftPlan plan(ServerPlayer player, SimpleContainer source, boolean partial) {
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < source.getContainerSize(); i++) stacks.add(source.getItem(i));
        return M4PlanningGameTests.search(player, "diamond_pickaxe", 1, partial, true,
                stacks.toArray(ItemStack[]::new)).plan().orElseThrow();
    }

    static EnvironmentSnapshot snapshot(ServerPlayer player, SimpleContainer source) {
        return snapshot(player, new ContainerEndpoint("fixture", EndpointKind.ENDER_CHEST, null,
                source, 0, source.getContainerSize()));
    }

    static EnvironmentSnapshot snapshot(ServerPlayer player, ContainerEndpoint... endpoints) {
        return new EnvironmentSnapshot(player.level().dimension(), player.blockPosition(),
                new EnvironmentScanSettings(4, 2, 0, true), player.level().getGameTime(), 1, List.of(endpoints), List.of());
    }

    private static void fill(ServerPlayer player) {
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
    }
}
