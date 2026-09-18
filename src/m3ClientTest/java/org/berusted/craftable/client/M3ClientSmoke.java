package org.berusted.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.inventory.RecipeBookType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.client.recipebook.*;
import org.berusted.craftable.network.CreateRecipeRequestPayload;

/** Opt-in real client/network/render acceptance, excluded from the production source set. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class M3ClientSmoke {
    private static int stage;
    private static int age;
    private static boolean started;
    private static volatile boolean configured;
    private static long quietSequence;
    private static long previewStart;
    private static final java.util.List<Long> previewSamples = new java.util.ArrayList<>();
    private static final BlockPos TABLE = new BlockPos(2, -60, 0);
    private static final BlockPos CHEST = new BlockPos(1, -60, 1);
    private static final ResourceLocation STICK = ResourceLocation.withDefaultNamespace("stick");

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("craftable.m3Smoke") || stage < 0) return;
        var mc = Minecraft.getInstance();
        try {
            if (!started) {
                if (!(mc.screen instanceof TitleScreen)) return;
                started = true;
                Craftable.LOGGER.warn("M3_SMOKE creating isolated test world");
                mc.createWorldOpenFlows().createFreshLevel("craftable-m3-smoke-" + System.currentTimeMillis(),
                        new LevelSettings("Craftable M3 smoke", GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(42, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(), mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
            if (++age > 600) throw new AssertionError("Timeout at stage " + stage);
            if (stage == 0) {
                var uuid = mc.player.getUUID();
                mc.getSingleplayerServer().execute(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                    var level = player.serverLevel();
                    player.teleportTo(0.5, -60, 0.5);
                    level.setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
                    level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
                    level.setBlockAndUpdate(CHEST.above(), Blocks.AIR.defaultBlockState());
                    ((ChestBlockEntity) level.getBlockEntity(CHEST)).setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
                    player.getInventory().clearContent();
                    configured = true;
                });
                advance();
            } else if (stage == 1 && configured && age > 60) {
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                // Match a real vanilla book toggle. A client-only flag is
                // overwritten by the first recipe-unlock packet's server
                // settings, folding the book and intentionally stopping demand.
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                        RecipeBookType.CRAFTING, true, false));
                mc.setScreen(new InventoryScreen(mc.player));
                advance();
            } else if (stage == 2 && mc.screen instanceof AmbientInventoryScreen screen) {
                var book = screen.getRecipeBookComponent();
                if (!book.isVisible()) book.toggleVisibility();
                ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.STICK.getDescription().getString());
                book.recipesUpdated();
                advance();
            } else if (stage == 3 && age > 80) {
                var button = button();
                Craftable.LOGGER.warn("M4_CLIENT stick status={} reason={} lifecycle={} generation={}",
                        RecipeButtonTargetResolver.status(button), ClientRecipeStatusStore.reason(STICK),
                        ClientRecipeStatusStore.lifecycle(STICK), ClientRecipeStatusStore.revision());
                require(RecipeButtonTargetResolver.status(button) == CraftingStatus.CRAFTABLE, "Chest-only stick not craftable");
                require(button.getCollection().hasCraftable(), "Vanilla collection not craftable");
                require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(STICK), "Wrong recipe variant selected");
                Screenshot.grab(mc.gameDirectory, "m3-smoke-craftable.png", mc.getMainRenderTarget(), ignored -> {});
                PacketDistributor.sendToServer(new CreateRecipeRequestPayload(STICK, ClientRequestSequence.next()));
                advance();
            } else if (stage == 4 && age > 40) {
                require(mc.player.getInventory().countItem(Items.STICK) == 4, "C did not create 4 sticks");
                Craftable.LOGGER.warn("M48_RECOVERY status={} reason={} lifecycle={} searches={} builds={}",
                        ClientRecipeStatusStore.get(STICK, false), ClientRecipeStatusStore.reason(STICK),
                        ClientRecipeStatusStore.lifecycle(STICK), org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches(),
                        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds());
                require(((AmbientInventoryScreen) mc.screen).getRecipeBookComponent().isVisible(), "Fixture unexpectedly folded recipe book");
                require(ClientRecipeStatusStore.get(STICK, false) == CraftingStatus.CRAFTABLE, "Repeat craft did not recover state");
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
                ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent().recipesUpdated();
                advance();
            } else if (stage == 5 && age > 40) {
                require(button().getCollection().hasCraftable(), "Craftable filter removed chest recipe");
                Screenshot.grab(mc.gameDirectory, "m3-smoke-filtered.png", mc.getMainRenderTarget(), ignored -> {});
                mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(TABLE, Blocks.AIR.defaultBlockState()));
                advance();
            } else if (stage == 6 && mc.player.containerMenu == mc.player.inventoryMenu && age > 20) {
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
                mc.setScreen(new InventoryScreen(mc.player));
                ((RecipeBookComponentAccessor) ((InventoryScreen) mc.screen).getRecipeBookComponent())
                        .craftable$getSearchBox().setValue(Items.DIAMOND_PICKAXE.getDescription().getString());
                ((InventoryScreen) mc.screen).getRecipeBookComponent().recipesUpdated();
                advance();
            } else if (stage == 7 && age > 80) {
                require(mc.screen instanceof InventoryScreen, "No-table menu was not vanilla 2x2");
                var button = button();
                require(RecipeButtonTargetResolver.status(button) == CraftingStatus.BLOCKED, "Pickaxe not blocked without table");
                require(ClientRecipeStatusStore.reason(RecipeButtonTargetResolver.preferredRecipe(button).id())
                        == org.berusted.craftable.api.CraftingResultCode.MISSING_WORKSTATION, "Missing workstation reason lost");
                String concise = net.minecraft.network.chat.Component.translatable("tooltip.craftable.blocked_reason",
                        net.minecraft.network.chat.Component.translatable("status.craftable.blocked"),
                        net.minecraft.network.chat.Component.translatable("reason.craftable.missing_workstation")).getString();
                require(button.getTooltipText().stream().filter(line -> line.getString().equals(concise)).count() == 1,
                        "Missing single-line status/reason tooltip");
                // Exercise the guarded left-click path while 3x3 ALL is shown on 2x2.
                mc.screen.mouseClicked(button.getX() + 5, button.getY() + 5, 0);
                Screenshot.grab(mc.gameDirectory, "m3-smoke-no-table.png", mc.getMainRenderTarget(), ignored -> {});
                advance();
            } else if (stage == 8) {
                stressRecipeBook(mc);
                if (age > 200) advance();
            } else if (stage == 9) {
                mc.player.closeContainer();
                mc.getSingleplayerServer().execute(() -> {
                    var player = serverPlayer();
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
                    player.openMenu(Blocks.CRAFTING_TABLE.defaultBlockState().getMenuProvider(level, TABLE));
                });
                advance();
            } else if (stage == 10 && mc.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen screen
                    && !(screen instanceof AmbientInventoryScreen)) {
                searchSticks(screen.getRecipeBookComponent());
                showSwordGhost(screen.getRecipeBookComponent());
                advance();
            } else if (stage == 11 && age > 60) {
                require(button().getCollection().hasCraftable(), "Physical workbench did not receive enhanced chest state");
                Screenshot.grab(mc.gameDirectory, "m3-smoke-workbench.png", mc.getMainRenderTarget(), ignored -> {});
                PacketDistributor.sendToServer(new CreateRecipeRequestPayload(STICK, ClientRequestSequence.next()));
                advance();
            } else if (stage == 12 && age > 30) {
                require(mc.player.getInventory().countItem(Items.STICK) == 8, "Physical workbench C failed");
                mc.player.closeContainer();
                mc.setScreen(new InventoryScreen(mc.player));
                advance();
            } else if (stage == 13 && mc.screen instanceof AmbientInventoryScreen) {
                mc.getSingleplayerServer().execute(() -> {
                    var player = serverPlayer();
                    player.containerMenu.getSlot(1).set(new ItemStack(Items.DIAMOND, 3));
                    player.containerMenu.setCarried(new ItemStack(Items.EMERALD, 2));
                    player.containerMenu.broadcastChanges();
                    player.setGameMode(GameType.CREATIVE);
                });
                advance();
            } else if (stage == 14 && age > 40) {
                require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen, "Creative catalog not restored");
                // Vanilla's creative catalog owns an ItemPickerMenu on the
                // client; it must not be confused with the temporary 3x3 menu.
                require(!(mc.player.containerMenu instanceof org.berusted.craftable.menu.AmbientInventoryMenu),
                        "Creative retained temporary menu");
                require(mc.player.getInventory().countItem(Items.DIAMOND) == 3, "Mode switch lost grid");
                require(mc.player.getInventory().countItem(Items.EMERALD) == 2, "Mode switch lost cursor");
                quietSequence = ClientRequestSequence.next();
                advance();
            } else if (stage == 15) {
                require(!RecipeBookProjection.active(), "Creative projection active");
                var key = new net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre(mc.screen,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_C, 0, 0);
                RecipeBookInputHandler.onKeyPressed(key);
                require(!key.isCanceled(), "Craftable consumed C in creative");
                if (age % 10 == 0) {
                    mc.player.closeContainer();
                    mc.setScreen(new InventoryScreen(mc.player)); // Same vanilla entry used by E.
                    require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen, "Creative E flicker/reopen failure");
                }
                if (age > 80) {
                    require(ClientRequestSequence.next() == quietSequence + 1, "Creative generated Craftable requests");
                    Screenshot.grab(mc.gameDirectory, "m3-smoke-creative.png", mc.getMainRenderTarget(), ignored -> {});
                    mc.getSingleplayerServer().execute(() -> serverPlayer().setGameMode(GameType.SURVIVAL));
                    advance();
                }
            } else if (stage == 16 && age > 40) {
                require(mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu, "Survival return auto-opened crafting");
                mc.setScreen(new InventoryScreen(mc.player));
                advance();
            } else if (stage == 17 && mc.screen instanceof AmbientInventoryScreen) {
                mc.getSingleplayerServer().execute(() -> serverPlayer().setGameMode(GameType.SPECTATOR));
                advance();
            } else if (stage == 18 && age > 40) {
                require(mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu, "Spectator retained custom screen/menu");
                require(!RecipeBookProjection.active(), "Spectator projection active");
                quietSequence = ClientRequestSequence.next();
                advance();
            } else if (stage == 19 && age > 40) {
                require(ClientRequestSequence.next() == quietSequence + 1, "Spectator generated Craftable requests");
                mc.getSingleplayerServer().execute(() -> serverPlayer().setGameMode(GameType.ADVENTURE));
                advance();
            } else if (stage == 20 && age > 40) {
                require(mc.screen == null, "Adventure return auto-opened inventory");
                mc.setScreen(new InventoryScreen(mc.player));
                advance();
            } else if (stage == 21 && mc.screen instanceof AmbientInventoryScreen && age > 30) {
                Screenshot.grab(mc.gameDirectory, "m3-smoke-inventory.png", mc.getMainRenderTarget(), ignored -> {});
                showSwordGhost(((AmbientInventoryScreen) mc.screen).getRecipeBookComponent());
                advance();
            } else if (stage == 22 && age > 20) {
                // Capture after rendered frames, not in the tick that sets up
                // the ghost. Compare compact output against the workbench shot.
                Screenshot.grab(mc.gameDirectory, "m3-smoke-inventory-ghost.png", mc.getMainRenderTarget(), ignored -> {});
                searchSticks(((AmbientInventoryScreen) mc.screen).getRecipeBookComponent());
                advance();
            } else if (stage == 23) {
                if (previewStart == 0) {
                    RecipeBookStatusHandler.afterCreate(ClientRequestSequence.next());
                    previewStart = System.nanoTime();
                } else if (ClientRecipeStatusStore.lifecycle(STICK) == ClientRecipeStatusStore.Lifecycle.KNOWN
                        && ClientRecipeStatusStore.reason(STICK) != org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED) {
                    previewSamples.add(System.nanoTime() - previewStart);
                    previewStart = 0;
                    if (previewSamples.size() < 40) return;
                    previewSamples.sort(Long::compare);
                    Craftable.LOGGER.warn("M4_CLIENT_PREVIEW warm_stick samples=40 p50={}ms p95={}ms max={}ms (invalidate through real network to known client state)",
                            previewSamples.get(19)/1e6, previewSamples.get(37)/1e6, previewSamples.get(39)/1e6);
                    require(previewSamples.get(37) <= 500_000_000L, "Warm visible target exceeded P95 500 ms");
                    advance();
                }
            } else if (stage == 24) {
                Craftable.LOGGER.warn("M3_SMOKE PASS: inventory layout and sword ghost, chest preview/filter/create, 200 ticks tab/index stress, physical workbench ghost, repeated creative E without requests, mode-switch returns, spectator isolation, explicit survival/adventure reopen");
                stage = -1;
                mc.stop();
            }
        } catch (Throwable failure) {
            Craftable.LOGGER.error("M3_SMOKE FAIL stage " + stage, failure);
            stage = -1;
            mc.stop();
        }
    }
    private static void advance() { stage++; age = 0; Craftable.LOGGER.warn("M3_SMOKE stage {}", stage); }
    private static void showSwordGhost(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent book) {
        var mc = Minecraft.getInstance();
        // Rendering-only fixture; no crafting placement or inventory mutation.
        book.setupGhostRecipe(mc.level.getRecipeManager()
                .byKey(ResourceLocation.withDefaultNamespace("diamond_sword")).orElseThrow(), mc.player.containerMenu.slots);
    }
    private static net.minecraft.server.level.ServerPlayer serverPlayer() {
        var mc = Minecraft.getInstance();
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }
    private static void searchSticks(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent book) {
        if (!book.isVisible()) book.toggleVisibility();
        Minecraft.getInstance().player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
        ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.STICK.getDescription().getString());
        book.recipesUpdated();
    }
    private static void require(boolean condition, String description) { if (!condition) throw new AssertionError(description); }
    @SuppressWarnings("unchecked")
    private static void stressRecipeBook(Minecraft mc) throws ReflectiveOperationException {
        var book = RecipeBookProjection.component(mc.screen);
        var accessor = (RecipeBookComponentAccessor) book;
        // Reflection is confined to this development-only harness. Deliberately
        // reproduce a previous animation frame's index after a collection rebind.
        var tabsField = net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class.getDeclaredField("tabButtons");
        tabsField.setAccessible(true);
        var tabs = (java.util.List<net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton>) tabsField.get(book);
        accessor.craftable$getSearchBox().setValue(age % 5 == 0 ? Items.STICK.getDescription().getString() : "");
        mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, age % 3 == 0);
        book.recipesUpdated();
        var tab = tabs.get(age % tabs.size());
        if (tab.visible) book.mouseClicked(tab.getX() + 5, tab.getY() + 5, 0);
        var indexField = RecipeButton.class.getDeclaredField("currentIndex");
        indexField.setAccessible(true);
        for (var button : ((RecipeBookPageAccessor) accessor.craftable$getRecipeBookPage()).craftable$getButtons()) {
            if (!button.visible) continue;
            indexField.setInt(button, 9);
            RecipeButtonTargetResolver.status(button);
            RecipeButtonTargetResolver.lifecycle(button);
            RecipeButtonTargetResolver.preferredRecipe(button);
            button.getRecipe();
            indexField.setInt(button, 3);
            button.getTooltipText();
        }
    }
    private static RecipeButton button() {
        var book = RecipeBookProjection.component(Minecraft.getInstance().screen);
        require(book != null, "No recipe book");
        var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
        return ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible).findFirst()
                .orElseThrow(() -> new AssertionError("No visible recipe button"));
    }
}
