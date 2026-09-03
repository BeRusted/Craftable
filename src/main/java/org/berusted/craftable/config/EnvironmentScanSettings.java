package org.berusted.craftable.config;

/**
 * Validated server settings that participate in environment snapshot identity.
 * Keeping this as a value object prevents callers from reading mutable config
 * values halfway through one scan.
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

    public long scanVolume() {
        long diameter = horizontalRadius * 2L + 1L;
        return diameter * diameter * (verticalRadius * 2L + 1L);
    }

    private static void requireInRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
