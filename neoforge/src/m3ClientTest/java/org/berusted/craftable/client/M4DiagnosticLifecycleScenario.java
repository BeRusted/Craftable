package org.berusted.craftable.client;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.environment.BrowsingSnapshot;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.planner.SearchResult;
import org.berusted.craftable.recipe.PlanningInput;

/** Production status-store/detail transitions over the active, authorized
 * frozen values. Only fixture continuations receive logical budgets: no fake
 * lease, world resources, execution token, alternate scheduler or solver. */
final class M4DiagnosticLifecycleScenario {
    private static final ResourceLocation ROD = ResourceLocation.withDefaultNamespace("fishing_rod");
    private static final long SEQUENCE = Long.MAX_VALUE - 42;

    static void verify() throws Exception {
        require(ClientBrowsePlanner.ready(), "Diagnostic lifecycle needs the real authorized snapshot");
        var snapshot = (BrowsingSnapshot) get(ClientBrowsePlanner.class, null, "snapshot");
        Object values = snapshot.contentIdentity();
        require(snapshot.sources().stream().anyMatch(s -> s.stack().is(Items.OAK_LOG))
                && snapshot.sources().stream().noneMatch(s -> s.stack().is(Items.STRING)),
                "Diagnostic lifecycle fixture lost its useful preparation/missing-string boundary");
        var scheduler = save(ClientBrowsePlanner.class, "front", "background", "detail", "retainedRequest",
                "retainedResult", "closure", "policy", "searches");
        var verdicts = save(ClientRecipeStatusStore.class, "STATUSES", "DISPLAY", "drops", "policy", "revision",
                "authorityRevision", "presentationRevision", "local", "authorized", "retainedBytes", "displayBytes");
        try {
            set(ClientBrowsePlanner.class, null, "front", null);
            set(ClientBrowsePlanner.class, null, "background", null);
            resetDetails();
            var closure = new CraftSearch.Reachability();
            set(CraftSearch.Reachability.class, closure, "budget", logical(SearchBudget.MAX_STATES));
            set(ClientBrowsePlanner.class, null, "closure", closure);
            verifyPromotedBoundedDiagnostic(snapshot);
            verifyNeverReadOnly(snapshot);
            require(snapshot.contentIdentity().equals(values), "Diagnostic fixture changed resource values");
        } finally {
            restore(ClientBrowsePlanner.class, scheduler);
            restore(ClientRecipeStatusStore.class, verdicts);
        }
        System.out.println("M4_DIAGNOSTIC_LIFECYCLE PASS fullMissing/diagnosticUnknown=separate promotedContinuation=shared retry=terminal never=readOnly witness=none");
    }

    private static void verifyPromotedBoundedDiagnostic(BrowsingSnapshot snapshot) throws Exception {
        var policy = (CraftRequest.PartialPolicy) get(ClientBrowsePlanner.class, null, "policy");
        boolean drops = (boolean) get(ClientBrowsePlanner.class, null, "drops");
        var request = new CraftRequest(ROD, 1, false, drops, policy, Map.of());
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.defaults(drops, policy);
        ClientRecipeStatusStore.authorizeLocal(true);
        var hidden = invoke("task", new Class<?>[] { ResourceLocation.class, boolean.class }, ROD, false);
        set(ClientBrowsePlanner.class, null, "background", hidden);
        var full = search(hidden);
        logicalFull(full);
        ClientBrowsePlanner.preview(SEQUENCE, request, "");
        var promoted = invoke("detailTask", new Class<?>[0]);
        require(promoted != null && search(promoted) == full && get(ClientBrowsePlanner.class, null, "background") == null,
                "Detail did not transfer the existing hidden continuation");
        var missing = full.run();
        require(missing.code() == CraftingResultCode.MISSING_INGREDIENTS && full.fullEvidence() != null,
                "Real promoted FULL_ONLY did not prove the missing string: " + missing.code());
        finish(promoted, missing);
        require(get(ClientBrowsePlanner.class, null, "detail") != null
                && get(ClientBrowsePlanner.class, null, "retainedResult") == null,
                "Full failure skipped its diagnostic upgrade");
        var diagnostic = invoke("detailTask", new Class<?>[0]);
        var partial = search(diagnostic);
        partial.withDiagnosticBudget(logical(1));
        var unknown = partial.run();
        require(unknown.code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                && partial.fullSearches() == 0 && partial.partialSearches() == 1,
                "Diagnostic upgrade repeated full work or escaped its own allowance");
        finish(diagnostic, unknown);
        require(ClientRecipeStatusStore.fullReason(request) == CraftingResultCode.MISSING_INGREDIENTS
                && ClientRecipeStatusStore.diagnosticReason(request) == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                && !ClientRecipeStatusStore.exhausted(request) && !ClientRecipeStatusStore.needsDiagnostic(ROD),
                "Diagnostic unknown contaminated the full verdict or re-queued hover work");
        require(ClientRecipeStatusStore.displayReason(ROD) == CraftingResultCode.MISSING_INGREDIENTS
                && ClientRecipeStatusStore.get(ROD, false) == CraftingStatus.BLOCKED,
                "Recipe-book presentation lost its proven missing-material conclusion");
        long searches = ClientBrowsePlanner.searches();
        require(invoke("detailTask", new Class<?>[0]) == null
                && ((SearchResult) get(ClientBrowsePlanner.class, null, "retainedResult")).code()
                        == CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                "Completed unknown was not retained for detail projection");
        require(get(ClientBrowsePlanner.class, null, "detail") == null, "Unknown detail did not finish publishing");
        // Evict the one permitted detail result, then revisit the exact intent.
        // Its minimal diagnostic terminal must still prevent a new allowance.
        resetDetails();
        ClientRecipeStatusStore.authorizeLocal(false); ClientRecipeStatusStore.authorizeLocal(true);
        ClientBrowsePlanner.preview(SEQUENCE + 1, request, "0");
        require(invoke("detailTask", new Class<?>[0]) == null && ClientBrowsePlanner.searches() == searches
                && get(ClientBrowsePlanner.class, null, "detail") == null,
                "Reopening/renewing an exhausted diagnostic manufactured a fresh search");
        require(snapshot.sources().stream().noneMatch(s -> s.stack().is(Items.STRING)), "Detail mutated frozen resources");
    }

