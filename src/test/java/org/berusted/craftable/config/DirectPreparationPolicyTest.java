package org.berusted.craftable.config;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectPreparationPolicyTest {
    private static final ResourceLocation PLANKS = ResourceLocation.parse("minecraft:oak_planks");
    private static final ResourceLocation OTHER = ResourceLocation.parse("minecraft:oak_boat");

    @Test void defaultPreservesStructuralEligibilityRatherThanGuessingFromIds() {
        assertTrue(DirectPreparationPolicy.DEFAULT.allowsWithoutConfirmation(PLANKS, true));
        assertFalse(DirectPreparationPolicy.DEFAULT.allowsWithoutConfirmation(PLANKS, false));
        assertFalse(DirectPreparationPolicy.DEFAULT.allowsWithoutConfirmation(OTHER, false));
        assertEquals(DirectPreparationPolicy.Mode.CUSTOM, DirectPreparationPolicy.DEFAULT.mode());
        assertTrue(DirectPreparationPolicy.DEFAULT.allowedRecipes().isEmpty());
        assertTrue(DirectPreparationPolicy.DEFAULT.confirmationRecipes().isEmpty());
    }

    @Test void overridesAreRecipeExactAndConfirmationWins() {
        var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                List.of("minecraft:oak_boat", "minecraft:oak_planks"), List.of("minecraft:oak_planks"));
        assertTrue(policy.allowsWithoutConfirmation(OTHER, false));
        assertFalse(policy.allowsWithoutConfirmation(PLANKS, true));
        assertFalse(policy.allowsWithoutConfirmation(PLANKS, false));
        assertFalse(policy.allowsWithoutConfirmation(ResourceLocation.parse("minecraft:birch_boat"), false));
    }

    @Test void allAndNoneAreModesNotDestructiveListRewrites() {
        var ids = List.of("minecraft:oak_boat");
        var denied = List.of("minecraft:oak_planks");
        var all = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.ALL_SUPPORTED, ids, denied);
        var none = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION, ids, denied);
        assertTrue(all.allowsWithoutConfirmation(PLANKS, false));
        assertTrue(all.allowsWithoutConfirmation(OTHER, false));
        assertFalse(none.allowsWithoutConfirmation(PLANKS, true));
        assertFalse(none.allowsWithoutConfirmation(OTHER, true));
        assertEquals(ids, all.allowedRecipes());
        assertEquals(denied, all.confirmationRecipes());
        assertEquals(ids, none.allowedRecipes());
        assertEquals(denied, none.confirmationRecipes());
    }

    @Test void normalizationIsImmutableSortedAndValueIdentified() {
        var raw = new ArrayList<>(List.of(" minecraft:stick ", "minecraft:oak_planks", "minecraft:stick"));
        var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, raw, List.of("future:kiln/iron"));
        raw.clear();
        assertEquals(List.of("minecraft:oak_planks", "minecraft:stick"), policy.allowedRecipes());
        assertEquals(List.of("future:kiln/iron"), policy.confirmationRecipes());
        assertThrows(UnsupportedOperationException.class, () -> policy.allowedRecipes().add("minecraft:boat"));
        var same = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                List.of("minecraft:stick", "minecraft:oak_planks"), List.of("future:kiln/iron"));
        assertEquals(policy, same);
        assertEquals(policy.hashCode(), same.hashCode());
        assertNotEquals(policy, DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.ALL_SUPPORTED,
                same.allowedRecipes(), same.confirmationRecipes()));
    }

    @Test void boundsAndFullNamespaceValidationApplyBeforeDeduplication() {
        assertTrue(DirectPreparationPolicy.validRecipeId("future:machine/recipe"));
        for (var invalid : List.of("oak_planks", "", "minecraft:", ":stick", "Minecraft:stick", "minecraft:two words", "minecraft:a:b")) {
            assertFalse(DirectPreparationPolicy.validRecipeId(invalid), invalid);
            assertThrows(IllegalArgumentException.class, () -> DirectPreparationPolicy.of(
                    DirectPreparationPolicy.Mode.CUSTOM, List.of(invalid), List.of()), invalid);
        }
        assertFalse(DirectPreparationPolicy.validRecipeId("m:" + "a".repeat(127)));
        assertTrue(DirectPreparationPolicy.validRecipeId("m:" + "a".repeat(126)));
        assertTrue(DirectPreparationPolicy.validRecipeList(java.util.Collections.nCopies(128, "minecraft:stick")));
        assertFalse(DirectPreparationPolicy.validRecipeList(java.util.Collections.nCopies(129, "minecraft:stick")));
        assertFalse(DirectPreparationPolicy.validRecipeList(List.of(1)));
        assertFalse(DirectPreparationPolicy.validRecipeList("minecraft:stick"));
        assertThrows(IllegalArgumentException.class, () -> DirectPreparationPolicy.of(
                DirectPreparationPolicy.Mode.CUSTOM, java.util.Collections.nCopies(129, "minecraft:stick"), List.of()));
        assertThrows(NullPointerException.class, () -> DirectPreparationPolicy.of(null, List.of(), List.of()));
    }
}
