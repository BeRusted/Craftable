package org.berusted.craftable.network;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.berusted.craftable.client.CraftableFeedback;
import org.berusted.craftable.client.ClientRequestSequence;
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

    static void handle(CraftingDetailPayloads.MaximumResponse payload) {
        org.berusted.craftable.client.CraftingPlanOverlay.receive(payload);
    }

    static void handle(CreateRecipeResultPayload payload) {
        if (payload.requestId() <= lastCreateResponseRequestId) return;
        lastCreateResponseRequestId = payload.requestId();
        if (Minecraft.getInstance().player == null || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        // Sequence barrier also covers previews sent after C but before its
        // response; they may have observed the pre-commit environment.
        RecipeBookStatusHandler.afterCreate(ClientRequestSequence.next());
        org.berusted.craftable.client.CraftingPlanOverlay.created(payload);
        CraftableFeedback.showCreateResult(payload, CraftableClientConfig.detailedFailureFeedbackEnabled());
    }
}
