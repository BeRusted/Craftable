package org.berusted.craftable.client.recipebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientRecipeStatusStoreTest {
    private static final ResourceLocation RECIPE = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    @AfterEach
    void clearStore() {
        ClientRecipeStatusStore.clear();
    }

    @Test
    void ignoresAnOlderResponseForTheSameRecipe() {
        ClientRecipeStatusStore.put(
                RECIPE, 2, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, 9, 100);
        ClientRecipeStatusStore.put(
                RECIPE, 1, CraftingStatus.BLOCKED, CraftingResultCode.MISSING_INGREDIENTS, 8, 101);

        assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.get(RECIPE, false));
    }

    @Test
    void freshnessUsesReceiptTimeRatherThanServerGeneration() {
        ClientRecipeStatusStore.put(
                RECIPE, 1, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, 5000, 100);

        assertTrue(ClientRecipeStatusStore.isFresh(RECIPE, 110, 10));
        assertFalse(ClientRecipeStatusStore.isFresh(RECIPE, 111, 10));
    }
}
