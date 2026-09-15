package org.berusted.craftable.environment;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

public class EnvironmentSnapshotEvents {
    private EnvironmentSnapshotEvents() {
    }

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    ServerPlayer player = handler.getPlayer();
                    EnvironmentSnapshotService.remove(player.getUUID());
                }
        );

        ServerPlayerEvents.COPY_FROM.register(
                (oldPlayer, newPlayer, alive) -> {
                    EnvironmentSnapshotService.invalidate(newPlayer.getUUID());
                }
        );

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> {
                    EnvironmentSnapshotService.invalidate(player.getUUID());
                }
        );
    }
}
