package org.berusted.craftable.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class CraftablePayloads {
    public static final String PROTOCOL_VERSION = "5";

    private CraftablePayloads() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(
                OpenInventoryRequestPayload.TYPE,
                OpenInventoryRequestPayload.STREAM_CODEC
        );

        PayloadTypeRegistry.playC2S().register(
                RecipeStatusRequestPayload.TYPE,
                RecipeStatusRequestPayload.STREAM_CODEC
        );

        PayloadTypeRegistry.playS2C().register(
                RecipeStatusResponsePayload.TYPE,
                RecipeStatusResponsePayload.STREAM_CODEC
        );

        PayloadTypeRegistry.playC2S().register(
                CreateRecipeRequestPayload.TYPE,
                CreateRecipeRequestPayload.STREAM_CODEC
        );

        PayloadTypeRegistry.playS2C().register(
                CreateRecipeResultPayload.TYPE,
                CreateRecipeResultPayload.STREAM_CODEC
        );


    }
}
