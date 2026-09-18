package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.planner.SearchResult;
import org.berusted.craftable.recipe.CraftingRecipes;
import org.berusted.craftable.workstation.WorkstationCapability;

/** Only server facade for planning and committing a chain; UI previews never authorize writes. */
public final class CraftingService {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private CraftingService() {}

    public static SearchResult evaluate(ServerPlayer player, ResourceLocation recipeId, EnvironmentSnapshot snapshot) {
        if (!validContext(player)) return SearchResult.blocked(CraftingResultCode.INVALID_CONTEXT);
        CraftRequest request = new CraftRequest(recipeId, 1, true, true,
                CraftRequest.PartialPolicy.EXPLICIT_SAFE, java.util.Map.of());
        return prepare(player, request, snapshot, new SearchBudget(8_000_000L)).result;
    }

    public static SearchResult evaluate(ServerPlayer player, ResourceLocation recipeId, EnvironmentSnapshot snapshot, SearchBudget budget) {
        if (!validContext(player)) return SearchResult.blocked(CraftingResultCode.INVALID_CONTEXT);
        var request = new CraftRequest(recipeId, 1, true, true, CraftRequest.PartialPolicy.EXPLICIT_SAFE, java.util.Map.of());
        return prepare(player, request, snapshot, budget).result;
    }

    public static CraftingResultCode createOne(ServerPlayer player, ResourceLocation recipeId) {
        return create(player, new CraftRequest(recipeId, 1, false, true,
                CraftRequest.PartialPolicy.EXPLICIT_SAFE, java.util.Map.of())).code();
    }

    public static Outcome create(ServerPlayer player, CraftRequest request) {
        return create(player, request, false, null);
    }

    public static Outcome attempt(ServerPlayer player, net.minecraft.resources.ResourceLocation recipe,
            long sequence, long precedingPress, boolean allowDrops, CraftRequest.PartialPolicy policy) {
        if (!validContext(player)) return Outcome.failed(CraftingResultCode.INVALID_CONTEXT);
        if (!CraftingSessions.acceptSequence(player, sequence)) return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        boolean partial = CraftingSessions.partialGesture(player, recipe, precedingPress);
        Outcome outcome = create(player, new CraftRequest(recipe, 1, partial, allowDrops, policy, java.util.Map.of()));
        CraftingSessions.recordAttempt(player, recipe, sequence, outcome.code(), partial);
        return outcome;
    }

    public static Draft preview(ServerPlayer player, CraftRequest request) {
        return preview(player, request, "");
    }

    public static Draft preview(ServerPlayer player, CraftRequest request, String choicePath) {
        if (!validContext(player)) return new Draft(new UUID(0, 0),
                org.berusted.craftable.planner.PlanView.failed(CraftingResultCode.INVALID_CONTEXT),
                new org.berusted.craftable.planner.PlanView.Choices(List.of(), false));
        CraftingSessions.discardOffer(player);
        try {
            var snapshot = EnvironmentSnapshotService.fresh(player);
            var prepared = prepare(player, request, snapshot, new SearchBudget(8_000_000L));
            var view = org.berusted.craftable.planner.PlanView.from(prepared.recipes(), prepared.request(),
                    prepared.result(), prepared.delivery().drops(), sources(snapshot).stream().map(ResourceLedger.Source::stack)
                            .filter(s -> !CraftingRecipes.protectedStack(s)).toList());
            // An incomplete display must never authorize an undisclosed plan.
            UUID token = view.complete() && (!view.primary().isEmpty())
                    && (prepared.result().plan().filter(CraftPlan::partial).isEmpty()
                        || request.partial() && prepared.request().policy() != CraftRequest.PartialPolicy.NEVER)
                    ? CraftingSessions.offer(player, prepared) : new UUID(0, 0);
            return new Draft(token, view, view.choices(prepared.recipes(), prepared.request(), choicePath));
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Plan preview failed for {}", request.recipe(), exception);
            return new Draft(new UUID(0, 0), org.berusted.craftable.planner.PlanView.failed(CraftingResultCode.INTERNAL_ERROR),
                    new org.berusted.craftable.planner.PlanView.Choices(List.of(), false));
        }
    }

    public static Outcome confirm(ServerPlayer player, UUID token) {
        var confirmation = CraftingSessions.take(player, token);
        if (confirmation == null) return Outcome.failed(CraftingResultCode.CONFIRMATION_EXPIRED);
        return create(player, confirmation.request(), true, null, confirmation);
    }

