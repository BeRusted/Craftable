package org.berusted.craftable.network;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.berusted.craftable.client.CraftableFeedback;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.config.CraftableClientConfig;

@OnlyIn(Dist.CLIENT)
final class ClientPayloadHandler {
    private static long lastCreateResponseRequestId = Long.MIN_VALUE;

    private ClientPayloadHandler() {}

    static void handle(RecipeStatusResponsePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        ClientRecipeStatusStore.put(
                payload.recipeId(),
                payload.requestId(),
                payload.status(),
                payload.resultCode(),
                payload.environmentGeneration(),
                minecraft.level.getGameTime());
    }

    static void handle(CreateRecipeResultPayload payload) {
        // Responses may cross on a slow connection. The echoed sequence is only
        // used to suppress stale UI feedback; the server never trusts it.
        if (payload.requestId() <= lastCreateResponseRequestId) {
            return;
        }
        lastCreateResponseRequestId = payload.requestId();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ClientRecipeStatusStore.clear();
        CraftableFeedback.showCreateResult(
                payload.resultCode(), CraftableClientConfig.detailedFailureFeedbackEnabled());
    }
}
