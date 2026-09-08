package org.berusted.craftable.client.recipebook;

import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class M3StatusLifecycleTest {
    private static final ResourceLocation A = ResourceLocation.withDefaultNamespace("stick");
    private static final ResourceLocation B = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    @AfterEach void clear() { ClientRecipeStatusStore.clear(); }
    private void put(ResourceLocation id, long request, long generation, CraftingStatus status) {
        ClientRecipeStatusStore.put(id, request, status, CraftingResultCode.MISSING_INGREDIENTS, generation, 100);
    }
    @Test void creationRetainsPreviousPositiveDisplayWhileRechecking() {
        put(A, 1, 1, CraftingStatus.CRAFTABLE);
        ClientRecipeStatusStore.invalidate(4);
        assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.get(A, false));
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(A));
        assertFalse(ClientRecipeStatusStore.isFresh(A, 101, 10));
        put(A, 5, 2, CraftingStatus.BLOCKED);
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(A, false));
        assertEquals(ClientRecipeStatusStore.Lifecycle.KNOWN, ClientRecipeStatusStore.lifecycle(A));
    }
    @Test void creationBarrierRejectsPreCommitResponsesEvenForPreviouslyUnknownRecipes() {
        ClientRecipeStatusStore.invalidate(4);
        put(B, 3, 1, CraftingStatus.CRAFTABLE);
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(B));
    }
    @Test void oldGenerationCannotOverwriteAnotherRecipesNewerSnapshot() {
        put(A, 2, 9, CraftingStatus.CRAFTABLE);
        put(B, 3, 8, CraftingStatus.CRAFTABLE);
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(B));
    }
    @Test void disconnectClearsKnowledgeAndBarrier() {
        put(A, 3, 9, CraftingStatus.CRAFTABLE);
        ClientRecipeStatusStore.invalidate(5);
        ClientRecipeStatusStore.clear();
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(A));
        assertTrue(ClientRecipeStatusStore.accepts(1, 1));
    }

    @Test void unchangedRefreshDoesNotRebuildPageButPendingResolutionDoes() {
        put(A, 1, 1, CraftingStatus.CRAFTABLE);
        long revision = ClientRecipeStatusStore.revision();
        put(A, 2, 2, CraftingStatus.CRAFTABLE);
        assertEquals(revision, ClientRecipeStatusStore.revision());
        ClientRecipeStatusStore.invalidate(3);
        long pendingRevision = ClientRecipeStatusStore.revision();
        put(A, 4, 3, CraftingStatus.CRAFTABLE);
        assertTrue(ClientRecipeStatusStore.revision() > pendingRevision);
    }
}
