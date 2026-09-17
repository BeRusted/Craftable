package org.berusted.craftable.environment;

import net.minecraft.server.level.ServerPlayer;
import org.berusted.craftable.config.CraftableServerConfigHandler;
import org.berusted.craftable.config.CraftableServerConfigHandler.EnvironmentScanSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EnvironmentSnapshotService {
    private static final Map<UUID, PlayerState> PLAYER_STATES = new HashMap<>();

    private EnvironmentSnapshotService() {
    }

    public static EnvironmentSnapshot preview(ServerPlayer player) {
        requireServerThread(player);
        EnvironmentScanSettings settings = CraftableServerConfigHandler.scanSettings();
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

    public static EnvironmentSnapshot fresh(ServerPlayer player) {
        requireServerThread(player);
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        return capture(player, CraftableServerConfigHandler.scanSettings(), state);
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
