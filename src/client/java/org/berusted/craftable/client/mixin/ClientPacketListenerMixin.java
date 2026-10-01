package org.berusted.craftable.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import org.berusted.craftable.client.ClientSessionEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleUpdateRecipes", at = @At("TAIL"))
    private void craftable$recipesUpdated(ClientboundUpdateRecipesPacket packet, CallbackInfo ci) {
        ClientSessionEvents.recipesUpdated();
    }
}
