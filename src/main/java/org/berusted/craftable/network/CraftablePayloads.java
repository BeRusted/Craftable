package org.berusted.craftable.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Owns the protocol version and all Craftable payload registrations.
 */
public final class CraftablePayloads {
    public static final String PROTOCOL_VERSION = "7";

    private CraftablePayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(CraftingDetailPayloads.BrowseRequest.TYPE, CraftingDetailPayloads.BrowseRequest.CODEC,
                CraftablePayloadHandlers::handleBrowse);
        registrar.playToClient(CraftingDetailPayloads.BrowseLease.TYPE, CraftingDetailPayloads.BrowseLease.CODEC,
                (p,c) -> c.enqueueWork(() -> ClientPayloadHandler.handle(p)));
        registrar.playToClient(CraftingDetailPayloads.BrowseChunk.TYPE, CraftingDetailPayloads.BrowseChunk.CODEC,
                (p,c) -> c.enqueueWork(() -> ClientPayloadHandler.handle(p)));
        registrar.playToServer(CraftingDetailPayloads.PreviewRequest.TYPE, CraftingDetailPayloads.PreviewRequest.CODEC,
                CraftablePayloadHandlers::handlePlanPreview);
        registrar.playToClient(CraftingDetailPayloads.PreviewResponse.TYPE, CraftingDetailPayloads.PreviewResponse.CODEC,
                CraftablePayloadHandlers::handlePlanPreviewResult);
        registrar.playToServer(CraftingDetailPayloads.MaximumRequest.TYPE, CraftingDetailPayloads.MaximumRequest.CODEC,
                CraftablePayloadHandlers::handlePlanMaximum);
        registrar.playToClient(CraftingDetailPayloads.MaximumResponse.TYPE, CraftingDetailPayloads.MaximumResponse.CODEC,
                CraftablePayloadHandlers::handlePlanMaximumResult);
        registrar.playToServer(CraftingDetailPayloads.ConfirmRequest.TYPE, CraftingDetailPayloads.ConfirmRequest.CODEC,
                CraftablePayloadHandlers::handlePlanConfirm);
        registrar.playToServer(OpenInventoryRequestPayload.TYPE, OpenInventoryRequestPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleOpenInventory);
        // Protocol 7 has no passive per-target status request. Old codecs may
        // remain in baseline tests, but cannot silently resurrect bulk polling.
        registrar.playToServer(
                CreateRecipeRequestPayload.TYPE,
                CreateRecipeRequestPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleCreateRequest);
        registrar.playToClient(
                CreateRecipeResultPayload.TYPE,
                CreateRecipeResultPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleCreateResult);
    }
}
