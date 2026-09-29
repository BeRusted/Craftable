package org.berusted.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.client.recipebook.*;
import org.berusted.craftable.environment.BrowsingSnapshot;
import org.lwjgl.glfw.GLFW;

/** Runs inside the inventory entry point: four actual C gestures across live
 * server replies. Never reacquire a replacement button to hide a disappearing target. */
final class M4RepeatCraftScenario {
    private int tick;
    private RecipeButton anchor;
    private Object collections, session;
    private long revision, resources;
    private int initialCount, pageNumber;

    boolean tick(Minecraft mc) throws ReflectiveOperationException {
        var book = RecipeBookProjection.component(mc.screen);
        var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
        if (anchor == null) {
            if (!ClientBrowsePlanner.ready()) return false;
            anchor = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible).findFirst().orElseThrow();
            initialCount = mc.player.getInventory().countItem(Items.STICK);
            revision = (long) read(ClientBrowsePlanner.class, null, "revision");
            var snapshot = (BrowsingSnapshot) read(ClientBrowsePlanner.class, null, "snapshot");
            session = snapshot.session(); resources = snapshot.resources();
            collections = read(RecipeBookPage.class, page, "recipeCollections");
            pageNumber = (int) read(RecipeBookPage.class, page, "currentPage");
        }
        require(anchor.visible && RecipeButtonTargetResolver.status(anchor) == CraftingStatus.CRAFTABLE,
                "Repeated C lost or recolored the still-craftable target at tick " + tick);
        require(collections == read(RecipeBookPage.class, page, "recipeCollections"), "Unchanged list rebuilt after C");
        require(pageNumber == (int) read(RecipeBookPage.class, page, "currentPage"), "Page number reset after C");
        require(revision == (long) read(ClientBrowsePlanner.class, null, "revision"), "C reopened the browse session");
        if (tick == 16) {
            // The same gestures also work with filtering disabled. This is an
            // explicit scope change; only later resource updates must be no-ops.
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
            book.recipesUpdated();
            collections = read(RecipeBookPage.class, page, "recipeCollections");
        }
        if (tick == 0 || tick == 4 || tick == 20 || tick == 24) {
            var hover = RecipeBookPage.class.getDeclaredField("hoveredButton"); hover.setAccessible(true);
            hover.set(page, anchor);
            var key = new ScreenEvent.KeyPressed.Pre(mc.screen, GLFW.GLFW_KEY_C, 0, 0);
            RecipeBookInputHandler.onKeyPressed(key);
            require(key.isCanceled(), "C gesture was not consumed");
            RecipeBookInputHandler.onKeyReleased(new ScreenEvent.KeyReleased.Pre(mc.screen, GLFW.GLFW_KEY_C, 0, 0));
        }
        if (++tick < 65) return false;
        require(mc.player.getInventory().countItem(Items.STICK) == initialCount + 16, "Four C presses did not commit exactly four batches");
        var current = (BrowsingSnapshot) read(ClientBrowsePlanner.class, null, "snapshot");
        require(ClientBrowsePlanner.ready() && current.session().equals(session) && current.resources() > resources,
                "Server did not push replacement resources in the original session");
        mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
        book.recipesUpdated();
        Craftable.LOGGER.warn("M4_REPEAT_C PASS: four gestures, both filters, stable list/page/color/nonce, same-session resource push");
        return true;
    }

    private static Object read(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
