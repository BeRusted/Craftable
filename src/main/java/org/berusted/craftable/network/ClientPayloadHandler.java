package org.berusted.craftable.network;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.berusted.craftable.client.CraftableFeedback;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;
import org.berusted.craftable.config.CraftableClientConfig;

@OnlyIn(Dist.CLIENT)
final class ClientPayloadHandler {
    private static long lastCreateResponseRequestId = Long.MIN_VALUE;
    private ClientPayloadHandler() {}
    public static void handle(CraftingDetailPayloads.BrowseLease payload) {
        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.receive(payload);
    }
    public static void handle(CraftingDetailPayloads.BrowseChunk payload) {
        org.berusted.craftable.client.recipebook.ClientBrowsePlanner.receive(payload);
    }

    static void handle(CraftingDetailPayloads.PreviewResponse payload) {
        org.berusted.craftable.client.CraftingPlanOverlay.receive(payload);
    }


    static void handle(CreateRecipeResultPayload payload) {
        if (payload.requestId() <= lastCreateResponseRequestId) return;
        lastCreateResponseRequestId = payload.requestId();
        if (Minecraft.getInstance().player == null || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        // Throttling/context refusals did not inspect or change resources.
        // A completed attempt is refreshed by the existing server browse session.
        if (payload.resultCode() != org.berusted.craftable.api.CraftingResultCode.REQUEST_THROTTLED
                && payload.resultCode() != org.berusted.craftable.api.CraftingResultCode.INVALID_CONTEXT)
            RecipeBookStatusHandler.afterCreate(payload.recipeId());
        org.berusted.craftable.client.CraftingPlanOverlay.created(payload);
        CraftableFeedback.showCreateResult(payload, CraftableClientConfig.detailedFailureFeedbackEnabled());
    }
}
