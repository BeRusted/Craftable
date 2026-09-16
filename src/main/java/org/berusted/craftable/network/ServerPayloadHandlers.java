package org.berusted.craftable.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import org.berusted.craftable.api.CraftableModePolicy;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.execution.DirectCraftingEvaluation;
import org.berusted.craftable.execution.DirectCraftingService;
import org.berusted.craftable.menu.AmbientInventoryMenu;
import org.berusted.craftable.network.payload.*;
import org.berusted.craftable.workstation.WorkstationCapability;
import org.berusted.craftable.workstation.WorkstationEndpoint;

public final class ServerPayloadHandlers {
    private ServerPayloadHandlers() {
    }

    public static void handleOpenInventory(OpenInventoryRequestPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (payload.requestId() < 0
                || !CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())
                || !player.isAlive()
                || player.containerMenu != player.inventoryMenu
                || !CraftableRequestLimiter.allowOpen(player.getUUID(), player.serverLevel().getGameTime())) {
            return;
        }
        EnvironmentSnapshot snapshot = EnvironmentSnapshotService.fresh(player);
        List<BlockPos> tables = snapshot.workstations().stream()
                .filter(workstation -> workstation.capability() == WorkstationCapability.CRAFTING_3X3)
                .map(WorkstationEndpoint::position)
                .filter(Objects::nonNull)
                .toList();
        if (tables.isEmpty()) {
            return;
        }
        player.inventoryMenu.removed(player);
        player.inventoryMenu.broadcastChanges();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, owner) -> new AmbientInventoryMenu(id, inventory, tables),
                Component.translatable("container.crafting")));
    }

    public static void handleStatusRequest(RecipeStatusRequestPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (!CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())) {
            return;
        }
        if (payload.requestId() < 0) {
            return;
        }
        if (!CraftableRequestLimiter.allowStatus(player.getUUID(), player.serverLevel().getGameTime())) {
            return;
        }
        EnvironmentSnapshot snapshot = EnvironmentSnapshotService.preview(player);
        List<RecipeStatusResponsePayload.Entry> entries = new ArrayList<>();
        long deadline = System.nanoTime() + 8_000_000L;
        for (ResourceLocation id : new LinkedHashSet<>(payload.recipeIds())) {
            DirectCraftingEvaluation evaluation = DirectCraftingService.evaluate(player, id, snapshot);
            entries.add(new RecipeStatusResponsePayload.Entry(id, evaluation.status(), evaluation.resultCode()));
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        EnvironmentScanSettings settings = snapshot.scanSettings();
        context.responseSender().sendPacket(new RecipeStatusResponsePayload(
                entries,
                payload.requestId(),
                snapshot.generation(),
                snapshot.supports(WorkstationCapability.CRAFTING_3X3),
                settings.horizontalRadius(),
                settings.verticalRadius(),
                settings.previewCacheTicks(),
                settings.includeEnderChest()));
    }

    public static void handleCreateRequest(CreateRecipeRequestPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (!CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())) {
            return;
        }
        if (payload.requestId() < 0) {
            return;
        }
        if (!CraftableRequestLimiter.allowCreate(player.getUUID(), player.serverLevel().getGameTime())) {
            context.responseSender().sendPacket(new CreateRecipeResultPayload(
                    payload.recipeId(), payload.requestId(), CraftingResultCode.REQUEST_THROTTLED));
            return;
        }
        CraftingResultCode result = DirectCraftingService.createOne(player, payload.recipeId());
        context.responseSender().sendPacket(new CreateRecipeResultPayload(
                payload.recipeId(), payload.requestId(), result));
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(
                OpenInventoryRequestPayload.TYPE,
                ServerPayloadHandlers::handleOpenInventory
        );

        ServerPlayNetworking.registerGlobalReceiver(
                RecipeStatusRequestPayload.TYPE,
                ServerPayloadHandlers::handleStatusRequest
        );

        ServerPlayNetworking.registerGlobalReceiver(
                CreateRecipeRequestPayload.TYPE,
                ServerPayloadHandlers::handleCreateRequest
        );
    }
}
