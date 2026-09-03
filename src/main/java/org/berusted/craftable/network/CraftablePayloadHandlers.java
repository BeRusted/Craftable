package org.berusted.craftable.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.execution.DirectCraftingEvaluation;
import org.berusted.craftable.execution.DirectCraftingService;

public final class CraftablePayloadHandlers {
    private CraftablePayloadHandlers() {}

    public static void handleStatusRequest(RecipeStatusRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (payload.requestId() < 0) {
                return;
            }
            if (!CraftableRequestLimiter.allowStatus(player.getUUID(), player.serverLevel().getGameTime())) {
                return;
            }
            EnvironmentSnapshot snapshot = EnvironmentSnapshotService.preview(player);
            DirectCraftingEvaluation evaluation = DirectCraftingService.evaluate(player, payload.recipeId(), snapshot);
            context.reply(new RecipeStatusResponsePayload(
                    payload.recipeId(),
                    payload.requestId(),
                    evaluation.status(),
                    evaluation.resultCode(),
                    snapshot.generation()));
        });
    }

    public static void handleCreateRequest(CreateRecipeRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (payload.requestId() < 0) {
                return;
            }
            if (!CraftableRequestLimiter.allowCreate(player.getUUID(), player.serverLevel().getGameTime())) {
                context.reply(new CreateRecipeResultPayload(
                        payload.recipeId(), payload.requestId(), CraftingResultCode.REQUEST_THROTTLED));
                return;
            }
            CraftingResultCode result = DirectCraftingService.createOne(player, payload.recipeId());
            context.reply(new CreateRecipeResultPayload(payload.recipeId(), payload.requestId(), result));
        });
    }

    public static void handleStatusResponse(RecipeStatusResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handle(payload));
    }

    public static void handleCreateResult(CreateRecipeResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handle(payload));
    }
}
