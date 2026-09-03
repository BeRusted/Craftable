package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;

/** A read-only preview intent; no client-provided resource facts are accepted. */
public record RecipeStatusRequestPayload(ResourceLocation recipeId, long requestId) implements CustomPacketPayload {
    public static final Type<RecipeStatusRequestPayload> TYPE = new Type<>(Craftable.id("recipe_status_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeStatusRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> {
                        buffer.writeResourceLocation(payload.recipeId());
                        buffer.writeVarLong(payload.requestId());
                    },
                    buffer -> new RecipeStatusRequestPayload(
                            buffer.readResourceLocation(), buffer.readVarLong()));

    @Override
    public Type<RecipeStatusRequestPayload> type() {
        return TYPE;
    }
}
