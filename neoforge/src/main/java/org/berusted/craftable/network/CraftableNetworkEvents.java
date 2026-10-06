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
    public static void onStop(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        CraftablePayloadHandlers.stopBrowsing();
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        CraftablePayloadHandlers.browseTick(event.getServer());
        if (event.getServer().getTickCount() % 20 == 0)
            org.berusted.craftable.execution.CraftingSessions.expire(event.getServer());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CraftablePayloadHandlers.closeBrowse(player);
            CraftableRequestLimiter.clear(player.getUUID());
            org.berusted.craftable.execution.CraftingSessions.clear(player.getUUID());
        }
    }
}
