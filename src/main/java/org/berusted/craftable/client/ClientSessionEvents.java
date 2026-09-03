package org.berusted.craftable.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.client.recipebook.ClientWorkstationProbe;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;

/** Prevents cached state from one server session from leaking into another. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class ClientSessionEvents {
    private ClientSessionEvents() {}

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        clearSessionState();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearSessionState();
    }

    private static void clearSessionState() {
        ClientRecipeStatusStore.clear();
        ClientWorkstationProbe.clear();
        RecipeBookStatusHandler.clearRequestState();
    }
}
