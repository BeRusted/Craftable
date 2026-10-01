package org.berusted.craftable.config;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.berusted.craftable.planner.CraftRequest;

/** Self-owned JSON settings, scoped to the currently running server world. */
public final class CraftableServerConfigHandler {
    private static Path file;
    private static EnvironmentScanSettings settings = new EnvironmentScanSettings(8, 4, 5, true);
    private static CraftableServerConfig.CraftingRules rules = defaults();
    private CraftableServerConfigHandler() {}

    public static EnvironmentScanSettings scanSettings() { return settings; }
    public static CraftableServerConfig.CraftingRules craftingRules() { return rules; }
    private static CraftableServerConfig.CraftingRules defaults() {
        return new CraftableServerConfig.CraftingRules(CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64);
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            settings = new EnvironmentScanSettings(8, 4, 5, true);
            rules = defaults();
            file = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig/craftable-server.json");
            if (Files.exists(file)) load(); else save();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) load();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            file = null;
            settings = new EnvironmentScanSettings(8, 4, 5, true);
            rules = defaults();
        });
    }

    public static void load() {
        if (file == null) return;
        JsonObject root = ConfigFileIO.readObject(file);
        if (root == null) return;
        var environment = ConfigFileIO.getObject(root, "environment");
        if (environment != null) settings = new EnvironmentScanSettings(
                bounded(environment, "horizontal_radius", 8, 1, 16),
                bounded(environment, "vertical_radius", 4, 0, 8),
                bounded(environment, "preview_cache_ticks", 5, 0, 20),
                ConfigFileIO.getBoolean(environment, "include_ender_chest", true));
        var crafting = ConfigFileIO.getObject(root, "crafting");
        if (crafting != null) rules = new CraftableServerConfig.CraftingRules(
                enumValue(crafting, "partial_execution", CraftRequest.PartialPolicy.EXPLICIT_SAFE),
                enumValue(crafting, "surplus_delivery", CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE),
                bounded(crafting, "max_craft_batch", 64, 1, 64));
    }

    public static void save() {
        if (file == null) return;
        var environment = new JsonObject();
        environment.addProperty("horizontal_radius", settings.horizontalRadius());
        environment.addProperty("vertical_radius", settings.verticalRadius());
        environment.addProperty("preview_cache_ticks", settings.previewCacheTicks());
        environment.addProperty("include_ender_chest", settings.includeEnderChest());
        var crafting = new JsonObject();
        crafting.addProperty("partial_execution", rules.partialPolicy().name());
        crafting.addProperty("surplus_delivery", rules.surplusDelivery().name());
        crafting.addProperty("max_craft_batch", rules.maxBatches());
        var root = new JsonObject();
        root.add("environment", environment);
        root.add("crafting", crafting);
        ConfigFileIO.writeObject(file, root);
    }

    private static int bounded(JsonObject object, String key, int fallback, int minimum, int maximum) {
        try { return Math.clamp(ConfigFileIO.getInt(object, key, fallback), minimum, maximum); }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static <E extends Enum<E>> E enumValue(JsonObject object, String key, E fallback) {
        try { return Enum.valueOf(fallback.getDeclaringClass(), object.get(key).getAsString()); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
