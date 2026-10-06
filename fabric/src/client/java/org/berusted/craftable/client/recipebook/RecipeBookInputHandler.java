package org.berusted.craftable.client.recipebook;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.CraftableKeyMappings;
import org.berusted.craftable.client.CraftingPlanOverlay;
import org.berusted.craftable.client.DoublePressGesture;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.client.config.CraftableClientConfig;
import org.berusted.craftable.client.network.CreateRecipeRequestPayload;

public final class RecipeBookInputHandler {
    private static final DoublePressGesture GESTURE =
            new DoublePressGesture();
    private RecipeBookInputHandler() {}

    public static void register() {
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register(RecipeBookInputHandler::onKeyPressed);
            net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyRelease(screen).register((s, key, scan, mods) -> {
                if (boundKey().equals(InputConstants.getKey(key, scan))) GESTURE.release();
                return true;
            });
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.beforeMouseClick(screen).register((s, x, y, button) -> GESTURE.clear());
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen).register(s -> GESTURE.clear());
        });
    }

    private static InputConstants.Key boundKey() {
        return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(CraftableKeyMappings.CREATE_ONE);
    }

    private static boolean onKeyPressed(net.minecraft.client.gui.screens.Screen screen, int keyCode, int scanCode, int modifiers) {
        if (CraftingPlanOverlay.active()) return true;
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
            GESTURE.clear();
            return true;
        }

        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        // Match the configured key and reserve Shift for opening plan details.
        boolean detail = modifiers == org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT
                && boundKey().equals(key);
        if (!detail && !boundKey().equals(key)) {
            GESTURE.clear();
            return true;
        }

        if (modifiers != 0 && !detail) {
            GESTURE.clear();
            return true; // Modified C must never accidentally consume a FULL_ONLY craft.
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

        // Optimize routes only within the displayed item's exact output group.
        RecipeHolder<?> recipe = RecipeButtonTargetResolver.preferredRecipe(hoveredButton);
        if (recipe == null) return true; // A filtered/reloaded collection can disappear between render and input.
        if (detail) {
            GESTURE.clear();
            CraftingPlanOverlay.open(screen, recipe.id(), false,
                    RecipeButtonTargetResolver.outputVariants(hoveredButton));
            return false;
        }
        long sequence = ClientRequestSequence.next();
        var press = GESTURE.press(minecraft.player.containerMenu, recipe.id(), sequence,
                System.nanoTime(), CraftableClientConfig.doublePressMillis() * 1_000_000L);
        if (press.isPresent()) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new CreateRecipeRequestPayload(
                recipe.id(), sequence, press.getAsLong(), CraftableClientConfig.allowSurplusDrops(),
                CraftableClientConfig.partialPolicy()));
        return false;
    }
}
