package org.berusted.craftable.client;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.client.recipebook.*;
import org.berusted.craftable.execution.CraftingService;
import org.berusted.craftable.planner.CraftRequest;

/** One planning-entry module. Only the animation clock is controlled; actual
 * C/Shift+C, root hit-testing, card selection and confirmation use UI/network. */
final class M4OutputVariantScenario {
    private static final ResourceLocation OAK = id("oak_boat"), BIRCH = id("birch_boat"), SPRUCE = id("spruce_boat");
    private int phase, age;
    private long detailsBefore;
    private volatile boolean configured;

    boolean tick(Minecraft mc) throws Exception {
        if (++age > 600) throw new AssertionError("Variant timeout phase=" + phase);
        if (phase == 0) {
            mc.setScreen(null);
            mc.getSingleplayerServer().execute(() -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 10));
                p.getInventory().setItem(1, new ItemStack(Items.BIRCH_PLANKS, 10));
                ((ChestBlockEntity) p.serverLevel().getBlockEntity(new BlockPos(1, -60, 1))).clearContent();
                p.serverLevel().setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                p.awardRecipes(p.serverLevel().getRecipeManager().getRecipes());
                p.containerMenu.broadcastChanges(); configured = true;
            });
            next(); return false;
        }
        if (phase == 1 && configured && age > 30 && mc.gameMode.getPlayerMode() == GameType.SURVIVAL) {
            mc.setScreen(new InventoryScreen(mc.player)); next(); return false;
        }
        if (phase == 2 && mc.screen instanceof AmbientInventoryScreen) {
            var book = RecipeBookProjection.component(mc.screen);
            if (!book.isVisible()) book.toggleVisibility();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                    RecipeBookType.CRAFTING, true, false));
            var access = (RecipeBookComponentAccessor) book;
            access.craftable$getSearchBox().setValue(Items.OAK_BOAT.getDescription().getString());
            access.craftable$getSearchBox().setFocused(false);
            book.recipesUpdated();
            var tabs = (List<?>) read(book, "tabButtons");
            var tab = (AbstractWidget) tabs.getFirst();
            book.mouseClicked(tab.getX() + 5, tab.getY() + 5, 0);
            next(); return false;
        }
        if (phase == 3 && age > 30 && ClientBrowsePlanner.ready()
                && ClientRecipeStatusStore.lifecycle(BIRCH) == ClientRecipeStatusStore.Lifecycle.KNOWN
                && ClientRecipeStatusStore.lifecycle(SPRUCE) == ClientRecipeStatusStore.Lifecycle.KNOWN) {
            var button = boatButton(mc);
            show(button, SPRUCE);
            require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(SPRUCE), "Unavailable output substituted oak");
            require(RecipeButtonTargetResolver.status(button) != CraftingStatus.CRAFTABLE, "Spruce frame inherited oak's green status");
            show(button, BIRCH);
            require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(BIRCH), "Birch frame substituted oak");
            hover(mc, button); key(0); next(); return false;
        }
        if (phase == 4 && age > 25 && mc.player.getInventory().countItem(Items.BIRCH_BOAT) == 1) {
            require(mc.player.getInventory().countItem(Items.OAK_BOAT) == 0, "C produced wrong boat");
            require(mc.player.getInventory().countItem(Items.BIRCH_PLANKS) == 5
                    && mc.player.getInventory().countItem(Items.OAK_PLANKS) == 10, "C used wrong material/cost");
            var button = boatButton(mc); show(button, BIRCH); hover(mc, button);
            detailsBefore = org.berusted.craftable.network.CraftablePayloadHandlers.detailRequests();
            key(org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
            require(CraftingPlanOverlay.active(), "Shift+C failed");
            require(intent().recipe().equals(BIRCH), "Detail opened wrong material"); next(); return false;
        }
        if (phase == 5 && ready()) {
            // Give the old root real UI-selected dependency pins, so clearing
            // them is verified rather than asserting an already empty map.
            clickMaterial(); next(); return false;
        }
        if (phase == 6 && draft() != null && !(boolean) read(overlay(), "dirty")
                && read(overlay(), "pending") == null) {
            require(draft().choices().candidates().getFirst().recipe().equals(id("birch_planks")), "Wrong material card");
            clickButton("applyChoice"); next(); return false;
        }
        if (phase == 7 && ready()) {
            require(!intent().selections().isEmpty(), "Material UI failed to pin old dependencies");
            clickRoot(); next(); return false;
        }
        if (phase == 8 && draft() != null && !(boolean) read(overlay(), "dirty")
                && read(overlay(), "pending") == null) {
            var choices = draft().choices().candidates();
            require(choices.stream().anyMatch(c -> c.recipe().equals(BIRCH))
                    && choices.stream().anyMatch(c -> c.recipe().equals(OAK))
                    && choices.stream().anyMatch(c -> c.recipe().equals(SPRUCE)), "Root chooser lost output variants");
            int wanted = java.util.stream.IntStream.range(0, choices.size()).filter(i -> choices.get(i).recipe().equals(OAK)).findFirst().orElseThrow();
            while ((int) read(overlay(), "choicePage") != wanted) clickButton("nextChoice");
            clickButton("applyChoice");
            require(intent().recipe().equals(OAK) && intent().selections().isEmpty(), "Root change retained old subtree pins");
            require(read(overlay(), "maximum") == null && read(overlay(), "draft") == null, "Root change retained old quantity/authority");
            next(); return false;
        }
        if (phase == 9 && ready()) {
            require(org.berusted.craftable.network.CraftablePayloadHandlers.detailRequests() == detailsBefore,
                    "Root cards/candidate/MAX queried server instead of reusing local planner");
            require(draft().view().code() == CraftingResultCode.CREATED, "Oak variant not replanned");
            require(draft().view().primary().stream().anyMatch(s -> s.is(Items.OAK_BOAT)), "Graph still shows birch output");
            require(draft().view().consumed().stream().anyMatch(s -> s.is(Items.OAK_PLANKS) && s.getCount() == 5)
                    && draft().view().consumed().stream().noneMatch(s -> s.is(Items.BIRCH_PLANKS)), "Root switch retained old cost");
            require(((CraftingService.Maximum) read(overlay(), "maximum")).lowerBound() == 2, "Root switch retained birch MAX");
            clickButton("create"); next(); return false;
        }
        if (phase == 10 && age > 25 && mc.player.getInventory().countItem(Items.OAK_BOAT) == 1) {
            require(mc.player.getInventory().countItem(Items.BIRCH_BOAT) == 1
                    && mc.player.getInventory().countItem(Items.BIRCH_PLANKS) == 5
                    && mc.player.getInventory().countItem(Items.OAK_PLANKS) == 5, "Root confirmation wrong output/cost");
            // Existing inventory smoke separately proves stick/bamboo animation
            // stays a shared-output choice. This module proves the opposite case.
            Craftable.LOGGER.warn("M4_OUTPUT_VARIANTS PASS: displayed C, per-output status, Shift+C root cards, cost/MAX reset, exact confirmation");
            return true;
        }
        return false;
    }

    private static RecipeButton boatButton(Minecraft mc) {
        var page = ((RecipeBookComponentAccessor) RecipeBookProjection.component(mc.screen)).craftable$getRecipeBookPage();
        return ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                && b.getCollection().getRecipes().stream().anyMatch(r -> r.id().equals(BIRCH))).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static void show(RecipeButton button, ResourceLocation recipe) throws Exception {
        var method = RecipeButton.class.getDeclaredMethod("getOrderedRecipes"); method.setAccessible(true);
        var ordered = (List<RecipeHolder<?>>) method.invoke(button);
        int index = java.util.stream.IntStream.range(0, ordered.size()).filter(i -> ordered.get(i).id().equals(recipe)).findFirst().orElseThrow();
        set(button, "currentIndex", index); set(button, "time", index * 30F);
    }
    private static void hover(Minecraft mc, RecipeButton button) throws Exception {
        var page = ((RecipeBookComponentAccessor) RecipeBookProjection.component(mc.screen)).craftable$getRecipeBookPage();
        set(page, "hoveredButton", button);
    }
    private static void key(int modifiers) {
        var screen = Minecraft.getInstance().screen;
        var event = new ScreenEvent.KeyPressed.Pre(screen, 67, 0, modifiers); NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "C leaked through UI");
        NeoForge.EVENT_BUS.post(new ScreenEvent.KeyReleased.Pre(screen, 67, 0, modifiers));
    }
    private static void clickRoot() throws Exception {
        var graph = read(overlay(), "graph");
        var root = ((List<?>) read(graph, "cells")).stream().filter(c -> {
            try { return read(c, "id").equals("0"); } catch (Exception e) { throw new RuntimeException(e); }
        }).findFirst().orElseThrow();
        var widget = (AbstractWidget) graph;
        click(widget.getX() + (int) read(root, "x") + (int) read(graph, "panX") + 8,
                widget.getY() + (int) read(root, "y") + (int) read(graph, "panY") + 8);
    }
    @SuppressWarnings("unchecked") private static void clickMaterial() throws Exception {
        var graph = read(overlay(), "graph");
        var material = ((List<?>) read(graph, "cells")).stream().filter(c -> {
            try { return ((List<ItemStack>) read(c, "needs")).stream().anyMatch(s -> s.is(Items.BIRCH_PLANKS)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).findFirst().orElseThrow();
        var widget = (AbstractWidget) graph;
        click(widget.getX() + (int) read(material, "x") + (int) read(graph, "panX") + 8,
                widget.getY() + (int) read(material, "y") + (int) read(graph, "panY") + 8);
    }
    private static void clickButton(String name) throws Exception {
        var button = (Button) read(overlay(), name);
        require(button.visible && button.active, "Inactive variant action " + name);
        click(button.getX() + 5, button.getY() + 5);
    }
    private static void click(double x, double y) {
        var screen = Minecraft.getInstance().screen;
        var event = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 0); NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "Overlay click leaked");
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, 0));
    }
    private static boolean ready() throws Exception {
        return overlay() != null && draft() != null && !(boolean) read(overlay(), "dirty")
                && read(overlay(), "pending") == null && read(overlay(), "maximum") != null
                && !((CraftingService.Maximum) read(overlay(), "maximum")).pending();
    }
    private static CraftingPlanOverlay overlay() throws Exception { return (CraftingPlanOverlay) read(null, "current"); }
    private static CraftingService.Draft draft() throws Exception { return (CraftingService.Draft) read(overlay(), "draft"); }
    private static CraftRequest intent() throws Exception { return (CraftRequest) read(overlay(), "intent"); }
    private static Field field(Object target, String name) throws Exception {
        var type = target == null ? CraftingPlanOverlay.class : target.getClass();
        while (type != null) {
            try { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object read(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private void next() { phase++; age = 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
