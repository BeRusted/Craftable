package org.berusted.craftable.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;

/** All entries share one snapshot. Only effective rules and statuses cross the wire. */
public record RecipeStatusResponsePayload(List<Entry> entries, long requestId, long environmentGeneration,
        boolean craftingTable, int horizontalRadius, int verticalRadius, int previewTicks, boolean enderChest)
        implements CustomPacketPayload {
    public RecipeStatusResponsePayload {
        entries = List.copyOf(entries);
        if (entries.size() > RecipeStatusRequestPayload.MAX_RECIPES) throw new IllegalArgumentException("Oversized recipe preview response");
    }
    public record Entry(ResourceLocation recipeId, CraftingStatus status, CraftingResultCode resultCode) {}
    public static final Type<RecipeStatusResponsePayload> TYPE = new Type<>(Craftable.id("recipe_status_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeStatusResponsePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buffer) -> {
                buffer.writeCollection(payload.entries(), (buf, entry) -> {
                    buf.writeResourceLocation(entry.recipeId());
                    buf.writeEnum(entry.status());
                    buf.writeEnum(entry.resultCode());
                });
                buffer.writeVarLong(payload.requestId());
                buffer.writeVarLong(payload.environmentGeneration());
                buffer.writeBoolean(payload.craftingTable());
                buffer.writeVarInt(payload.horizontalRadius());
                buffer.writeVarInt(payload.verticalRadius());
                buffer.writeVarInt(payload.previewTicks());
                buffer.writeBoolean(payload.enderChest());
            }, buffer -> new RecipeStatusResponsePayload(
                    buffer.readCollection(net.minecraft.network.FriendlyByteBuf.limitValue(java.util.ArrayList::new,
                            RecipeStatusRequestPayload.MAX_RECIPES),
                            buf -> new Entry(buf.readResourceLocation(), buf.readEnum(CraftingStatus.class), buf.readEnum(CraftingResultCode.class))),
                    buffer.readVarLong(), buffer.readVarLong(), buffer.readBoolean(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()));
    @Override public Type<RecipeStatusResponsePayload> type() { return TYPE; }
}
