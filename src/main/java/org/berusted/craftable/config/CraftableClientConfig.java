package org.berusted.craftable.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Client presentation and request preferences; they can only tighten world rules. */
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

    public static boolean unlockedOnly() { return VALUES.unlockedOnly.get(); }

    public static org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy() { return VALUES.partialPolicy.get(); }
    public static boolean allowSurplusDrops() { return VALUES.allowSurplusDrops.get(); }
    public static int doublePressMillis() { return VALUES.doublePressMillis.get(); }

    private static final class Values {
        private final ModConfigSpec.BooleanValue recipeBookEnhancements;
        private final ModConfigSpec.BooleanValue detailedFailureFeedback;
        private final ModConfigSpec.BooleanValue unlockedOnly;
        private final ModConfigSpec.EnumValue<org.berusted.craftable.planner.CraftRequest.PartialPolicy> partialPolicy;
        private final ModConfigSpec.BooleanValue allowSurplusDrops;
        private final ModConfigSpec.IntValue doublePressMillis;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("presentation");
            unlockedOnly = builder.translation("config.craftable.client.unlocked_only")
                    .comment("Only show recipes unlocked by the player; does not change progression.")
                    .define("unlockedOnly", false);
            recipeBookEnhancements = builder
                    .comment("Enable Craftable's current vanilla recipe-book entry points.")
                    .translation("config.craftable.client.recipe_book_enhancements")
                    .define("recipeBookEnhancements", true);
            detailedFailureFeedback = builder
                    .comment("Show the specific server result when a create request fails.")
                    .translation("config.craftable.client.detailed_failure_feedback")
                    .define("detailedFailureFeedback", true);
            builder.pop();
            builder.push("crafting");
            partialPolicy = builder.translation("config.craftable.client.partial_policy")
                    .comment("May only tighten the world's partial execution policy.")
                    .defineEnum("partialPolicy", org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            allowSurplusDrops = builder.translation("config.craftable.client.allow_surplus_drops")
                    .comment("Follow world surplus rules. False additionally requires all outputs to fit.")
                    .define("allowSurplusDrops", true);
            doublePressMillis = builder.translation("config.craftable.client.double_press_millis")
                    .defineInRange("doublePressMillis", 350, 150, 800);
            builder.pop();
        }
    }
}
