package org.berusted.craftable.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread packet rate limits protecting the bounded but non-trivial world scan. */
final class CraftableRequestLimiter {
    private static final Map<UUID, Long> LAST_STATUS_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_CREATE_REQUEST = new HashMap<>();

    private CraftableRequestLimiter() {}

    static boolean allowStatus(UUID playerId, long gameTime) {
        return allow(LAST_STATUS_REQUEST, playerId, gameTime, 4);
    }

    static boolean allowCreate(UUID playerId, long gameTime) {
        return allow(LAST_CREATE_REQUEST, playerId, gameTime, 1);
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
