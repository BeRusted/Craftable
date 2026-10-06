package org.berusted.craftable.client.recipebook;

import java.util.HashMap;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.SearchResult;
import org.berusted.craftable.planner.CraftRequest;

/** Minimal conclusions for one resource scope. Authorization expires separately
 * from mathematical evidence: renewing unchanged values never restarts search. */
public final class ClientRecipeStatusStore {
    public enum Lifecycle { UNKNOWN, PENDING, KNOWN }
    private static final HashMap<CraftRequest, StatusEntry> STATUSES = new HashMap<>();
    // Presentation only: never contains proofs/plans and is never read by the solver.
    // Retain across resource replacement, but not menus, reloads or connections.
    private static final HashMap<ResourceLocation, DisplayEntry> DISPLAY = new HashMap<>();
    private static boolean drops = true;
    private static CraftRequest.PartialPolicy policy = CraftRequest.PartialPolicy.EXPLICIT_SAFE;
    private static long revision, authorityRevision, presentationRevision;
    private static boolean local, authorized;
    private static long retainedBytes, displayBytes;
    private static final long MAX_RESULT_BYTES = 2L * 1024 * 1024;
    private ClientRecipeStatusStore() {}

    public static CraftingStatus get(ResourceLocation id, boolean vanillaCraftable) {
        if (local && !authorized) return CraftingStatus.BLOCKED;
        var entry = STATUSES.get(key(id));
        return entry == null ? (vanillaCraftable && !local ? CraftingStatus.CRAFTABLE : CraftingStatus.BLOCKED) : entry.status();
    }

    public static Lifecycle lifecycle(ResourceLocation id) {
        if (local && !authorized) return Lifecycle.PENDING;
        if (!STATUSES.containsKey(key(id)) && DISPLAY.containsKey(id)) return Lifecycle.PENDING;
        return STATUSES.containsKey(key(id)) ? Lifecycle.KNOWN : Lifecycle.UNKNOWN;
    }

    public static CraftingStatus display(ResourceLocation id, boolean vanillaCraftable) {
        var entry = DISPLAY.get(id);
        return entry == null ? get(id, vanillaCraftable) : entry.status();
    }
    public static boolean hasDisplay(ResourceLocation id) { return DISPLAY.containsKey(id); }
    public static CraftingResultCode displayReason(ResourceLocation id) {
        var entry = DISPLAY.get(id);
        return entry == null ? reason(id) : entry.code();
    }

    public static CraftingResultCode reason(ResourceLocation id) {
        var entry = STATUSES.get(key(id));
        return entry == null ? CraftingResultCode.MISSING_INGREDIENTS : entry.code();
    }

