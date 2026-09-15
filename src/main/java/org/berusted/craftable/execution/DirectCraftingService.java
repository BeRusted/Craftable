package org.berusted.craftable.execution;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.environment.EnvironmentSnapshotService;

/** Public server-authoritative facade for the deliberately single-step M2 feature. */
public final class DirectCraftingService {
    private DirectCraftingService() {}

    public static DirectCraftingEvaluation evaluate(ServerPlayer player, ResourceLocation recipeId) {
        if (!DirectCraftingPlanner.hasValidContext(player)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.INVALID_CONTEXT);
        }
        return DirectCraftingPlanner.evaluate(player, recipeId, EnvironmentSnapshotService.preview(player));
    }

    public static DirectCraftingEvaluation evaluate(
            ServerPlayer player, ResourceLocation recipeId, EnvironmentSnapshot snapshot) {
        return DirectCraftingPlanner.evaluate(player, recipeId, snapshot);
    }

    public static CraftingResultCode createOne(ServerPlayer player, ResourceLocation recipeId) {
        if (!DirectCraftingPlanner.hasValidContext(player)) {
            return CraftingResultCode.INVALID_CONTEXT;
        }
        // Preview plans never authorize writes. Every create request rescans and
        // replans from current server state before entering the transaction.
        try {
            EnvironmentSnapshot snapshot = EnvironmentSnapshotService.fresh(player);
            DirectCraftingEvaluation evaluation = DirectCraftingPlanner.evaluate(player, recipeId, snapshot);
            return evaluation.plan()
                    .map(plan -> DirectCraftingTransaction.execute(player, recipeId, plan))
                    .orElse(evaluation.resultCode());
        } catch (RuntimeException exception) {
            // Planning/scanning failures occur before mutation; transaction
            // failures are compensated internally before they can escape.
            Craftable.LOGGER.error(
                    "Direct crafting request failed for {} and recipe {}",
                    player.getGameProfile().getName(),
                    recipeId,
                    exception);
            return CraftingResultCode.INTERNAL_ERROR;
        } finally {
            // Success and failure can both make an earlier preview misleading.
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }
}
