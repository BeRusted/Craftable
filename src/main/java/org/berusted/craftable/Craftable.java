package org.berusted.craftable;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.berusted.craftable.command.CraftableCommands;
import org.berusted.craftable.environment.EnvironmentSnapshotEvents;
import org.berusted.craftable.menu.CraftableMenus;
import org.berusted.craftable.network.CraftableNetworkEvents;
import org.berusted.craftable.network.CraftablePayloads;
import org.berusted.craftable.network.ServerPayloadHandlers;

public class Craftable implements ModInitializer {

    public static final String MOD_ID = "craftable";
    public static final String MOD_NAME = "Craftable";
    public static final Logger LOGGER = LogManager.getLogger();

    public Craftable() {

    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        CraftableMenus.register();
        CraftableCommands.register();
        EnvironmentSnapshotEvents.register();
        CraftableNetworkEvents.register();
        CraftablePayloads.register();
        ServerPayloadHandlers.register();
    }
}
