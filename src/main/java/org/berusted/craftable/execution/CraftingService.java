package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
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
    // Monotonic structural counters; no plans or per-request telemetry retained.
    static long activeFullSearches, activePartialSearches, witnessValidations;
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
        return attempt(player, recipe, sequence, precedingPress, allowDrops, policy, null);
    }

    public static Outcome attempt(ServerPlayer player, net.minecraft.resources.ResourceLocation recipe,
            long sequence, long precedingPress, boolean allowDrops, CraftRequest.PartialPolicy policy,
            LongSupplier remainingNanos) {
        if (!validContext(player)) return Outcome.failed(CraftingResultCode.INVALID_CONTEXT);
        if (!CraftingSessions.acceptSequence(player, sequence)) return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        boolean partial = CraftingSessions.partialGesture(player, recipe, precedingPress);
        Outcome outcome = create(player, new CraftRequest(recipe, 1, partial, allowDrops, policy, java.util.Map.of()),
                false, null, null, null, remainingNanos);
        CraftingSessions.recordAttempt(player, recipe, sequence, outcome.code(), partial);
        return outcome;
    }

    public static Draft preview(ServerPlayer player, CraftRequest request) {
        return preview(player, request, "");
    }

    public static Draft preview(ServerPlayer player, CraftRequest request, String choicePath) {
        return preview(player, request, choicePath, null, -1);
    }

    public static Draft preview(ServerPlayer player, CraftRequest request, String choicePath,
            CraftPlan.Witness witness, long sequence) {
        return preview(player, request, choicePath, witness, sequence, null);
    }

    public static Draft preview(ServerPlayer player, CraftRequest request, String choicePath,
            CraftPlan.Witness witness, long sequence, LongSupplier remainingNanos) {
        return preview(player, request, choicePath, witness, sequence, null, remainingNanos);
    }

    // Functional GameTests inject the same budget used for their plan fixture;
    // network/public entry points always keep the production admission cap.
    static Draft preview(ServerPlayer player, CraftRequest request, String choicePath,
            CraftPlan.Witness witness, long sequence, SearchBudget testBudget, LongSupplier remainingNanos) {
        if (!validContext(player)) return new Draft(new UUID(0, 0),
                org.berusted.craftable.planner.PlanView.failed(CraftingResultCode.INVALID_CONTEXT),
                new org.berusted.craftable.planner.PlanView.Choices(List.of(), false));
        CraftingSessions.discardOffer(player);
        if (witness != null && (!CraftingSessions.acceptSequence(player, sequence)
                || !CraftingSessions.acceptsWitness(player, witness))) return failedDraft(CraftingResultCode.ENVIRONMENT_CHANGED);
        try {
            if (admissionExhausted(remainingNanos)) return failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var snapshot = EnvironmentSnapshotService.fresh(player);
            var allowance = testBudget == null ? planningBudget(remainingNanos, System::nanoTime) : testBudget;
            if (allowance == null) return failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var prepared = witness == null ? prepare(player, request, snapshot, allowance)
                    : prepareWitness(player, request, snapshot, witness, allowance);
            if (prepared.result().plan().isPresent() && exhausted(allowance, remainingNanos))
                return failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var view = org.berusted.craftable.planner.PlanView.from(prepared.recipes(), prepared.request(),
                    prepared.result(), prepared.delivery().drops(), sources(snapshot).stream().map(ResourceLedger.Source::stack)
                            .filter(s -> !CraftingRecipes.protectedStack(s)).toList());
            var choices = view.choices(prepared.recipes(), prepared.request(), choicePath);
            if (prepared.result().plan().isPresent() && exhausted(allowance, remainingNanos))
                return failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            // An incomplete display must never authorize an undisclosed plan.
            UUID token = view.complete() && (!view.primary().isEmpty())
                    && (prepared.result().plan().filter(CraftPlan::partial).isEmpty()
                        || request.partial() && prepared.request().policy() != CraftRequest.PartialPolicy.NEVER)
                    ? CraftingSessions.offer(player, prepared, witness != null) : new UUID(0, 0);
            return new Draft(token, view, choices);
        } catch (IllegalArgumentException rejected) {
            return failedDraft(CraftingResultCode.ENVIRONMENT_CHANGED);
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Plan preview failed for {}", request.recipe(), exception);
            return new Draft(new UUID(0, 0), org.berusted.craftable.planner.PlanView.failed(CraftingResultCode.INTERNAL_ERROR),
                    new org.berusted.craftable.planner.PlanView.Choices(List.of(), false));
        }
    }

    public static Outcome confirm(ServerPlayer player, UUID token) {
        return confirm(player, token, null, -1);
    }

    public static Outcome confirm(ServerPlayer player, UUID token, CraftPlan.Witness witness, long sequence) {
        return confirm(player, token, witness, sequence, null);
    }

    public static Outcome confirm(ServerPlayer player, UUID token, CraftPlan.Witness witness, long sequence,
            LongSupplier remainingNanos) {
        return confirm(player, token, witness, sequence, null, remainingNanos);
    }

    static Outcome confirm(ServerPlayer player, UUID token, CraftPlan.Witness witness, long sequence,
            SearchBudget testBudget, LongSupplier remainingNanos) {
        if (!validContext(player)) {
            CraftingSessions.discardOffer(player);
            return Outcome.failed(CraftingResultCode.CONFIRMATION_EXPIRED);
        }
        if (witness != null && !CraftingSessions.acceptSequence(player, sequence))
            return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        var confirmation = CraftingSessions.take(player, token);
        if (confirmation == null) return Outcome.failed(CraftingResultCode.CONFIRMATION_EXPIRED);
        if (!confirmation.rules().equals(CraftableServerConfig.craftingRules())
                || !CraftingRecipes.isCurrentGeneration(player.getServer(), confirmation.recipeGeneration()))
            return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
        if (confirmation.witness() != (witness != null)
                || witness != null && !CraftingSessions.acceptsWitness(player, witness))
            return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
        return create(player, confirmation.request(), true, testBudget, confirmation, witness, remainingNanos);
    }

    // Deterministic GameTests supply a generous deadline; production callers
    // always use the bounded entry above. Performance tests exercise real caps.
    static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget) {
        return create(player, request, confirmed, budget, null);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget,
            CraftingSessions.Confirmation confirmation) {
        return create(player, request, confirmed, budget, confirmation, null);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget,
            CraftingSessions.Confirmation confirmation, CraftPlan.Witness witness) {
        return create(player, request, confirmed, budget, confirmation, witness, null);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget,
            CraftingSessions.Confirmation confirmation, CraftPlan.Witness witness, LongSupplier remainingNanos) {
        if (!validContext(player)) return Outcome.failed(CraftingResultCode.INVALID_CONTEXT);
        if (!ACTIVE.add(player.getUUID())) return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        try {
            if (admissionExhausted(remainingNanos)) return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var snapshot = EnvironmentSnapshotService.fresh(player);
            // Network requests borrow only what is left after fresh discovery.
            // Search, real validation and delivery share this one deadline.
            var allowance = budget == null ? planningBudget(remainingNanos, System::nanoTime) : budget;
            if (allowance == null) return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            Prepared prepared = witness == null ? prepare(player, request, snapshot, allowance)
                    : prepareWitness(player, request, snapshot, witness, allowance);
            // Read-only preparation may time out after complete exclusion.
            // Plain C still has a proven material failure; do not turn it into
            // uncertainty or let unfinished diagnostic counts authorize work.
            if (confirmation == null && !request.partial() && prepared.fullMissing()
                    && prepared.result().code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED)
                return Outcome.failed(CraftingResultCode.MISSING_INGREDIENTS);
            if (prepared.result().code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                    || prepared.result().plan().isPresent() && exhausted(allowance, remainingNanos))
                return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
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
            // Reject before the atomic transaction begins; never stop halfway
            // through mutations. Admission still charges its complete duration.
            if (exhausted(allowance, remainingNanos)) return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var code = CraftingTransaction.execute(player, plan, snapshot, prepared.delivery);
            return new Outcome(code, plan, plan.missing(),
                    code == CraftingResultCode.CREATED || code == CraftingResultCode.PARTIAL_CREATED
                            ? prepared.delivery.drops() : List.of());
        } catch (IllegalArgumentException rejected) {
            return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Crafting chain request failed for {} and {}", player.getUUID(), request.recipe(), exception);
            return Outcome.failed(CraftingResultCode.INTERNAL_ERROR);
        } finally {
            ACTIVE.remove(player.getUUID());
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }

    /** Zero remaining admission must not manufacture a fresh solver deadline. */
    static SearchBudget planningBudget(LongSupplier remainingNanos, LongSupplier clock) {
        long nanos = remainingNanos == null ? 8_000_000L : Math.min(8_000_000L, remainingNanos.getAsLong());
        return nanos <= 0 ? null : new SearchBudget(clock, nanos, SearchBudget.MAX_STATES);
    }

    private static boolean admissionExhausted(LongSupplier remainingNanos) {
        return remainingNanos != null && remainingNanos.getAsLong() <= 0;
    }

    private static boolean exhausted(SearchBudget budget, LongSupplier remainingNanos) {
        return !budget.alive() || admissionExhausted(remainingNanos);
    }

    static Prepared prepare(ServerPlayer player, CraftRequest requested, EnvironmentSnapshot snapshot, SearchBudget budget) {
        return prepare(player, requested, snapshot, budget, true);
    }

    private static Draft failedDraft(CraftingResultCode code) {
        return new Draft(new UUID(0, 0), org.berusted.craftable.planner.PlanView.failed(code),
                new org.berusted.craftable.planner.PlanView.Choices(List.of(), false));
    }

    /** A fresh scan binds opaque references to actual extraction sources. The
     * resulting Prepared enters exactly the same review/transaction path as a
     * server search; invalid witnesses never trigger an alternative search. */
    static Prepared prepareWitness(ServerPlayer player, CraftRequest requested, EnvironmentSnapshot world,
            CraftPlan.Witness witness, SearchBudget budget) {
        var browsing = CraftingSessions.refreshBrowsing(player, world);
        if (!CraftingSessions.acceptsWitness(player, witness) || requested.partial())
            throw new IllegalArgumentException("Changed witness authority");
        var rules = CraftableServerConfig.craftingRules();
        var effective = new CraftRequest(requested.recipe(), requested.batches(), false,
                requested.allowDrops() && rules.surplusDelivery() == CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW,
                requested.policy().restrict(rules.partialPolicy()), requested.selections());
        if (effective.batches() > rules.maxBatches()) throw new IllegalArgumentException("Batch limit");
        var recipes = new CraftingRecipes(player, workbench(player, world));
        witnessValidations++;
        CraftPlan plan;
        try { plan = CraftWitnessValidator.validate(recipes, effective, witness, browsing.sources(), budget); }
        catch (IllegalArgumentException rejected) {
            if (budget.alive()) throw rejected;
            return new Prepared(SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), effective, recipes, rules,
                    MainInventoryInsertion.Delivery.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
        }
        var bindings = CraftingSessions.witnessBindings(player, world);
        var extractions = plan.extractions().stream().map(extraction -> {
            var source = bindings.get(extraction.endpointId());
            if (source == null || !net.minecraft.world.item.ItemStack.matches(source.stack(), extraction.expected()))
                throw new IllegalArgumentException("Changed witness source");
            return new CraftPlan.Extraction(source.endpointId(), source.slot(), extraction.count(), source.stack());
        }).toList();
        plan = new CraftPlan(plan.target(), plan.requestedBatches(), plan.completedBatches(), plan.steps(), extractions,
                plan.primary(), plan.surplus(), plan.missing(), plan.safePartial());
        var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, world, effective.allowDrops());
        var result = delivery.failure() == null
                ? new SearchResult(CraftingResultCode.CREATED, java.util.Optional.of(plan), List.of(), true, 0)
                : SearchResult.blocked(delivery.failure());
        if (!budget.alive()) return new Prepared(SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), effective, recipes, rules,
                MainInventoryInsertion.Delivery.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
        return new Prepared(result, effective, recipes, rules, delivery);
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
        // A server-only negative proof is reusable only after this fresh scan
        // matches ALL dynamic inputs, including empty capacity and policy.
        // No client failure assertion enters this store; one proof, 40 ticks.
        Object identity = List.of(recipes.accessIdentity(), rules, diagnostic.withPartial(false), snapshot.dimension(),
                snapshot.scanSettings(), sources.stream().map(s -> List.of(s.endpointId(), s.slot(),
                        CraftPlan.stackKeys(List.of(s.stack())))).toList(),
                CraftPlan.stackKeys(mainSlots), inventoryMaximum, playerEndpoints, endpointIds);
        var previous = CraftingSessions.failure(player, identity);
        var scope = previous == null ? new CraftSearch.Reachability() : previous.scope();
        var search = new CraftSearch(recipes, diagnostic, sources, budget,
                plan -> MainInventoryInsertion.simulate(mainSlots, inventoryMaximum, plan, playerEndpoints, endpointIds, effective.allowDrops()).failure())
                .withReachability(scope).withFullEvidence(previous == null ? null : previous.evidence());
        SearchResult result = search.run();
        activeFullSearches += search.fullSearches(); activePartialSearches += search.partialSearches();
        if (previous == null) CraftingSessions.rememberFailure(player, identity, scope, search.fullEvidence());
        if (result.plan().isPresent()) {
            boolean valid = recipes.validate(result.plan().get());
            var code = !budget.alive() ? CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                    : !valid ? CraftingResultCode.UNSUPPORTED_RECIPE : null;
            if (code != null) return new Prepared(SearchResult.blocked(code), effective, recipes, rules,
                    MainInventoryInsertion.Delivery.failed(code), search.fullEvidence() != null);
        }
        var delivery = result.plan().map(plan ->
                MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, effective.allowDrops()))
                .orElseGet(() -> MainInventoryInsertion.Delivery.failed(result.code()));
        return new Prepared(result, effective, recipes, rules, delivery, search.fullEvidence() != null);
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
        return maximum(player, requested, null);
    }

    // Deterministic correctness fixtures may supply a clock/deadline; the
    // network entry above always retains the production 8 ms shared allowance.
    static Maximum maximum(ServerPlayer player, CraftRequest requested, SearchBudget allowance) {
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
        var budget = allowance == null ? new SearchBudget(8_000_000L) : allowance;
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
            CraftableServerConfig.CraftingRules rules, MainInventoryInsertion.Delivery delivery, boolean fullMissing) {
        Prepared(SearchResult result, CraftRequest request, CraftingRecipes recipes,
                CraftableServerConfig.CraftingRules rules, MainInventoryInsertion.Delivery delivery) {
            this(result, request, recipes, rules, delivery, false);
        }
    }

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
