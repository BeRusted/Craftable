package org.berusted.craftable.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipePreviewPayloadTest {
    private static final ResourceLocation ID = ResourceLocation.withDefaultNamespace("stick");
    @Test void deferredAdmissionAcknowledgesOnlyRequestIdentity() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var response = RecipeStatusResponsePayload.deferred(42);
            RecipeStatusResponsePayload.STREAM_CODEC.encode(buffer, response);
            var decoded = RecipeStatusResponsePayload.STREAM_CODEC.decode(buffer);
            assertEquals(42, decoded.requestId());
            assertTrue(decoded.entries().isEmpty());
            assertTrue(decoded.environmentGeneration() < 0);
        } finally { buffer.release(); }
    }
    @Test void requestAndResponseRoundTripAndBoundSize() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var request = new RecipeStatusRequestPayload(List.of(ID), 42);
            RecipeStatusRequestPayload.STREAM_CODEC.encode(buffer, request);
            assertEquals(request, RecipeStatusRequestPayload.STREAM_CODEC.decode(buffer));
            buffer.clear();
            var response = new RecipeStatusResponsePayload(List.of(new RecipeStatusResponsePayload.Entry(
                    ID, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED)), 42, 8, true, 8, 4, 5, true);
            RecipeStatusResponsePayload.STREAM_CODEC.encode(buffer, response);
            assertEquals(response, RecipeStatusResponsePayload.STREAM_CODEC.decode(buffer));
            assertThrows(IllegalArgumentException.class, () -> new RecipeStatusRequestPayload(java.util.Collections.nCopies(33, ID), 1));
            assertThrows(IllegalArgumentException.class, () -> new RecipeStatusResponsePayload(
                    java.util.Collections.nCopies(33, response.entries().getFirst()), 1, 1, false, 8, 4, 5, true));
        } finally { buffer.release(); }
    }

    @Test void maliciousLengthIsRejectedBeforeReadingOrAllocatingEntries() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> RecipeStatusRequestPayload.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> RecipeStatusResponsePayload.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
