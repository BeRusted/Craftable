package org.berusted.craftable.client.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.berusted.craftable.client.CraftingPlanOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void craftable$overlayDrag(double x, double y, int button, double dx, double dy, CallbackInfoReturnable<Boolean> ci) {
        if (CraftingPlanOverlay.drag((Screen) (Object) this, x, y, button, dx, dy)) ci.setReturnValue(true);
    }
}
