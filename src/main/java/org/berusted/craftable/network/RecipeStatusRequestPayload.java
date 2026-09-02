package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;

public record RecipeStatusRequestPayload(ResourceLocation recipeId) implements CustomPacketPayload {
    public static final Type<RecipeStatusRequestPayload> TYPE = new Type<>(Craftable.id("recipe_status_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeStatusRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> buffer.writeResourceLocation(payload.recipeId),
                    buffer -> new RecipeStatusRequestPayload(buffer.readResourceLocation()));

    @Override
    public Type<RecipeStatusRequestPayload> type() {
        return TYPE;
    }
}
