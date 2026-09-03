package org.berusted.craftable.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Server-owned settings that change resource and workstation semantics. */
public final class CraftableServerConfig {
    public static final ModConfigSpec SPEC;
    private static final Values VALUES;

    static {
        Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private CraftableServerConfig() {}

    /** Takes one coherent copy so a live config reload cannot split a scan across two settings. */
    public static EnvironmentScanSettings scanSettings() {
        return new EnvironmentScanSettings(
                VALUES.horizontalRadius.get(),
                VALUES.verticalRadius.get(),
                VALUES.previewCacheTicks.get(),
                VALUES.includeEnderChest.get());
    }

    private static final class Values {
        private final ModConfigSpec.IntValue horizontalRadius;
        private final ModConfigSpec.IntValue verticalRadius;
        private final ModConfigSpec.IntValue previewCacheTicks;
        private final ModConfigSpec.BooleanValue includeEnderChest;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("environment");
            horizontalRadius = builder
                    .comment("Horizontal radius of the vanilla work-environment scan.")
                    .translation("config.craftable.server.horizontal_radius")
                    .defineInRange(
                            "horizontalRadius",
                            8,
                            EnvironmentScanSettings.MIN_HORIZONTAL_RADIUS,
                            EnvironmentScanSettings.MAX_HORIZONTAL_RADIUS);
            verticalRadius = builder
                    .comment("Vertical radius of the vanilla work-environment scan.")
                    .translation("config.craftable.server.vertical_radius")
                    .defineInRange(
                            "verticalRadius",
                            4,
                            EnvironmentScanSettings.MIN_VERTICAL_RADIUS,
                            EnvironmentScanSettings.MAX_VERTICAL_RADIUS);
            previewCacheTicks = builder
                    .comment("Maximum age of a preview snapshot. Creation requests always bypass this cache.")
                    .translation("config.craftable.server.preview_cache_ticks")
                    .defineInRange(
                            "previewCacheTicks",
                            5,
                            EnvironmentScanSettings.MIN_PREVIEW_CACHE_TICKS,
                            EnvironmentScanSettings.MAX_PREVIEW_CACHE_TICKS);
            includeEnderChest = builder
                    .comment("Whether a nearby usable ender chest grants access to the player's personal ender inventory.")
                    .translation("config.craftable.server.include_ender_chest")
                    .define("includeEnderChest", true);
            builder.pop();
        }
    }
}
