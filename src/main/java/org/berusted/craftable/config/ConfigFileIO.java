package org.berusted.craftable.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.berusted.craftable.Craftable;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 极简的配置文件读写工具，只依赖 Gson（Minecraft 自带），不依赖任何模组。
 * 读写失败时记录日志并返回 null / 静默跳过，绝不让配置问题阻断游戏启动。
 */
public final class ConfigFileIO {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigFileIO() {
    }

    /**
     * 读取 json 文件为 {@link JsonObject}；文件不存在、不可读或内容非法时返回 null。
     */
    public static JsonObject readObject(Path file) {
        if (!Files.isReadable(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            Craftable.LOGGER.error("Config file '{}' is not a json object", file.toAbsolutePath());
        } catch (Exception e) {
            Craftable.LOGGER.error("Failed to read config file '{}'", file.toAbsolutePath(), e);
        }
        return null;
    }

    /**
     * 把 {@link JsonObject} 写入 json 文件，必要时自动创建父目录。
     */
    public static void writeObject(Path file, JsonObject root) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            Craftable.LOGGER.error("Failed to write config file '{}'", file.toAbsolutePath(), e);
        }
    }

    public static JsonObject getObject(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static int getInt(JsonObject obj, String key, int defaultValue) {
        JsonElement element = obj.get(key);
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        return defaultValue;
    }

    public static boolean getBoolean(JsonObject obj, String key, boolean defaultValue) {
        JsonElement element = obj.get(key);
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        return defaultValue;
    }
}
