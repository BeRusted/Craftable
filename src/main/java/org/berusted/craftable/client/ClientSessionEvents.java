package org.berusted.craftable.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;

/** Prevents cached state from one server session from leaking into another. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class ClientSessionEvents {
    private ClientSessionEvents() {}

    @SubscribeEvent
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        // In an integrated game the same event bus also sees server loading.
        // Only the received client binding invalidates this client's graph.
        if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED)
            org.berusted.craftable.client.recipebook.ClientBrowsePlanner.recipesChanged();
    }

    @SubscribeEvent
    public static void onRecipesUpdated(net.neoforged.neoforge.client.event.RecipesUpdatedEvent event) {
        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.recipesChanged();
        ClientRecipeStatusStore.clear();
        ClientRecipeStatusStore.invalidate(ClientRequestSequence.next());
        RecipeBookStatusHandler.clearRequestState();
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        clearSessionState();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearSessionState();
    }

    private static void clearSessionState() {
        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.disconnect();
        ClientRecipeStatusStore.clear();
        org.berusted.craftable.client.menu.AmbientInventoryEvents.clear();
        RecipeBookStatusHandler.clearRequestState();
    }
}
