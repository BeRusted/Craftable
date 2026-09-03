package org.berusted.craftable.client.recipebook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.network.RecipeStatusRequestPayload;

/** Requests authoritative status only for the recipe the player is inspecting. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class RecipeBookStatusHandler {
    private static final long STATUS_MAX_AGE_TICKS = 10;
    private static ResourceLocation lastRequestedRecipe;
    private static long lastRequestGameTime = Long.MIN_VALUE;

    private RecipeBookStatusHandler() {}

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!CraftableClientConfig.recipeBookEnhancementsEnabled()) {
            return;
        }
        if (!(event.getScreen() instanceof InventoryScreen inventoryScreen)) {
            return;
        }
        RecipeBookComponent component = inventoryScreen.getRecipeBookComponent();
        if (!component.isVisible()) {
            return;
        }

        RecipeBookPage page = ((RecipeBookComponentAccessor) component).craftable$getRecipeBookPage();
        RecipeButton hoveredButton = ((RecipeBookPageAccessor) page).craftable$getHoveredButton();
        Minecraft minecraft = Minecraft.getInstance();
        if (hoveredButton == null || !hoveredButton.visible || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }

        ResourceLocation recipeId = hoveredButton.getRecipe().id();
        long gameTime = minecraft.level.getGameTime();
        if (ClientRecipeStatusStore.isFresh(recipeId, gameTime, STATUS_MAX_AGE_TICKS)) {
            return;
        }
        long requestAge = gameTime - lastRequestGameTime;
        if (recipeId.equals(lastRequestedRecipe) && requestAge >= 0 && requestAge < STATUS_MAX_AGE_TICKS) {
            return;
        }

        lastRequestedRecipe = recipeId;
        lastRequestGameTime = gameTime;
        PacketDistributor.sendToServer(new RecipeStatusRequestPayload(
                recipeId, ClientRequestSequence.next()));
    }

    public static void clearRequestState() {
        lastRequestedRecipe = null;
        lastRequestGameTime = Long.MIN_VALUE;
    }
}
