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
    private static boolean drops = true;
    private static CraftRequest.PartialPolicy policy = CraftRequest.PartialPolicy.EXPLICIT_SAFE;
    private static long revision, authorityRevision;
    private static boolean local, authorized;
    private static long retainedBytes;
    private static final long MAX_RESULT_BYTES = 2L * 1024 * 1024;
    private ClientRecipeStatusStore() {}

    public static CraftingStatus get(ResourceLocation id, boolean vanillaCraftable) {
        if (local && !authorized) return CraftingStatus.BLOCKED;
        var entry = STATUSES.get(key(id));
        return entry == null ? (vanillaCraftable && !local ? CraftingStatus.CRAFTABLE : CraftingStatus.BLOCKED) : entry.status();
    }

    public static Lifecycle lifecycle(ResourceLocation id) {
        if (local && !authorized) return Lifecycle.PENDING;
        return STATUSES.containsKey(key(id)) ? Lifecycle.KNOWN : Lifecycle.UNKNOWN;
    }

    public static CraftingResultCode reason(ResourceLocation id) {
        var entry = STATUSES.get(key(id));
        return entry == null ? CraftingResultCode.MISSING_INGREDIENTS : entry.code();
    }

    public static void invalidate(long ignoredLegacyBarrier) { authorizeLocal(false); }
    public static void clear() {
        local = authorized = false;
        STATUSES.clear();
        retainedBytes = 0;
        revision++; authorityRevision++;
    }
    public static long revision() { return revision; }
    public static long authorityRevision() { return authorityRevision; }
    public static void beginLocal() { clear(); local = true; }
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
        return computed(request) || STATUSES.size() < 16_384 && retainedBytes + weight(request) <= MAX_RESULT_BYTES;
    }
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
    public static boolean needsDiagnostic(ResourceLocation id) {
        var entry = STATUSES.get(key(id));
        return entry != null && !entry.diagnosed() && entry.evidence() != null;
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
        var next = new StatusEntry(status, code, evidence, diagnosed);
        if (!STATUSES.containsKey(request)) retainedBytes += weight(request);
        if (!next.equals(STATUSES.put(request, next))) revision++;
    }
    private record StatusEntry(CraftingStatus status, CraftingResultCode code,
            CraftSearch.FullEvidence evidence, boolean diagnosed) {}
}
