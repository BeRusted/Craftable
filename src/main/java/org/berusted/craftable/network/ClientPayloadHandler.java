package org.berusted.craftable.network;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.berusted.craftable.client.CraftableFeedback;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.menu.AmbientInventoryEvents;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;
import org.berusted.craftable.config.CraftableClientConfig;

@OnlyIn(Dist.CLIENT)
final class ClientPayloadHandler {
    private static long lastCreateResponseRequestId = Long.MIN_VALUE;
    private ClientPayloadHandler() {}

    static void handle(RecipeStatusResponsePayload payload) {
        RecipeBookStatusHandler.received(payload);
        var mc = Minecraft.getInstance();
        if (mc.level == null || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()
                || !ClientRecipeStatusStore.accepts(payload.requestId(), payload.environmentGeneration())) return;
        for (var entry : payload.entries()) {
            ClientRecipeStatusStore.put(entry.recipeId(), payload.requestId(), entry.status(), entry.resultCode(),
                    payload.environmentGeneration(), mc.level.getGameTime());
        }
        AmbientInventoryEvents.receiveRules(payload);
    }

    static void handle(CreateRecipeResultPayload payload) {
        if (payload.requestId() <= lastCreateResponseRequestId) return;
        lastCreateResponseRequestId = payload.requestId();
        if (Minecraft.getInstance().player == null || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        // Sequence barrier also covers previews sent after C but before its
        // response; they may have observed the pre-commit environment.
        RecipeBookStatusHandler.afterCreate(ClientRequestSequence.next());
        CraftableFeedback.showCreateResult(payload.resultCode(), CraftableClientConfig.detailedFailureFeedbackEnabled());
    }
}
