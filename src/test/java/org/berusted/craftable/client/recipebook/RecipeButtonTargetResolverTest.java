package org.berusted.craftable.client.recipebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.berusted.craftable.api.CraftingStatus;
import org.junit.jupiter.api.Test;

class RecipeButtonTargetResolverTest {
    @Test
    void craftableVariantKeepsCollectionCraftableWhileAnimationShowsBlockedVariant() {
        List<CraftingStatus> statuses = List.of(CraftingStatus.CRAFTABLE, CraftingStatus.BLOCKED);

        assertEquals(CraftingStatus.CRAFTABLE, RecipeButtonTargetResolver.strongestStatus(statuses));
        assertEquals(0, RecipeButtonTargetResolver.preferredIndex(statuses, List.of(false, false), 1));
    }

    @Test
    void fallsBackToVanillaCraftableVariantBeforeServerStatusArrives() {
        assertEquals(
                1,
                RecipeButtonTargetResolver.preferredIndex(
                        List.of(CraftingStatus.BLOCKED, CraftingStatus.BLOCKED),
                        List.of(false, true),
                        0));
    }

    @Test
    void rejectsMisalignedVariantState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RecipeButtonTargetResolver.preferredIndex(
                        List.of(CraftingStatus.BLOCKED), List.of(), 0));
    }
}
