package org.berusted.craftable.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import org.berusted.craftable.client.menu.AmbientInventoryEvents;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;

public final class ClientSessionEvents {

    private ClientSessionEvents() {}

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    clearSessionState();
                }
        );

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    clearSessionState();
                }
        );
    }

    private static void clearSessionState() {
        ClientRecipeStatusStore.clear();

        AmbientInventoryEvents.clear();

        RecipeBookStatusHandler.clearRequestState();
    }
}