    public static void invalidate(long ignoredLegacyBarrier) { authorizeLocal(false); }
    public static void clear() {
        local = authorized = false;
        STATUSES.clear();
        DISPLAY.clear();
        retainedBytes = displayBytes = 0;
        revision++; authorityRevision++; presentationRevision++;
    }
    public static long revision() { return revision; }
    public static long authorityRevision() { return authorityRevision; }
    public static long presentationRevision() { return presentationRevision; }
    public static void beginLocal() { clear(); local = true; }
    public static void beginRefresh() {
        // A resource change invalidates ALL execution evidence (capacity alone
        // can affect unrelated targets), not the last displayed button colors.
        STATUSES.clear(); retainedBytes = 0;
        local = true;
        authorizeLocal(false);
    }
    public static void authorizeLocal(boolean value) {
        if (local && authorized != value) { authorized = value; revision++; authorityRevision++; }
    }
    public static void defaults(boolean allowDrops, CraftRequest.PartialPolicy partialPolicy) {
        drops = allowDrops; policy = partialPolicy;
    }
    private static CraftRequest key(ResourceLocation id) { return new CraftRequest(id, 1, false, drops, policy, java.util.Map.of()); }
    public static boolean computed(ResourceLocation id) { return computed(key(id)); }
    public static boolean canRecord(ResourceLocation id) { return canRecord(key(id)); }
    public static boolean computed(CraftRequest request) { return STATUSES.containsKey(request.withPartial(false)); }
    public static boolean canRecord(CraftRequest request) {
        return computed(request) || STATUSES.size() < 16_384
                && retainedBytes + displayBytes + weight(request) + displayWeight(request.recipe()) <= MAX_RESULT_BYTES;
    }
    private static long displayWeight(ResourceLocation id) { return 128L + 2L * id.toString().length(); }
    private static long weight(CraftRequest request) {
        // Extended identities include constraint strings, not only a recipe ID.
        // Account them before insertion inside the existing 2 MiB result reserve.
        return 256L + 2L * request.recipe().toString().length() + request.selections().entrySet().stream()
                .mapToLong(e -> 128L + 2L * (e.getKey().length() + e.getValue().toString().length())).sum();
    }
    public static boolean exhausted(CraftRequest request) {
        var code = reason(request);
        return code == CraftingResultCode.SEARCH_BUDGET_EXCEEDED || code == CraftingResultCode.UNSUPPORTED_RECIPE;
    }
    public static CraftingResultCode reason(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false));
        return entry == null ? null : entry.code();
    }
    /** Full-only conclusions drive filtering and MAX. A bounded diagnostic
     * is separate completed work, not a revision of that conclusion. */
    public static CraftingResultCode fullReason(CraftRequest request) { return reason(request); }
    public static CraftingResultCode diagnosticReason(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false));
        return entry == null ? null : entry.diagnosticCode();
    }
    public static boolean diagnosed(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false));
        return entry != null && entry.diagnosed();
    }
    public static boolean diagnosticExhausted(CraftRequest request) {
        var code = diagnosticReason(request);
        return code == CraftingResultCode.SEARCH_BUDGET_EXCEEDED || code == CraftingResultCode.UNSUPPORTED_RECIPE;
    }
    public static boolean needsDiagnostic(ResourceLocation id) {
        var entry = STATUSES.get(key(id));
        return entry != null && !entry.diagnosed() && entry.evidence() != null && entry.status() != CraftingStatus.CRAFTABLE;
    }
    public static CraftSearch.FullEvidence evidence(ResourceLocation id) {
        return evidence(key(id));
    }
    public static CraftSearch.FullEvidence evidence(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false)); return entry == null ? null : entry.evidence();
    }
    public static void completeLocal(ResourceLocation id, SearchResult result, CraftSearch.FullEvidence evidence, boolean diagnosed) {
        completeLocal(id, result.status(), result.code(), evidence, diagnosed);
    }
    static void completeLocal(ResourceLocation id, CraftingStatus status, CraftingResultCode code,
            CraftSearch.FullEvidence evidence, boolean diagnosed) {
        put(key(id), status, code, evidence, diagnosed);
    }
    public static void completeLocal(CraftRequest request, SearchResult result, CraftSearch.FullEvidence evidence, boolean diagnosed) {
        put(request.withPartial(false), result.status(), result.code(), evidence, diagnosed);
    }
    private static void put(CraftRequest request, CraftingStatus status, CraftingResultCode code,
            CraftSearch.FullEvidence evidence, boolean diagnosed) {
        if (!local || !canRecord(request)) return;
        // Plans/frontiers are never retained in the catalog-wide result store.
        // A bounded/unknown conclusion is still completed work, not a retry cue.
        var previous = STATUSES.get(request);
        StatusEntry next;
        if (diagnosed) {
            // A diagnostic may also have completed its full phase before
            // entering preparation. Preserve the proof even when that later
            // phase is bounded, and never downgrade an existing full success.
            var fullStatus = previous == null ? (status == CraftingStatus.PARTIAL ? CraftingStatus.BLOCKED : status) : previous.status();
            var fullCode = previous == null ? (status == CraftingStatus.PARTIAL ? CraftingResultCode.SEARCH_BUDGET_EXCEEDED : code) : previous.code();
            var fullEvidence = previous == null ? evidence : previous.evidence();
            if (fullStatus != CraftingStatus.CRAFTABLE && fullCode != CraftingResultCode.CREATED) {
                if (code == CraftingResultCode.CREATED) {
                    fullStatus = CraftingStatus.CRAFTABLE; fullCode = code; fullEvidence = null;
                } else if (evidence != null) {
                    fullStatus = CraftingStatus.BLOCKED; fullCode = CraftingResultCode.MISSING_INGREDIENTS; fullEvidence = evidence;
                }
            }
            next = new StatusEntry(fullStatus, fullCode, fullEvidence, status, code, true);
        } else {
            next = new StatusEntry(status, code, evidence, null, null, false);
        }
        if (!STATUSES.containsKey(request)) retainedBytes += weight(request);
        if (!next.equals(STATUSES.put(request, next))) revision++;
        if (request.equals(key(request.recipe()))) {
            if (!DISPLAY.containsKey(request.recipe())) displayBytes += displayWeight(request.recipe());
            // Preparation is useful presentation, not proof of a complete
            // craft. NEVER permits reading it in details, not a partial action.
            boolean preparation = next.status() != CraftingStatus.CRAFTABLE && next.code() != CraftingResultCode.CREATED
                    && next.diagnosticStatus() == CraftingStatus.PARTIAL && request.policy() != CraftRequest.PartialPolicy.NEVER;
            var display = new DisplayEntry(preparation ? CraftingStatus.PARTIAL : next.status(),
                    preparation ? next.diagnosticCode() : next.code());
            if (!display.equals(DISPLAY.put(request.recipe(), display))) presentationRevision++;
        }
    }
    private record DisplayEntry(CraftingStatus status, CraftingResultCode code) {}
    private record StatusEntry(CraftingStatus status, CraftingResultCode code,
            CraftSearch.FullEvidence evidence, CraftingStatus diagnosticStatus,
            CraftingResultCode diagnosticCode, boolean diagnosed) {}
}
