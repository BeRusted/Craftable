package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.environment.PlayerMenuInputs;
import org.berusted.craftable.menu.AmbientInventoryMenu;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Real menu inputs use the same scan, ledger, witness and atomic transaction. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4MenuResourceGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final BlockPos TABLE = new BlockPos(1, 1, 1);

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void cursorAndGridFundCraftsInAllThreeMenusAndCloseReturnsOnlyRemainder(GameTestHelper h) {
        var p = M4PlanningGameTests.player(h);
        try {
            for (int kind = 0; kind < 3; kind++) {
                p.getInventory().clearContent();
                open(h, p, kind);
                var inputs = PlayerMenuInputs.current(p);
                inputs.setItem(0, new ItemStack(Items.OAK_PLANKS));
                p.containerMenu.setCarried(new ItemStack(Items.OAK_PLANKS, 2));
                var prepared = prepare(p, "stick");
                h.assertTrue(prepared.result().plan().isPresent(), "menu " + kind + " ignored owned inputs");
                h.assertValueEqual(create(p, "stick"), CraftingResultCode.CREATED, "menu " + kind + " create");
                h.assertValueEqual(p.getInventory().countItem(Items.STICK), 4, "one batch output");
                h.assertTrue(inputs.grid().isEmpty(), "grid consumption");
                h.assertValueEqual(p.containerMenu.getCarried().getCount(), 1, "cursor remainder");
                p.doCloseContainer();
                h.assertValueEqual(p.getInventory().countItem(Items.OAK_PLANKS), 1, "return once");
                h.assertValueEqual(p.getInventory().countItem(Items.STICK), 4, "close duplicated output");
                if (kind != 0) h.assertFalse(inputs.stillValid(p), "closed menu still authorizes input");
            }
        } finally { p.doCloseContainer(); M4PlanningGameTests.remove(p); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void derivedResultAndInactiveGridAreNeverResources(GameTestHelper h) {
        var p = M4PlanningGameTests.player(h);
        try {
            var inputs = PlayerMenuInputs.current(p);
            inputs.setItem(0, new ItemStack(Items.OAK_LOG));
            h.assertTrue(p.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "derived result fixture");
            var snapshot = CraftingSessions.refreshBrowsing(p);
            h.assertValueEqual(snapshot.inputs().size(), 1, "result counted as an input");
            h.assertTrue(snapshot.inputs().getFirst().stack().is(Items.OAK_LOG), "wrong real input");
            h.assertValueEqual(create(p, "oak_planks"), CraftingResultCode.CREATED, "craft grid log");
            h.assertValueEqual(p.getInventory().countItem(Items.OAK_PLANKS), 4, "result duplication");
            h.assertTrue(p.containerMenu.getSlot(0).getItem().isEmpty(), "stale derived result after commit");
            p.getInventory().clearContent();
            inputs.setItem(0, new ItemStack(Items.DIAMOND));
            open(h, p, 1); // Deliberately leave inactive 2x2 populated in this fixture.
            h.assertTrue(CraftingSessions.refreshBrowsing(p).inputs().isEmpty(), "inactive grid included");
            inputs.grid().clearContent();
        } finally { p.doCloseContainer(); M4PlanningGameTests.remove(p); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void stableRescansRenewButMovesInvalidateWitnessAndNewWitnessConsumesCursor(GameTestHelper h) {
        var p = M4PlanningGameTests.player(h);
        try {
            p.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            var first = CraftingSessions.refreshBrowsing(p);
            var again = CraftingSessions.refreshBrowsing(p);
            h.assertValueEqual(again.resources(), first.resources(), "fresh adapter changed resource version");
            h.assertValueEqual(again.contentIdentity(), first.contentIdentity(), "fresh scan changed refs");
            var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("stick"));
            var oldWitness = witness(p, first, request);
            p.containerMenu.setCarried(p.getInventory().removeItemNoUpdate(0));
            var moved = CraftingSessions.refreshBrowsing(p);
            h.assertValueEqual(moved.session(), first.session(), "move replaced session");
            h.assertTrue(moved.resources() > first.resources(), "move kept stale take location/capacity");
            h.assertValueEqual(moved.inputs().getFirst().stack().getCount(), 2, "move lost materials");
            h.assertFalse(CraftingSessions.acceptsWitness(p, oldWitness), "old refs authorized after move");
            var next = witness(p, moved, request);
            var prepared = CraftingService.prepareWitness(p, request, EnvironmentSnapshotService.fresh(p), next, budget());
            h.assertTrue(prepared.result().plan().isPresent(), "cursor witness not remapped");
            long searches = CraftingService.activeFullSearches;
            var token = CraftingSessions.offer(p, prepared, true);
            h.assertValueEqual(CraftingService.confirm(p, token, next, 100).code(), CraftingResultCode.CREATED, "cursor witness commit");
            h.assertValueEqual(CraftingService.activeFullSearches, searches, "witness triggered route search");
            h.assertTrue(p.containerMenu.getCarried().isEmpty(), "cursor not consumed");
            h.assertValueEqual(p.getInventory().countItem(Items.STICK), 4, "witness output");
        } finally { M4PlanningGameTests.remove(p); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void gridAndCursorAreNotOutputCapacityAndProtectedStacksStayProtected(GameTestHelper h) {
        var p = M4PlanningGameTests.player(h);
        try {
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            p.containerMenu.setCarried(new ItemStack(Items.OAK_LOG));
            h.assertValueEqual(create(p, "oak_planks"), CraftingResultCode.NO_OUTPUT_SPACE, "cursor invented output capacity");
            h.assertTrue(p.containerMenu.getCarried().is(Items.OAK_LOG), "capacity failure consumed cursor");
            p.getInventory().clearContent();
            var named = new ItemStack(Items.OAK_LOG);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
            p.containerMenu.setCarried(named);
            PlayerMenuInputs.current(p).setItem(0, named.copy());
            h.assertTrue(CraftingSessions.refreshBrowsing(p).inputs().isEmpty(), "protected input exposed");
            h.assertValueEqual(create(p, "oak_planks"), CraftingResultCode.PROTECTED_INGREDIENTS, "protected input consumed");
            h.assertTrue(ItemStack.matches(p.containerMenu.getCarried(), named), "cursor components changed");
            h.assertTrue(ItemStack.matches(PlayerMenuInputs.current(p).getItem(0), named), "grid components changed");
        } finally { M4PlanningGameTests.remove(p); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void dropRejectionRestoresGridCursorAndMainInventoryWithoutCraftStats(GameTestHelper h) {
        var p = M4PlanningGameTests.player(h);
        var entities = new ArrayList<ItemEntity>();
        Consumer<EntityJoinLevelEvent> cancel = event -> {
            if (event.getEntity() instanceof ItemEntity item && item.getOwner() == p) {
                entities.add(item); event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancel);
        try {
            open(h, p, 1);
            var inputs = PlayerMenuInputs.current(p);
            inputs.setItem(0, new ItemStack(Items.OAK_LOG));
            p.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 3));
            var prepared = prepare(p, "diamond_pickaxe");
            var plan = prepared.result().plan().orElseThrow();
            for (int i = 1; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            var world = EnvironmentSnapshotService.fresh(p);
            var delivery = MainInventoryInsertion.simulate(p.getInventory(), plan, world, true);
            h.assertTrue(delivery.failure() == null && !delivery.drops().isEmpty(), "overflow fixture");
            h.assertValueEqual(CraftingTransaction.execute(p, plan, world, delivery), CraftingResultCode.ENVIRONMENT_CHANGED, "rollback");
            h.assertTrue(inputs.getItem(0).is(Items.OAK_LOG) && inputs.getItem(0).getCount() == 1, "grid not restored");
            h.assertTrue(p.containerMenu.getCarried().is(Items.DIAMOND) && p.containerMenu.getCarried().getCount() == 3, "cursor not restored");
            h.assertTrue(p.getInventory().getItem(0).isEmpty(), "output escaped rollback");
            h.assertValueEqual(p.getInventory().countItem(Items.COBBLESTONE), 35 * 64, "main inventory changed");
            h.assertTrue(!entities.isEmpty() && entities.stream().allMatch(ItemEntity::isRemoved), "drop leaked");
            h.assertTrue(p.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "rollback derived result missing");
            h.assertValueEqual(p.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.DIAMOND_PICKAXE)), 0, "rollback stats");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel); entities.forEach(ItemEntity::discard);
            p.doCloseContainer(); M4PlanningGameTests.remove(p);
        }
        h.succeed();
    }

    private static void open(GameTestHelper h, ServerPlayer p, int kind) {
        if (kind == 0) { p.containerMenu = p.inventoryMenu; return; }
        h.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        var pos = h.absolutePos(TABLE);
        p.containerMenu = kind == 1 ? new AmbientInventoryMenu(kind, p.getInventory(), List.of(pos))
                : new CraftingMenu(kind, p.getInventory(), ContainerLevelAccess.create(p.level(), pos));
    }

    private static SearchBudget budget() { return new SearchBudget(1_000_000_000L); }

    private static CraftingService.Prepared prepare(ServerPlayer p, String recipe) {
        return CraftingService.prepare(p, CraftRequest.one(ResourceLocation.withDefaultNamespace(recipe)),
                EnvironmentSnapshotService.fresh(p), budget());
    }

    private static CraftingResultCode create(ServerPlayer p, String recipe) {
        return CraftingService.create(p, CraftRequest.one(ResourceLocation.withDefaultNamespace(recipe)), false, budget()).code();
    }

    private static CraftPlan.Witness witness(ServerPlayer p, org.berusted.craftable.environment.BrowsingSnapshot snapshot,
            CraftRequest request) {
        var result = new CraftSearch(new CraftingRecipes(p, snapshot.workbench()), request, snapshot.sources(), budget(), plan -> null).run();
        return CraftPlan.Witness.from(result.plan().orElseThrow(), snapshot);
    }
}
