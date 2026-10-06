package org.berusted.craftable.client.recipebook;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.planner.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientRecipeStatusStoreTest {
    private static final ResourceLocation RECIPE = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    @AfterEach void clearStore() { ClientRecipeStatusStore.clear(); }

    @Test void refreshRetainsPresentationButDiscardsEvidenceAndDoesNotAuthorizeCrafting() {
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.authorizeLocal(true);
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        ClientRecipeStatusStore.beginRefresh();
        assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.display(RECIPE, false));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(RECIPE, false));
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
        assertNull(ClientRecipeStatusStore.evidence(RECIPE));
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.display(RECIPE, false));
        assertEquals(ClientRecipeStatusStore.Lifecycle.KNOWN, ClientRecipeStatusStore.lifecycle(RECIPE));
    }

    @Test void repeatedRefreshCannotLoseDisplayAndNewMenuCannotInheritIt() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        for (int i = 0; i < 50; i++) ClientRecipeStatusStore.beginRefresh();
        assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.display(RECIPE, false));
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.hasDisplay(RECIPE));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.display(RECIPE, false));
    }

    @Test void sameDisplayAfterRefreshDoesNotRequestACollectionRebuild() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        long shown = ClientRecipeStatusStore.presentationRevision();
        ClientRecipeStatusStore.beginRefresh();
        ClientRecipeStatusStore.authorizeLocal(true);
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        assertEquals(shown, ClientRecipeStatusStore.presentationRevision());
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        assertEquals(shown + 1, ClientRecipeStatusStore.presentationRevision());
    }

    @Test void authorizationRenewalKeepsComputedUnknownWithoutGrantingRetry() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        assertFalse(ClientRecipeStatusStore.needsDiagnostic(RECIPE));
        long before = ClientRecipeStatusStore.revision();
        ClientRecipeStatusStore.authorizeLocal(true);
        for (int i = 0; i < 100; i++) ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(before + 1, ClientRecipeStatusStore.revision());
        ClientRecipeStatusStore.authorizeLocal(false);
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        ClientRecipeStatusStore.authorizeLocal(true);
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
    }

    @Test void newScopeDoesNotInheritOldConclusionsOrVanillaOptimism() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(ClientRecipeStatusStore.Lifecycle.KNOWN, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(RECIPE, true));
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(RECIPE));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(RECIPE, true));
    }

    @Test void foregroundChurnCannotStarveHiddenHeadAcross400Slices() {
        var turns = new ClientBrowsePlanner.Turns();
        int last = -1, served = 0;
        for (int tick = 0; tick < 400; tick++) {
            boolean hidden = turns.hidden(true, true);
            turns.served(hidden);
            if (hidden) { assertTrue(tick - last <= 4); last = tick; served++; }
        }
        assertEquals(100, served);
        // Idle hidden queues do not accumulate unbounded foreground credit.
        for (int tick = 0; tick < 100; tick++) turns.served(false);
        assertTrue(turns.hidden(true, true));
        turns.served(true);
        assertFalse(turns.hidden(true, true));
        assertTrue(turns.hidden(false, true));
    }

    @Test void detailCountsAndConstraintsShareOneStoreButNeverBorrowEvidence() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.defaults(false, org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE);
        var one = org.berusted.craftable.planner.CraftRequest.one(RECIPE);
        assertSame(one, one.withPartial(false));
        assertSame(one, one.withBatches(1));
        ClientRecipeStatusStore.completeLocal(one, SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        assertTrue(ClientRecipeStatusStore.exhausted(one.withPartial(true)));
        assertFalse(ClientRecipeStatusStore.computed(one.withBatches(2)));
        var pin = new org.berusted.craftable.planner.CraftRequest(RECIPE, 1, false, one.allowDrops(), one.policy(),
                java.util.Map.of("0.7", ResourceLocation.withDefaultNamespace("stick_from_bamboo_item")));
        assertFalse(ClientRecipeStatusStore.computed(pin));
        for (int i = 0; i < 100; i++) ClientRecipeStatusStore.authorizeLocal(true);
        assertTrue(ClientRecipeStatusStore.exhausted(one));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.exhausted(one));
    }

    @Test void localMaximumKeepsNonMonotoneCapacityAndUnknownUpperCounts() {
        ClientRecipeStatusStore.beginLocal();
        var intent = org.berusted.craftable.planner.CraftRequest.one(RECIPE);
        ClientRecipeStatusStore.completeLocal(intent, SearchResult.blocked(CraftingResultCode.NO_OUTPUT_SPACE), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(2), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        assertTrue(ClientBrowsePlanner.maximumView(intent, 4).pending());
        ClientRecipeStatusStore.completeLocal(intent.withBatches(3), SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(4), SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        var limited = ClientBrowsePlanner.maximumView(intent, 4);
        assertEquals(2, limited.lowerBound());
        assertFalse(limited.proven()); assertTrue(limited.limited()); assertFalse(limited.pending());
        ClientRecipeStatusStore.completeLocal(intent.withBatches(4), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        var complete = ClientBrowsePlanner.maximumView(intent, 4);
        assertEquals(4, complete.lowerBound()); assertTrue(complete.proven());
        assertFalse(complete.limited());
    }

    @Test void largeConstraintIdentitiesStopBeforeTheResultMemoryReserve() {
        ClientRecipeStatusStore.beginLocal();
        int inserted = 0;
        var pins = new java.util.HashMap<String, ResourceLocation>();
        for (int i = 0; i < 32; i++) pins.put("0." + (i / 9) + "." + (i % 9), RECIPE);
        for (int i = 0; i < 16_384; i++) {
            var request = new org.berusted.craftable.planner.CraftRequest(ResourceLocation.withDefaultNamespace("test_" + i),
                    1, false, false, org.berusted.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
            if (!ClientRecipeStatusStore.canRecord(request)) break;
            ClientRecipeStatusStore.completeLocal(request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
            inserted++;
        }
        assertTrue(inserted > 0 && inserted < 512, "constraint strings must be charged, not just entry count");
        ClientRecipeStatusStore.beginLocal();
        assertTrue(ClientRecipeStatusStore.canRecord(org.berusted.craftable.planner.CraftRequest.one(RECIPE)));
    }

    @Test void certifiedMaterialCeilingDoesNotTurnUnknownVerdictsIntoFailureProof() {
        ClientRecipeStatusStore.beginLocal();
        var intent = org.berusted.craftable.planner.CraftRequest.one(RECIPE);
        ClientRecipeStatusStore.completeLocal(intent, SearchResult.blocked(CraftingResultCode.NO_OUTPUT_SPACE), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(2), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(3), SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        var exact = ClientBrowsePlanner.maximumView(intent, 64, 2);
        assertEquals(2, exact.lowerBound()); assertEquals(64, exact.cap());
        assertTrue(exact.proven()); assertFalse(exact.pending()); assertFalse(exact.limited());
        assertTrue(ClientRecipeStatusStore.exhausted(intent.withBatches(3)));
        assertNull(ClientRecipeStatusStore.evidence(intent.withBatches(3)));
        // An unknown quantity INSIDE the certificate still prevents precision,
        // even though a smaller one succeeded and capacity is non-monotone.
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64, 3).limited());
        assertFalse(ClientBrowsePlanner.maximumView(intent, 64, 3).proven());
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64).pending());
        ClientRecipeStatusStore.beginRefresh();
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64, 2).pending());
        ClientRecipeStatusStore.completeLocal(intent.withBatches(32), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        var largest = ClientBrowsePlanner.maximumView(intent, 64, 32);
        assertEquals(32, largest.lowerBound()); assertTrue(largest.proven());
        assertFalse(largest.pending()); assertFalse(largest.limited());
        // Lower uncomputed quantities are not implied feasible, but do not
        // change the MAX once its certified ceiling has a concrete witness.
        assertFalse(ClientRecipeStatusStore.computed(intent.withBatches(31)));
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64).pending());
    }
}
