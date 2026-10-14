package org.berusted.craftable.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;
import java.util.List;

/** Server-owned settings that change resource and workstation semantics. */
public final class CraftableServerConfig {
    public static final int DEFAULT_MAX_PLAN_STEPS = org.berusted.craftable.planner.SearchBudget.DEFAULT_MAX_STEPS;
    public static final int MIN_MAX_PLAN_STEPS = 1;
    public static final int MAX_MAX_PLAN_STEPS = org.berusted.craftable.planner.SearchBudget.HARD_MAX_STEPS;
    public static final ModConfigSpec SPEC;
    private static final Values VALUES;
    private static volatile PolicySnapshot policySnapshot;

    static {
        Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private CraftableServerConfig() {}

    public static CraftingRules craftingRules() {
        return new CraftingRules(VALUES.partialExecution.get(), VALUES.surplusDelivery.get(),
                VALUES.maxCraftBatch.get(), VALUES.maxPlanSteps.get(), directPreparationPolicy());
    }

    /** The same registered values are edited by the vanilla and professional
     * presentations. No second defaults, validators or settings store. */
    public static List<ModConfigSpec.ConfigValue<?>> directPreparationValues() {
        return List.of(VALUES.directPreparationMode, VALUES.directPreparationAllowedRecipes,
                VALUES.directPreparationConfirmationRecipes);
    }

    /** Immutable normalized policy is reused until the raw config changes.
     * Keeping a defensive raw snapshot also detects in-place list edits. */
    public static DirectPreparationPolicy directPreparationPolicy() {
        var mode = VALUES.directPreparationMode.get();
        var allowed = VALUES.directPreparationAllowedRecipes.get();
        var confirmation = VALUES.directPreparationConfirmationRecipes.get();
        var cached = policySnapshot;
        if (cached != null && cached.matches(mode, allowed, confirmation)) return cached.policy();
        var policy = DirectPreparationPolicy.of(mode, allowed, confirmation);
        policySnapshot = new PolicySnapshot(mode, List.copyOf(allowed), List.copyOf(confirmation), policy);
        return policy;
    }

    private record PolicySnapshot(DirectPreparationPolicy.Mode mode, List<String> allowed, List<String> confirmation,
            DirectPreparationPolicy policy) {
        boolean matches(DirectPreparationPolicy.Mode currentMode, List<String> currentAllowed, List<String> currentConfirmation) {
            return mode == currentMode && allowed.equals(currentAllowed) && confirmation.equals(currentConfirmation);
        }
    }

    public enum SurplusDelivery { REQUIRE_SPACE, DROP_OVERFLOW }

    public record CraftingRules(org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy,
            SurplusDelivery surplusDelivery, int maxBatches, int maxPlanSteps, DirectPreparationPolicy directPreparation) {
        public CraftingRules {
            java.util.Objects.requireNonNull(partialPolicy, "partialPolicy");
            java.util.Objects.requireNonNull(surplusDelivery, "surplusDelivery");
            java.util.Objects.requireNonNull(directPreparation, "directPreparation");
            if (maxBatches < 1 || maxBatches > 64) throw new IllegalArgumentException("maxBatches must be between 1 and 64");
            if (maxPlanSteps < MIN_MAX_PLAN_STEPS || maxPlanSteps > MAX_MAX_PLAN_STEPS)
                throw new IllegalArgumentException("maxPlanSteps must be between 1 and 256");
        }
        /** Existing callers retain the old default, never an unbounded rule. */
        public CraftingRules(org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy,
                SurplusDelivery surplusDelivery, int maxBatches) {
            this(partialPolicy, surplusDelivery, maxBatches, DEFAULT_MAX_PLAN_STEPS, DirectPreparationPolicy.DEFAULT);
        }
        public CraftingRules(org.berusted.craftable.planner.CraftRequest.PartialPolicy partialPolicy,
                SurplusDelivery surplusDelivery, int maxBatches, int maxPlanSteps) {
            this(partialPolicy, surplusDelivery, maxBatches, maxPlanSteps, DirectPreparationPolicy.DEFAULT);
        }
    }

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
        private final ModConfigSpec.EnumValue<org.berusted.craftable.planner.CraftRequest.PartialPolicy> partialExecution;
        private final ModConfigSpec.EnumValue<SurplusDelivery> surplusDelivery;
        private final ModConfigSpec.IntValue maxCraftBatch;
        private final ModConfigSpec.IntValue maxPlanSteps;
        private final ModConfigSpec.EnumValue<DirectPreparationPolicy.Mode> directPreparationMode;
        private final ModConfigSpec.ConfigValue<List<String>> directPreparationAllowedRecipes;
        private final ModConfigSpec.ConfigValue<List<String>> directPreparationConfirmationRecipes;

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
                    .comment("Legacy key: maximum age of the read-only environment diagnostic snapshot only.",
                            "Does not affect recipe-book/detail refresh or crafting authorization. 0 disables diagnostic caching.")
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
            builder.push("crafting");
            partialExecution = builder.translation("config.craftable.server.partial_execution")
                    .comment("Only applies to an explicit partial request; a single C never consumes a partial plan.")
                    .defineEnum("partialExecution", org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE);
            surplusDelivery = builder.translation("config.craftable.server.surplus_delivery")
                    .comment("Primary output must fit. DROP_OVERFLOW throws only surplus that will not fit; drops can be lost.")
                    .defineEnum("surplusDelivery", SurplusDelivery.REQUIRE_SPACE);
            maxCraftBatch = builder.translation("config.craftable.server.max_craft_batch")
                    .comment("Maximum root recipe executions per request; total intermediate steps are separately bounded.")
                    .defineInRange("maxCraftBatch", 64, 1, 64);
            maxPlanSteps = builder.translation("config.craftable.server.max_plan_steps")
                    .comment("Maximum actual recipe executions in one plan, including root and intermediate recipes.",
                            "This does not increase search time, memory or packet limits; unresolved plans remain unknown.")
                    .defineInRange("maxPlanSteps", DEFAULT_MAX_PLAN_STEPS, MIN_MAX_PLAN_STEPS, MAX_MAX_PLAN_STEPS);
            directPreparationMode = builder.translation("config.craftable.server.direct_preparation_mode")
                    .comment("Consent policy for explicit safe partial requests only; does not permit unsupported recipes.",
                            "CUSTOM applies structural defaults plus recipe-ID overrides; ALL_SUPPORTED skips additional partial confirmation; REQUIRE_CONFIRMATION always asks.",
                            "Switching mode preserves both override lists. NEVER and CONFIRM partial policies still apply.")
                    .defineEnum("directPreparationMode", DirectPreparationPolicy.Mode.CUSTOM);
            // NeoForge's list element validator alone does not validate total
            // size in ValueSpec.test(). Use one bounded whole-list predicate
            // so TOML correction, UI validation and rule packets agree.
            directPreparationAllowedRecipes = builder.translation("config.craftable.server.direct_preparation_allowed_recipes")
                    .comment("Full recipe IDs allowed without additional partial confirmation in CUSTOM mode.",
                            "At most 128 IDs, 128 characters each. Unknown valid IDs are retained but cannot add execution support.")
                    .define(List.of("directPreparationAllowedRecipes"), () -> List.<String>of(),
                            DirectPreparationPolicy::validRecipeList, List.class);
            directPreparationConfirmationRecipes = builder.translation("config.craftable.server.direct_preparation_confirmation_recipes")
                    .comment("Full recipe IDs requiring additional partial confirmation in CUSTOM mode; these override both defaults and the allowed list.",
                            "At most 128 IDs, 128 characters each. Empty lists retain the structural default policy.")
                    .define(List.of("directPreparationConfirmationRecipes"), () -> List.<String>of(),
                            DirectPreparationPolicy::validRecipeList, List.class);
            builder.pop();
        }
    }
}
