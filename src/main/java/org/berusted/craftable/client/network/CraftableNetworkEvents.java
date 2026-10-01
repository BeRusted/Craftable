package org.berusted.craftable.client.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.berusted.craftable.execution.CraftingSessions;

public final class CraftableNetworkEvents {
    private CraftableNetworkEvents() {}
    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CraftablePayloadHandlers.stopBrowsing());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CraftablePayloadHandlers.browseTick(server);
            if (server.getTickCount() % 20 == 0) CraftingSessions.expire(server);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CraftablePayloadHandlers.closeBrowse(handler.player);
            CraftableRequestLimiter.clear(handler.player.getUUID());
            CraftingSessions.clear(handler.player.getUUID());
        });
    }
}
