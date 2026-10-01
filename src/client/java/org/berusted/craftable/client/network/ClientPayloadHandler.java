package org.berusted.craftable.client.network;

import net.minecraft.client.Minecraft;
import org.berusted.craftable.client.CraftableFeedback;
import org.berusted.craftable.client.CraftingPlanOverlay;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;
import org.berusted.craftable.client.config.CraftableClientConfig;

public final class ClientPayloadHandler {
    private static long lastCreateResponseRequestId = Long.MIN_VALUE;
    private ClientPayloadHandler() {}
    public static void register() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                CraftingDetailPayloads.BrowseLease.TYPE, (payload, context) -> handle(payload));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                CraftingDetailPayloads.BrowseChunk.TYPE, (payload, context) -> handle(payload));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                CraftingDetailPayloads.PreviewResponse.TYPE, (payload, context) -> handle(payload));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                CreateRecipeResultPayload.TYPE, (payload, context) -> handle(payload));
    }
    public static void clear() { lastCreateResponseRequestId = Long.MIN_VALUE; }
    public static void handle(CraftingDetailPayloads.BrowseLease payload) {
        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.receive(payload);
    }
    public static void handle(CraftingDetailPayloads.BrowseChunk payload) {
        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.receive(payload);
    }

    public static void handle(CraftingDetailPayloads.PreviewResponse payload) {
        CraftingPlanOverlay.receive(payload);
    }


    public static void handle(CreateRecipeResultPayload payload) {
        if (payload.requestId() <= lastCreateResponseRequestId) return;
        lastCreateResponseRequestId = payload.requestId();
        if (Minecraft.getInstance().player == null || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        // Throttling/context refusals did not inspect or change resources.
        // A completed attempt is refreshed by the existing server browse session.
        if (payload.resultCode() != org.berusted.craftable.api.CraftingResultCode.REQUEST_THROTTLED
                && payload.resultCode() != org.berusted.craftable.api.CraftingResultCode.INVALID_CONTEXT)
            RecipeBookStatusHandler.afterCreate(payload.recipeId());
        CraftingPlanOverlay.created(payload);
        CraftableFeedback.showCreateResult(payload, CraftableClientConfig.detailedFailureFeedbackEnabled());
    }
}
