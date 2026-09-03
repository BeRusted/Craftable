package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;

public record RecipeStatusResponsePayload(
        ResourceLocation recipeId,
        long requestId,
        CraftingStatus status,
        CraftingResultCode resultCode,
        long environmentGeneration) implements CustomPacketPayload {
    public static final Type<RecipeStatusResponsePayload> TYPE = new Type<>(Craftable.id("recipe_status_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeStatusResponsePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> {
                        buffer.writeResourceLocation(payload.recipeId());
                        buffer.writeVarLong(payload.requestId());
                        buffer.writeEnum(payload.status());
                        buffer.writeEnum(payload.resultCode());
                        buffer.writeVarLong(payload.environmentGeneration());
                    },
                    buffer -> new RecipeStatusResponsePayload(
                            buffer.readResourceLocation(),
                            buffer.readVarLong(),
                            buffer.readEnum(CraftingStatus.class),
                            buffer.readEnum(CraftingResultCode.class),
                            buffer.readVarLong()));

    @Override
    public Type<RecipeStatusResponsePayload> type() {
        return TYPE;
    }
}
