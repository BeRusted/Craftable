package org.berusted.craftable.config;

import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import org.berusted.craftable.Craftable;

public class CraftableServerConfig implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = Craftable.MOD_ID + ".server.json";
    private static final int CONFIG_VERSION = 1;

    private static final String GENERIC_KEY = Craftable.MOD_ID + ".config.server.generic";


    private CraftableServerConfig() {
    }

    public static EnvironmentScanSettings scanSettings() {

        return new EnvironmentScanSettings(
                8,
                4,
                5,
                true
        );
    }

    @Override
    public void load() {

    }

    @Override
    public void save() {

    }

    public static class GENERIC {
        public static final ConfigInteger horizontalRadius = new ConfigInteger(GENERIC_KEY + ".horizontal_radius", 8);
        public static final ConfigInteger verticalRadius = new ConfigInteger(GENERIC_KEY + ".vertical_radius", 4);
        public static final ConfigInteger previewCacheTicks = new ConfigInteger(GENERIC_KEY + ".preview_cache_ticks", 5);
        public static final ConfigBoolean includeEnderChest = new ConfigBoolean(GENERIC_KEY + ".include_ender_chest", true);
    }
}
