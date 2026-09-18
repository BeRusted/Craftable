package org.berusted.craftable.client.mixin;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.recipebook.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin extends AbstractWidget {
    @Shadow private int currentIndex;
    @Shadow private List<net.minecraft.world.item.crafting.RecipeHolder<?>> getOrderedRecipes() { throw new AssertionError(); }
    protected RecipeButtonMixin(int x, int y, int width, int height, Component message) { super(x, y, width, height, message); }

    @Inject(method = "init", at = @At("TAIL"))
    private void craftable$rebindIndex(net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
            net.minecraft.client.gui.screens.recipebook.RecipeBookPage page, CallbackInfo ci) {
        if (RecipeBookProjection.active()) currentIndex = 0;
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void craftable$validRender(GuiGraphics gui, int x, int y, float delta, CallbackInfo ci) {
        // Teardown can leave old buttons alive for one frame after the mode
        // gate disables projection. Index safety must outlive that projection.
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        int size = getOrderedRecipes().size();
        if (size == 0) { ci.cancel(); return; }
        currentIndex = Math.floorMod(currentIndex, size);
    }

    @Inject(method = "getRecipe", at = @At("HEAD"), cancellable = true)
    private void craftable$validSelection(CallbackInfoReturnable<net.minecraft.world.item.crafting.RecipeHolder<?>> ci) {
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        var ordered = getOrderedRecipes();
        ci.setReturnValue(ordered.isEmpty() ? null : ordered.get(Math.floorMod(currentIndex, ordered.size())));
    }

    @Inject(method = "getTooltipText", at = @At("HEAD"), cancellable = true)
    private void craftable$validTooltip(CallbackInfoReturnable<List<Component>> ci) {
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        int size = getOrderedRecipes().size();
        if (size == 0) { ci.setReturnValue(new java.util.ArrayList<>()); return; }
        currentIndex = Math.floorMod(currentIndex, size);
    }

    @Inject(method = "updateWidgetNarration", at = @At("HEAD"), cancellable = true)
    private void craftable$validNarration(net.minecraft.client.gui.narration.NarrationElementOutput output, CallbackInfo ci) {
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        int size = getOrderedRecipes().size();
        if (size == 0) { ci.cancel(); return; }
        currentIndex = Math.floorMod(currentIndex, size);
    }

    @Redirect(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void craftable$background(GuiGraphics gui, ResourceLocation sprite, int x, int y, int width, int height) {
        if (!RecipeBookProjection.active()) { gui.blitSprite(sprite, x, y, width, height); return; }
        var button = (RecipeButton) (Object) this;
        var status = RecipeButtonTargetResolver.status(button);
        boolean unknown = RecipeButtonTargetResolver.lifecycle(button) == ClientRecipeStatusStore.Lifecycle.UNKNOWN;
        if (!unknown) {
            switch (status) {
                case CRAFTABLE -> gui.setColor(0.65F, 1F, 0.65F, 1F);
                case PARTIAL -> gui.setColor(1F, 0.9F, 0.5F, 1F);
                case BLOCKED -> gui.setColor(1F, 0.65F, 0.65F, 1F);
            }
        }
        // Tint the vanilla background itself. There is no second outline whose
        // meaning could disagree with the vanilla filtering collection.
        gui.blitSprite(sprite, x, y, width, height);
        gui.setColor(1F, 1F, 1F, 1F);
    }

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void craftable$symbol(GuiGraphics gui, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!RecipeBookProjection.active()) return;
        var button = (RecipeButton) (Object) this;
        var lifecycle = RecipeButtonTargetResolver.lifecycle(button);
        String symbol = lifecycle == ClientRecipeStatusStore.Lifecycle.UNKNOWN ? "?"
                : lifecycle == ClientRecipeStatusStore.Lifecycle.PENDING ? "~"
                : switch (RecipeButtonTargetResolver.status(button)) {
                    case CRAFTABLE -> "+"; case PARTIAL -> "~"; case BLOCKED -> "!";
                };
        // Item rendering writes at a positive depth; a later draw alone is not
        // sufficient. Keep the badge above items but below the tooltip layer.
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 250);
        gui.drawString(Minecraft.getInstance().font, symbol, getX() + 18, getY() + 1, 0xFFFFFFFF, true);
        gui.pose().popPose();
    }

    @Inject(method = "getTooltipText", at = @At("RETURN"))
    private void craftable$tooltip(CallbackInfoReturnable<List<Component>> ci) {
        if (!RecipeBookProjection.active()) return;
        var button = (RecipeButton) (Object) this;
        var lifecycle = RecipeButtonTargetResolver.lifecycle(button);
        if (lifecycle != ClientRecipeStatusStore.Lifecycle.UNKNOWN) {
            var status = RecipeButtonTargetResolver.status(button);
            Component label = Component.translatable("status." + status.name().toLowerCase(java.util.Locale.ROOT));
            ci.getReturnValue().add(status == CraftingStatus.BLOCKED
                    ? Component.translatable("tooltip.blocked_reason", label, Component.translatable("reason." +
                        ClientRecipeStatusStore.reason(RecipeButtonTargetResolver.preferredRecipe(button).id()).name().toLowerCase(java.util.Locale.ROOT)))
                    : label);
        }
        if (lifecycle != ClientRecipeStatusStore.Lifecycle.KNOWN) ci.getReturnValue().add(Component.translatable("tooltip.pending"));
        ci.getReturnValue().add(Component.translatable("tooltip.create_one"));
    }
}
