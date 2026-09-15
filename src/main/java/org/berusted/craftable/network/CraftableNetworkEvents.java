package org.berusted.craftable.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

public class CraftableNetworkEvents {
    private CraftableNetworkEvents() {
    }

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    ServerPlayer player = handler.player;
                    CraftableRequestLimiter.clear(player.getUUID());
                }
        );
    }


}
