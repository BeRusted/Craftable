package org.berusted.craftable.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.CraftableFeedback;
import org.berusted.craftable.client.menu.AmbientInventoryEvents;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.network.payload.CreateRecipeResultPayload;
import org.berusted.craftable.network.payload.RecipeStatusResponsePayload;

public final class ClientPayloadHandler {
    private static long lastCreateResponseRequestId = Long.MIN_VALUE;

    private ClientPayloadHandler() {
    }

    public static void handleStatusResponse(RecipeStatusResponsePayload payload, ClientPlayNetworking.Context context) {
        RecipeBookStatusHandler.received(payload);
        var mc = context.client();

        if (mc.level == null
                || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()
                || !ClientRecipeStatusStore.accepts(
                payload.requestId(),
                payload.environmentGeneration())) {
            return;
        }

        for (var entry : payload.entries()) {
            ClientRecipeStatusStore.put(
                    entry.recipeId(),
                    payload.requestId(),
                    entry.status(),
                    entry.resultCode(),
                    payload.environmentGeneration(),
                    mc.level.getGameTime()
            );
        }

        AmbientInventoryEvents.receiveRules(payload);
    }

    public static void handleCreateResult(CreateRecipeResultPayload payload, ClientPlayNetworking.Context context) {
        if (payload.requestId() <= lastCreateResponseRequestId) {
            return;
        }
        var mc = context.client();
        lastCreateResponseRequestId = payload.requestId();

        if (mc.player == null
                || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) {
            return;
        }

        RecipeBookStatusHandler.afterCreate(ClientRequestSequence.next());

        CraftableFeedback.showCreateResult(
                payload.resultCode(),
                CraftableClientConfig.detailedFailureFeedbackEnabled()
        );
    }

    public static void register() {

        ClientPlayNetworking.registerGlobalReceiver(
                RecipeStatusResponsePayload.TYPE,
                ClientPayloadHandler::handleStatusResponse
        );

        ClientPlayNetworking.registerGlobalReceiver(
                CreateRecipeResultPayload.TYPE,
                ClientPayloadHandler::handleCreateResult
        );
    }

}