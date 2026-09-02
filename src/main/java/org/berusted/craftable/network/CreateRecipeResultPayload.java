package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;

public record CreateRecipeResultPayload(ResourceLocation recipeId, CraftingResultCode resultCode)
        implements CustomPacketPayload {
    public static final Type<CreateRecipeResultPayload> TYPE = new Type<>(Craftable.id("create_recipe_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateRecipeResultPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> {
                        buffer.writeResourceLocation(payload.recipeId);
                        buffer.writeEnum(payload.resultCode);
                    },
                    buffer -> new CreateRecipeResultPayload(
                            buffer.readResourceLocation(), buffer.readEnum(CraftingResultCode.class)));

    @Override
    public Type<CreateRecipeResultPayload> type() {
        return TYPE;
    }
}
