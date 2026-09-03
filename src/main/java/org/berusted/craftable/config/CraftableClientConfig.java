package org.berusted.craftable.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Client-only presentation preferences; none of these values affect server truth. */
public final class CraftableClientConfig {
    public static final ModConfigSpec SPEC;
    private static final Values VALUES;

    static {
        Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private CraftableClientConfig() {}

    public static boolean recipeBookEnhancementsEnabled() {
        return VALUES.recipeBookEnhancements.get();
    }

    public static boolean detailedFailureFeedbackEnabled() {
        return VALUES.detailedFailureFeedback.get();
    }

    private static final class Values {
        private final ModConfigSpec.BooleanValue recipeBookEnhancements;
        private final ModConfigSpec.BooleanValue detailedFailureFeedback;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("presentation");
            recipeBookEnhancements = builder
                    .comment("Enable Craftable's current vanilla recipe-book entry points.")
                    .translation("config.craftable.client.recipe_book_enhancements")
                    .define("recipeBookEnhancements", true);
            detailedFailureFeedback = builder
                    .comment("Show the specific server result when a create request fails.")
                    .translation("config.craftable.client.detailed_failure_feedback")
                    .define("detailedFailureFeedback", true);
            builder.pop();
        }
    }
}
