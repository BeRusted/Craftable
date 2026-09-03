package org.berusted.craftable.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.berusted.craftable.Craftable;

/** Releases per-connection request state when a player leaves the server. */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class CraftableNetworkEvents {
    private CraftableNetworkEvents() {}

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CraftableRequestLimiter.clear(player.getUUID());
        }
    }
}
