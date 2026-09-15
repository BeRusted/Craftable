package org.berusted.craftable.config;

import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import org.berusted.craftable.Craftable;

public class CraftableClientConfig implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = Craftable.MOD_ID + ".client.json";
    private static final int CONFIG_VERSION = 1;

    private static final String GENERIC_KEY = Craftable.MOD_ID + ".config.client.generic";

    private CraftableClientConfig() {

    }

    public static boolean recipeBookEnhancementsEnabled() {
        return true;
    }

    public static boolean detailedFailureFeedbackEnabled() {
        return true;
    }

    public static boolean unlockedOnly() {
        return false;
    }

    @Override
    public void load() {

    }

    @Override
    public void save() {

    }

    public static class Generic {
        public static final ConfigBoolean recipeBookEnhancements = new ConfigBoolean(GENERIC_KEY + ".recipe_book_enhancements", true);
        public static final ConfigBoolean detailedFailureFeedback = new ConfigBoolean(GENERIC_KEY + ".detailed_failure_feedback   ", false);
        public static final ConfigBoolean unlockedOnly = new ConfigBoolean(GENERIC_KEY + ".unlocked_only", false);
    }
}
