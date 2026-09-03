package org.berusted.craftable.environment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.EnvironmentScanSettings;

/**
 * The only runtime entry point for environment discovery. The service is
 * intentionally main-thread-only because snapshots contain live containers.
 */
public final class EnvironmentSnapshotService {
    private static final Map<UUID, PlayerState> PLAYER_STATES = new HashMap<>();

    private EnvironmentSnapshotService() {}

    /** Returns a bounded-age snapshot suitable for read-only previews. */
    public static EnvironmentSnapshot preview(ServerPlayer player) {
        requireServerThread(player);
        EnvironmentScanSettings settings = CraftableServerConfig.scanSettings();
        EnvironmentSnapshotCacheKey key = key(player, settings);
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        EnvironmentSnapshot cached = state.cached;
        if (cached != null
                && key.canReuse(cached, player.serverLevel().getGameTime())
                && cached.endpoints().stream().allMatch(endpoint -> endpoint.isStillValid(player))) {
            return cached;
        }
        return capture(player, settings, state);
    }

    /**
     * Always scans again. Resource-changing operations must use this method so
     * a preview cache can never authorize a transaction.
     */
    public static EnvironmentSnapshot fresh(ServerPlayer player) {
        requireServerThread(player);
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        return capture(player, CraftableServerConfig.scanSettings(), state);
    }

    public static void invalidate(UUID playerId) {
        PlayerState state = PLAYER_STATES.get(playerId);
        if (state != null) {
            state.cached = null;
        }
    }

    public static void remove(UUID playerId) {
        PLAYER_STATES.remove(playerId);
    }

    private static EnvironmentSnapshot capture(
            ServerPlayer player, EnvironmentScanSettings settings, PlayerState state) {
        if (state.lastGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Environment generation exhausted");
        }
        long generation = ++state.lastGeneration;
        EnvironmentSnapshot snapshot = EnvironmentScanner.scan(player, settings, generation);
        state.cached = snapshot;
        return snapshot;
    }

    private static EnvironmentSnapshotCacheKey key(
            ServerPlayer player, EnvironmentScanSettings settings) {
        return new EnvironmentSnapshotCacheKey(
                player.serverLevel().dimension().location(), player.blockPosition(), settings);
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.serverLevel().getServer().isSameThread()) {
            throw new IllegalStateException("Environment snapshots may only be accessed on the server thread");
        }
    }

    private static final class PlayerState {
        private long lastGeneration;
        private EnvironmentSnapshot cached;
    }
}