    private static void verifyNeverReadOnly(BrowsingSnapshot snapshot) throws Exception {
        resetDetails();
        set(ClientBrowsePlanner.class, null, "policy", CraftRequest.PartialPolicy.NEVER);
        boolean drops = (boolean) get(ClientBrowsePlanner.class, null, "drops");
        var request = new CraftRequest(ROD, 1, false, drops, CraftRequest.PartialPolicy.NEVER, Map.of());
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.defaults(drops, request.policy());
        ClientRecipeStatusStore.authorizeLocal(true);
        // The production closure shortcut must issue exactly the same SAFE
        // mathematical identity as the subsequent read-only diagnostic.
        require(Boolean.FALSE.equals(invoke("needsSearch", new Class<?>[] { ResourceLocation.class }, ROD))
                && ClientRecipeStatusStore.evidence(request) != null,
                "Known closure exclusion did not publish a full proof under NEVER");
        var target = (CraftRequest) get(CraftSearch.FullEvidence.class, ClientRecipeStatusStore.evidence(request), "target");
        require(target.policy() == CraftRequest.PartialPolicy.EXPLICIT_SAFE && !target.partial(),
                "Closure proof used a different mathematical identity from diagnostics");
        ClientBrowsePlanner.preview(SEQUENCE + 2, request, "");
        var diagnostic = invoke("detailTask", new Class<?>[0]);
        var search = search(diagnostic);
        search.withDiagnosticBudget(logical(SearchBudget.MAX_STATES));
        var result = search.run();
        require(result.code() == CraftingResultCode.PARTIAL_CREATED && result.plan().orElseThrow().partial()
                && search.fullSearches() == 0 && search.partialSearches() == 1,
                "NEVER prevented read-only preparation or repeated the full proof: " + result.code());
        var intent = (CraftRequest) get(diagnostic.getClass(), diagnostic, "request");
        require(intent.policy() == CraftRequest.PartialPolicy.NEVER, "Diagnostic computation promoted player execution policy");
        finish(diagnostic, result);
        require(ClientRecipeStatusStore.fullReason(request) == CraftingResultCode.MISSING_INGREDIENTS
                && ClientRecipeStatusStore.diagnosticReason(request) == CraftingResultCode.PARTIAL_CREATED
                && ClientRecipeStatusStore.display(ROD, false) == CraftingStatus.BLOCKED,
                "Read-only preparation enabled a partial recipe-book action under NEVER");
        require(ClientBrowsePlanner.witness(request) == null, "Read-only preparation exported execution authority");
        require(invoke("detailTask", new Class<?>[0]) == null
                && ((SearchResult) get(ClientBrowsePlanner.class, null, "retainedResult")).plan().orElseThrow().partial(),
                "NEVER detail did not retain/project its read-only preparation");
        require(snapshot == get(ClientBrowsePlanner.class, null, "snapshot"), "Diagnostic replaced authorized values");
    }

    private static void resetDetails() throws Exception {
        set(ClientBrowsePlanner.class, null, "detail", null);
        set(ClientBrowsePlanner.class, null, "retainedRequest", null);
        set(ClientBrowsePlanner.class, null, "retainedResult", null);
    }
    private static SearchBudget logical(int states) { return SearchBudget.resumable(() -> 0L, 1_000_000_000L, states); }
    private static void logicalFull(CraftSearch search) throws Exception {
        var budget = logical(SearchBudget.MAX_STATES);
        set(CraftSearch.class, search, "fullBudget", budget);
        set(CraftSearch.class, search, "budget", budget);
    }
    private static CraftSearch search(Object task) throws Exception {
        require(task != null, "Expected a production detail task");
        return (CraftSearch) get(task.getClass(), task, "search");
    }
    private static void finish(Object task, SearchResult result) throws Exception {
        invoke("finishDetail", new Class<?>[] { task.getClass(), SearchResult.class }, task, result);
    }
    private static Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = ClientBrowsePlanner.class.getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(null, arguments);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Class<?> type, Object instance, String name) throws Exception { return field(type, name).get(instance); }
    private static void set(Class<?> type, Object instance, String name, Object value) throws Exception { field(type, name).set(instance, value); }
    private static Map<String, Object> save(Class<?> type, String... names) throws Exception {
        var saved = new LinkedHashMap<String, Object>();
        for (String name : names) {
            var value = get(type, null, name);
            saved.put(name, value instanceof Map<?, ?> map ? new HashMap<>(map) : value);
        }
        return saved;
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void restore(Class<?> type, Map<String, Object> values) throws Exception {
        for (var entry : values.entrySet()) {
            var existing = get(type, null, entry.getKey());
            if (existing instanceof Map map) { map.clear(); map.putAll((Map) entry.getValue()); }
            else set(type, null, entry.getKey(), entry.getValue());
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
