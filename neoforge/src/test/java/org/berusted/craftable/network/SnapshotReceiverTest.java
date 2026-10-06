package org.berusted.craftable.network;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotReceiverTest {
    private static final long TICK = 50_000_000L;

    private static CraftingWire.SnapshotHeader header(byte[] bytes) {
        return new CraftingWire.SnapshotHeader(UUID.randomUUID(), UUID.randomUUID(), 1, 1, bytes.length,
                Math.max(1, (bytes.length + CraftingWire.SNAPSHOT_CHUNK_BYTES - 1) / CraftingWire.SNAPSHOT_CHUNK_BYTES),
                CraftingWire.sha256().digest(bytes));
    }

    private static byte[] chunk(byte[] bytes, int index) {
        int offset = index * CraftingWire.SNAPSHOT_CHUNK_BYTES;
        return Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + CraftingWire.SNAPSHOT_CHUNK_BYTES));
    }

    @Test void eightFullSizeReceiversUseActualSenderFairnessWithinFixedDeadlines() {
        CraftablePayloadHandlers.stopBrowsing();
        byte[] bytes = new byte[CraftingWire.SNAPSHOT_BYTES];
        Arrays.fill(bytes, (byte) 19);
        var ids = java.util.stream.IntStream.range(0, 8).mapToObj(i -> UUID.randomUUID()).toList();
        var headers = ids.stream().map(id -> header(bytes)).toList();
        var receivers = headers.stream().map(h -> new CraftingWire.SnapshotReceiver(h, 0)).toList();
        int[] sent = new int[8], last = new int[8];
        try {
            for (int tick = 1; tick <= 266; tick++) {
                var turn = CraftablePayloadHandlers.transferTurn(ids, id -> sent[ids.indexOf(id)] < 133);
                assertEquals(4, turn.size());
                assertEquals(4, new java.util.HashSet<>(turn).size(), "one viewer cannot get two chunks in a tick");
                for (var id : turn) {
                    int index = ids.indexOf(id);
                    var h = headers.get(index);
                    assertTrue(tick - last[index] <= 2, "eight active viewers must alternate fairly");
                    assertFalse(receivers.get(index).expired(tick * TICK));
                    assertTrue(receivers.get(index).accept(h.session(), h.transfer(), sent[index],
                            chunk(bytes, sent[index]), tick * TICK));
                    last[index] = tick; sent[index]++;
                }
            }
            for (int i = 0; i < 8; i++) {
                assertEquals(133, sent[i]);
                assertTrue(receivers.get(i).complete());
                assertArrayEquals(bytes, receivers.get(i).take(266 * TICK));
                assertEquals(0, receivers.get(i).bufferedBytes());
            }
            assertTrue(CraftablePayloadHandlers.transferTurn(ids, id -> false).isEmpty());
            assertEquals(java.util.List.of(ids.getFirst()),
                    CraftablePayloadHandlers.transferTurn(java.util.List.of(ids.getFirst()), id -> true));
        } finally {
            receivers.forEach(CraftingWire.SnapshotReceiver::close);
            CraftablePayloadHandlers.stopBrowsing();
        }
    }

    @Test void fullSizeSurvivesDeclaredInterleavedTransferRates() {
        byte[] bytes = new byte[CraftingWire.SNAPSHOT_BYTES];
        Arrays.fill(bytes, (byte) 73);
        for (int spacing : new int[]{1, 2, 4}) {
            var header = header(bytes);
            assertEquals(133, header.chunks());
            var receive = new CraftingWire.SnapshotReceiver(header, 0);
            for (int part = 0; part < header.chunks(); part++) {
                long now = (long) (part + 1) * spacing * TICK;
                assertFalse(receive.expired(now));
                assertTrue(receive.accept(header.session(), header.transfer(), part, chunk(bytes, part), now));
            }
            assertTrue(receive.complete());
            assertArrayEquals(bytes, receive.take((long) header.chunks() * spacing * TICK));
            assertEquals(0, receive.bufferedBytes());
            assertFalse(receive.complete());
        }
    }

    @Test void emptyAndBoundaryProjectionsHaveCanonicalChunks() {
        for (int size : new int[]{0, CraftingWire.SNAPSHOT_CHUNK_BYTES, CraftingWire.SNAPSHOT_CHUNK_BYTES + 1}) {
            byte[] bytes = new byte[size];
            var header = header(bytes);
            var receive = new CraftingWire.SnapshotReceiver(header, 0);
            for (int part = 0; part < header.chunks(); part++)
                assertTrue(receive.accept(header.session(), header.transfer(), part, chunk(bytes, part), TICK));
            assertArrayEquals(bytes, receive.take(TICK));
        }
    }

    @Test void duplicateForeignAndOutOfOrderBlocksDoNotResetProgress() {
        byte[] bytes = new byte[2 * CraftingWire.SNAPSHOT_CHUNK_BYTES];
        var header = header(bytes);
        var receive = new CraftingWire.SnapshotReceiver(header, 0);
        assertFalse(receive.accept(header.session(), header.transfer(), 1, chunk(bytes, 1), TICK));
        assertFalse(receive.accept(UUID.randomUUID(), header.transfer(), 0, chunk(bytes, 0), TICK));
        assertFalse(receive.accept(header.session(), UUID.randomUUID(), 0, chunk(bytes, 0), TICK));
        assertTrue(receive.accept(header.session(), header.transfer(), 0, chunk(bytes, 0), TICK));
        for (int tick = 2; tick <= 100; tick++) {
            assertFalse(receive.accept(header.session(), header.transfer(), 0, chunk(bytes, 0), tick * TICK));
            assertFalse(receive.expired(tick * TICK));
        }
        assertTrue(receive.expired(101 * TICK));
        assertEquals(0, receive.bufferedBytes());
        assertThrows(IllegalStateException.class, () -> receive.take(101 * TICK));
    }

    @Test void continuingProgressStillStopsAtFixedTotalDeadline() {
        byte[] bytes = new byte[CraftingWire.SNAPSHOT_BYTES];
        var header = header(bytes);
        var receive = new CraftingWire.SnapshotReceiver(header, 0);
        for (int part = 0; part < 6; part++)
            assertTrue(receive.accept(header.session(), header.transfer(), part, chunk(bytes, part), (part + 1L) * 99 * TICK));
        assertFalse(receive.expired(631 * TICK));
        assertTrue(receive.expired(632 * TICK));
        assertEquals(0, receive.bufferedBytes());
    }

    @Test void integrityAndLengthsAreValidatedBeforePublication() {
        byte[] bytes = new byte[3];
        var header = header(bytes);
        var receive = new CraftingWire.SnapshotReceiver(header, 0);
        assertFalse(receive.accept(header.session(), header.transfer(), 0, new byte[4], TICK));
        assertEquals(0, receive.receivedChunks());
        assertThrows(IllegalStateException.class, () -> receive.take(TICK));
        assertFalse(receive.accept(header.session(), header.transfer(), 0, new byte[]{1, 2, 3}, TICK));
        assertFalse(receive.complete());
        assertEquals(0, receive.bufferedBytes());
        assertThrows(IllegalArgumentException.class, () -> new CraftingWire.SnapshotHeader(
                header.session(), header.transfer(), 1, 1, Integer.MAX_VALUE, 1, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> new CraftingWire.SnapshotHeader(
                header.session(), header.transfer(), 1, 1, 3, 2, new byte[32]));
        byte[] digest = header.digest(); digest[0]++;
        assertFalse(Arrays.equals(digest, header.digest()));
    }

    @Test void oneRetryKeepsOriginalDeadlineAndCannotChangeContent() {
        byte[] bytes = new byte[CraftingWire.SNAPSHOT_BYTES];
        var header = header(bytes);
        var receive = new CraftingWire.SnapshotReceiver(header, 0);
        assertTrue(receive.expired(100 * TICK));
        var next = new CraftingWire.SnapshotHeader(header.session(), UUID.randomUUID(), header.recipes(), header.resources(),
                header.bytes(), header.chunks(), header.digest());
        var retry = receive.retry(next, 100 * TICK);
        assertThrows(IllegalStateException.class, () -> receive.retry(next, 100 * TICK));
        for (int part = 0; part < 5; part++)
            assertTrue(retry.accept(next.session(), next.transfer(), part, chunk(bytes, part), (100 + 99L * (part + 1)) * TICK));
        assertFalse(retry.expired(631 * TICK));
        assertTrue(retry.expired(632 * TICK));
        assertThrows(IllegalStateException.class, () -> retry.retry(next, 632 * TICK));
        assertThrows(IllegalStateException.class, () -> receive.retry(header(new byte[4]), 100 * TICK));
    }

    @Test void cancellationAndDigestFailureCannotBeRetried() {
        var header = header(new byte[1]);
        var cancelled = new CraftingWire.SnapshotReceiver(header, 0);
        cancelled.close();
        assertThrows(IllegalStateException.class, () -> cancelled.retry(header, TICK));
        var corrupt = new CraftingWire.SnapshotReceiver(header, 0);
        assertFalse(corrupt.accept(header.session(), header.transfer(), 0, new byte[]{1}, TICK));
        assertThrows(IllegalStateException.class, () -> corrupt.retry(header, TICK));
    }

    @Test void monotonicTimerDoesNotDependOnGameTimeAndSurvivesSignedWrap() {
        byte[] bytes = new byte[1];
        var header = header(bytes);
        long start = Long.MAX_VALUE - 2 * TICK;
        var receive = new CraftingWire.SnapshotReceiver(header, start);
        assertTrue(receive.accept(header.session(), header.transfer(), 0, bytes, start + 3 * TICK));
        assertArrayEquals(bytes, receive.take(start + 3 * TICK));
        receive = new CraftingWire.SnapshotReceiver(header, 100);
        assertTrue(receive.expired(99));
        assertEquals(0, receive.bufferedBytes());
    }
}
