package org.berusted.craftable.client.mixin;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin extends AbstractWidget {
    protected RecipeButtonMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void craftable$renderStatus(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        RecipeButton self = (RecipeButton) (Object) this;
        CraftingStatus status = craftable$status(self);
        guiGraphics.renderOutline(getX(), getY(), getWidth(), getHeight(), craftable$color(status));
    }

    @Inject(method = "getTooltipText", at = @At("RETURN"))
    private void craftable$appendStatus(CallbackInfoReturnable<List<Component>> callback) {
        RecipeButton self = (RecipeButton) (Object) this;
        CraftingStatus status = craftable$status(self);
        callback.getReturnValue().add(Component.translatable("tooltip.craftable.status", Component.translatable(
                "status.craftable." + status.name().toLowerCase())));
        callback.getReturnValue().add(Component.translatable("tooltip.craftable.create_one"));
    }

    private static CraftingStatus craftable$status(RecipeButton button) {
        RecipeHolder<?> recipe = button.getRecipe();
        return ClientRecipeStatusStore.get(recipe.id(), button.getCollection().isCraftable(recipe));
    }

    private static int craftable$color(CraftingStatus status) {
        return switch (status) {
            case CRAFTABLE -> 0xFF55FF55;
            case PARTIAL -> 0xFFFFFF55;
            case BLOCKED -> 0xFFFF5555;
        };
    }
}
