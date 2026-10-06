package org.berusted.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.client.recipebook.*;
import org.berusted.craftable.environment.BrowsingSnapshot;
import org.lwjgl.glfw.GLFW;

/** Final isolated inventory-smoke phase. Real vanilla click packets, not fake
 * client snapshots; asserts every tick throughout input-location replacement. */
final class M4MenuMoveScenario {
    private static final BlockPos TABLE = new BlockPos(2, -60, 0), CHEST = new BlockPos(1, -60, 1);
    private static final ResourceLocation PLANKS = ResourceLocation.withDefaultNamespace("oak_planks");
    private int kind, phase, tick, mainSlot, settle;
    private volatile boolean configured;
    private RecipeButton anchor;
    private Object collections, session;
    private long revision, resources;
    private int pageNumber;

    boolean tick(Minecraft mc) throws ReflectiveOperationException {
        if (phase == 0) {
            configured = false;
            int menuKind = kind;
            mc.getSingleplayerServer().execute(() -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                var level = p.serverLevel();
                ((ChestBlockEntity) level.getBlockEntity(CHEST)).clearContent();
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
                // Isolate resource movement from vanilla's recipe-unlock
                // packets, which legitimately replace collection objects.
                p.awardRecipes(level.getRecipeManager().getRecipes());
                level.setBlockAndUpdate(TABLE, menuKind == 1 ? Blocks.AIR.defaultBlockState() : Blocks.CRAFTING_TABLE.defaultBlockState());
                p.containerMenu.broadcastChanges();
                if (menuKind == 2) p.openMenu(Blocks.CRAFTING_TABLE.defaultBlockState().getMenuProvider(level, TABLE));
                configured = true;
            });
            phase = 1; tick = 0;
            return false;
        }
        if (phase == 1) {
            if (!configured || ++tick < 25) return false;
            if (kind != 2) mc.setScreen(new InventoryScreen(mc.player));
            phase = 2;
            return false;
        }
        if (phase == 2) {
            if (kind == 0 && !(mc.screen instanceof AmbientInventoryScreen)
                    || kind == 1 && !(mc.screen instanceof InventoryScreen)
                    || kind == 2 && !(mc.screen instanceof CraftingScreen)) return false;
            var book = RecipeBookProjection.component(mc.screen);
            if (!book.isVisible()) book.toggleVisibility();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                    RecipeBookType.CRAFTING, true, true));
            ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.OAK_PLANKS.getDescription().getString());
            book.recipesUpdated();
            // Earlier smoke phases switch tabs. Search must include building
            // recipes, not inherit the last tools/misc tab from that phase.
            var tabs = (java.util.List<?>) read(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class, book, "tabButtons");
            var all = (net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton) tabs.getFirst();
            book.mouseClicked(all.getX() + 5, all.getY() + 5, 0);
            phase = 3; tick = 0; settle = 20;
            return false;
        }
        var book = RecipeBookProjection.component(mc.screen);
        var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
        if (anchor == null) {
            // Let initial book settings/recipe packets and render-time result
            // merges settle before anchoring a resource-only change.
            if (settle-- > 0) return false;
            if (!ClientBrowsePlanner.ready() || ClientRecipeStatusStore.get(PLANKS, false) != CraftingStatus.CRAFTABLE) return false;
            anchor = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                    && RecipeButtonTargetResolver.preferredRecipe(b).id().equals(PLANKS)).findFirst().orElse(null);
            // Results merge before render, one frame after a tick completes.
            if (anchor == null) return false;
            collections = read(RecipeBookPage.class, page, "recipeCollections");
            pageNumber = (int) read(RecipeBookPage.class, page, "currentPage");
            revision = (long) read(ClientBrowsePlanner.class, null, "revision");
            var snapshot = (BrowsingSnapshot) read(ClientBrowsePlanner.class, null, "snapshot");
            session = snapshot.session(); resources = snapshot.resources();
            for (int i = 0; i < mc.player.containerMenu.slots.size(); i++) {
                var slot = mc.player.containerMenu.getSlot(i);
                if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == 0) mainSlot = i;
            }
        }
        // Unfiltered vanilla groups cycle oak alongside other wood outputs.
        // Isolate resource-location changes from that legitimate target change;
        // the output-variant module separately verifies material-specific frames.
        var orderedMethod = RecipeButton.class.getDeclaredMethod("getOrderedRecipes");
        orderedMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var ordered = (java.util.List<net.minecraft.world.item.crafting.RecipeHolder<?>>) orderedMethod.invoke(anchor);
        int shown = java.util.stream.IntStream.range(0, ordered.size()).filter(i -> ordered.get(i).id().equals(PLANKS)).findFirst().orElseThrow();
        var animation = RecipeButton.class.getDeclaredField("time"); animation.setAccessible(true); animation.setFloat(anchor, shown * 30F);
        var index = RecipeButton.class.getDeclaredField("currentIndex"); index.setAccessible(true); index.setInt(anchor, shown);
        require(anchor.visible && RecipeButtonTargetResolver.status(anchor) == CraftingStatus.CRAFTABLE,
                "Moved log disappeared/recolored: menu=" + kind + " tick=" + tick);
        var currentCollections = read(RecipeBookPage.class, page, "recipeCollections");
        if (collections != currentCollections) Craftable.LOGGER.warn("M4_MENU_MOVE changed menu={} tick={} filtering={} ready={} old={} new={}",
                kind, tick, mc.player.getRecipeBook().isFiltering(RecipeBookType.CRAFTING), ClientBrowsePlanner.ready(),
                collectionIds(collections), collectionIds(currentCollections));
        require(collections == currentCollections, "Move rebuilt recipe list: menu=" + kind + " tick=" + tick);
        require(pageNumber == (int) read(RecipeBookPage.class, page, "currentPage"), "Move reset page");
        require(revision == (long) read(ClientBrowsePlanner.class, null, "revision"), "Move reopened session");
        // Hold each location long enough to receive the server's replacement
        // resources, including a split stack shared by main/grid/cursor.
        if (tick == 0 || tick == 36 || tick == 48 || tick == 72) click(mc, mainSlot, tick == 48 ? 1 : 0);
        if (tick == 12 || tick == 24 || tick == 60) click(mc, 1, 0);
        if (tick == 80) {
            // Use the real toggle (including server book-settings sync), not a
            // client-only flag that a later vanilla packet can overwrite.
            var filter = (net.minecraft.client.gui.components.StateSwitchingButton) read(
                    net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class, book, "filterButton");
            book.mouseClicked(filter.getX() + 5, filter.getY() + 5, 0);
            require(!mc.player.getRecipeBook().isFiltering(RecipeBookType.CRAFTING), "Real filter toggle failed");
            collections = read(RecipeBookPage.class, page, "recipeCollections");
        }
        if (tick == 84) {
            var hover = RecipeBookPage.class.getDeclaredField("hoveredButton"); hover.setAccessible(true); hover.set(page, anchor);
            var key = new ScreenEvent.KeyPressed.Pre(mc.screen, GLFW.GLFW_KEY_C, 0, 0);
            RecipeBookInputHandler.onKeyPressed(key);
            require(key.isCanceled(), "C on grid/cursor sources not consumed");
            RecipeBookInputHandler.onKeyReleased(new ScreenEvent.KeyReleased.Pre(mc.screen, GLFW.GLFW_KEY_C, 0, 0));
        }
        if (++tick < 125) return false;
        require(mc.player.getInventory().countItem(Items.OAK_PLANKS) == 4, "Grid-funded C output");
        require(mc.player.containerMenu.getCarried().is(Items.OAK_LOG) && mc.player.containerMenu.getCarried().getCount() == 1,
                "Cursor moved-item quantity lost");
        require(mc.player.containerMenu.getSlot(1).getItem().is(Items.OAK_LOG)
                && mc.player.containerMenu.getSlot(1).getItem().getCount() == 1, "Grid consumption count");
        var current = (BrowsingSnapshot) read(ClientBrowsePlanner.class, null, "snapshot");
        require(ClientBrowsePlanner.ready() && current.session().equals(session) && current.resources() > resources,
                "No authoritative same-session updates for clicks");
        mc.player.closeContainer();
        Craftable.LOGGER.warn("M4_MENU_MOVE menu={} PASS: real clicks main/cursor/grid/split, both filters, stable color/list/page/session, actual C", kind);
        anchor = null; phase = 0;
        return ++kind == 3;
    }

    private static void click(Minecraft mc, int slot, int button) {
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot, button, ClickType.PICKUP, mc.player);
    }
    private static Object read(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object collectionIds(Object value) {
        return ((java.util.List<?>) value).stream().map(c -> ((net.minecraft.client.gui.screens.recipebook.RecipeCollection) c)
                .getRecipes().stream().map(r -> r.id().toString()).toList()).toList();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
