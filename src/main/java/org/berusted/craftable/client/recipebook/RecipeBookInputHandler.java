package org.berusted.craftable.client.recipebook;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.CraftableKeyMappings;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.network.CreateRecipeRequestPayload;

@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class RecipeBookInputHandler {
    private RecipeBookInputHandler() {}

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
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

        RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) component;
        EditBox searchBox = componentAccessor.craftable$getSearchBox();
        if (searchBox != null && searchBox.isFocused()) {
            return;
        }

        InputConstants.Key key = InputConstants.getKey(event.getKeyCode(), event.getScanCode());
        if (!CraftableKeyMappings.CREATE_ONE.isActiveAndMatches(key)) {
            return;
        }

        RecipeBookPage page = componentAccessor.craftable$getRecipeBookPage();
        RecipeButton hoveredButton = ((RecipeBookPageAccessor) page).craftable$getHoveredButton();
        if (hoveredButton == null || !hoveredButton.visible) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        RecipeHolder<?> recipe = hoveredButton.getRecipe();
        PacketDistributor.sendToServer(new CreateRecipeRequestPayload(
                recipe.id(), ClientRequestSequence.next()));
        event.setCanceled(true);
    }
}
