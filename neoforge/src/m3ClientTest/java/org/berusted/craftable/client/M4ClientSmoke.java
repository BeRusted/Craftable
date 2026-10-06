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
    private static boolean oldPauseOnLostFocus;
    private static boolean queuedCancellationChecked;
    private static long queuedCancellationWitnesses, queuedCancellationFull, queuedCancellationPartial;
    private static volatile boolean configured;
    private static Object menu;
    private static int oldScale, oldWidth, oldHeight;
    private static String oldLanguage;
    private static long previewStart;
    private static final M4BrowsingScenario browsing = new M4BrowsingScenario();
    private static final M4OutputVariantScenario outputVariants = new M4OutputVariantScenario();
    private static final M4DetailQuantityScenario detailQuantity = new M4DetailQuantityScenario();
    private static final M4BulkQuantityScenario bulkQuantity = new M4BulkQuantityScenario();
    private static long serverDetails;
    private static long witnessBefore, fullSearchesBefore;
    private static int reviewMismatchPhase;
    private static int partialUiPhase, refreshedPanY;
    private static final java.util.List<Long> timings = new java.util.ArrayList<>();
    private static final BlockPos CHEST = new BlockPos(1, -60, 1);
    private static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    // A MAX certificate can finish in the same Post event in which the
    // overlay asks for it. Drive the ordinary overlay tick before the planner
    // callback and inspect its real pending request synchronously; the fixture
    // must not require a calculation to remain slow for a whole game tick.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void observeQueuedCancellation(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("craftable.m4Smoke") || stage != 19 || queuedCancellationChecked) return;
        var mc = Minecraft.getInstance();
        try {
            var self = overlay();
            if (self == null || draft() == null || draft().view().code() != CraftingResultCode.CREATED
                    || (boolean) field(self, "dirty")) return;
            self.tick(); // Uses the real debounce, request identity and local MAX scheduler.
            if (field(self, "pending") != null && field(self, "pending").toString().equals("MAXIMUM"))
                cancelQueuedMaximum(mc);
        } catch (Throwable error) {
            Craftable.LOGGER.error("M4_SMOKE FAIL stage " + stage, error);
            stage = -1;
            restoreOptions();
            mc.stop();
        }
    }

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
                // Keep integrated-server progress aligned with assertion
                // ticks even if this automated window runs in the background.
                oldPauseOnLostFocus = mc.options.pauseOnLostFocus;
                mc.options.pauseOnLostFocus = false;
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
                    && Boolean.FALSE.equals(field(overlay(), "queuedAction")))
                require(((Button) field(overlay(), "create")).active, "MAX slice blinked the confirm button");
            if (++age > (stage == 3 || stage == 30 ? 1400 : 600)) throw new AssertionError("Timeout at stage " + stage + ", pending="
                    + (overlay() == null ? "closed" : field(overlay(), "pending") + ", max=" + field(overlay(), "maximum")
                    + ", dirty=" + field(overlay(), "dirty") + ", draft=" + (draft() == null ? "none" : draft().view().code())));
            if (stage <= 3) setupAndBrowse(mc);
            else if (stage <= 19) detailsAndCrafting(mc);
            else lifecycleAndPresentation(mc);
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
    private static void setupAndBrowse(Minecraft mc) throws Exception {
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
                if (!browsing.tick(mc, age, PICK)) return;
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
                key(67, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
                require(CraftingPlanOverlay.active(), "Shift+C did not open embedded detail");
                require(mc.player.containerMenu == menu, "Detail replaced parent menu");
                next();
            }
    }

    private static void detailsAndCrafting(Minecraft mc) throws Exception {
            if (stage == 4 && ready()) {
                M4DiagnosticLifecycleScenario.verify();
                M4GraphPresentationScenario.verify();
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
                require(create.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("screen.craftable.plan.create").getString()),
                        "Primary action no longer has one stable label");
                require(allButtons(overlay()).stream().filter(b -> b.visible && b.getY() == create.getY()).count() == 1,
                        "Old partial/refresh actions remain on bottom row");
                var refresh = (Button) field(overlay(), "refresh");
                require(refresh.getWidth() == 20 && refresh.getY() < create.getY(), "Refresh is still a full-size primary action");
                require(allButtons(overlay()).stream().noneMatch(b -> b.visible && b.getY() < 34), "Old top navigation remains");
                click(widget.getX() + (int) field(cell, "x") + (int) field(graph, "panX") + 8,
                        widget.getY() + (int) field(cell, "y") + (int) field(graph, "panY") + 8);
                next();
            } else if (stage == 5 && age == 30) {
                // Hover the first ingredient for several rendered frames before
                // capturing: this reproduces later-slot blits covering its tooltip.
                int cx = mc.screen.width / 2, cy = Math.max(24, (mc.screen.height - 76) / 2 - 38);
                var window = mc.getWindow();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.getWindow(),
                        (cx - 62.0) * window.getScreenWidth() / mc.screen.width,
                        (cy + 25.0) * window.getScreenHeight() / mc.screen.height);
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
                require(org.berusted.craftable.network.CraftablePayloadHandlers.detailRequests() == serverDetails,
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
                    witnessBefore = executionCounter("witnessValidations");
                    fullSearchesBefore = executionCounter("activeFullSearches");
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
                require(executionCounter("witnessValidations") >= witnessBefore + 2, "Full detail did not validate witness twice");
                require(executionCounter("activeFullSearches") == fullSearchesBefore, "Full detail re-searched on server");
                Craftable.LOGGER.warn("M49_CLIENT full detail review+confirm witnessValidations=2 fullSearches=0");
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
                if (partialUiPhase == 0) {
                    require(((CraftRequest) field(overlay(), "intent")).partial(), "Partial frontier was not selected for read-only display");
                    var graph = (PlanGraphWidget) field(overlay(), "graph");
                    graph.mouseScrolled(graph.getX() + 2, graph.getY() + 2, 0, 1);
                    refreshedPanY = (int) field(graph, "panY");
                    mc.getSingleplayerServer().execute(() -> {
                        var chest = (ChestBlockEntity) serverPlayer().serverLevel().getBlockEntity(CHEST);
                        chest.setItem(1, new ItemStack(Items.DIAMOND, 3)); chest.setChanged();
                    });
                    partialUiPhase = 1;
                    return;
                }
                if (partialUiPhase == 1) {
                    if (draft().view().code() != CraftingResultCode.CREATED) return;
                    require(!((CraftRequest) field(overlay(), "intent")).partial(), "Automatic full refresh retained partial intent");
                    require((int) field(field(overlay(), "graph"), "panY") == refreshedPanY, "Automatic refresh reset graph position");
                    require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Auto-refresh crafted without a click");
                    mc.getSingleplayerServer().execute(() -> {
                        var chest = (ChestBlockEntity) serverPlayer().serverLevel().getBlockEntity(CHEST);
                        chest.setItem(1, ItemStack.EMPTY); chest.setChanged();
                    });
                    partialUiPhase = 2;
                    return;
                }
                if (draft().view().code() != CraftingResultCode.PARTIAL_CREATED) return;
                require(draft().view().code() == CraftingResultCode.PARTIAL_CREATED, "Missing diamond not shown as partial");
                shot("partial-graph");
                require(((Button) field(overlay(), "create")).active, "Displayed partial action disabled");
                next();
            } else if (stage == 11 && ready()) {
                require(((CraftRequest) field(overlay(), "intent")).partial(), "Review did not select partial intent");
                require(mc.player.getInventory().countItem(Items.STICK) == 0, "First partial click consumed before review");
                var create = (Button) field(overlay(), "create");
                require(create.active, "Displayed partial craft disabled");
                click(create.getX() + 5, create.getY() + 5);
                next();
            } else if (stage == 12 && age > 30 && ready()) {
                if (mc.player.getInventory().countItem(Items.STICK) != 4) shot("partial-failed");
                require(mc.player.getInventory().countItem(Items.STICK) == 4,
                        "Partial did not deliver one whole stick batch: code=" + draft().view().code()
                                + ", token=" + draft().token() + ", local=" + field(overlay(), "localDraft")
                                + ", pane=" + field(overlay(), "pane") + ", routeChanged=" + field(overlay(), "routeChanged")
                                + ", notice=" + ((net.minecraft.network.chat.Component) field(overlay(), "notice")).getString()
                                + ", fullSearches=" + executionCounter("activeFullSearches")
                                + ", partialSearches=" + executionCounter("activePartialSearches"));
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Partial fabricated root");
                require(!((Button) field(overlay(), "create")).active, "Existing frontier allowed redundant partial");
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
            } else if (stage == 15 && age > 5 && ready()) {
                require(((CraftRequest) field(overlay(), "intent")).batches() == 6, "Slider did not choose six batches");
                require(draft().view().code() == CraftingResultCode.CREATED, "Mixed MAX is not executable");
                require((boolean) field(overlay(), "routeChanged"), "Mixed route warning missing");
                var cells = (List<?>) field(field(overlay(), "graph"), "cells");
                var materialIds = new java.util.HashSet<String>();
                boolean deviation = false;
                for (var cell : cells) {
                    var needs = (List<ItemStack>) field(cell, "needs");
                    if (needs.stream().anyMatch(s -> s.is(Items.BIRCH_LOG) || s.is(Items.OAK_LOG))) {
                        require(needs.size() == 1, "Mixed actual materials still hidden behind one icon");
                        materialIds.add((String) field(cell, "id"));
                        deviation |= (boolean) field(cell, "deviation");
                    }
                }
                require(materialIds.size() >= 2 && deviation, "Mixed graph missing oak/birch branches or deviation badge");
                for (int i = 0; i < cells.size(); i++) for (int j = i + 1; j < cells.size(); j++) {
                    var a = cells.get(i); var b = cells.get(j);
                    if (field(a, "x").equals(field(b, "x")))
                        require(Math.abs((int) field(a, "y") - (int) field(b, "y")) >= 26,
                                "Shared mixed supplies overlap in the same column");
                }
                // Mixed material allocations are not equivalent fixed-output
                // demands. Check their total needs and real production rather
                // than inferring quantities from the number of display nodes.
                int plankNeeds = 0, plankMade = 0;
                int connectedVariants = 0;
                for (var cell : cells) {
                    for (var stack : (List<ItemStack>) field(cell, "needs"))
                        if (stack.is(Items.OAK_PLANKS) || stack.is(Items.BIRCH_PLANKS)) plankNeeds += stack.getCount();
                    for (var stack : (List<ItemStack>) field(cell, "made"))
                        if (stack.is(Items.OAK_PLANKS) || stack.is(Items.BIRCH_PLANKS)) plankMade += stack.getCount();
                }
                require(plankNeeds == 12 && plankMade == 12, "Mixed shared demands lost total needs or real production");
                for (var cell : cells) {
                    var needs = (List<ItemStack>) field(cell, "needs");
                    if (needs.stream().noneMatch(s -> s.is(Items.OAK_PLANKS) || s.is(Items.BIRCH_PLANKS))) continue;
                    require(needs.size() == 1, "Mixed production still labels several materials as one wood");
                    var wood = needs.getFirst();
                    var source = wood.is(Items.OAK_PLANKS) ? Items.OAK_LOG : Items.BIRCH_LOG;
                    int expected = wood.is(Items.OAK_PLANKS) ? 8 : 4;
                    require(wood.getCount() == expected, "Mixed wood variant lost exact demand quantity");
                    var children = (List<String>) field(cell, "children");
                    require(children.size() == 1 && materialIds.contains(children.getFirst()), "Mixed wood has missing or duplicate source edges");
                    var child = cells.stream().filter(c -> {
                        try { return field(c, "id").equals(children.getFirst()); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }).findFirst().orElseThrow();
                    var inputs = (List<ItemStack>) field(child, "needs");
                    require(inputs.size() == 1 && inputs.getFirst().is(source) && inputs.getFirst().getCount() == expected / 4,
                            "Mixed wood connected to another material's log");
                    var icon = PlanGraphWidget.class.getDeclaredMethod("icon", cell.getClass());
                    icon.setAccessible(true);
                    var displayed = (ItemStack) icon.invoke(null, cell);
                    require(ItemStack.isSameItemSameComponents(displayed, wood) && displayed.getCount() == expected,
                            "Mixed plank icon uses another material's identity or quantity");
                    connectedVariants++;
                }
                require(connectedVariants == 2, "Mixed inputs not connected to separate exact production variants");
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
                mc.getSingleplayerServer().execute(() -> {
                    stock(true);
                    var chest = (ChestBlockEntity) serverPlayer().serverLevel().getBlockEntity(CHEST);
                    // A fresh resource scope has uncomputed multi-pick
                    // quantities, rather than the old cap-one cached MAX.
                    chest.setItem(0, new ItemStack(Items.OAK_LOG, 64));
                    chest.setItem(1, new ItemStack(Items.DIAMOND, 64));
                    chest.setChanged();
                    configured = true;
                });
                next();
            } else if (stage == 18 && configured && age > 30) {
                CraftingPlanOverlay.open(mc.screen, PICK, false);
                next();
            } else if (stage == 19 && !queuedCancellationChecked && draft() != null
                    && draft().view().code() == CraftingResultCode.CREATED
                    && field(overlay(), "pending") != null && field(overlay(), "pending").toString().equals("MAXIMUM")) {
                cancelQueuedMaximum(mc);
            } else if (stage == 19 && ready()) {
                require(queuedCancellationChecked, "Real in-flight MAX cancellation was not exercised");
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Canceled queued click still crafted");
                require(executionCounter("witnessValidations") == queuedCancellationWitnesses
                        && executionCounter("activeFullSearches") == queuedCancellationFull
                        && executionCounter("activePartialSearches") == queuedCancellationPartial,
                        "Canceled queued click reached authoritative preparation after Esc");
                mc.options.guiScale().set(1); mc.resizeDisplay();
                next();
            }
    }

    private static void lifecycleAndPresentation(Minecraft mc) throws Exception {
            if (stage == 20 && age > 15 && ready()) {
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
                M4GraphPresentationScenario.verifyPlayerWording();
                shot("english");
                next();
            } else if (stage == 23) {
                if (previewStart == 0 && ready()) {
                    pressButton(net.minecraft.network.chat.Component.translatable("screen.craftable.plan.refresh").getString());
                    require(!((Button) field(overlay(), "refresh")).active, "Refresh allowed duplicate clicks while pending");
                    require((boolean) field(field(overlay(), "refresh"), "busy"), "Refresh has no pending visual state");
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
                next();
            } else if (stage == 28 && outputVariants.tick(mc)) {
                next();
            } else if (stage == 29 && detailQuantity.tick(mc)) {
                next();
            } else if (stage == 30 && bulkQuantity.tick(mc)) {
                Craftable.LOGGER.warn("M4_SMOKE PASS: real Shift+C, candidates/pin, mixed MAX/slider/cost review, full/partial, GUI scales 1/2/3, zh/en, detail latency, menu/mode isolation, bulk64logs/MAX/drag/witness commit");
                stage = -1;
                restoreOptions();
                mc.stop();
            }
    }

    private static void restoreOptions() {
        if (!started) return;
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = oldPauseOnLostFocus;
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
    private static void cancelQueuedMaximum(Minecraft mc) throws Exception {
        var create = (Button) field(overlay(), "create");
        require(create.active, "Pending MAX disabled the real queued-action button");
        queuedCancellationWitnesses = executionCounter("witnessValidations");
        queuedCancellationFull = executionCounter("activeFullSearches");
        queuedCancellationPartial = executionCounter("activePartialSearches");
        click(create.getX() + 5, create.getY() + 5);
        require(Boolean.TRUE.equals(field(overlay(), "queuedAction")), "MAX click was lost instead of queued once");
        key(256, 0);
        require(!CraftingPlanOverlay.active(), "Esc did not cancel waiting action");
        queuedCancellationChecked = true;
        CraftingPlanOverlay.open(mc.screen, PICK, false);
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
    private static long executionCounter(String name) throws Exception {
        var field = CraftingService.class.getDeclaredField(name); field.setAccessible(true);
        return field.getLong(null);
    }
    private static void next() { stage++; age = 0; Craftable.LOGGER.warn("M4_SMOKE stage {}", stage); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
