package org.berusted.craftable.client.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import org.berusted.craftable.Craftable;

import java.nio.file.Files;
import java.nio.file.Path;

public class CraftableClientConfig implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = Craftable.MOD_ID + ".json";
    private static final int CONFIG_VERSION = 1;

    private static final String GENERIC_KEY = Craftable.MOD_ID + ".config.generic";

    public static boolean recipeBookEnhancementsEnabled() {
        return true;
    }

    public static boolean detailedFailureFeedbackEnabled() {
        return true;
    }

    public static boolean unlockedOnly() {
        return false;
    }

    public static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile)) {
            JsonElement element = JsonUtils.parseJsonFileAsPath(configFile);

            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();

                ConfigUtils.readConfigBase(root, "Generic", CraftableClientConfig.Generic.OPTIONS);

                //int version = JsonUtils.getIntegerOrDefault(root, "config_version", 0);


            } else {
                Craftable.LOGGER.error("loadFromFile(): Failed to load config file '{}'.", configFile.toAbsolutePath());
            }

        }
    }

    public static void saveToFile() {
        Path dir = FileUtils.getConfigDirectoryAsPath();

        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }

        if (Files.isDirectory(dir)) {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Generic", CraftableClientConfig.Generic.OPTIONS);

            root.add("config_version", new JsonPrimitive(CONFIG_VERSION));
            JsonUtils.writeJsonToFileAsPath(root, dir.resolve(CONFIG_FILE_NAME));

        } else {
            Craftable.LOGGER.error("Config directory '{}' does not exist or is not a directory", dir.toAbsolutePath());
        }

    }

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }

    public static class Generic {
        public static final ConfigBoolean recipeBookEnhancements = new ConfigBoolean("recipe_book_enhancements", true, "null").apply(GENERIC_KEY);
        public static final ConfigBoolean detailedFailureFeedback = new ConfigBoolean("detailed_failure_feedback", false, "null").apply(GENERIC_KEY);
        public static final ConfigBoolean unlockedOnly = new ConfigBoolean("unlocked_only", false, "null").apply(GENERIC_KEY);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                recipeBookEnhancements,
                detailedFailureFeedback,
                unlockedOnly
        );
    }

}
