package org.berusted.craftable.network;

import io.netty.buffer.Unpooled;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.execution.CraftingService;
import org.berusted.craftable.planner.CraftRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftingDetailPayloadTest {
    private static final ResourceLocation ID = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    @Test void browsingEnvelopeRoundTripAndChunkLimits() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var session = java.util.UUID.randomUUID(); var transfer = java.util.UUID.randomUUID();
        try {
            var request = new CraftingDetailPayloads.BrowseRequest(1, 9, true, session, -1);
            CraftingDetailPayloads.BrowseRequest.CODEC.encode(buffer, request);
            assertEquals(request, CraftingDetailPayloads.BrowseRequest.CODEC.decode(buffer));
            buffer.clear();
            var header = new CraftingWire.SnapshotHeader(session, transfer, 2, 3, 3, 1, CraftingWire.sha256().digest(new byte[]{1,2,3}));
            var lease = new CraftingDetailPayloads.BrowseLease(1, 9, session, 2, 3, 100, "a".repeat(64), header,
                    new org.berusted.craftable.config.EnvironmentScanSettings(8, 4, 10, true), true);
            CraftingDetailPayloads.BrowseLease.CODEC.encode(buffer, lease);
            assertTrue(buffer.readableBytes() < 1024);
            var decoded = CraftingDetailPayloads.BrowseLease.CODEC.decode(buffer);
            assertEquals(lease.session(), decoded.session()); assertEquals(lease.settings(), decoded.settings());
            assertArrayEquals(header.digest(), decoded.header().digest());
            buffer.clear();
            var chunk = new CraftingDetailPayloads.BrowseChunk(1, 9, session, transfer, 0, new byte[]{1,2,3});
            CraftingDetailPayloads.BrowseChunk.CODEC.encode(buffer, chunk);
            assertArrayEquals(chunk.data(), CraftingDetailPayloads.BrowseChunk.CODEC.decode(buffer).data());
            assertThrows(IllegalArgumentException.class, () -> new CraftingDetailPayloads.BrowseChunk(1,9,session,transfer,133,new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new CraftingDetailPayloads.BrowseChunk(1,9,session,transfer,0,new byte[32768]));
        } finally { buffer.release(); }
    }

    @Test void selectedIntentAndUnknownMaximumRoundTrip() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var intent = new CraftRequest(ID, 3, true, false, CraftRequest.PartialPolicy.CONFIRM,
                    Map.of("0.4", ResourceLocation.withDefaultNamespace("stick")));
            var request = new CraftingDetailPayloads.PreviewRequest(1, 12, intent, "0.4");
            CraftingDetailPayloads.PreviewRequest.CODEC.encode(buffer, request);
            assertEquals(request, CraftingDetailPayloads.PreviewRequest.CODEC.decode(buffer));
            buffer.clear();
            var maximum = new CraftingDetailPayloads.MaximumResponse(1, 13,
                    new CraftingService.Maximum(0, false, 64, true, false));
            CraftingDetailPayloads.MaximumResponse.CODEC.encode(buffer, maximum);
            assertEquals(maximum, CraftingDetailPayloads.MaximumResponse.CODEC.decode(buffer));
            assertFalse(maximum.maximum().proven()); // Unknown is not a proven zero.
        } finally { buffer.release(); }
    }

    @Test void boundsRejectMalformedDemandAndOversizedFrameBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new CraftingDetailPayloads.PreviewRequest(1, 1,
                CraftRequest.one(ID), "0.99"));
        assertThrows(IllegalArgumentException.class, () -> new CraftRequest(ID, 65, false, false,
                CraftRequest.PartialPolicy.NEVER, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CraftRequest(ID, 1, false, false,
                CraftRequest.PartialPolicy.NEVER, Map.of("0", ID)));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(1); buffer.writeVarLong(1); buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> CraftingDetailPayloads.PreviewResponse.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
