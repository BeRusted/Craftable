package org.berusted.craftable.config;

/**
 * 服务端配置：目前写死在代码里、不可更改，也不读写任何配置文件。
 *
 * <p>API 形态与客户端 {@code CraftableConfigHandler} 对齐：{@link #init()}、
 * {@link #load()}、{@link #save()} 作为文件相关接口保留，但均为空实现，
 * 以后要让服务端配置可修改时，直接在这三个函数里填实现即可，调用方无需改动。
 *
 * <p>注意本类位于主源集，严禁引用可选模组（如 malilib）的类型。
 */
public final class CraftableServerConfigHandler {
    private CraftableServerConfigHandler() {
    }

    public static EnvironmentScanSettings scanSettings() {
        return new EnvironmentScanSettings(
                Option.HORIZONTAL_RADIUS.intValue(),
                Option.VERTICAL_RADIUS.intValue(),
                Option.PREVIEW_CACHE_TICKS.intValue(),
                Option.INCLUDE_ENDER_CHEST.booleanValue()
        );
    }

    /**
     * 预留：初始化配置文件（不存在时写出默认配置）。当前配置写死在代码里，不实现。
     */
    public static void init() {
        // 暂不实现：服务端配置不落盘
    }

    /**
     * 预留：从磁盘读取配置。当前配置写死在代码里，不实现。
     */
    public static void load() {
        // 暂不实现：服务端配置不读盘
    }

    /**
     * 预留：把配置写回磁盘。当前配置写死在代码里，不实现。
     */
    public static void save() {
        // 暂不实现：服务端配置不落盘
    }

    /**
     * 配置项定义：json 键名 + 默认值 + 当前值，新增配置只需在这里加一行。
     * 当前值恒等于默认值（写死）；将来实现 load() 时在枚举内读取文件覆盖 value 即可。
     */
    public enum Option {
        HORIZONTAL_RADIUS("horizontal_radius", 8),
        VERTICAL_RADIUS("vertical_radius", 4),
        PREVIEW_CACHE_TICKS("preview_cache_ticks", 5),
        INCLUDE_ENDER_CHEST("include_ender_chest", true);

        private final String key;
        private final Object defaultValue;
        private final Object value;

        Option(String key, Object defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public String key() {
            return this.key;
        }

        public Object value() {
            return this.value;
        }

        public int intValue() {
            return (Integer) this.value;
        }

        public boolean booleanValue() {
            return (Boolean) this.value;
        }
    }

    /**
     * 环境扫描参数（不可变）。构造时会校验各参数的取值范围。
     */
    public record EnvironmentScanSettings(
            int horizontalRadius,
            int verticalRadius,
            int previewCacheTicks,
            boolean includeEnderChest) {
        public static final int MIN_HORIZONTAL_RADIUS = 1;
        public static final int MAX_HORIZONTAL_RADIUS = 16;
        public static final int MIN_VERTICAL_RADIUS = 0;
        public static final int MAX_VERTICAL_RADIUS = 8;
        public static final int MIN_PREVIEW_CACHE_TICKS = 0;
        public static final int MAX_PREVIEW_CACHE_TICKS = 20;

        public EnvironmentScanSettings {
            requireInRange("horizontalRadius", horizontalRadius, MIN_HORIZONTAL_RADIUS, MAX_HORIZONTAL_RADIUS);
            requireInRange("verticalRadius", verticalRadius, MIN_VERTICAL_RADIUS, MAX_VERTICAL_RADIUS);
            requireInRange("previewCacheTicks", previewCacheTicks, MIN_PREVIEW_CACHE_TICKS, MAX_PREVIEW_CACHE_TICKS);
        }

        private static void requireInRange(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
        }

        public long scanVolume() {
            long diameter = horizontalRadius * 2L + 1L;
            return diameter * diameter * (verticalRadius * 2L + 1L);
        }
    }
}
