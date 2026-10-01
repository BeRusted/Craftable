package org.berusted.craftable.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Owns the codecs and server receivers for the M4 protocol. */
public final class CraftablePayloads {
    public static final String PROTOCOL_VERSION = "9";
    private CraftablePayloads() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(CraftingDetailPayloads.BrowseRequest.TYPE, CraftingDetailPayloads.BrowseRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(CraftingDetailPayloads.BrowseLease.TYPE, CraftingDetailPayloads.BrowseLease.CODEC);
        PayloadTypeRegistry.playS2C().register(CraftingDetailPayloads.BrowseChunk.TYPE, CraftingDetailPayloads.BrowseChunk.CODEC);
        PayloadTypeRegistry.playC2S().register(CraftingDetailPayloads.PreviewRequest.TYPE, CraftingDetailPayloads.PreviewRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(CraftingDetailPayloads.PreviewResponse.TYPE, CraftingDetailPayloads.PreviewResponse.CODEC);
        PayloadTypeRegistry.playC2S().register(CraftingDetailPayloads.ConfirmRequest.TYPE, CraftingDetailPayloads.ConfirmRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(OpenInventoryRequestPayload.TYPE, OpenInventoryRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(CreateRecipeRequestPayload.TYPE, CreateRecipeRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(CreateRecipeResultPayload.TYPE, CreateRecipeResultPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CraftingDetailPayloads.BrowseRequest.TYPE, CraftablePayloadHandlers::handleBrowse);
        ServerPlayNetworking.registerGlobalReceiver(CraftingDetailPayloads.PreviewRequest.TYPE, CraftablePayloadHandlers::handlePlanPreview);
        ServerPlayNetworking.registerGlobalReceiver(CraftingDetailPayloads.ConfirmRequest.TYPE, CraftablePayloadHandlers::handlePlanConfirm);
        ServerPlayNetworking.registerGlobalReceiver(OpenInventoryRequestPayload.TYPE, CraftablePayloadHandlers::handleOpenInventory);
        ServerPlayNetworking.registerGlobalReceiver(CreateRecipeRequestPayload.TYPE, CraftablePayloadHandlers::handleCreateRequest);
    }
}
