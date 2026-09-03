package org.berusted.craftable.environment;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.berusted.craftable.Craftable;

/** Keeps per-player snapshot state from crossing player lifecycle boundaries. */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class EnvironmentSnapshotEvents {
    private EnvironmentSnapshotEvents() {}

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EnvironmentSnapshotService.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }
}
