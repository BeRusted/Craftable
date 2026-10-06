package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;

/** Client intent only: the server resolves every resource and workstation. */
public record CreateRecipeRequestPayload(ResourceLocation recipeId, long requestId, long precedingPress,
        boolean allowDrops, org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy) implements CustomPacketPayload {
    public CreateRecipeRequestPayload(ResourceLocation recipeId, long requestId) {
        this(recipeId, requestId, -1, true, org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE);
    }
    public static final Type<CreateRecipeRequestPayload> TYPE = new Type<>(Craftable.id("create_recipe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateRecipeRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> {
                        buffer.writeResourceLocation(payload.recipeId());
                        buffer.writeVarLong(payload.requestId());
                        buffer.writeVarLong(payload.precedingPress());
                        buffer.writeBoolean(payload.allowDrops());
                        buffer.writeEnum(payload.partialPolicy());
                    },
                    buffer -> new CreateRecipeRequestPayload(
                            buffer.readResourceLocation(), buffer.readVarLong(), buffer.readVarLong(),
                            buffer.readBoolean(), buffer.readEnum(org.berusted.craftable.planner.CraftRequest.PartialPolicy.class)));

    @Override
    public Type<CreateRecipeRequestPayload> type() {
        return TYPE;
    }
}
