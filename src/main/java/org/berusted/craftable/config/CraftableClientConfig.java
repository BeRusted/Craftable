package org.berusted.craftable.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Client presentation and request preferences; they can only tighten world rules. */
public final class CraftableClientConfig {
    public static final int MIN_DOUBLE_PRESS_MILLIS = 150;
    public static final int MAX_DOUBLE_PRESS_MILLIS = 800;
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

    /** One draft value for the native settings UI; this is not a second config. */
    public record Preferences(boolean recipeBookEnhancements, boolean detailedFailureFeedback, boolean unlockedOnly,
            org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy,
            boolean allowSurplusDrops, int doublePressMillis) {
        public Preferences {
            java.util.Objects.requireNonNull(partialPolicy, "partialPolicy");
            if (doublePressMillis < MIN_DOUBLE_PRESS_MILLIS || doublePressMillis > MAX_DOUBLE_PRESS_MILLIS)
                throw new IllegalArgumentException("doublePressMillis must be between 150 and 800");
        }
    }

    public static Preferences preferences() {
        return new Preferences(recipeBookEnhancementsEnabled(), detailedFailureFeedbackEnabled(), unlockedOnly(),
                partialPolicy(), allowSurplusDrops(), doublePressMillis());
    }

    public static Preferences defaultPreferences() {
        return new Preferences(VALUES.recipeBookEnhancements.getDefault(), VALUES.detailedFailureFeedback.getDefault(),
                VALUES.unlockedOnly.getDefault(), VALUES.partialPolicy.getDefault(),
                VALUES.allowSurplusDrops.getDefault(), VALUES.doublePressMillis.getDefault());
    }

    /** Save through the registered spec; personal preferences can only tighten server rules. */
    public static void savePreferences(Preferences preferences) {
        VALUES.recipeBookEnhancements.set(preferences.recipeBookEnhancements());
        VALUES.detailedFailureFeedback.set(preferences.detailedFailureFeedback());
        VALUES.unlockedOnly.set(preferences.unlockedOnly());
        VALUES.partialPolicy.set(preferences.partialPolicy());
        VALUES.allowSurplusDrops.set(preferences.allowSurplusDrops());
        VALUES.doublePressMillis.set(preferences.doublePressMillis());
        SPEC.save();
    }

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
                    .defineInRange("doublePressMillis", 350, MIN_DOUBLE_PRESS_MILLIS, MAX_DOUBLE_PRESS_MILLIS);
            builder.pop();
        }
    }
}
