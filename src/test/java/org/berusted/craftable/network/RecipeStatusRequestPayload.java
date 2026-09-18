package org.berusted.craftable.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;

/** Bounded read-only batch; decoding rejects oversized lists before allocation. */
// Protocol 6 baseline fixture only; not present in the production artifact.
public record RecipeStatusRequestPayload(List<ResourceLocation> recipeIds, long requestId, boolean allowDrops,
        org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy) implements CustomPacketPayload {
    public RecipeStatusRequestPayload(List<ResourceLocation> recipeIds, long requestId) {
        this(recipeIds, requestId, true, org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE);
    }
    public static final int MAX_RECIPES = 32;
    public RecipeStatusRequestPayload {
        recipeIds = List.copyOf(recipeIds);
        if (recipeIds.size() > MAX_RECIPES) throw new IllegalArgumentException("Oversized recipe preview");
    }
    public static final Type<RecipeStatusRequestPayload> TYPE = new Type<>(Craftable.id("recipe_status_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeStatusRequestPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buffer) -> {
                buffer.writeCollection(payload.recipeIds(), (buf, id) -> buf.writeResourceLocation(id));
                buffer.writeVarLong(payload.requestId());
                buffer.writeBoolean(payload.allowDrops());
                buffer.writeEnum(payload.partialPolicy());
            },
            buffer -> new RecipeStatusRequestPayload(
                    buffer.readCollection(net.minecraft.network.FriendlyByteBuf.limitValue(java.util.ArrayList::new, MAX_RECIPES),
                            buf -> buf.readResourceLocation()), buffer.readVarLong(), buffer.readBoolean(),
                    buffer.readEnum(org.berusted.craftable.planner.CraftRequest.PartialPolicy.class)));
    @Override public Type<RecipeStatusRequestPayload> type() { return TYPE; }
}
