package org.berusted.craftable.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;

/** Prevents resource and recipe state from crossing connections or bindings. */
public final class ClientSessionEvents {
    private ClientSessionEvents() {}
    public static void register() {
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) ClientBrowsePlanner.recipesChanged();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clearSessionState());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearSessionState());
    }
    public static void recipesUpdated() {
        ClientBrowsePlanner.recipesChanged();
        ClientRecipeStatusStore.clear();
        ClientRecipeStatusStore.invalidate(ClientRequestSequence.next());
        RecipeBookStatusHandler.clearRequestState();
    }
    private static void clearSessionState() {
        CraftingPlanOverlay.clear();
        org.berusted.craftable.network.ClientPayloadHandler.clear();
        ClientBrowsePlanner.disconnect();
        ClientRecipeStatusStore.clear();
        org.berusted.craftable.client.menu.AmbientInventoryEvents.clear();
        RecipeBookStatusHandler.clearRequestState();
    }
}
