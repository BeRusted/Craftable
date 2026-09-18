package org.berusted.craftable.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.execution.CraftingService;
import org.berusted.craftable.planner.CraftRequest;

/** Isolated, opt-in client/network/UI acceptance. Reflection inspects UI state
 * only; every confirmation still travels through the real network handler. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class M4ClientSmoke {
    private static int stage, age;
    private static boolean started;
    private static boolean queuedCancellationChecked;
    private static volatile boolean configured;
    private static Object menu;
    private static int oldScale, oldWidth, oldHeight;
    private static String oldLanguage;
    private static long previewStart;
    private static int browsePhase, browseStarted;
    private static long browseSearches, browseCompleted;
    private static long browseStartNanos;
    private static int rangeReturns;
    private static final java.util.List<Double> filterTimings = new java.util.ArrayList<>();
    private static final java.util.List<Double> filterCpu = new java.util.ArrayList<>();
    private static int browseBuilds;
    private static long serverDetails, serverMaximums;
    private static int reviewMismatchPhase;
    private static final java.util.List<Long> timings = new java.util.ArrayList<>();
    private static final BlockPos CHEST = new BlockPos(1, -60, 1);
    private static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    // Observe after the overlay has sent this tick's request. At the same
    // priority a fast integrated-server reply can arrive before our next tick,
    // making the in-flight cancellation fixture miss the entire pending state.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("craftable.m4Smoke") || stage < 0) return;
        var mc = Minecraft.getInstance();
        try {
            if (!started) {
                if (!(mc.screen instanceof TitleScreen)) return;
                started = true;
                oldScale = mc.options.guiScale().get();
                oldWidth = mc.getWindow().getScreenWidth(); oldHeight = mc.getWindow().getScreenHeight();
                oldLanguage = mc.getLanguageManager().getSelected();
                mc.createWorldOpenFlows().createFreshLevel("craftable-m4-smoke-" + System.currentTimeMillis(),
                        new LevelSettings("Craftable M4 smoke", GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(), mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
            if (overlay() != null && draft() != null && draft().view().code() == CraftingResultCode.CREATED
                    && !(boolean) field(overlay(), "dirty") && !((CraftRequest) field(overlay(), "intent")).partial()
                    && field(overlay(), "pane").toString().equals("GRAPH")
                    && field(overlay(), "pending") != null && field(overlay(), "pending").toString().equals("MAXIMUM")
                    && field(overlay(), "queuedAction") == null)
                require(((Button) field(overlay(), "create")).active, "MAX slice blinked the confirm button");
            if (++age > (stage == 3 ? 1400 : 600)) throw new AssertionError("Timeout at stage " + stage + ", pending="
                    + (overlay() == null ? "closed" : field(overlay(), "pending") + ", max=" + field(overlay(), "maximum")
                    + ", dirty=" + field(overlay(), "dirty") + ", draft=" + (draft() == null ? "none" : draft().view().code())));
            if (stage == 0) {
                mc.getSingleplayerServer().execute(() -> {
                    var player = serverPlayer();
                    player.teleportTo(0.5, -60, 0.5);
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                    level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
                    level.setBlockAndUpdate(CHEST.above(), Blocks.AIR.defaultBlockState());
                    stock(true);
                    configured = true;
                });
                next();
            } else if (stage == 1 && configured && age > 60) {
                if (!org.berusted.craftable.recipe.M48ClientKnowledgeProbe.sliced(mc)) return;
                org.berusted.craftable.recipe.M48ClientKnowledgeProbe.measure(mc);
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                mc.setScreen(new InventoryScreen(mc.player));
                next();
            } else if (stage == 2 && mc.screen instanceof AmbientInventoryScreen screen) {
                var book = screen.getRecipeBookComponent();
                if (!book.isVisible()) book.toggleVisibility();
                ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.DIAMOND_PICKAXE.getDescription().getString());
                book.recipesUpdated();
                next();
            } else if (stage == 3 && age > 80) {
                var liveBook = ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent();
                var liveSearch = ((RecipeBookComponentAccessor) liveBook).craftable$getSearchBox();
                if (browsePhase == 0) {
                    browseSearches = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches();
                    browseBuilds = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds();
                    browseStarted = age;
                    browseStartNanos = System.nanoTime();
                    mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
                    liveSearch.setValue(""); liveBook.recipesUpdated();
                    org.berusted.craftable.client.recipebook.ClientBrowsePlanner.invalidate();
                    browsePhase = 5; return;
                }
                if (browsePhase == 1) {
                    filterCpu.add(org.berusted.craftable.client.recipebook.ClientBrowsePlanner.lastTickNanos() / 1e6);
                    var ids = org.berusted.craftable.client.recipebook.RecipeBookProjection.scope(liveBook).stream()
                            .flatMap(c -> org.berusted.craftable.client.recipebook.RecipeBookProjection.candidates(c).stream())
                            .map(r -> r.id()).distinct().toList();
                    long completed = ids.stream().filter(org.berusted.craftable.client.recipebook.ClientRecipeStatusStore::computed).count();
                    if (completed != ids.size() && age - browseStarted < 400) return;
                    require(completed == ids.size(), "Local hidden filtering did not finish within 400 ticks: " + completed + "/" + ids.size());
                    long unknown = ids.stream().filter(id -> org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.reason(id)
                            == CraftingResultCode.SEARCH_BUDGET_EXCEEDED).count();
                    var cpu = filterCpu.stream().sorted().toList();
                    Craftable.LOGGER.warn("M48_LIVE_FILTER targets={} ticks={} quantityTasks={} unknown={} catalogBuilds={} cpuP95Ms={} cpuMaxMs={}",
                            ids.size(), age - browseStarted, org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches() - browseSearches,
                            unknown, org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds(),
                            cpu.get((int) Math.ceil(cpu.size() * .95) - 1), cpu.getLast());
                    filterCpu.clear();
                    filterTimings.add((System.nanoTime() - browseStartNanos) / 1e6);
                    require(unknown == 0, "Stopped unknown work is not filter convergence");
                    if (filterTimings.size() < 5) {
                        // A fresh dynamic session must capture/transfer/bind again,
                        // but the connection's static catalog must survive.
                        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.invalidate();
                        browseStarted = age; browseStartNanos = System.nanoTime();
                        browseSearches = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches();
                        browsePhase = 5;
                        return;
                    }
                    var sorted = filterTimings.stream().sorted().toList();
                    Craftable.LOGGER.warn("M48_FILTER_SESSIONS samples=5 ms={} P50={} P95={} max={} catalogBuilds={}",
                            filterTimings, sorted.get(2), sorted.get(4), sorted.get(4),
                            org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds());
                    browseCompleted = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches();
                    browseStarted = age; browsePhase = 2;
                    liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated(); return;
                }
                if (browsePhase == 5) {
                    // invalidate() hides authority immediately; wait for the new
                    // snapshot so old conclusions cannot finish a cold sample.
                    if (!org.berusted.craftable.client.recipebook.ClientBrowsePlanner.ready()) return;
                    browsePhase = 1; return;
                }
                if (browsePhase == 2) {
                    if (age - browseStarted < 1) return;
                    liveSearch.setValue(""); liveBook.recipesUpdated(); browseStarted = age; browsePhase = 3; return;
                }
                if (browsePhase == 3) {
                    if (age - browseStarted < 1) return;
                    require(org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches() == browseCompleted,
                            "Same-version category/search return restarted searches");
                    require(org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds() == browseBuilds,
                            "Category/search changed static catalog generation");
                    if (++rangeReturns < 100) {
                        liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated();
                        browseStarted = age; browsePhase = 2; return;
                    }
                    Craftable.LOGGER.warn("M48_LIVE_REUSE completeRangeReturns=100 newSearches=0 unchangedLease=0Rebuilds");
                    var ids = org.berusted.craftable.client.recipebook.RecipeBookProjection.scope(liveBook).stream()
                            .flatMap(c -> org.berusted.craftable.client.recipebook.RecipeBookProjection.candidates(c).stream())
                            .map(r -> r.id()).distinct().toList();
                    M48SchedulingProbe.start(ids); browsePhase = 6; return;
                }
                if (browsePhase == 6) {
                    if (!M48SchedulingProbe.complete()
                            || !org.berusted.craftable.client.recipebook.ClientBrowsePlanner.ready()
                            || !org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.computed(PICK)) return;
                    liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated();
                    browsePhase = 4;
                }
                require(org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.lifecycle(PICK)
                        == org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.Lifecycle.KNOWN,
                        "Production local snapshot never became authorized");
                require(org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.get(PICK, false)
                        == org.berusted.craftable.api.CraftingStatus.CRAFTABLE,
                        "Production local recursive preview failed: " + org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.reason(PICK));
                var book = ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent();
                var accessor = (RecipeBookComponentAccessor) book;
                accessor.craftable$getSearchBox().setFocused(false);
                var page = accessor.craftable$getRecipeBookPage();
                var button = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                        && b.getCollection().getRecipes().stream().anyMatch(r -> r.id().equals(PICK))).findFirst().orElseThrow();
                var hover = RecipeBookPage.class.getDeclaredField("hoveredButton");
                hover.setAccessible(true); hover.set(page, button);
                menu = mc.player.containerMenu;
                serverDetails = org.berusted.craftable.network.CraftablePayloadHandlers.detailRequests();
                serverMaximums = org.berusted.craftable.network.CraftablePayloadHandlers.maximumRequests();
                key(67, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
                require(CraftingPlanOverlay.active(), "Shift+C did not open embedded detail");
                require(mc.player.containerMenu == menu, "Detail replaced parent menu");
                next();
            } else if (stage == 4 && ready()) {
                var draft = draft();
                require(draft.view().code() == CraftingResultCode.CREATED, "Full preview not craftable: " + draft.view().code());
                require(draft.view().operations().size() == 3, "Preview lost recursive steps");
                require(draft.token().equals(org.berusted.craftable.network.CraftingDetailPayloads.NO_TOKEN),
                        "Local browsing manufactured server execution authority");
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Preview mutated inventory");
                var maximum = (CraftingService.Maximum) field(overlay(), "maximum");
                require(maximum.lowerBound() == 1, "Wrong full maximum");
                shot("graph");
                // Exercise the graph hit-test and input interception, not a private chooser call.
                var graph = field(overlay(), "graph");
                var cells = (List<?>) field(graph, "cells");
                var cell = cells.stream().filter(c -> {
                    try { return ((List<?>) field(c, "recipes")).contains("minecraft:stick"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }).findFirst().orElseThrow();
                var widget = (net.minecraft.client.gui.components.AbstractWidget) graph;
                require(widget.getHeight() >= mc.screen.height * 0.65, "Graph still squeezed by toolbar");
                var create = (Button) field(overlay(), "create");
                var slider = (net.minecraft.client.gui.components.AbstractWidget) field(overlay(), "slider");
                require(create.getY() == slider.getY(), "Bottom actions not on one row");
                require(allButtons(overlay()).stream().noneMatch(b -> b.visible && b.getY() < 34), "Old top navigation remains");
                click(widget.getX() + (int) field(cell, "x") + (int) field(graph, "panX") + 8,
                        widget.getY() + (int) field(cell, "y") + (int) field(graph, "panY") + 8);
                next();
            } else if (stage == 5 && age > 60 && field(overlay(), "pending") == null) {
                require(field(overlay(), "pane").toString().equals("CHOICES"), "Node click missed chooser");
                var states = (java.util.Map<?, ?>) field(overlay(), "candidateStates");
                require(states.get(ResourceLocation.withDefaultNamespace("stick")) == CraftingResultCode.CREATED, "Usable candidate not verified globally");
                require(states.get(ResourceLocation.withDefaultNamespace("stick_from_bamboo_item")) != CraftingResultCode.CREATED, "Bamboo candidate falsely available");
                shot("choices");
                var next = (Button) field(overlay(), "nextChoice");
                click(next.getX() + 5, next.getY() + 5);
                require((int) field(overlay(), "choicePage") == 1, "Next recipe did not change card");
                shot("bamboo-card");
                var apply = (Button) field(overlay(), "applyChoice");
                click(apply.getX() + 5, apply.getY() + 5);
                next();
            } else if (stage == 6 && ready()) {
                require(org.berusted.craftable.network.CraftablePayloadHandlers.detailRequests() == serverDetails
                        && org.berusted.craftable.network.CraftablePayloadHandlers.maximumRequests() == serverMaximums,
                        "Passive details/candidates/MAX still searched on server");
                Craftable.LOGGER.warn("M48_LOCAL_DETAILS previews/candidates/MAX serverRequests=0");
                require(draft().view().code() != CraftingResultCode.CREATED, "Unavailable pin silently ignored");
                require(mc.player.getInventory().countItem(Items.STICK) == 0, "Candidate selection crafted materials");
                shot("blocked-pin");
                key(256, 0);
                require(!CraftingPlanOverlay.active() && mc.player.containerMenu == menu && mc.screen instanceof AmbientInventoryScreen,
                        "Esc closed/replaced the parent menu");
                CraftingPlanOverlay.open(mc.screen, PICK, false);
                next();
            } else if (stage == 7 && ready()) {
                var create = (Button) field(overlay(), "create");
                require(create.active, "Full confirm disabled");
                if (reviewMismatchPhase == 0) {
                    // Simulate a stale/mismatching client review, not changed
                    // server inventory. The first click may obtain authority
                    // but must not silently accept different material costs.
                    var original = draft(); var view = original.view();
                    var changed = new org.berusted.craftable.planner.PlanView(view.code(), view.workbench(), view.complete(),
                            view.completedBatches(), view.nodes(), view.operations(), List.of(new ItemStack(Items.OAK_LOG, 2)),
                            view.primary(), view.surplus(), view.drops(), view.missing());
                    var field = overlay().getClass().getDeclaredField("draft"); field.setAccessible(true);
                    field.set(overlay(), new CraftingService.Draft(original.token(), changed, original.choices()));
                    click(create.getX() + 5, create.getY() + 5);
                    reviewMismatchPhase = 1;
                    return;
                }
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0,
                        "Changed authoritative review auto-executed without a second click");
                require(!draft().token().equals(org.berusted.craftable.network.CraftingDetailPayloads.NO_TOKEN),
                        "Explicit review never reached the server");
                click(create.getX() + 5, create.getY() + 5);
                next();
            } else if (stage == 8 && age > 30 && ready()) {
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 1, "Confirm did not deliver pickaxe");
                require(mc.player.getInventory().countItem(Items.STICK) == 2, "Wrong stick surplus");
                require(mc.player.getInventory().countItem(Items.OAK_PLANKS) == 2, "Wrong plank surplus");
                require(mc.player.containerMenu == menu, "Confirmation changed menu");
                key(256, 0);
                configured = false;
                mc.getSingleplayerServer().execute(() -> { stock(false); configured = true; });
                next();
            } else if (stage == 9 && configured && age > 30) {
                CraftingPlanOverlay.open(mc.screen, PICK, false);
                next();
            } else if (stage == 10 && ready()) {
                require(draft().view().code() == CraftingResultCode.PARTIAL_CREATED, "Missing diamond not shown as partial");
                shot("partial-graph");
                var partial = (Button) field(overlay(), "partial");
                require(partial.active, "Partial review disabled");
                click(partial.getX() + 5, partial.getY() + 5);
                next();
            } else if (stage == 11 && ready()) {
                require(((CraftRequest) field(overlay(), "intent")).partial(), "Review did not select partial intent");
                require(mc.player.getInventory().countItem(Items.STICK) == 0, "First partial click consumed before review");
                var partial = (Button) field(overlay(), "partial");
                require(partial.active, "Reviewed partial confirm disabled");
                click(partial.getX() + 5, partial.getY() + 5);
                next();
            } else if (stage == 12 && age > 30 && ready()) {
                require(mc.player.getInventory().countItem(Items.STICK) == 4, "Partial did not deliver one whole stick batch");
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Partial fabricated root");
                require(!((Button) field(overlay(), "partial")).active, "Existing frontier allowed redundant partial");
                shot("partial-completed");
                key(256, 0);
                configured = false;
                mc.getSingleplayerServer().execute(() -> {
                    var player = serverPlayer();
                    player.getInventory().clearContent();
                    var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(CHEST);
                    chest.clearContent();
                    chest.setItem(0, new ItemStack(Items.BIRCH_LOG));
                    chest.setItem(1, new ItemStack(Items.OAK_LOG, 2));
                    chest.setChanged(); player.containerMenu.broadcastChanges(); configured = true;
                });
                next();
            } else if (stage == 13 && configured && age > 30) {
                CraftingPlanOverlay.open(mc.screen, ResourceLocation.withDefaultNamespace("stick"), false);
                next();
            } else if (stage == 14 && ready()) {
                require(((CraftingService.Maximum) field(overlay(), "maximum")).lowerBound() == 6, "Mixed stock MAX not six batches");
                var slider = (net.minecraft.client.gui.components.AbstractSliderButton) field(overlay(), "slider");
                require(slider.active, "Known MAX did not enable slider");
                click(slider.getX() + slider.getWidth() - 4, slider.getY() + 10);
                next();
            } else if (stage == 15 && ready()) {
                require(((CraftRequest) field(overlay(), "intent")).batches() == 6, "Slider did not choose six batches");
                require(draft().view().code() == CraftingResultCode.CREATED, "Mixed MAX is not executable");
                require((boolean) field(overlay(), "routeChanged"), "Mixed route warning missing");
                shot("mixed-graph");
                var create = (Button) field(overlay(), "create");
                click(create.getX() + 5, create.getY() + 5);
                require(field(overlay(), "pane").toString().equals("COSTS"), "Mixed route skipped actual cost review");
                require(mc.player.getInventory().countItem(Items.STICK) == 0, "Mixed route spent before second confirmation");
                next();
            } else if (stage == 16 && age > 15 && ready()) {
                shot("mixed-costs");
                var create = (Button) field(overlay(), "create");
                click(create.getX() + 5, create.getY() + 5);
                next();
            } else if (stage == 17 && age > 30 && ready()) {
                require(mc.player.getInventory().countItem(Items.STICK) == 24, "Mixed MAX did not yield 24 sticks");
                key(256, 0);
                configured = false;
                mc.getSingleplayerServer().execute(() -> { stock(true); configured = true; });
                next();
            } else if (stage == 18 && configured && age > 30) {
                CraftingPlanOverlay.open(mc.screen, PICK, false);
                next();
            } else if (stage == 19 && !queuedCancellationChecked && draft() != null
                    && draft().view().code() == CraftingResultCode.CREATED
                    && field(overlay(), "pending") != null && field(overlay(), "pending").toString().equals("MAXIMUM")) {
                var create = (Button) field(overlay(), "create");
                click(create.getX() + 5, create.getY() + 5);
                require(Boolean.FALSE.equals(field(overlay(), "queuedAction")), "MAX click was lost instead of queued once");
                key(256, 0);
                require(!CraftingPlanOverlay.active(), "Esc did not cancel waiting action");
                queuedCancellationChecked = true;
                CraftingPlanOverlay.open(mc.screen, PICK, false);
            } else if (stage == 19 && ready()) {
                require(queuedCancellationChecked, "Real in-flight MAX cancellation was not exercised");
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Canceled queued click still crafted");
                mc.options.guiScale().set(1); mc.resizeDisplay();
                next();
            } else if (stage == 20 && age > 15 && ready()) {
                shot("scale-1");
                mc.getWindow().setWindowed(1280, 960);
                mc.options.guiScale().set(3); mc.resizeDisplay();
                next();
            } else if (stage == 21 && age > 15 && ready()) {
                require(mc.getWindow().getGuiScale() == 3, "Scale-three fixture not actually applied");
                shot("scale-3");
                mc.getLanguageManager().setSelected("en_us"); mc.options.languageCode = "en_us";
                mc.reloadResourcePacks();
                next();
            } else if (stage == 22 && age > 100 && mc.getOverlay() == null && ready()) {
                require(net.minecraft.network.chat.Component.translatable("screen.craftable.plan.graph").getString().equals("Item chain"),
                        "English translation not loaded");
                shot("english");
                next();
            } else if (stage == 23) {
                if (previewStart == 0 && ready()) {
                    pressButton(net.minecraft.network.chat.Component.translatable("screen.craftable.plan.refresh").getString());
                    previewStart = System.nanoTime();
                } else if (previewStart != 0 && draft() != null && !(boolean) field(overlay(), "dirty")) {
                    timings.add(System.nanoTime() - previewStart); previewStart = 0;
                    if (timings.size() == 40) {
                        var sorted = timings.stream().sorted().toList();
                        long p95 = sorted.get(37);
                        Craftable.LOGGER.warn("M4_SMOKE detail 40 refreshes P50={}ms P95={}ms MAX={}ms",
                                sorted.get(19) / 1e6, p95 / 1e6, sorted.getLast() / 1e6);
                        require(p95 <= 500_000_000L, "Ordinary detail P95 exceeded 500 ms");
                        next();
                    }
                }
            } else if (stage == 24 && ready()) {
                mc.getSingleplayerServer().execute(() -> {
                    var player = serverPlayer();
                    player.containerMenu.getSlot(1).set(new ItemStack(Items.EMERALD, 3));
                    player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 2));
                    player.containerMenu.broadcastChanges();
                    player.setGameMode(GameType.CREATIVE);
                });
                next();
            } else if (stage == 25 && age > 40) {
                require(!CraftingPlanOverlay.active(), "Mode change retained detail");
                require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen,
                        "Mode change lost vanilla creative UI");
                require(mc.player.getInventory().countItem(Items.EMERALD) == 3, "Mode change lost grid");
                require(mc.player.getInventory().countItem(Items.GOLD_INGOT) == 2, "Mode change lost cursor");
                mc.setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(
                        net.neoforged.fml.ModList.get().getModContainerById(Craftable.MOD_ID).orElseThrow(), mc.screen));
                next();
            } else if (stage == 26 && age > 20) {
                shot("config-index");
                var label = net.minecraft.network.chat.Component.translatable("craftable.configuration.section.craftable.client.toml").getString();
                var button = allButtons(mc.screen).stream().filter(b -> b.getMessage().getString().contains(label)).findFirst().orElseThrow();
                require(button.active, "Local client preferences unavailable");
                mc.screen.mouseClicked(button.getX() + 5, button.getY() + 5, 0);
                next();
            } else if (stage == 27 && age > 20) {
                require(mc.screen instanceof net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen,
                        "Native configuration section did not open");
                shot("config-client");
                Craftable.LOGGER.warn("M4_SMOKE PASS: real Shift+C, candidates/pin, mixed MAX/slider/cost review, full/partial, GUI scales 1/2/3, zh/en, detail latency, menu/mode isolation");
                stage = -1;
                restoreOptions();
                mc.stop();
            }
        } catch (Throwable error) {
            Craftable.LOGGER.error("M4_SMOKE FAIL stage " + stage, error);
            stage = -1;
            restoreOptions();
            mc.stop();
        }
    }

    private static void stock(boolean diamonds) {
        var player = serverPlayer();
        player.getInventory().clearContent();
        var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(CHEST);
        chest.clearContent(); chest.setItem(0, new ItemStack(Items.OAK_LOG));
        if (diamonds) chest.setItem(1, new ItemStack(Items.DIAMOND, 3));
        chest.setChanged(); player.containerMenu.broadcastChanges();
    }
    private static void restoreOptions() {
        if (!started) return;
        var mc = Minecraft.getInstance();
        mc.options.guiScale().set(oldScale);
        mc.options.languageCode = oldLanguage;
        mc.getLanguageManager().setSelected(oldLanguage);
        mc.getWindow().setWindowed(oldWidth, oldHeight);
        mc.options.save();
    }
    private static java.util.List<Button> allButtons(net.minecraft.client.gui.components.events.GuiEventListener listener) {
        var result = new java.util.ArrayList<Button>();
        if (listener instanceof Button button) result.add(button);
        if (listener instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container)
            container.children().forEach(child -> result.addAll(allButtons(child)));
        return result;
    }
    private static net.minecraft.server.level.ServerPlayer serverPlayer() {
        var mc = Minecraft.getInstance();
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }
    private static Object field(Object object, String name) throws Exception {
        var field = (object == null ? CraftingPlanOverlay.class : object.getClass()).getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }
    private static CraftingPlanOverlay overlay() throws Exception { return (CraftingPlanOverlay) field(null, "current"); }
    private static CraftingService.Draft draft() throws Exception { return (CraftingService.Draft) field(overlay(), "draft"); }
    private static boolean ready() throws Exception {
        if (overlay() == null || field(overlay(), "pending") != null || (boolean) field(overlay(), "dirty") || draft() == null) return false;
        var maximum = (CraftingService.Maximum) field(overlay(), "maximum");
        return maximum != null && !maximum.pending();
    }
    private static void click(double x, double y) {
        Screen parent = Minecraft.getInstance().screen;
        var press = new ScreenEvent.MouseButtonPressed.Pre(parent, x, y, 0);
        NeoForge.EVENT_BUS.post(press);
        require(press.isCanceled(), "Overlay allowed click-through");
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(parent, x, y, 0));
    }
    private static void key(int key, int modifiers) {
        var event = new ScreenEvent.KeyPressed.Pre(Minecraft.getInstance().screen, key, 0, modifiers);
        NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "Expected key not consumed: " + key);
    }
    private static void pressButton(String suffix) throws Exception {
        var button = overlay().children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().endsWith(suffix))
                .map(c -> (Button) c).findFirst().orElseThrow();
        click(button.getX() + 5, button.getY() + 5);
    }
    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "m4-smoke-" + name + ".png", mc.getMainRenderTarget(), ignored -> {});
    }
    private static void next() { stage++; age = 0; Craftable.LOGGER.warn("M4_SMOKE stage {}", stage); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
