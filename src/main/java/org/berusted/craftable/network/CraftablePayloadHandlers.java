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

    public static void handleOpenInventory(OpenInventoryRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || payload.requestId() < 0
                    || !org.berusted.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())
                    || !player.isAlive() || player.containerMenu != player.inventoryMenu
                    || !CraftableRequestLimiter.allowOpen(player.getUUID(), player.serverLevel().getGameTime())) return;
            var snapshot = EnvironmentSnapshotService.fresh(player);
            var tables = snapshot.workstations().stream()
                    .filter(w -> w.capability() == org.berusted.craftable.workstation.WorkstationCapability.CRAFTING_3X3)
                    .map(w -> w.position()).filter(java.util.Objects::nonNull).toList();
            if (tables.isEmpty()) return;
            // openMenu does not remove inventoryMenu itself. Return its grid and
            // carried stack first, so opening cannot strand items in the old 2x2.
            player.inventoryMenu.removed(player);
            player.inventoryMenu.broadcastChanges();
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, owner) -> new org.berusted.craftable.menu.AmbientInventoryMenu(id, inventory, tables),
                    net.minecraft.network.chat.Component.translatable("container.crafting")));
        });
    }

    public static void handleStatusRequest(RecipeStatusRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !org.berusted.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())) {
                return;
            }
            if (payload.requestId() < 0) {
                return;
            }
            if (!CraftableRequestLimiter.allowStatus(player.getUUID(), player.serverLevel().getGameTime())) {
                return;
            }
            EnvironmentSnapshot snapshot = EnvironmentSnapshotService.preview(player);
            java.util.List<RecipeStatusResponsePayload.Entry> entries = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + 8_000_000L;
            for (var id : new java.util.LinkedHashSet<>(payload.recipeIds())) {
                DirectCraftingEvaluation evaluation = DirectCraftingService.evaluate(player, id, snapshot);
                entries.add(new RecipeStatusResponsePayload.Entry(id, evaluation.status(), evaluation.resultCode()));
                // A partial batch is retried by the client, never reported as blocked.
                if (System.nanoTime() >= deadline) break;
            }
            var settings = snapshot.scanSettings();
            context.reply(new RecipeStatusResponsePayload(entries, payload.requestId(), snapshot.generation(),
                    snapshot.supports(org.berusted.craftable.workstation.WorkstationCapability.CRAFTING_3X3),
                    settings.horizontalRadius(), settings.verticalRadius(), settings.previewCacheTicks(), settings.includeEnderChest()));
        });
    }

    public static void handleCreateRequest(CreateRecipeRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !org.berusted.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())) {
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
