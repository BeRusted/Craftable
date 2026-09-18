package org.berusted.craftable.client.recipebook;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
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
    private static final org.berusted.craftable.client.DoublePressGesture GESTURE =
            new org.berusted.craftable.client.DoublePressGesture();
    private RecipeBookInputHandler() {}

    @SubscribeEvent
    public static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (CraftableKeyMappings.CREATE_ONE.isActiveAndMatches(
                InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) GESTURE.release();
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) { GESTURE.clear(); }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        // A category/page click changes the gesture context even if the same
        // recipe reappears under the cursor within the double-press window.
        GESTURE.clear();
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (org.berusted.craftable.client.CraftingPlanOverlay.active()) return;
        if (!RecipeBookProjection.active()) {
            return;
        }
        RecipeBookComponent component = RecipeBookProjection.component(event.getScreen());
        if (component == null || !component.isVisible()) {
            return;
        }

        RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) component;
        EditBox searchBox = componentAccessor.craftable$getSearchBox();
        if (searchBox != null && searchBox.isFocused()) {
            GESTURE.clear();
            return;
        }

        InputConstants.Key key = InputConstants.getKey(event.getKeyCode(), event.getScanCode());
        // GUI-context NONE mappings intentionally reject held modifiers in
        // NeoForge. Match the configured base key explicitly for Shift+C.
        boolean detail = event.getModifiers() == org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT
                && CraftableKeyMappings.CREATE_ONE.getKey().equals(key);
        if (!detail && !CraftableKeyMappings.CREATE_ONE.isActiveAndMatches(key)) {
            GESTURE.clear();
            return;
        }

        if (event.getModifiers() != 0 && !detail) {
            GESTURE.clear();
            return; // Modified C must never accidentally consume a FULL_ONLY craft.
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

        // A vanilla button may animate through several equivalent outputs.
        // Execute the collection's best known target, not the current frame.
        RecipeHolder<?> recipe = RecipeButtonTargetResolver.preferredRecipe(hoveredButton);
        if (recipe == null) return; // A filtered/reloaded collection can disappear between render and input.
        if (detail) {
            GESTURE.clear();
            org.berusted.craftable.client.CraftingPlanOverlay.open(event.getScreen(), recipe.id(), false);
            event.setCanceled(true);
            return;
        }
        long sequence = ClientRequestSequence.next();
        var press = GESTURE.press(minecraft.player.containerMenu, recipe.id(), sequence,
                System.nanoTime(), CraftableClientConfig.doublePressMillis() * 1_000_000L);
        if (press.isPresent()) PacketDistributor.sendToServer(new CreateRecipeRequestPayload(
                recipe.id(), sequence, press.getAsLong(), CraftableClientConfig.allowSurplusDrops(),
                CraftableClientConfig.partialPolicy()));
        event.setCanceled(true);
    }
}
