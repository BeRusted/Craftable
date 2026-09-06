package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EndpointKind;
import org.berusted.craftable.environment.EnvironmentSnapshotService;

/** Server-backed regression tests for the M2 one-step transaction boundary. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M2GameTests {
    private static final String EMPTY_TEMPLATE = "bastion/mobs/empty";
    private static final BlockPos PLAYER_POS = new BlockPos(0, 2, 0);
    private static final BlockPos CHEST_POS = new BlockPos(1, 1, 1);
    private static final BlockPos TABLE_POS = new BlockPos(2, 1, 1);

    private M2GameTests() {}

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void constrainedIngredientAllocationDoesNotGreedilyConsumeExactMatch(GameTestHelper helper) {
        SimpleContainer container = new SimpleContainer(
                new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.BIRCH_PLANKS));
        ContainerEndpoint endpoint = endpoint("allocation", container);

        DirectCraftingPlanner.AllocatedGrid allocation = DirectCraftingPlanner.allocate(
                List.of(
                        Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS),
                        Ingredient.of(Items.OAK_PLANKS)),
                2,
                0,
                false,
                List.of(endpoint));

        helper.assertTrue(allocation != null, "Expected a valid allocation");
        helper.assertTrue(allocation.gridItems().get(0).is(Items.BIRCH_PLANKS), "Broad ingredient used oak");
        helper.assertTrue(allocation.gridItems().get(1).is(Items.OAK_PLANKS), "Exact ingredient lost oak");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void producedItemInsertionIsComponentExactAndAtomic(GameTestHelper helper) {
        List<ItemStack> componentSlots = emptySlots(2);
        ItemStack namedStone = new ItemStack(Items.STONE, 63);
        namedStone.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        componentSlots.set(0, namedStone);

        helper.assertTrue(
                MainInventoryInsertion.insertAll(componentSlots, 64, List.of(new ItemStack(Items.STONE))),
                "Plain stone should fit in the empty slot");
        helper.assertValueEqual(componentSlots.get(0).getCount(), 63, "named stack count");
        helper.assertValueEqual(componentSlots.get(1).getCount(), 1, "plain stack count");

        List<ItemStack> fullSlots = new ArrayList<>(List.of(
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.DIRT, 64)));
        List<ItemStack> before = fullSlots.stream().map(ItemStack::copy).toList();
        helper.assertFalse(
                MainInventoryInsertion.insertAll(
                        fullSlots,
                        64,
                        List.of(new ItemStack(Items.COBBLESTONE), new ItemStack(Items.STICK))),
                "Insertion should reject a partial result");
        helper.assertTrue(ItemStack.matches(before.get(0), fullSlots.get(0)), "First slot changed on failure");
        helper.assertTrue(ItemStack.matches(before.get(1), fullSlots.get(1)), "Second slot changed on failure");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void craftingPlanDefensivelyCopiesMutableStacks(GameTestHelper helper) {
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 2));
        ContainerEndpoint sourceEndpoint = endpoint("copy", source);
        List<ItemStack> grid = new ArrayList<>(List.of(
                source.getItem(0).copyWithCount(1),
                ItemStack.EMPTY,
                source.getItem(0).copyWithCount(1),
                ItemStack.EMPTY));
        ItemStack output = new ItemStack(Items.STICK, 4);
        ItemStack remainder = new ItemStack(Items.BUCKET);
        ItemStack expected = source.getItem(0).copy();
        DirectCraftingPlan plan = new DirectCraftingPlan(
                recipe(helper, "stick"),
                2,
                grid,
                output,
                List.of(remainder),
                List.of(new DirectCraftingPlan.Extraction(sourceEndpoint, 0, 2, expected)));

        grid.set(0, ItemStack.EMPTY);
        output.setCount(1);
        remainder.setCount(0);
        expected.setCount(1);

        helper.assertTrue(plan.gridItems().get(0).is(Items.OAK_PLANKS), "Grid leaked caller mutation");
        helper.assertValueEqual(plan.output().getCount(), 4, "copied output count");
        helper.assertValueEqual(plan.remainingItems().getFirst().getCount(), 1, "copied remainder count");
        helper.assertValueEqual(
                plan.extractions().getFirst().expectedStack().getCount(), 2, "copied fingerprint count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void playerInputExtractionCanFreeOutputCapacity(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        ContainerEndpoint playerEndpoint = new ContainerEndpoint(
                "capacity:player", EndpointKind.PLAYER, null, player.getInventory(), 0, 36);
        DirectCraftingPlan plan = new DirectCraftingPlan(
                recipe(helper, "stick"),
                2,
                List.of(
                        new ItemStack(Items.OAK_PLANKS),
                        ItemStack.EMPTY,
                        new ItemStack(Items.OAK_PLANKS),
                        ItemStack.EMPTY),
                new ItemStack(Items.STICK, 4),
                List.of(),
                List.of(new DirectCraftingPlan.Extraction(
                        playerEndpoint, 0, 2, player.getInventory().getItem(0))));
        try {
            helper.assertTrue(
                    MainInventoryInsertion.canFitAfterExtractions(player.getInventory(), plan),
                    "Consumed player slot was not counted as output capacity");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void createsTwoByTwoRecipeFromNearbyChestAndFiresVanillaHooks(GameTestHelper helper) {
        ChestBlockEntity chest = prepareChest(helper);
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        ServerPlayer player = mockPlayer(helper);
        AtomicInteger craftedEvents = new AtomicInteger();
        Consumer<PlayerEvent.ItemCraftedEvent> listener = event -> {
            if (event.getEntity() == player && event.getCrafting().is(Items.STICK)) {
                craftedEvents.incrementAndGet();
            }
        };
        NeoForge.EVENT_BUS.addListener(PlayerEvent.ItemCraftedEvent.class, listener);
        try {
            CraftingResultCode result = DirectCraftingService.createOne(player, id("stick"));

            helper.assertValueEqual(result, CraftingResultCode.CREATED, "crafting result");
            helper.assertValueEqual(count(player, Items.STICK), 4, "crafted sticks");
            helper.assertValueEqual(chest.countItem(Items.OAK_PLANKS), 0, "remaining planks");
            helper.assertValueEqual(craftedEvents.get(), 1, "ItemCraftedEvent count");
            helper.assertValueEqual(
                    player.getStats().getValue(Stats.ITEM_CRAFTED.get(Items.STICK)),
                    4,
                    "crafted item statistic");
            helper.assertTrue(player.getRecipeBook().contains(recipe(helper, "stick")), "Recipe was not awarded");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void createsThreeByThreeRecipeAndReturnsContainerItems(GameTestHelper helper) {
        ChestBlockEntity chest = prepareChest(helper);
        helper.setBlock(TABLE_POS, Blocks.CRAFTING_TABLE);
        chest.setItem(0, new ItemStack(Items.MILK_BUCKET));
        chest.setItem(1, new ItemStack(Items.MILK_BUCKET));
        chest.setItem(2, new ItemStack(Items.MILK_BUCKET));
        chest.setItem(3, new ItemStack(Items.SUGAR, 2));
        chest.setItem(4, new ItemStack(Items.EGG));
        chest.setItem(5, new ItemStack(Items.WHEAT, 3));
        ServerPlayer player = mockPlayer(helper);
        try {
            CraftingResultCode result = DirectCraftingService.createOne(player, id("cake"));

            helper.assertValueEqual(result, CraftingResultCode.CREATED, "crafting result");
            helper.assertValueEqual(count(player, Items.CAKE), 1, "crafted cake");
            helper.assertValueEqual(count(player, Items.BUCKET), 3, "returned buckets");
            helper.assertTrue(chest.isEmpty(), "Chest ingredients were not consumed exactly");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void fullInventoryRejectsCraftWithoutConsumingContainer(GameTestHelper helper) {
        ChestBlockEntity chest = prepareChest(helper);
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        ServerPlayer player = mockPlayer(helper);
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        try {
            CraftingResultCode result = DirectCraftingService.createOne(player, id("stick"));

            helper.assertValueEqual(result, CraftingResultCode.NO_OUTPUT_SPACE, "crafting result");
            helper.assertValueEqual(chest.countItem(Items.OAK_PLANKS), 2, "planks after rejected craft");
            helper.assertValueEqual(count(player, Items.STICK), 0, "unexpected sticks");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void repeatedExecutionCannotSpendTheSameIngredientsTwice(GameTestHelper helper) {
        ChestBlockEntity chest = prepareChest(helper);
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        ServerPlayer player = mockPlayer(helper);
        try {
            CraftingResultCode first = DirectCraftingService.createOne(player, id("stick"));
            CraftingResultCode second = DirectCraftingService.createOne(player, id("stick"));

            helper.assertValueEqual(first, CraftingResultCode.CREATED, "first result");
            helper.assertValueEqual(second, CraftingResultCode.MISSING_INGREDIENTS, "second result");
            helper.assertValueEqual(count(player, Items.STICK), 4, "crafted sticks");
            helper.assertValueEqual(chest.countItem(Items.OAK_PLANKS), 0, "remaining planks");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void extractionFailureRestoresEveryTouchedSlot(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        SimpleContainer first = new SimpleContainer(new ItemStack(Items.OAK_PLANKS));
        SimpleContainer failing = new FailingRemovalContainer(new ItemStack(Items.OAK_PLANKS));
        ContainerEndpoint firstEndpoint = endpoint("rollback:first", first);
        ContainerEndpoint failingEndpoint = endpoint("rollback:failing", failing);
        DirectCraftingPlan plan = new DirectCraftingPlan(
                recipe(helper, "stick"),
                2,
                List.of(
                        new ItemStack(Items.OAK_PLANKS),
                        ItemStack.EMPTY,
                        new ItemStack(Items.OAK_PLANKS),
                        ItemStack.EMPTY),
                new ItemStack(Items.STICK, 4),
                List.of(),
                List.of(
                        new DirectCraftingPlan.Extraction(firstEndpoint, 0, 1, first.getItem(0)),
                        new DirectCraftingPlan.Extraction(failingEndpoint, 0, 1, failing.getItem(0))));
        try {
            CraftingResultCode result = DirectCraftingTransaction.execute(player, id("stick"), plan);

            helper.assertValueEqual(result, CraftingResultCode.ENVIRONMENT_CHANGED, "crafting result");
            helper.assertValueEqual(first.countItem(Items.OAK_PLANKS), 1, "first restored container");
            helper.assertValueEqual(failing.countItem(Items.OAK_PLANKS), 1, "failing restored container");
            helper.assertValueEqual(count(player, Items.STICK), 0, "unexpected output");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY_TEMPLATE)
    public static void changedStackFingerprintIsRejectedBeforeExtraction(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 2));
        ContainerEndpoint sourceEndpoint = endpoint("fingerprint", source);
        DirectCraftingPlan plan = new DirectCraftingPlan(
                recipe(helper, "stick"),
                2,
                List.of(
                        new ItemStack(Items.OAK_PLANKS),
                        ItemStack.EMPTY,
                        new ItemStack(Items.OAK_PLANKS),
                        ItemStack.EMPTY),
                new ItemStack(Items.STICK, 4),
                List.of(),
                List.of(new DirectCraftingPlan.Extraction(sourceEndpoint, 0, 2, source.getItem(0))));
        source.setItem(0, new ItemStack(Items.BIRCH_PLANKS, 2));
        try {
            CraftingResultCode result = DirectCraftingTransaction.execute(player, id("stick"), plan);

            helper.assertValueEqual(result, CraftingResultCode.ENVIRONMENT_CHANGED, "crafting result");
            helper.assertValueEqual(source.countItem(Items.BIRCH_PLANKS), 2, "changed source stack");
            helper.assertValueEqual(count(player, Items.STICK), 0, "unexpected output");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    private static ChestBlockEntity prepareChest(GameTestHelper helper) {
        helper.setBlock(CHEST_POS, Blocks.CHEST);
        // Vanilla refuses access to a chest with a solid block above it. The
        // borrowed bastion template is only a host, so normalize this cell.
        helper.setBlock(CHEST_POS.above(), Blocks.AIR);
        return helper.getBlockEntity(CHEST_POS);
    }

    @SuppressWarnings("removal")
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos position = helper.absolutePos(PLAYER_POS);
        player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        return player;
    }

    private static void removePlayer(ServerPlayer player) {
        EnvironmentSnapshotService.remove(player.getUUID());
        player.getServer().getPlayerList().remove(player);
    }

    @SuppressWarnings("unchecked")
    private static RecipeHolder<CraftingRecipe> recipe(GameTestHelper helper, String path) {
        RecipeHolder<?> holder = helper.getLevel().getRecipeManager().byKey(id(path)).orElseThrow();
        return (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder;
    }

    private static ContainerEndpoint endpoint(String id, SimpleContainer container) {
        return new ContainerEndpoint(id, EndpointKind.ENDER_CHEST, null, container, 0, container.getContainerSize());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static int count(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static List<ItemStack> emptySlots(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, ItemStack.EMPTY));
    }

    private static final class FailingRemovalContainer extends SimpleContainer {
        private FailingRemovalContainer(ItemStack stack) {
            super(stack);
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            return ItemStack.EMPTY;
        }
    }
}
