package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;

/** Client intent only: the server resolves every resource and workstation. */
public record CreateRecipeRequestPayload(ResourceLocation recipeId, long requestId) implements CustomPacketPayload {
    public static final Type<CreateRecipeRequestPayload> TYPE = new Type<>(Craftable.id("create_recipe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateRecipeRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> {
                        buffer.writeResourceLocation(payload.recipeId());
                        buffer.writeVarLong(payload.requestId());
                    },
                    buffer -> new CreateRecipeRequestPayload(
                            buffer.readResourceLocation(), buffer.readVarLong()));

    @Override
    public Type<CreateRecipeRequestPayload> type() {
        return TYPE;
    }
}
