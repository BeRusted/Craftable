package org.berusted.craftable.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.berusted.craftable.client.CraftingPlanOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void craftable$overlayText(long window, int codePoint, int modifiers, CallbackInfo ci) {
        var client = Minecraft.getInstance();
        if (window != client.getWindow().getWindow() || !CraftingPlanOverlay.active()) return;
        for (char character : Character.toChars(codePoint))
            if (CraftingPlanOverlay.character(client.screen, character, modifiers)) ci.cancel();
    }
}
