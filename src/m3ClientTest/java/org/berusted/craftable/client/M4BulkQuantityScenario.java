package org.berusted.craftable.client;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.client.recipebook.RecipeBookProjection;
import org.berusted.craftable.environment.BrowsingSnapshot;
import org.berusted.craftable.execution.CraftingService;
import org.berusted.craftable.network.CraftingDetailPayloads;
import org.berusted.craftable.planner.CraftRequest;

/** Bulk MAX through the real menu, server resource lease, slider and network
 * confirmation. No fake scope, search injection or private execution shortcut.
 * MAX quality/timing is evidence, not a hardware-dependent pass threshold. */
final class M4BulkQuantityScenario {
    private static final ResourceLocation CHEST = ResourceLocation.withDefaultNamespace("chest");
    private int phase, age, selected;
    private volatile boolean configured, serverChecked;
    private volatile Throwable serverFailure;
    private Object menu, reviewed;
    private long maximumStarted, maximumElapsed, firstPositiveElapsed, dragStarted, dragElapsed;
    private long witnessBefore, fullSearchBefore, partialSearchBefore;
    private boolean maximumProven, maximumLimited, costPaneObserved, serverReviewObserved;
    private boolean refreshedMaximum;
    private long maximumScope;

    boolean tick(Minecraft mc) throws Exception {
        if (serverFailure != null) throw new AssertionError("Bulk server inventory verification failed", serverFailure);
        if (++age > 1200) throw new AssertionError("Bulk quantity protocol timeout phase=" + phase + ", max="
                + (overlay() == null ? "closed" : read(overlay(), "maximum")));
        if (phase == 0) {
            mc.setScreen(null);
            resources(mc);
            next(); return false;
        }
        if (phase == 1 && configured && age > 30 && inventory(mc, 64, 0)) {
            mc.setScreen(new InventoryScreen(mc.player));
            next(); return false;
        }
        if (phase == 2 && mc.screen instanceof AmbientInventoryScreen) {
            var book = RecipeBookProjection.component(mc.screen);
            if (!book.isVisible()) book.toggleVisibility();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                    RecipeBookType.CRAFTING, true, false));
            var access = (RecipeBookComponentAccessor) book;
            access.craftable$getSearchBox().setValue(Items.CHEST.getDescription().getString());
            access.craftable$getSearchBox().setFocused(false);
            book.recipesUpdated();
            var tab = (AbstractWidget) ((List<?>) read(book, "tabButtons")).getFirst();
            book.mouseClicked(tab.getX() + 5, tab.getY() + 5, 0);
            next(); return false;
        }
        if (phase == 3 && age > 15 && onlyOakSnapshot()) {
            var page = ((RecipeBookComponentAccessor) RecipeBookProjection.component(mc.screen)).craftable$getRecipeBookPage();
            RecipeButton button = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                    && b.getCollection().getRecipes().stream().anyMatch(recipe -> recipe.id().equals(CHEST))).findFirst().orElseThrow();
            field(page, "hoveredButton").set(page, button);
            menu = mc.player.containerMenu;
            maximumStarted = System.nanoTime();
            var press = new ScreenEvent.KeyPressed.Pre(mc.screen, 67, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
            NeoForge.EVENT_BUS.post(press);
            NeoForge.EVENT_BUS.post(new ScreenEvent.KeyReleased.Pre(mc.screen, 67, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT));
            require(press.isCanceled() && CraftingPlanOverlay.active() && intent().recipe().equals(CHEST),
                    "Real Shift+C did not open chest details");
            next(); return false;
        }
        if (phase == 4) {
            var maximum = maximum();
            if (maximum != null && maximum.lowerBound() > 0 && firstPositiveElapsed == 0)
                firstPositiveElapsed = System.nanoTime() - maximumStarted;
            if (!ready() || maximum == null || maximum.pending()) return false;
            if (maximumElapsed == 0) maximumElapsed = System.nanoTime() - maximumStarted;
            selected = maximum.lowerBound(); maximumProven = maximum.proven(); maximumLimited = maximum.limited();
            require(selected >= 1 && selected <= 32, "64 logs produced an impossible positive chest bound: " + maximum);
            var certificate = (java.util.OptionalInt) plannerField("materialCeiling").get(null);
            if (certificate.isPresent()) {
                require(certificate.getAsInt() == 32, "Normal 64-log catalog produced the wrong material certificate");
                if (selected == 32) require(maximum.proven() && !maximum.limited(), "Complete material ceiling did not settle exact MAX");
                if (selected == 32 && maximum.proven() && !refreshedMaximum) {
                    maximumScope = ClientBrowsePlanner.scopeVersion();
                    var refresh = (AbstractWidget) read(overlay(), "refresh");
                    var press = new ScreenEvent.MouseButtonPressed.Pre(mc.screen, refresh.getX() + 5, refresh.getY() + 5, 0);
                    NeoForge.EVENT_BUS.post(press);
                    var release = new ScreenEvent.MouseButtonReleased.Pre(mc.screen, refresh.getX() + 5, refresh.getY() + 5, 0);
                    NeoForge.EVENT_BUS.post(release);
                    require(press.isCanceled() && release.isCanceled(), "Bulk refresh leaked through the parent screen");
                    refreshedMaximum = true; age = 0; return false;
                }
                if (refreshedMaximum) {
                    require(ClientBrowsePlanner.scopeVersion() == maximumScope,
                            "Unchanged explicit refresh rebuilt the material certificate scope");
                    require(!org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.computed(intent().withBatches(31)),
                            "Cached exact MAX redundantly searched a smaller quantity");
                }
            }
            require(intent().batches() == 1 && onlyOakSnapshot() && inventory(mc, 64, 0),
                    "Read-only bulk MAX changed quantity or resources");
            Craftable.LOGGER.warn("M4_BULK_MAX evidence logs=64 lower={} proven={} limited={} pending={} cap={} firstPositive={}ms settled={}ms materialUpper=32",
                    selected, maximum.proven(), maximum.limited(), maximum.pending(), maximum.cap(),
                    firstPositiveElapsed / 1_000_000.0, maximumElapsed / 1_000_000.0);
            Craftable.LOGGER.warn("M4_BULK_MATERIAL_CERTIFICATE present={} value={} scope={} unchangedRefresh={}", certificate.isPresent(),
                    certificate.orElse(-1), ClientBrowsePlanner.scopeVersion(), refreshedMaximum);
            dragStarted = System.nanoTime();
            if (selected > 1) {
                var slider = (AbstractWidget) read(overlay(), "slider");
                require(slider.active, "Published positive bulk bound disabled its slider");
                double y = slider.getY() + 10, left = slider.getX() + 4, right = slider.getX() + slider.getWidth() - 4;
                var press = new ScreenEvent.MouseButtonPressed.Pre(mc.screen, left, y, 0);
                NeoForge.EVENT_BUS.post(press);
                var drag = new ScreenEvent.MouseDragged.Pre(mc.screen, right, y, 0, right - left, 0);
                NeoForge.EVENT_BUS.post(drag);
                var release = new ScreenEvent.MouseButtonReleased.Pre(mc.screen, right, y, 0);
                NeoForge.EVENT_BUS.post(release);
                require(press.isCanceled() && drag.isCanceled() && release.isCanceled() && intent().batches() == selected,
                        "Real bulk drag lost the published lower bound");
                require(read(overlay(), "slider") == slider, "Bulk drag rebuilt its captured widget");
            }
            next(); return false;
        }
        if (phase == 5 && ready() && intent().batches() == selected) {
            dragElapsed = System.nanoTime() - dragStarted;
            verifyReview();
            var witness = ClientBrowsePlanner.witness(intent());
            require(witness != null && witness == read(overlay(), "displayedWitness")
                    && CraftingDetailPayloads.fitsWitness(intent(), witness, mc.level.registryAccess()),
                    "Published bulk review has no bounded matching witness");
            require(witness.steps().size() <= org.berusted.craftable.planner.SearchBudget.MAX_STEPS,
                    "Published bulk review exceeded the existing witness bound");
            require(inventory(mc, 64, 0) && mc.player.containerMenu == menu, "Bulk preview wrote resources or replaced menu");
            reviewed = draft().view().reviewIdentity();
            witnessBefore = counter("witnessValidations"); fullSearchBefore = counter("activeFullSearches");
            partialSearchBefore = counter("activePartialSearches");
            clickCreate(mc);
            costPaneObserved = read(overlay(), "pane").toString().equals("COSTS") && read(overlay(), "pending") == null;
            next(); return false;
        }
        if (phase == 6) {
            // When the ordinary route-change guard requires cost review, make
            // its second click through the same real button. Matching server
            // authorization otherwise immediately confirms the reviewed values.
            if (costPaneObserved && age > 2 && read(overlay(), "pending") == null) {
                verifyReview();
                require(draft().view().reviewIdentity().equals(reviewed) && inventory(mc, 64, 0),
                        "Bulk cost review changed values or committed before its explicit second click");
                clickCreate(mc); costPaneObserved = false;
            }
            if (draft() != null && !draft().token().equals(CraftingDetailPayloads.NO_TOKEN)) {
                serverReviewObserved = true;
                verifyReview();
                require(draft().view().reviewIdentity().equals(reviewed), "Server bulk review changed exact source quantities");
            }
            if (!inventory(mc, 64 - selected * 2, selected)) return false;
            require(counter("witnessValidations") == witnessBefore + 2, "Bulk review/confirm did not validate the witness exactly twice");
            require(counter("activeFullSearches") == fullSearchBefore && counter("activePartialSearches") == partialSearchBefore,
                    "Bulk reviewed witness triggered an alternative server search");
            require(mc.player.containerMenu == menu, "Bulk confirmation replaced the parent menu");
            verifyServer(mc);
            next(); return false;
        }
        if (phase == 7 && serverChecked && inventory(mc, 64 - selected * 2, selected)) {
            require(((java.util.OptionalInt) plannerField("materialCeiling").get(null)).isEmpty(),
                    "Actual crafting kept the old immutable resource ceiling");
            Craftable.LOGGER.warn("M4_BULK_QUANTITY PASS: actual64logs lower={} proven={} limited={} settled={}ms dragPreview={}ms, consumed={}logs created={}chests extras=0 witnessValidations=2 fullSearches=0 partialSearches=0 serverReviewFrameObserved={}",
                    selected, maximumProven, maximumLimited, maximumElapsed / 1_000_000.0, dragElapsed / 1_000_000.0,
                    selected * 2, selected, serverReviewObserved);
            if (selected != 32) Craftable.LOGGER.warn("M4_BULK_QUANTITY limitation: observed lower {}, not the material upper 32; this scenario does not claim a complete or fast MAX", selected);
            return true;
        }
        return false;
    }

    private void resources(Minecraft mc) {
        configured = false;
        mc.getSingleplayerServer().execute(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            player.closeContainer(); player.setGameMode(GameType.SURVIVAL); player.teleportTo(0.5, -60, 0.5);
            var chest = player.serverLevel().getBlockEntity(new BlockPos(1, -60, 1));
            if (chest instanceof ChestBlockEntity container) { container.clearContent(); container.setChanged(); }
            player.serverLevel().setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
            player.awardRecipes(player.serverLevel().getRecipeManager().getRecipes());
            player.getInventory().clearContent();
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
            for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            player.containerMenu.broadcastChanges(); configured = true;
        });
    }

    private void verifyServer(Minecraft mc) {
        serverChecked = false;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                require(player.getInventory().countItem(Items.OAK_LOG) == 64 - selected * 2
                        && player.getInventory().countItem(Items.CHEST) == selected, "Server bulk commit delivered wrong quantities");
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
                    require(allowed(player.getInventory().getItem(slot)), "Bulk commit delivered an extra inventory item");
                require(player.containerMenu.getCarried().isEmpty(), "Bulk commit delivered an extra cursor item");
                for (int slot = 1; slot <= 4; slot++) require(player.inventoryMenu.getSlot(slot).getItem().isEmpty(),
                        "Bulk commit left an extra crafting-grid item");
                var chest = player.serverLevel().getBlockEntity(new BlockPos(1, -60, 1));
                require(!(chest instanceof ChestBlockEntity container) || container.isEmpty(), "Bulk commit wrote to unrelated external stock");
                serverChecked = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }

    private static void verifyReview() throws Exception {
        var view = draft().view();
        require(view.code() == CraftingResultCode.CREATED && view.complete() && !intent().partial(),
                "Published bulk lower bound did not produce a complete fixed-quantity review");
        require(total(view.consumed(), Items.OAK_LOG) == intent().batches() * 2
                && view.consumed().stream().allMatch(stack -> stack.is(Items.OAK_LOG))
                && total(view.primary(), Items.CHEST) == intent().batches()
                && view.primary().stream().allMatch(stack -> stack.is(Items.CHEST)) && view.surplus().isEmpty()
                && view.drops().isEmpty(), "Bulk review changed exact logs/chests or invented leftovers");
    }
    private static boolean onlyOakSnapshot() throws Exception {
        if (!ClientBrowsePlanner.ready()) return false;
        var snapshot = (BrowsingSnapshot) plannerField("snapshot").get(null);
        return snapshot.workbench() && snapshot.sources().stream().allMatch(source -> source.stack().is(Items.OAK_LOG))
                && snapshot.sources().stream().mapToInt(source -> source.stack().getCount()).sum() == 64;
    }
    private static boolean inventory(Minecraft mc, int logs, int chests) {
        if (mc.player.getInventory().countItem(Items.OAK_LOG) != logs || mc.player.getInventory().countItem(Items.CHEST) != chests) return false;
        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++)
            if (!allowed(mc.player.getInventory().getItem(slot))) return false;
        return mc.player.containerMenu.getCarried().isEmpty();
    }
    private static boolean allowed(ItemStack stack) { return stack.isEmpty() || stack.is(Items.OAK_LOG) || stack.is(Items.CHEST); }
    private static void clickCreate(Minecraft mc) throws Exception {
        var button = (AbstractWidget) read(overlay(), "create"); require(button.active, "Bulk create control disabled");
        var press = new ScreenEvent.MouseButtonPressed.Pre(mc.screen, button.getX() + 5, button.getY() + 5, 0);
        NeoForge.EVENT_BUS.post(press);
        var release = new ScreenEvent.MouseButtonReleased.Pre(mc.screen, button.getX() + 5, button.getY() + 5, 0);
        NeoForge.EVENT_BUS.post(release);
        require(press.isCanceled() && release.isCanceled(), "Bulk confirmation leaked through its parent menu");
    }
    private static boolean ready() throws Exception {
        return overlay() != null && draft() != null && !(boolean) read(overlay(), "dirty") && read(overlay(), "pending") == null;
    }
    private static CraftingPlanOverlay overlay() throws Exception { return (CraftingPlanOverlay) read(null, "current"); }
    private static CraftingService.Draft draft() throws Exception { return overlay() == null ? null : (CraftingService.Draft) read(overlay(), "draft"); }
    private static CraftingService.Maximum maximum() throws Exception { return overlay() == null ? null : (CraftingService.Maximum) read(overlay(), "maximum"); }
    private static CraftRequest intent() throws Exception { return (CraftRequest) read(overlay(), "intent"); }
    private static int total(List<ItemStack> stacks, Item item) { return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static long counter(String name) throws Exception { var field = CraftingService.class.getDeclaredField(name); field.setAccessible(true); return field.getLong(null); }
    private static Field plannerField(String name) throws Exception { var field = ClientBrowsePlanner.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object read(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static Field field(Object target, String name) throws Exception {
        var type = target == null ? CraftingPlanOverlay.class : target.getClass();
        while (type != null) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private void next() { phase++; age = 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