    // Deterministic GameTests supply a generous deadline; production callers
    // always use the bounded entry above. Performance tests exercise real caps.
    static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget) {
        return create(player, request, confirmed, budget, null);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget,
            CraftingSessions.Confirmation confirmation) {
        if (!validContext(player)) return Outcome.failed(CraftingResultCode.INVALID_CONTEXT);
        if (!ACTIVE.add(player.getUUID())) return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        try {
            var snapshot = EnvironmentSnapshotService.fresh(player);
            // Scanning has its own bounded M1 radius/volume. Start the search
            // deadline after scanning, while total server admission accounts
            // for the complete request separately.
            Prepared prepared = prepare(player, request, snapshot,
                    budget == null ? new SearchBudget(8_000_000L) : budget);
            if (confirmation != null && !confirmation.matches(prepared)) {
                return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
            }
            var result = prepared.result;
            if (result.plan().isEmpty()) return new Outcome(result.code(), null, result.missing(), List.of());
            CraftPlan plan = result.plan().get();
            if (plan.partial()) {
                if (!request.partial() || prepared.request.policy() == CraftRequest.PartialPolicy.NEVER) {
                    return new Outcome(CraftingResultCode.MISSING_INGREDIENTS, null, plan.missing(), List.of());
                }
                if (!confirmed && (prepared.request.policy() == CraftRequest.PartialPolicy.CONFIRM || !plan.safePartial())) {
                    return new Outcome(CraftingResultCode.CONFIRMATION_REQUIRED, plan, plan.missing(), prepared.delivery.drops());
                }
            }
            if (!validContext(player) || !prepared.rules.equals(CraftableServerConfig.craftingRules())
                    || prepared.recipes.generation() != new CraftingRecipes(player, workbench(player, snapshot)).generation()) {
                return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
            }
            var code = CraftingTransaction.execute(player, plan, snapshot, prepared.delivery);
            return new Outcome(code, plan, plan.missing(),
                    code == CraftingResultCode.CREATED || code == CraftingResultCode.PARTIAL_CREATED
                            ? prepared.delivery.drops() : List.of());
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Crafting chain request failed for {} and {}", player.getUUID(), request.recipe(), exception);
            return Outcome.failed(CraftingResultCode.INTERNAL_ERROR);
        } finally {
            ACTIVE.remove(player.getUUID());
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }

    static Prepared prepare(ServerPlayer player, CraftRequest requested, EnvironmentSnapshot snapshot, SearchBudget budget) {
        return prepare(player, requested, snapshot, budget, true);
    }

    private static Prepared prepare(ServerPlayer player, CraftRequest requested, EnvironmentSnapshot snapshot,
            SearchBudget budget, boolean diagnostics) {
        var rules = CraftableServerConfig.craftingRules();
        CraftRequest effective = new CraftRequest(requested.recipe(), requested.batches(), requested.partial(),
                requested.allowDrops() && rules.surplusDelivery() == CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW,
                requested.policy().restrict(rules.partialPolicy()), requested.selections());
        var recipes = new CraftingRecipes(player, workbench(player, snapshot));
        if (effective.batches() > rules.maxBatches()) {
            return new Prepared(SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), effective,
                    recipes, rules, MainInventoryInsertion.Delivery.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
        }
        var sources = sources(snapshot);
        // Even FULL_ONLY obtains read-only preparation diagnostics. Intent is
        // checked again before commit; a partial preview is never an implicit C
        // authorization. This also reports the whole diamond deficit, not just
        // whichever missing ingredient happened to be visited first.
        var diagnostic = new CraftRequest(effective.recipe(), effective.batches(), diagnostics, effective.allowDrops(),
                CraftRequest.PartialPolicy.EXPLICIT_SAFE, effective.selections());
        var mainSlots = MainInventoryInsertion.copyMainInventory(player.getInventory());
        int inventoryMaximum = player.getInventory().getMaxStackSize();
        var playerEndpoints = snapshot.endpoints().stream().filter(e -> e.kind() == org.berusted.craftable.environment.EndpointKind.PLAYER)
                .map(e -> e.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var endpointIds = snapshot.endpoints().stream().map(e -> e.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        SearchResult result = new CraftSearch(recipes, diagnostic, sources, budget,
                plan -> MainInventoryInsertion.simulate(mainSlots, inventoryMaximum, plan, playerEndpoints, endpointIds, effective.allowDrops()).failure()).run();
        if (result.plan().isPresent()) {
            boolean valid = recipes.validate(result.plan().get());
            var code = !budget.alive() ? CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                    : !valid ? CraftingResultCode.UNSUPPORTED_RECIPE : null;
            if (code != null) return new Prepared(SearchResult.blocked(code), effective, recipes, rules,
                    MainInventoryInsertion.Delivery.failed(code));
        }
        var delivery = result.plan().map(plan ->
                MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, effective.allowDrops()))
                .orElseGet(() -> MainInventoryInsertion.Delivery.failed(result.code()));
        return new Prepared(result, effective, recipes, rules, delivery);
    }

    private static List<ResourceLedger.Source> sources(EnvironmentSnapshot snapshot) {
        var sources = new ArrayList<ResourceLedger.Source>();
        for (var endpoint : snapshot.endpoints()) {
            for (int slot = endpoint.firstSlot(); slot < endpoint.firstSlot() + endpoint.slotCount(); slot++) {
                var stack = endpoint.container().getItem(slot);
                if (!stack.isEmpty()) sources.add(new ResourceLedger.Source(endpoint.id(), slot, stack));
            }
        }
        return sources;
    }

    /** One value view per batch; verified unchanged batches share closure/evidence, never reservations. */
    public static BatchPreview previews(ServerPlayer player, EnvironmentSnapshot snapshot) {
        return new BatchPreview(player, snapshot, true, CraftRequest.PartialPolicy.EXPLICIT_SAFE);
    }

    public static BatchPreview previews(ServerPlayer player, EnvironmentSnapshot snapshot,
            boolean allowDrops, CraftRequest.PartialPolicy policy) {
        return new BatchPreview(player, snapshot, allowDrops, policy);
    }

    public record Verdict(org.berusted.craftable.api.CraftingStatus status, CraftingResultCode code) {}

    record CachedVerdict(Verdict verdict, long retryAfter, int attempts,
            boolean diagnostic, CraftSearch.FullEvidence fullEvidence) {}

    public static final class BatchPreview {
        private final ServerPlayer player;
        private final CraftingRecipes recipes;
        private final List<ResourceLedger.Source> sources;
        private final CraftableServerConfig.CraftingRules rules;
        private final CraftingSessions.PreviewState cache;
        private final List<net.minecraft.world.item.ItemStack> mainSlots;
        private final int inventoryMaximum;
        private final java.util.Set<String> playerEndpoints;
        private final java.util.Set<String> endpointIds;
        private final boolean allowDrops;
        private final CraftRequest.PartialPolicy policy;

        private BatchPreview(ServerPlayer player, EnvironmentSnapshot snapshot, boolean allowDrops, CraftRequest.PartialPolicy policy) {
            this.player = player;
            recipes = new CraftingRecipes(player, workbench(player, snapshot));
            sources = sources(snapshot);
            mainSlots = MainInventoryInsertion.copyMainInventory(player.getInventory());
            inventoryMaximum = player.getInventory().getMaxStackSize();
            playerEndpoints = snapshot.endpoints().stream().filter(e -> e.kind() == org.berusted.craftable.environment.EndpointKind.PLAYER)
                    .map(e -> e.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            endpointIds = snapshot.endpoints().stream().map(e -> e.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            rules = CraftableServerConfig.craftingRules();
            this.allowDrops = allowDrops && rules.surplusDelivery() == CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW;
            this.policy = policy.restrict(rules.partialPolicy());
            Object identity = List.of(recipes.accessIdentity(), rules, this.allowDrops, this.policy, snapshot.dimension(), snapshot.origin(), snapshot.scanSettings(),
                    sources.stream().map(s -> List.of(s.endpointId(), s.slot(), CraftPlan.stackKeys(List.of(s.stack())))).toList(),
                    // Empty slots and player-vs-external provenance affect net
                    // delivery even when the list of ingredient stacks agrees.
                    List.of(CraftPlan.stackKeys(mainSlots), inventoryMaximum, playerEndpoints, endpointIds));
            cache = CraftingSessions.previews(player, identity);
        }

        /** Cold recipe matching may exceed 1 ms. Retries borrow a larger slice,
         * never a larger page/server budget, instead of repeating the same
         * deadline forever. Cache hits need no search allowance. */
        public long allowance(ResourceLocation id, long remaining) {
            return allowance(id, remaining, true);
        }

        public long allowance(ResourceLocation id, long remaining, boolean diagnostics) {
            var cached = cache.results.get(id);
            if (cached != null && (!diagnostics && cached.fullEvidence() != null
                    || reusable(cached, diagnostics) && (cached.attempts() == 0 || player.level().getGameTime() < cached.retryAfter())))
                return Math.min(remaining, 1L); // Cached metadata requires no planning slice.
            int attempts = cached == null ? 0 : Math.min(2, cached.attempts());
            return Math.min(remaining, 1_000_000L << attempts);
        }

        public Verdict evaluate(ResourceLocation id, SearchBudget budget) {
            return evaluate(id, budget, true);
        }

        /** FULL_ONLY is internal until the snapshot protocol can represent its
         * lower precision. Do not publish a full-negative as a three-state
         * BLOCKED diagnosis on the legacy recipe-book wire. */
        public Verdict evaluate(ResourceLocation id, SearchBudget budget, boolean diagnostics) {
            if (!validContext(player)) return new Verdict(org.berusted.craftable.api.CraftingStatus.BLOCKED, CraftingResultCode.INVALID_CONTEXT);
            if (recipes.find(id) == null) return new Verdict(org.berusted.craftable.api.CraftingStatus.BLOCKED, CraftingResultCode.UNSUPPORTED_RECIPE);
            var cached = cache.results.get(id);
            if (cached == null && cache.results.size() >= CraftingSessions.PreviewState.MAX_RESULTS)
                return new Verdict(org.berusted.craftable.api.CraftingStatus.BLOCKED, CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            long now = player.level().getGameTime();
            if (!diagnostics && cached != null && cached.fullEvidence() != null) {
                cache.hits++;
                return new Verdict(org.berusted.craftable.api.CraftingStatus.BLOCKED, CraftingResultCode.MISSING_INGREDIENTS);
            }
            if (cached != null && reusable(cached, diagnostics) && (cached.attempts() == 0 || now < cached.retryAfter())) {
                cache.hits++;
                return cached.verdict();
            }
            var request = new CraftRequest(id, 1, diagnostics, allowDrops, policy, java.util.Map.of());
            var search = new CraftSearch(recipes, request, sources, budget,
                    plan -> MainInventoryInsertion.simulate(mainSlots, inventoryMaximum, plan,
                            playerEndpoints, endpointIds, request.allowDrops()).failure())
                    .withReachability(cache.reachability)
                    .withFullEvidence(cached == null ? null : cached.fullEvidence());
            var result = search.run();
            cache.fullSearches += search.fullSearches();
            cache.partialSearches += search.partialSearches();
            var verdict = new Verdict(result.status(), result.code());
            var evidence = search.fullEvidence() != null ? search.fullEvidence() : cached == null ? null : cached.fullEvidence();
            if (Boolean.getBoolean("craftable.m3Smoke") && id.getPath().equals("stick"))
                org.berusted.craftable.Craftable.LOGGER.warn("M4_CLIENT server stick code={} states={} cachedAttempts={}",
                        result.code(), result.visitedStates(), cached == null ? 0 : cached.attempts());
            // Completed proofs and limited retry metadata contain no executable
            // plan. Five exhausted passive attempts stop until this exact
            // resource/rule identity changes; explicit C/details remain fresh.
            if (result.completeSearch()) {
                cache.put(id, new CachedVerdict(verdict, Long.MAX_VALUE, 0, diagnostics, evidence));
            } else {
                int attempts = cached == null ? 1 : cached.attempts() + 1;
                cache.put(id, new CachedVerdict(verdict,
                        attempts >= 5 ? Long.MAX_VALUE : now + 4, attempts, diagnostics, evidence));
            }
            return verdict;
        }

        private static boolean reusable(CachedVerdict cached, boolean diagnostics) {
            return !diagnostics || cached.diagnostic()
                    || cached.verdict().status() == org.berusted.craftable.api.CraftingStatus.CRAFTABLE
                    || cached.attempts() == 0 && cached.verdict().code() != CraftingResultCode.MISSING_INGREDIENTS;
        }

        // Small counters establish structural reuse without retaining plans or
        // timing world calls with a second, test-only planning algorithm.
        public PreviewStats stats() {
            return new PreviewStats(cache.reachability.builds(), cache.reachability.attempts(), cache.reachability.buildNanos(),
                    cache.fullSearches, cache.partialSearches, cache.hits, cache.results.size());
        }
    }

    public record PreviewStats(int closureBuilds, int closureAttempts, long closureNanos,
            long fullSearches, long partialSearches, long hits, int results) {}

    public static Maximum maximum(ServerPlayer player, CraftRequest requested) {
        if (!validContext(player)) return new Maximum(0, false, 0, true, false);
        var snapshot = EnvironmentSnapshotService.fresh(player);
        var catalog = new CraftingRecipes(player, workbench(player, snapshot));
        var rules = CraftableServerConfig.craftingRules();
        // Capacity is not monotone: consuming a larger input stack can free a
        // slot. Prove each bounded count instead of binary-searching a predicate
        // that can be false/true/false. Across requests keep only verdicts plus
        // exact snapshot values, never a live ledger or a reserved inventory.
        Object identity = java.util.List.of(
                new CraftRequest(requested.recipe(), 1, false, requested.allowDrops(), requested.policy(), requested.selections()),
                catalog.accessIdentity(), rules, player.containerMenu, player.level().dimension(),
                snapshot.origin(), snapshot.scanSettings(), workbench(player, snapshot),
                snapshot.endpoints().stream().map(endpoint -> {
                    var values = new ArrayList<net.minecraft.world.item.ItemStack>();
                    for (int i = endpoint.firstSlot(); i < endpoint.firstSlot() + endpoint.slotCount(); i++)
                        values.add(endpoint.container().getItem(i));
                    return java.util.List.of(endpoint.id(), CraftPlan.stackKeys(values));
                }).toList());
        var progress = CraftingSessions.maximum(player, identity, rules.maxBatches());
        // Bound the whole progressive query, not just each count's retries.
        // Unexamined higher counts remain unknown (capacity is non-monotone).
        if (progress.next <= progress.cap && progress.slices++ >= SearchBudget.MAX_MAXIMUM_SLICES) {
            progress.highestUnknown = progress.cap;
            progress.next = progress.cap + 1;
        }
        var budget = new SearchBudget(8_000_000L);
        while (progress.next <= progress.cap && budget.alive()) {
            int count = progress.next;
            int startingStates = budget.states();
            var result = prepare(player, requested.withBatches(count).withPartial(false), snapshot, budget, false).result();
            if (result.plan().isPresent() && !result.plan().get().partial()) {
                progress.lowerBound = count;
            } else if (!result.completeSearch()) {
                if (startingStates > 0) break; // Retry with a fresh shared slice, not leftover time.
                // Cold JVM/GC pauses are not a permanent inability proof.
                // Give this count at most three separate request slices; never
                // restart another full 8 ms search inside the current request.
                if (progress.retries++ < 2) break;
                // An interrupted count remains unknown, not infeasible. Once
                // higher counts succeed they subsume this lower uncertainty.
                progress.highestUnknown = count;
            }
            progress.retries = 0;
            progress.next++;
            if (!budget.alive()) break;
        }
        return new Maximum(progress.lowerBound, progress.proven(), progress.cap,
                progress.highestUnknown > progress.lowerBound, progress.next <= progress.cap);
    }

    public record Maximum(int lowerBound, boolean proven, int cap, boolean limited, boolean pending) {}

    public static boolean validContext(ServerPlayer player) {
        return org.berusted.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())
                && !player.isDeadOrDying()
                && (player.containerMenu == player.inventoryMenu
                    || player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu)
                && player.containerMenu.stillValid(player)
                && player.serverLevel().getServer().isSameThread();
    }

    private static boolean workbench(ServerPlayer player, EnvironmentSnapshot snapshot) {
        return snapshot.supports(WorkstationCapability.CRAFTING_3X3)
                || player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu;
    }

    record Prepared(SearchResult result, CraftRequest request, CraftingRecipes recipes,
            CraftableServerConfig.CraftingRules rules, MainInventoryInsertion.Delivery delivery) {}

    public record Draft(UUID token, org.berusted.craftable.planner.PlanView view,
            org.berusted.craftable.planner.PlanView.Choices choices) {}

    public record Outcome(CraftingResultCode code, CraftPlan plan, List<CraftPlan.Missing> missing,
            List<net.minecraft.world.item.ItemStack> drops) {
        public Outcome {
            missing = List.copyOf(missing);
            drops = CraftPlan.copies(drops);
        }
        @Override public List<net.minecraft.world.item.ItemStack> drops() { return CraftPlan.copies(drops); }
        static Outcome failed(CraftingResultCode code) { return new Outcome(code, null, List.of(), List.of()); }
    }
}
