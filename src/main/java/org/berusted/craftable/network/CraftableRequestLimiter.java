package org.berusted.craftable.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread packet rate limits protecting the bounded but non-trivial world scan. */
final class CraftableRequestLimiter {
    private static final Map<UUID, Long> LAST_STATUS_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_CREATE_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_OPEN_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_DETAIL_REQUEST = new HashMap<>();
    private static final Map<net.minecraft.server.MinecraftServer, Frame> FRAMES = new java.util.WeakHashMap<>();

    // Background preview/MAX may use at most half the shared tick allowance.
    // Reserve the rest for explicit C/confirm requests, without a work queue.
    static Lease planning(net.minecraft.server.MinecraftServer server, UUID player, boolean active) {
        Frame frame = FRAMES.computeIfAbsent(server, ignored -> new Frame());
        long tick = server.getTickCount();
        if (frame.tick != tick) { frame.tick = tick; frame.total = frame.background = 0; }
        long remaining = Math.min(8_000_000L, Math.max(0, 16_000_000L - frame.total));
        if (!active) remaining = Math.min(remaining, Math.max(0, 8_000_000L - frame.background));
        if (!active && !frame.turns.acquire(player, tick, remaining > 0)) remaining = 0;
        return new Lease(frame, active, remaining);
    }

    /** Fair admission metadata only: no queued work or retained world state.
     * A recently waiting player takes the next background turn before a
     * continually first-arriving player can consume another whole page. */
    static final class BackgroundTurns {
        private final Map<UUID, Turn> players = new HashMap<>();
        boolean acquire(UUID player, long tick, boolean budgetAvailable) {
            players.entrySet().removeIf(e -> tick - e.getValue().seen > 8 || tick < e.getValue().seen);
            var current = players.computeIfAbsent(player, ignored -> new Turn());
            current.seen = tick;
            if (!budgetAvailable || players.values().stream().anyMatch(t -> t != current && t.granted < current.granted))
                return false;
            current.granted = tick;
            return true;
        }
        void remove(UUID player) { players.remove(player); }
        private static final class Turn { long seen; long granted = Long.MIN_VALUE; }
    }

    private static final class Frame {
        long tick = Long.MIN_VALUE;
        long total;
        long background;
        final BackgroundTurns turns = new BackgroundTurns();
    }

    static final class Lease implements AutoCloseable {
        final Frame frame;
        final boolean active;
        final long start = System.nanoTime();
        final long allowance;
        Lease(Frame frame, boolean active, long allowance) { this.frame = frame; this.active = active; this.allowance = allowance; }
        long remaining() { return Math.max(0, allowance - (System.nanoTime() - start)); }
        boolean allowed() { return allowance > 0; }
        @Override public void close() {
            long elapsed = System.nanoTime() - start;
            frame.total += elapsed;
            if (!active) frame.background += elapsed;
        }
    }

    private CraftableRequestLimiter() {}

    static boolean allowStatus(UUID playerId, long gameTime) {
        return allow(LAST_STATUS_REQUEST, playerId, gameTime, 4);
    }

    static boolean allowCreate(UUID playerId, long gameTime) {
        return allow(LAST_CREATE_REQUEST, playerId, gameTime, 1);
    }

    static boolean allowDetail(UUID playerId, long gameTime) {
        return allow(LAST_DETAIL_REQUEST, playerId, gameTime, 4);
    }

    static void clear(UUID playerId) {
        LAST_OPEN_REQUEST.remove(playerId);
        LAST_STATUS_REQUEST.remove(playerId);
        LAST_CREATE_REQUEST.remove(playerId);
        LAST_DETAIL_REQUEST.remove(playerId);
        FRAMES.values().forEach(frame -> frame.turns.remove(playerId));
    }

    static boolean allowOpen(UUID playerId, long gameTime) {
        return allow(LAST_OPEN_REQUEST, playerId, gameTime, 10);
    }

    private static boolean allow(Map<UUID, Long> requests, UUID playerId, long gameTime, long interval) {
        Long previous = requests.get(playerId);
        if (previous != null && gameTime >= previous && gameTime - previous < interval) {
            return false;
        }
        requests.put(playerId, gameTime);
        return true;
    }
}
