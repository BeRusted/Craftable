package org.berusted.craftable.client.recipebook;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Verdict transitions only. The planning client scenario obtains genuine
 * evidence through the production scheduler and verifies detail promotion. */
class ClientDiagnosticLifecycleTest {
    private static final ResourceLocation RECIPE = ResourceLocation.withDefaultNamespace("fishing_rod");
    @AfterEach void clear() { ClientRecipeStatusStore.clear(); }

    @Test void boundedDiagnosticCannotReplaceFullMissingOrGrantAutomaticRetry() throws Exception {
        var request = start(CraftRequest.PartialPolicy.EXPLICIT_SAFE);
        var evidence = evidence(request);
        ClientRecipeStatusStore.completeLocal(request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), evidence, false);
        assertTrue(ClientRecipeStatusStore.needsDiagnostic(RECIPE));
        long presentation = ClientRecipeStatusStore.presentationRevision();
        ClientRecipeStatusStore.completeLocal(request.withPartial(true), SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), evidence, true);
        assertEquals(CraftingResultCode.MISSING_INGREDIENTS, ClientRecipeStatusStore.fullReason(request));
        assertEquals(CraftingResultCode.SEARCH_BUDGET_EXCEEDED, ClientRecipeStatusStore.diagnosticReason(request));
        assertSame(evidence, ClientRecipeStatusStore.evidence(request));
        assertTrue(ClientRecipeStatusStore.computed(request));
        assertFalse(ClientRecipeStatusStore.exhausted(request));
        assertTrue(ClientRecipeStatusStore.diagnosticExhausted(request));
        assertFalse(ClientRecipeStatusStore.needsDiagnostic(RECIPE));
        assertEquals(CraftingResultCode.MISSING_INGREDIENTS, ClientRecipeStatusStore.displayReason(RECIPE));
        assertEquals(presentation, ClientRecipeStatusStore.presentationRevision());
        for (int i = 0; i < 100; i++) ClientRecipeStatusStore.authorizeLocal(true);
        assertTrue(ClientRecipeStatusStore.diagnosed(request));
        var maximum = ClientBrowsePlanner.maximumView(request, 1);
        assertTrue(maximum.proven()); assertFalse(maximum.limited()); assertFalse(maximum.pending());
        ClientRecipeStatusStore.beginRefresh();
        assertNull(ClientRecipeStatusStore.diagnosticReason(request));
        assertFalse(ClientRecipeStatusStore.computed(request));
    }

    @Test void preparationIsPresentationOnlyAndNeverCannotEnableIt() throws Exception {
        for (var policy : CraftRequest.PartialPolicy.values()) {
            var request = start(policy);
            var evidence = evidence(request);
            ClientRecipeStatusStore.completeLocal(request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), evidence, false);
            ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.PARTIAL, CraftingResultCode.PARTIAL_CREATED, evidence, true);
            assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(RECIPE, false));
            assertEquals(CraftingResultCode.MISSING_INGREDIENTS, ClientRecipeStatusStore.reason(request));
            assertEquals(CraftingResultCode.PARTIAL_CREATED, ClientRecipeStatusStore.diagnosticReason(request));
            assertEquals(policy == CraftRequest.PartialPolicy.NEVER ? CraftingStatus.BLOCKED : CraftingStatus.PARTIAL,
                    ClientRecipeStatusStore.display(RECIPE, false));
            assertFalse(ClientRecipeStatusStore.needsDiagnostic(RECIPE));
        }
    }

    @Test void laterDiagnosticCannotDowngradeKnownFullSuccess() throws Exception {
        var request = start(CraftRequest.PartialPolicy.EXPLICIT_SAFE);
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        long presentation = ClientRecipeStatusStore.presentationRevision();
        for (var code : new CraftingResultCode[] { CraftingResultCode.PARTIAL_CREATED, CraftingResultCode.SEARCH_BUDGET_EXCEEDED }) {
            ClientRecipeStatusStore.completeLocal(RECIPE, code == CraftingResultCode.PARTIAL_CREATED ? CraftingStatus.PARTIAL : CraftingStatus.BLOCKED,
                    code, evidence(request), true);
            assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.get(RECIPE, false));
            assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.display(RECIPE, false));
            assertEquals(CraftingResultCode.CREATED, ClientRecipeStatusStore.fullReason(request));
            assertEquals(presentation, ClientRecipeStatusStore.presentationRevision());
            assertNull(ClientRecipeStatusStore.evidence(request));
        }
    }

    @Test void diagnosticCompletionBelongsToExactFullIntent() throws Exception {
        var request = start(CraftRequest.PartialPolicy.EXPLICIT_SAFE);
        ClientRecipeStatusStore.completeLocal(request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), evidence(request), false);
        ClientRecipeStatusStore.completeLocal(request, SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED),
                ClientRecipeStatusStore.evidence(request), true);
        assertTrue(ClientRecipeStatusStore.diagnosticExhausted(request.withPartial(true)));
        assertFalse(ClientRecipeStatusStore.diagnosed(request.withBatches(2)));
        var constrained = new CraftRequest(RECIPE, 1, false, false, request.policy(), Map.of("0.0", RECIPE));
        assertFalse(ClientRecipeStatusStore.diagnosed(constrained));
        var never = new CraftRequest(RECIPE, 1, false, false, CraftRequest.PartialPolicy.NEVER, Map.of());
        assertNull(ClientRecipeStatusStore.diagnosticReason(never));
        assertNull(ClientRecipeStatusStore.evidence(never));
    }

    private static CraftRequest start(CraftRequest.PartialPolicy policy) {
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.defaults(false, policy);
        ClientRecipeStatusStore.authorizeLocal(true);
        return new CraftRequest(RECIPE, 1, false, false, policy, Map.of());
    }

    private static CraftSearch.FullEvidence evidence(CraftRequest request) throws Exception {
        var constructor = CraftSearch.FullEvidence.class.getDeclaredConstructor(CraftSearch.Reachability.class, CraftRequest.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new CraftSearch.Reachability(), request);
    }
}
