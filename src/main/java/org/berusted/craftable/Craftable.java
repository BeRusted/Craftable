package org.berusted.craftable;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class Craftable implements ModInitializer {

    public static final String MOD_ID = "craftable";
    public static final Logger LOGGER = LogManager.getLogger();

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
    }
}
