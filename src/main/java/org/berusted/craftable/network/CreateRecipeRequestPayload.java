package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;

public record CreateRecipeRequestPayload(ResourceLocation recipeId) implements CustomPacketPayload {
    public static final Type<CreateRecipeRequestPayload> TYPE = new Type<>(Craftable.id("create_recipe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateRecipeRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> buffer.writeResourceLocation(payload.recipeId),
                    buffer -> new CreateRecipeRequestPayload(buffer.readResourceLocation()));

    @Override
    public Type<CreateRecipeRequestPayload> type() {
        return TYPE;
    }
}
