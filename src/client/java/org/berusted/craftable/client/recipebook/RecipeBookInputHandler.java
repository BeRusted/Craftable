package org.berusted.craftable.client.recipebook;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.CraftableKeyMappings;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.network.payload.CreateRecipeRequestPayload;

public final class RecipeBookInputHandler {

    private RecipeBookInputHandler() {
    }

    public static void register() {
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> {
                return onKeyPressed(s, key, scancode, modifiers); // 返回 false 可取消
            });

        });
    }

    private static boolean onKeyPressed(
            Screen screen,
            int keyCode,
            int scanCode,
            int modifiers
    ) {
        if (!RecipeBookProjection.active()) {
            return true;
        }

        RecipeBookComponent component = RecipeBookProjection.component(screen);

        if (component == null || !component.isVisible()) {
            return true;
        }

        RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) component;

        EditBox searchBox = componentAccessor.craftable$getSearchBox();

        if (searchBox != null && searchBox.isFocused()) {
            return true;
        }

        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);

        if (!KeyBindingHelper.getBoundKeyOf(CraftableKeyMappings.CREATE_ONE).equals(key)) {
            return true;
        }

        RecipeBookPage page = componentAccessor.craftable$getRecipeBookPage();

        RecipeButton hoveredButton = ((RecipeBookPageAccessor) page).craftable$getHoveredButton();

        if (hoveredButton == null || !hoveredButton.visible) {
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.level == null) {
            return true;
        }

        RecipeHolder<?> recipe = RecipeButtonTargetResolver.preferredRecipe(hoveredButton);

        if (recipe == null) {
            return true;
        }

        ClientPlayNetworking.send(
                new CreateRecipeRequestPayload(
                        recipe.id(),
                        ClientRequestSequence.next()
                )
        );
        return false;
    }

}