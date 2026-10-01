package org.berusted.craftable.client.config;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.berusted.craftable.Craftable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端配置核心，负责 {@code config/craftable.json} 的初始化、读取与保存。
 * 提供以下操作：{@link #init()}、{@link #load()}、{@link #save()}、{@link #update(Option, boolean)}。
 *
 * <p>本类必须保持零模组依赖：RecipeBookProjection、ClientPayloadHandler 等核心逻辑
 * 在 malilib 未安装时也会加载并调用这里的方法。malilib 安装后仅作为这些配置值的
 * 图形编辑界面，见 {@code client/compat/malilib/} 包。
 */
public final class CraftableConfigHandler {

    private static final String CONFIG_FILE_NAME = Craftable.MOD_ID + ".json";
    private static final int CONFIG_VERSION = 1;
    private static final String GROUP_KEY = "generic";

    private CraftableConfigHandler() {
    }

    /**
     * 启动时初始化：配置文件已存在则读取，不存在则写出一份默认配置。
     */
    public static void init() {
        if (Files.exists(configFile())) {
            load();
        } else {
            save();
        }
    }

    /**
     * 从磁盘读取配置到内存；文件缺失或损坏时保持当前值不变。
     */
    public static void load() {
        JsonObject root = ConfigFileIO.readObject(configFile());
        JsonObject generic = root == null ? null : ConfigFileIO.getObject(root, GROUP_KEY);
        if (generic == null) {
            return;
        }
        for (Option option : Option.values()) {
            option.value = option.defaultValue;
            try {
                var element = generic.get(option.key);
                if (element == null || !element.isJsonPrimitive()) continue;
                if (option.defaultValue instanceof Boolean && element.getAsJsonPrimitive().isBoolean()) option.value = element.getAsBoolean();
                else if (option.defaultValue instanceof Integer && element.getAsJsonPrimitive().isNumber())
                    option.value = Math.clamp(element.getAsInt(), 150, 800);
                else if (option.defaultValue instanceof org.berusted.craftable.planner.CraftRequest.PartialPolicy
                        && element.getAsJsonPrimitive().isString())
                    option.value = org.berusted.craftable.planner.CraftRequest.PartialPolicy.valueOf(element.getAsString());
            } catch (IllegalArgumentException ignored) {
                option.value = option.defaultValue;
            }
        }
    }

    /**
     * 把内存中的全部配置写回磁盘（不存在的目录会自动创建）。
     */
    public static void save() {
        JsonObject generic = new JsonObject();
        for (Option option : Option.values()) {
            writeValue(generic, option);
        }
        JsonObject root = new JsonObject();
        root.add(GROUP_KEY, generic);
        root.addProperty("config_version", CONFIG_VERSION);
        ConfigFileIO.writeObject(configFile(), root);
    }

    /**
     * 修改单个字段：更新内存值，并只把该字段合并进现有文件，
     * 文件里的其他内容（包括未知键）原样保留。
     */
    public static void update(Option option, Object value) {
        if (!option.defaultValue.getClass().isInstance(value)) throw new IllegalArgumentException("Invalid config value");
        option.value = value instanceof Integer number ? Math.clamp(number, 150, 800) : value;

        Path file = configFile();
        JsonObject root = ConfigFileIO.readObject(file);
        if (root == null) {
            root = new JsonObject();
        }
        JsonObject generic = ConfigFileIO.getObject(root, GROUP_KEY);
        if (generic == null) {
            generic = new JsonObject();
            root.add(GROUP_KEY, generic);
        }
        writeValue(generic, option);
        if (!root.has("config_version")) {
            root.addProperty("config_version", CONFIG_VERSION);
        }
        ConfigFileIO.writeObject(file, root);
    }

    public static boolean get(Option option) {
        return (Boolean) option.value;
    }

    // 既有调用点的语义化访问器
    public static boolean recipeBookEnhancementsEnabled() {
        return get(Option.RECIPE_BOOK_ENHANCEMENTS);
    }

    public static boolean detailedFailureFeedbackEnabled() {
        return get(Option.DETAILED_FAILURE_FEEDBACK);
    }

    public static boolean unlockedOnly() {
        return get(Option.UNLOCKED_ONLY);
    }

    private static void writeValue(JsonObject target, Option option) {
        if (option.value instanceof Boolean value) target.addProperty(option.key, value);
        else if (option.value instanceof Number value) target.addProperty(option.key, value);
        else target.addProperty(option.key, option.value.toString());
    }

    public static int doublePressMillis() { return (Integer) Option.DOUBLE_PRESS_MILLIS.value; }
    public static boolean allowSurplusDrops() { return get(Option.ALLOW_SURPLUS_DROPS); }
    public static org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy() {
        return (org.berusted.craftable.planner.CraftRequest.PartialPolicy) Option.PARTIAL_POLICY.value;
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    /**
     * 配置项定义：json 键名 + 默认值 + 当前值，包含显示、双击手势和制作策略设置。
     */
    public enum Option {
        RECIPE_BOOK_ENHANCEMENTS("recipe_book_enhancements", true),
        DETAILED_FAILURE_FEEDBACK("detailed_failure_feedback", true),
        UNLOCKED_ONLY("unlocked_only", false),
        ALLOW_SURPLUS_DROPS("allow_surplus_drops", true),
        DOUBLE_PRESS_MILLIS("double_press_millis", 350),
        PARTIAL_POLICY("partial_policy", org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE);

        private final String key;
        private final Object defaultValue;
        private Object value;

        Option(String key, Object defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }
    }
}
