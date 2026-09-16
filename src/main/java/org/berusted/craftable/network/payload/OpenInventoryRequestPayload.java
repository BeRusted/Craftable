package org.berusted.craftable.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.berusted.craftable.Craftable;

public record OpenInventoryRequestPayload(long requestId) implements CustomPacketPayload {
    public static final Type<OpenInventoryRequestPayload> TYPE = new Type<>(Craftable.id("open_inventory"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenInventoryRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec((p, b) -> b.writeVarLong(p.requestId()), b -> new OpenInventoryRequestPayload(b.readVarLong()));

    @Override
    public Type<OpenInventoryRequestPayload> type() {
        return TYPE;
    }
}
