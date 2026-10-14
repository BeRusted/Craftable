package org.berusted.craftable.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.berusted.craftable.planner.SearchBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the registered correction rules, not a parallel settings parser. */
class PreM5ConfigTest {
    @Test void missingSectionsRestoreDefaultsAndNumericLimitsClampToBounds() {
        var config = CommentedConfig.inMemory();
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(128, (int) config.get("crafting.maxPlanSteps"));
        config.set("crafting.maxPlanSteps", 256);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(256, (int) config.get("crafting.maxPlanSteps"));
        config.remove("crafting");
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(128, (int) config.get("crafting.maxPlanSteps"));
        assertEquals(64, (int) config.get("crafting.maxCraftBatch"));
        config.set("crafting.maxPlanSteps", 257);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(256, (int) config.get("crafting.maxPlanSteps"));
        config.set("crafting.maxPlanSteps", 0);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(1, (int) config.get("crafting.maxPlanSteps"));
        assertEquals(SearchBudget.DEFAULT_MAX_STEPS, CraftableServerConfig.DEFAULT_MAX_PLAN_STEPS);
        assertEquals(SearchBudget.HARD_MAX_STEPS, CraftableServerConfig.MAX_MAX_PLAN_STEPS);
    }

    @Test void legacyDiagnosticValueSurvivesCorrectionButMissingSectionDoesNotKeepIt() {
        var config = CommentedConfig.inMemory();
        config.set("environment.previewCacheTicks", 17);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(17, (int) config.get("environment.previewCacheTicks"));
        config.remove("environment");
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(5, (int) config.get("environment.previewCacheTicks"));
    }

    @Test void directPreparationDefaultsAndRegisteredWholeListBoundsAreShared() {
        var config = CommentedConfig.inMemory();
        CraftableServerConfig.SPEC.correct(config);
        assertEquals("CUSTOM", config.get("crafting.directPreparationMode").toString());
        assertEquals(java.util.List.of(), config.get("crafting.directPreparationAllowedRecipes"));
        assertEquals(java.util.List.of(), config.get("crafting.directPreparationConfirmationRecipes"));
        var values = CraftableServerConfig.directPreparationValues();
        assertEquals(3, values.size());
        assertSame(values.get(0), CraftableServerConfig.directPreparationValues().get(0));
        assertSame(values.get(1), CraftableServerConfig.directPreparationValues().get(1));
        assertSame(values.get(2), CraftableServerConfig.directPreparationValues().get(2));
        var allowedSpec = CraftableServerConfig.SPEC.getSpec().get(values.get(1).getPath());
        assertInstanceOf(net.neoforged.neoforge.common.ModConfigSpec.ValueSpec.class, allowedSpec);
        var metadata = (net.neoforged.neoforge.common.ModConfigSpec.ValueSpec) allowedSpec;
        assertTrue(metadata.test(java.util.Collections.nCopies(128, "future:unknown")));
        assertFalse(metadata.test(java.util.Collections.nCopies(129, "future:unknown")));
        assertFalse(metadata.test(java.util.List.of("stick")));
        assertFalse(metadata.test(java.util.List.of("minecraft:" + "a".repeat(128))));
        config.set("crafting.directPreparationAllowedRecipes", java.util.Collections.nCopies(129, "future:unknown"));
        config.set("crafting.directPreparationConfirmationRecipes", java.util.List.of("future:kiln/iron"));
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(java.util.List.of(), config.get("crafting.directPreparationAllowedRecipes"));
        assertEquals(java.util.List.of("future:kiln/iron"), config.get("crafting.directPreparationConfirmationRecipes"));
        config.set("crafting.directPreparationAllowedRecipes", java.util.List.of("stick"));
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(java.util.List.of(), config.get("crafting.directPreparationAllowedRecipes"));
    }

    @Test void oldCraftingRulesConstructorsRetainConservativeDefaultsAndPolicyAffectsIdentity() {
        var old = new CraftableServerConfig.CraftingRules(org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64);
        var bounded = new CraftableServerConfig.CraftingRules(old.partialPolicy(), old.surplusDelivery(), old.maxBatches(), 128);
        assertEquals(old, bounded);
        assertSame(DirectPreparationPolicy.DEFAULT, old.directPreparation());
        assertNotEquals(old, new CraftableServerConfig.CraftingRules(old.partialPolicy(), old.surplusDelivery(),
                old.maxBatches(), old.maxPlanSteps(), DirectPreparationPolicy.of(
                        DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION, java.util.List.of(), java.util.List.of())));
    }
}
