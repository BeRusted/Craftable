package org.berusted.craftable.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Owns the protocol version and all Craftable payload registrations.
 */
public final class CraftablePayloads {
    public static final String PROTOCOL_VERSION = "2";

    private CraftablePayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(
                RecipeStatusRequestPayload.TYPE,
                RecipeStatusRequestPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleStatusRequest);
        registrar.playToClient(
                RecipeStatusResponsePayload.TYPE,
                RecipeStatusResponsePayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleStatusResponse);
        registrar.playToServer(
                CreateRecipeRequestPayload.TYPE,
                CreateRecipeRequestPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleCreateRequest);
        registrar.playToClient(
                CreateRecipeResultPayload.TYPE,
                CreateRecipeResultPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleCreateResult);
    }
}
