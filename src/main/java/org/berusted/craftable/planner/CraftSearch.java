package org.berusted.craftable.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.recipe.CraftingRecipes;
import org.berusted.craftable.recipe.CraftingRecipes.Entry;

/**
 * Bounded demand backtracking. Every branch owns its counts and unfinished
 * grids. The explicit frontier avoids turning recipe depth or batch size into
 * Java call-stack depth; a failed branch can never leak reservations to another.
 */
public final class CraftSearch {
    private final org.berusted.craftable.recipe.PlanningInput recipes;
    private final CraftRequest request;
    private final ResourceLedger initial;
    private SearchBudget budget;
    private final SearchBudget fullBudget;
    private SearchBudget diagnosticBudget;
    private final Function<CraftPlan, CraftingResultCode> validateDelivery;
    private Set<Item> reachable = Set.of();
    private Reachability shared = new Reachability();
    private FullEvidence previousFull;
    private FullEvidence fullEvidence;
    private int fullSearches;
    private int partialSearches;
    private CraftingResultCode failure = CraftingResultCode.MISSING_INGREDIENTS;
    private List<CraftPlan.Missing> bestMissing = List.of();
    private int bestProgress = -1;
    private int bestBindings = -1;
    private CapacityDiagnosis capacityDiagnosis;
    private enum Phase { START, EXISTING, CLOSURE, FULL, PARTIAL, DONE }
    private Phase phase = Phase.START;
    private Entry root;
    private SearchResult completed;
    private final ArrayDeque<Branch> frontier = new ArrayDeque<>();
    private final Set<Object> visited = new HashSet<>();
    private final Set<Object> completedPrefixes = new HashSet<>();
    private boolean partialMode, existingOnly;
    private int initializations;
    private boolean unsupportedRelation;
    private boolean stepBoundChecked;
    private boolean wantsMaterialUpperBound, materialUpperBoundChecked;
    private java.util.OptionalInt materialUpperBound = java.util.OptionalInt.empty();
    // Conservative retained-state estimate, not a JVM heap measurement. Two
    // continuations must still leave room for snapshot/results in a 16 MiB session.
    private static final long MAX_RETAINED_BYTES = 4L * 1024 * 1024;
    private long frontierBytes, memoBytes;

    public CraftSearch(CraftingRecipes recipes, CraftRequest request, List<ResourceLedger.Source> sources,
            SearchBudget budget, Function<CraftPlan, CraftingResultCode> validateDelivery) {
        this(recipes.planningInput(), request, sources, budget, validateDelivery);
    }

    public CraftSearch(org.berusted.craftable.recipe.PlanningInput recipes, CraftRequest request, List<ResourceLedger.Source> sources,
            SearchBudget budget, Function<CraftPlan, CraftingResultCode> validateDelivery) {
        this.recipes = recipes;
        this.request = request;
        this.initial = new ResourceLedger(sources);
        this.budget = budget;
        this.fullBudget = budget;
        this.validateDelivery = validateDelivery;

    }

    /** A caller may share only within one identical immutable resource view. */
    public CraftSearch withReachability(Reachability shared) {
        requireNotStarted(); this.shared = java.util.Objects.requireNonNull(shared); return this;
    }

    /** Optional MAX presentation certificate, charged to this same search.
     * It neither supplies a plan nor proves this request's full failure. */
    public CraftSearch withMaterialUpperBound() {
        wantsMaterialUpperBound = true;
        return this;
    }
    public java.util.OptionalInt materialUpperBound() { return materialUpperBound; }
    public boolean materialUpperBoundAttempted() { return materialUpperBoundChecked; }
    public static final class Reachability {
        private Set<Item> value;
        private int builds;
        private int attempts;
        private long buildNanos;
        private Set<Item> working;
        private int cursor;
        private boolean changed;
        private SearchBudget budget;
        public int builds() { return builds; }
        public int attempts() { return attempts; }
        public long buildNanos() { return buildNanos; }
        /** Cheap FULL_ONLY exclusion, not a positive quantity verdict. Unknown
         * producer semantics disable this shortcut for the whole catalog. The
         * caller must bind this closure to one immutable resource identity. */
        public FullEvidence excludes(org.berusted.craftable.recipe.PlanningInput recipes, CraftRequest request) {
            if (value == null || !recipes.fullySupported() || !request.selections().isEmpty()) return null;
            var entry = recipes.find(request.recipe());
            if (recipes.unavailable(entry) != null) return null;
            for (var demand : entry.requirements()) {
                if (java.util.Arrays.stream(demand.ingredient().getItems()).noneMatch(s -> value.contains(s.getItem())))
                    return new FullEvidence(this, request);
            }
            return null;
        }
        public static Reachability resumable() {
            var result = new Reachability();
            result.budget = SearchBudget.resumable(8_000_000L);
            return result;
        }
    }

    /** Scope-local evidence, never a serializable "no recipe" assertion.
     * The server can reuse only its own scope's evidence, not a client's flag.
     * Its scope belongs to one verified resource/capacity/access identity;
     * a new scope makes old evidence unusable on either side. */
    public static final class FullEvidence {
        private final Reachability scope;
        private final CraftRequest target;
        private FullEvidence(Reachability scope, CraftRequest request) {
            this.scope = scope;
            this.target = request.withPartial(false);
        }
    }

    public CraftSearch withFullEvidence(FullEvidence evidence) { requireNotStarted(); previousFull = evidence; return this; }
    public FullEvidence fullEvidence() { return fullEvidence; }
    public int fullSearches() { return fullSearches; }
    public int partialSearches() { return partialSearches; }
    public int initializations() { return initializations; }
    public CraftSearch withDiagnosticBudget(SearchBudget value) { requireNotStarted(); diagnosticBudget = value; return this; }
    private void requireNotStarted() {
        if (phase != Phase.START) throw new IllegalStateException("Cannot rebind a running or completed search");
    }

    public SearchResult run() {
        while (completed == null) advance(Long.MAX_VALUE / 2);
        return completed;
    }

    /** One continuation, also used by synchronous server run(). Empty means
     * PAUSED, not failure. Frontier, memo sets and closure cursor stay owned by
     * this task/scope; another slice never starts from its root again. */
    public Optional<SearchResult> advance(long nanos) {
        if (completed != null) return Optional.of(completed);
        if (phase == Phase.START) {
            initializations++;
            root = recipes.find(request.recipe());
            var unavailable = recipes.unavailable(root);
            if (unavailable != null) return finish(SearchResult.blocked(unavailable));
            if (previousFull != null && previousFull.scope == shared && shared.value != null
                    && previousFull.target.equals(request.withPartial(false))) {
                fullEvidence = previousFull;
                reachable = shared.value;
                beginPartial();
            } else {
                fullSearches++;
                beginSearch(Phase.EXISTING);
            }
            if (completed != null) return Optional.of(completed);
        }
        SearchBudget charged = phase == Phase.CLOSURE && shared.budget != null ? shared.budget : budget;
        charged.beginSlice(nanos);
        try {
            if (!charged.alive()) return finish(limited());
            if (!stepBoundChecked && phase == Phase.EXISTING) {
                stepBoundChecked = true;
                if (exceedsStepLimit()) {
                    // This is a representation bound, not a material denial
                    // or permission to execute an unfinished preparation.
                    budget.truncate(); return finish(limited());
                }
            }
            if (phase == Phase.CLOSURE) {
                long start = System.nanoTime();
                boolean ready;
                try { ready = advanceClosure(charged); }
                finally { shared.buildNanos += System.nanoTime() - start; }
                if (charged.truncated()) return finish(limited());
                if (ready) { reachable = shared.value; beginSearch(Phase.FULL); }
                return Optional.empty();
            }
            if (wantsMaterialUpperBound && !materialUpperBoundChecked && phase == Phase.FULL && shared.value != null) {
                materialUpperBoundChecked = true;
                // Its transient maps share the solver's retained-memory cap;
                // inability to reserve them merely omits this optimization.
                if (frontierBytes + memoBytes + MaterialUpperBound.MAX_RETAINED_BYTES <= MAX_RETAINED_BYTES)
                    materialUpperBound = MaterialUpperBound.compute(recipes, request, initial, shared.value, charged);
            }
            CraftPlan plan = search();
            if (plan != null) return finish(success(plan));
            if (budget.exhausted()) return finish(limited());
            if (!frontier.isEmpty()) return Optional.empty();
            // An omitted alternative prevents a negative proof, not a later
            // slice from finding a concrete full witness in the kept frontier.
            if (budget.truncated()) return finish(limited());
            if (phase == Phase.EXISTING) {
                failure = CraftingResultCode.MISSING_INGREDIENTS;
                bestMissing = List.of(); bestProgress = bestBindings = -1;
                phase = Phase.CLOSURE;
            } else if (phase == Phase.FULL && failure == CraftingResultCode.MISSING_INGREDIENTS) {
                // Structural/time truncation never produces negative evidence.
                fullEvidence = new FullEvidence(shared, request);
                beginPartial();
            } else if (phase == Phase.PARTIAL && capacityDiagnosis != null) {
                return finish(new SearchResult(capacityDiagnosis.code(), Optional.empty(), capacityDiagnosis.missing(),
                        true, visitedStates()));
            } else return finish(new SearchResult(failure, Optional.empty(), bestMissing, true, visitedStates()));
            return Optional.ofNullable(completed);
        } finally { charged.endSlice(); }
    }

    private void beginPartial() {
        if (request.partial() && request.policy() != CraftRequest.PartialPolicy.NEVER) {
            partialSearches++;
            // A separate allowance can only be selected AFTER complete full
            // exclusion. It never lends time/states to the full phase.
            if (diagnosticBudget != null) budget = diagnosticBudget;
            beginSearch(Phase.PARTIAL);
        } else finish(new SearchResult(failure, Optional.empty(), bestMissing, true, visitedStates()));
    }

    private Optional<SearchResult> finish(SearchResult result) {
        completed = result;
        phase = Phase.DONE;
        frontier.clear(); visited.clear(); completedPrefixes.clear();
        frontierBytes = memoBytes = 0;
        return Optional.of(result);
    }

    public void cancel() {
        if (completed == null) finish(limited());
    }

    private SearchResult success(CraftPlan plan) {
        return new SearchResult(plan.partial() ? CraftingResultCode.PARTIAL_CREATED : CraftingResultCode.CREATED,
                Optional.of(plan), plan.missing(), !plan.partial() || !budget.truncated(), visitedStates());
    }

    private SearchResult limited() {
        return new SearchResult(unsupportedRelation ? CraftingResultCode.UNSUPPORTED_RECIPE : CraftingResultCode.SEARCH_BUDGET_EXCEEDED, Optional.empty(),
                capacityDiagnosis == null ? bestMissing : capacityDiagnosis.missing(), false, visitedStates());
    }

    public int visitedStates() { return fullBudget.states() + (budget == fullBudget ? 0 : budget.states()); }

    private void beginSearch(Phase next) {
        phase = next;
        partialMode = next == Phase.PARTIAL;
        existingOnly = next == Phase.EXISTING;
        if (partialMode) {
            // Full search stops at its first deficit. Its unfinished diagnostic
            // cannot outrank the whole-demand accounting of the partial phase.
            bestMissing = List.of(); bestProgress = bestBindings = -1;
        }
        frontier.clear(); visited.clear(); completedPrefixes.clear();
        frontierBytes = memoBytes = 0;
        State start = new State(initial.copy());
        for (int i = 0; i < request.batches(); i++) start.tasks.addLast(new Root(root));
        push(start);
    }

    private CraftPlan search() {
        while (!frontier.isEmpty() && budget.canContinue() && budget.enter()) {
            Branch next = frontier.pop();
            frontierBytes -= next.retainedBytes();
            State state = next.materialize();
            if (state.tasks.isEmpty()) {
                if (state.rejected != null) { remember(state); continue; }
                if (state.steps.isEmpty() || state.ledger.delivery(true).isEmpty()) {
                    remember(state);
                    continue;
                }
                var plan = new CraftPlan(request.recipe(), request.batches(), state.completed,
                        state.steps, state.ledger.extractions(), state.ledger.delivery(true),
                        state.ledger.delivery(false), mergeMissing(state.missing),
                        state.steps.stream().allMatch(s -> recipes.find(s.recipe().id()).safePreparation()));
                // A reboxed existing item is not a newly craftable result.
                // Keep this exact accounting check shared with witness replay.
                if (!plan.hasMaterialChange()) continue;
                CraftingResultCode invalid = validateDelivery.apply(plan);
                if (invalid == null) {
                    // Capacity may select another real route, but cannot make
                    // deliberately omitted materials a better preparation.
                    if (partialMode && capacityDiagnosis != null
                            && addsMissing(plan.missing(), capacityDiagnosis.missing())) continue;
                    return plan;
                }
                if (partialMode && (invalid == CraftingResultCode.NO_OUTPUT_SPACE
                        || invalid == CraftingResultCode.DROP_LIMIT_EXCEEDED)) {
                    if (capacityDiagnosis == null || addsMissing(capacityDiagnosis.missing(), plan.missing()))
                        capacityDiagnosis = new CapacityDiagnosis(invalid, plan.missing());
                }
                failure = invalid;
                continue;
            }
            Task task = state.tasks.removeFirst();
            if (task instanceof Root r) {
                // Collapse equivalent *complete* prefixes, not unfinished
                // ingredient assignments. Mixed wood batch permutations must
                // not replay the same exhausted inventory exponentially.
                // Partial preparation keeps its stricter history-sensitive key.
                if (!partialMode && state.grids.isEmpty() && state.active.isEmpty()) {
                    if (!rememberIdentity(completedPrefixes,
                            () -> List.of(state.completed, state.steps.size(), state.ledger.batchBoundaryIdentity()),
                            state.ledger.identityBytes(), state)) continue;
                }
                expandRecipe(state, r.recipe, "0", 0, null);
                push(state);
            } else if (task instanceof Finish finish) {
                Grid grid = state.grids.remove(finish.grid);
                state.active.remove(finish.recipe.id());
                if (grid.missing) {
                    if (!partialMode) continue;
                    if (grid.parent != null && state.steps.size() == grid.startSteps
                            && java.util.Arrays.stream(grid.taken).allMatch(java.util.Objects::isNull)) {
                        // No actual input or processing seeds this producer.
                        // Explain the parent demand instead of an arbitrary
                        // conversion (two ingots, not nine unseeded nuggets).
                        // A seeded incomplete producer keeps its input deficit:
                        // four bound nuggets genuinely need five more nuggets.
                        state.ledger = grid.before.copy();
                        state.bindings = grid.startBindings;
                        state.missing.subList(grid.startMissing, state.missing.size()).clear();
                        state.missing.add(missing(grid.parent));
                    } else {
                        for (var taken : grid.taken) if (taken != null) state.ledger.reserveFrontier(taken);
                    }
                    state.results.put(finish.grid, -1);
                } else {
                    if (state.steps.size() >= SearchBudget.MAX_STEPS) { budget.truncate(); continue; }
                    CraftPlan.Step step = recipes.assemble(finish.recipe, finish.path, grid.items());
                    if (step == null) { failure = CraftingResultCode.UNSUPPORTED_RECIPE; continue; }
                    if (!budget.alive()) break;
                    // Capture the actual lot references while they still exist.
                    // Reconstructing by item ID later would misattribute shared
                    // surplus, mixed batches or a consumed crafting remainder.
                    step = new CraftPlan.Step(step.recipe(), step.path(), step.gridSize(), step.inputs(),
                            step.output(), step.remainders(), java.util.Arrays.stream(grid.taken)
                                    .map(state.ledger::origin).toList());
                    int origin = state.steps.size();
                    state.steps.add(step);
                    int lot = state.ledger.produce(step.output(), finish.root, false, finish.path, origin);
                    state.results.put(finish.grid, lot);
                    for (ItemStack remainder : step.remainders()) state.ledger.produce(remainder, false, true, finish.path, origin);
                    if (finish.root) state.completed++;
                }
                push(state);
            } else if (task instanceof Supply supply) {
                Integer lot = state.results.remove(supply.producer);
                var taken = state.ledger.producedChoice(lot == null ? -1 : lot, supply.need.ingredient);
                if (taken == null) {
                    if (!partialMode) continue;
                    state.grids.get(supply.need.grid).missing = true;
                } else {
                    bind(state, supply.need, taken);
                }
                push(state);
            } else if (task instanceof Need originalNeed) {
                var pinnedId = request.selections().get(originalNeed.path);
                var selected = pinnedId == null ? null : recipes.find(pinnedId);
                if (pinnedId != null && (selected == null || !originalNeed.ingredient.test(selected.output()))) {
                    failure = CraftingResultCode.UNSUPPORTED_RECIPE;
                    continue;
                }
                // A chosen birch-plank producer also selects birch planks as
                // this OR-ingredient's item. Otherwise another branch's oak
                // surplus could silently bypass the user's explicit choice.
                Need need = selected == null ? originalNeed : new Need(Ingredient.of(selected.output()),
                        originalNeed.grid, originalNeed.slot, originalNeed.path, originalNeed.depth, originalNeed.producersOnly);
                var existing = need.producersOnly ? List.<ResourceLedger.Taken>of() : state.ledger.choices(need.ingredient);
                // Keep the concrete preferred continuation in place. Backups
                // must be copied from the untouched preimage, but inability to
                // retain another backup must never discard this first route.
                Consumer<State> preferred = null;
                List<Consumer<State>> branches = new ArrayList<>();
                for (var taken : existing) {
                    if (preferred == null) { preferred = s -> bind(s, need, taken); continue; }
                    if (!canFork(state, branches.size())) break;
                    branches.add(s -> bind(s, need, taken));
                }
                boolean deferProducers = !existingOnly && !partialMode && !existing.isEmpty();
                if (deferProducers && (!recipes.producing(need.ingredient).isEmpty() || recipes.hasUnsupportedProducer(need.ingredient))
                        && canFork(state, branches.size()))
                    branches.add(s -> s.tasks.addFirst(new Need(need.ingredient, need.grid, need.slot, need.path, need.depth, true)));
                // Ranking every producer of every already-bound surplus slot
                // is wasteful. The fallback owns the same preimage and ranks
                // them only if existing stock really fails downstream.
                List<Entry> candidates = existingOnly || deferProducers ? new ArrayList<>() : new ArrayList<>(recipes.producing(need.ingredient));
                if (!existingOnly && !deferProducers && recipes.hasUnsupportedProducer(need.ingredient)) {
                    // Omitting unknown predicates must not prove impossibility
                    // and thereby authorize partial consumption. A known full
                    // witness may still succeed; absence remains unproven.
                    unsupportedRelation = true;
                    budget.truncate();
                }
                // Once full completion was disproved, preparation may fill a
                // deficit but may not replace already-available intermediates
                // just to manufacture a superficially "productive" frontier.
                if (partialMode && !existing.isEmpty()) candidates.clear();
                var pinned = request.selections().get(need.path);
                if (pinned != null) candidates.removeIf(e -> !e.id().equals(pinned));
                candidates.sort(Comparator.<Entry>comparingInt(e -> estimatedMissing(e, state.ledger))
                        .thenComparing(e -> e.id().toString()));
                boolean permissionBlocked = false;
                CraftingResultCode blockedReason = null;
                int considered = 0;
                for (Entry candidate : candidates) {
                    if (state.active.contains(candidate.id())) continue;
                    if (recipes.isExactInverse(candidate, state.grids.get(need.grid).recipe)) continue;
                    // Reachability belongs to a candidate's inputs, not merely
                    // its output (sticks reachable via planks do not seed bamboo).
                    // An unseeded, locked conversion is not a reason to reject
                    // an otherwise unlocked target's useful preparation.
                    if (!candidate.requirements().stream().allMatch(r -> java.util.Arrays.stream(r.ingredient().getItems())
                            .anyMatch(s -> reachable.contains(s.getItem())))) continue;
                    // A fixed raw input with neither producer nor possible
                    // return cannot be supplied by this branch after its stock
                    // runs out. This excludes only that concrete producer;
                    // alternate reservations still live in the frontier.
                    if (!partialMode && candidate.requirements().stream().anyMatch(r -> !state.ledger.has(r.ingredient())
                            && recipes.producing(r.ingredient()).isEmpty()
                            && !recipes.hasUnsupportedProducer(r.ingredient()) && !recipes.mayReturn(r.ingredient()))) continue;
                    var denied = recipes.unavailable(candidate);
                    if (denied != null) {
                        permissionBlocked = true;
                        blockedReason = denied;
                        continue;
                    }
                    if (!reachable.contains(candidate.output().getItem())) continue;
                    if (++considered > SearchBudget.MAX_CANDIDATES || need.depth >= SearchBudget.MAX_DEPTH) {
                        budget.truncate();
                        break;
                    }
                    if (preferred == null) {
                        preferred = s -> expandRecipe(s, candidate, need.path, need.depth, need);
                        continue;
                    }
                    if (!canFork(state, branches.size())) break;
                    branches.add(s -> expandRecipe(s, candidate, need.path, need.depth, need));
                }
                if ((partialMode && existing.isEmpty()) || preferred == null) {
                    var rejected = preferred == null && (permissionBlocked || state.ledger.hasProtected(need.ingredient))
                            ? state.ledger.hasProtected(need.ingredient) ? CraftingResultCode.PROTECTED_INGREDIENTS : blockedReason
                            : null;
                    Consumer<State> markMissing = s -> {
                        s.grids.get(need.grid).missing = true;
                        s.missing.add(missing(need));
                        if (rejected != null) s.rejected = rejected;
                    };
                    // Partial diagnostics must finish all sibling/root demands;
                    // an intermediate failure has neither final items nor counts.
                    if (preferred == null) {
                        if (partialMode) preferred = markMissing;
                        else { markMissing.accept(state); remember(state); continue; }
                    } else if (canFork(state, branches.size())) {
                        branches.add(markMissing);
                    }
                }
                // Avoid copying the entire history for every single-material
                // FULL surplus slot. Omitting this optimization key may spend
                // extra states, never remove an alternative or grant a proof.
                // Multi-material assignments/partial preparation keep the full
                // key; complete batch prefixes are memoized independently.
                if (!branches.isEmpty() && (partialMode || existing.size() > 1)
                        && !rememberIdentity(visited, () -> state.identity(need),
                        state.ledger.identityBytes() + state.tasks.size() * 128L
                                + state.grids.size() * 256L + state.steps.size() * 128L, state)) continue;
                // All deferred alternatives share one immutable preimage.
                // Clone/expand only the branch actually visited, rather than
                // all producers of every already-available ingredient slot.
                State preimage = branches.isEmpty() ? null : state.copy();
                preferred.accept(state);
                // Reserve the preferred route before admitting any backups.
                // Its growth can evict only the lowest-priority old backups;
                // omissions invalidate negative evidence, not a full witness.
                if (!makeRoom(state)) continue;
                int kept = 0;
                long room = MAX_RETAINED_BYTES - frontierBytes - memoBytes - state.retainedBytes();
                while (kept < branches.size() && kept < SearchBudget.MAX_FRONTIER - frontier.size() - 1) {
                    long bytes = new Branch(preimage, branches.get(kept)).retainedBytes();
                    if (bytes > room) break;
                    room -= bytes;
                    kept++;
                }
                if (kept < branches.size()) budget.truncate();
                for (int i = kept - 1; i >= 0; i--) {
                    retain(new Branch(preimage, branches.get(i)));
                }
                retain(new Branch(state, null));
            }
        }
        return null;
    }

    private boolean rememberIdentity(Set<Object> identities, Supplier<Object> identity, long bytes, State current) {
        if (frontierBytes + memoBytes + current.retainedBytes() + bytes > MAX_RETAINED_BYTES) {
            // Memoization is an optimization, not a prerequisite for completing
            // a concrete route. Avoid even allocating a large rejected key.
            budget.truncate(); return true;
        }
        Object key = identity.get();
        if (!identities.add(key)) return false;
        memoBytes += bytes;
        return true;
    }

    private boolean canFork(State state, int staged) {
        if (frontierBytes + memoBytes + state.retainedBytes() * (staged + 2L) > MAX_RETAINED_BYTES) {
            budget.truncate(); return false;
        }
        return true;
    }

    private void push(State state) {
        if (makeRoom(state)) retain(new Branch(state, null));
    }

    private boolean makeRoom(State state) {
        long bytes = state.retainedBytes();
        while (!frontier.isEmpty() && (frontier.size() >= SearchBudget.MAX_FRONTIER
                || frontierBytes + memoBytes + bytes > MAX_RETAINED_BYTES)) {
            frontierBytes -= frontier.removeLast().retainedBytes();
            budget.truncate();
        }
        if (memoBytes + bytes > MAX_RETAINED_BYTES) {
            // The retained current state may grow after a recipe finishes.
            // Releasing optimization keys preserves the hard memory bound;
            // the same cumulative CPU/state/depth limits still terminate it.
            visited.clear(); completedPrefixes.clear(); memoBytes = 0;
            budget.truncate();
        }
        if (frontier.size() >= SearchBudget.MAX_FRONTIER || frontierBytes + memoBytes + bytes > MAX_RETAINED_BYTES) {
            budget.truncate(); return false;
        }
        return true;
    }

    private void retain(Branch branch) {
        frontier.push(branch);
        frontierBytes += branch.retainedBytes();
    }

    private record Branch(State preimage, Consumer<State> choice) {
        long retainedBytes() {
            // Repeatedly charge the shared immutable preimage. Also reserve a
            // producer grid/ledger/task expansion before it is materialized.
            return preimage.retainedBytes() + (choice == null ? 0 : 4096L + preimage.ledger.retainedBytes());
        }
        State materialize() {
            if (choice == null) return preimage;
            State state = preimage.copy();
            choice.accept(state);
            return state;
        }
    }

    private void expandRecipe(State state, Entry recipe, String path, int depth, Need parent) {
        int id = state.nextGrid++;
        Grid grid = new Grid(recipe);
        grid.parent = parent;
        grid.before = state.ledger.copy();
        grid.startSteps = state.steps.size();
        grid.startMissing = state.missing.size();
        grid.startBindings = state.bindings;
        state.grids.put(id, grid);
        state.active.add(recipe.id());
        if (parent != null) state.tasks.addFirst(new Supply(parent, id));
        state.tasks.addFirst(new Finish(recipe, id, path, parent == null));
        var requirements = new ArrayList<>(recipe.requirements());
        // Detect a fixed raw-material deficit before backtracking through
        // unrelated intermediate producers. This changes only visit order;
        // it is not a quantity/presence proof (remainders may supply a need).
        var producerless = new HashSet<Integer>();
        if (phase == Phase.FULL) for (var r : requirements) {
            if (recipes.producing(r.ingredient()).isEmpty() && !recipes.hasUnsupportedProducer(r.ingredient())
                    && !recipes.mayReturn(r.ingredient()))
                producerless.add(r.slot());
        }
        requirements.sort(Comparator.<CraftingRecipes.Requirement>comparingInt(r -> r.ingredient().getItems().length)
                .thenComparingInt(r -> producerless.contains(r.slot()) ? 0 : 1));
        for (int i = requirements.size() - 1; i >= 0; i--) {
            var requirement = requirements.get(i);
            state.tasks.addFirst(new Need(requirement.ingredient(), id, requirement.slot(),
                    path + "." + requirement.slot(), depth + 1));
        }
    }

    private void bind(State state, Need need, ResourceLedger.Taken taken) {
        state.ledger.take(taken);
        state.grids.get(need.grid).taken[need.slot] = taken;
        state.bindings++;
    }

    /** Necessary operation count only. Each root needs one operation; a fixed
     * repeated ingredient needs at least ceil(shortfall / largest output) more.
     * Returns/unknown producers disable the bound, and overlapping ingredient
     * groups are checked separately, never added. No route is constructed and
     * passing this test proves neither materials nor delivery feasibility. */
    private boolean exceedsStepLimit() {
        // This particular lower bound cannot exceed one root operation plus
        // one direct producer per root slot. Small requests need no producer
        // enumeration/source scan here; the actual search still enforces all
        // upstream steps and passing this check authorizes nothing.
        if ((long) request.batches() * (1 + root.requirements().size()) <= SearchBudget.MAX_STEPS) return false;
        var groups = new LinkedHashMap<Object, Ingredient>();
        var counts = new HashMap<Object, Integer>();
        for (var demand : root.requirements()) {
            Object key = Set.copyOf(java.util.Arrays.stream(demand.ingredient().getItems()).map(ResourceLedger.Key::of).toList());
            groups.putIfAbsent(key, demand.ingredient());
            counts.merge(key, 1, Math::addExact);
        }
        for (var group : groups.entrySet()) {
            var ingredient = group.getValue();
            if (recipes.hasUnsupportedProducer(ingredient) || recipes.mayReturn(ingredient)) continue;
            int largest = recipes.producing(ingredient).stream().mapToInt(e -> e.output().getCount()).max().orElse(0);
            if (largest == 0) continue;
            long shortfall = (long) counts.get(group.getKey()) * request.batches() - initial.quantity(ingredient);
            long operations = request.batches() + (Math.max(0, shortfall) + largest - 1) / largest;
            if (operations > SearchBudget.MAX_STEPS) return true;
        }
        return false;
    }

    private int estimatedMissing(Entry entry, ResourceLedger ledger) {
        int result = 0;
        for (var requirement : entry.requirements()) if (!ledger.has(requirement.ingredient())) result++;
        return result;
    }

    private boolean advanceClosure(SearchBudget allowance) {
        // Presence-only relaxation can reject unseeded conversion cycles, but
        // never claims sufficient quantities. Exact quantities are still solved
        // by the ledger. It also keeps missing diamonds as diamonds instead of
        // explaining an impossible diamond-block round trip as nine diamonds.
        if (shared.value != null) return true;
        if (shared.working == null) {
            shared.working = initial.availableItems();
            shared.attempts++;
        }
        Set<Item> result = shared.working;
        while (true) {
            while (shared.cursor < recipes.entries().size()) {
                if (!allowance.canContinue()) return false;
                Entry entry = recipes.entries().get(shared.cursor++);
                // This relaxation ignores permissions so a genuinely needed
                // locked intermediate can still be diagnosed by search. Every
                // expanded recipe is checked there; this set never authorizes it.
                shared.changed |= recipes.addReachableOutputs(entry, result);
            }
            if (!shared.changed) {
                shared.value = Set.copyOf(result);
                shared.working = null;
                shared.builds++;
                return true;
            }
            shared.changed = false;
            shared.cursor = 0;
        }
    }

    private void remember(State state) {
        int progress = state.completed * SearchBudget.MAX_STEPS + state.steps.size();
        // Prefer real production, then actual assigned inputs. Never use the
        // reachability closure as evidence that a candidate's materials exist.
        // Stable ties keep the first route rather than the last visited failure.
        if (progress > bestProgress || progress == bestProgress && state.bindings > bestBindings) {
            bestProgress = progress;
            bestBindings = state.bindings;
            bestMissing = mergeMissing(state.missing);
            if (state.rejected != null) failure = state.rejected;
        }
    }

    private static CraftPlan.Missing missing(Need need) {
        return new CraftPlan.Missing(need.path, java.util.Arrays.stream(need.ingredient.getItems())
                .limit(16).map(s -> s.copyWithCount(1)).toList(), 1);
    }

    private static List<CraftPlan.Missing> mergeMissing(List<CraftPlan.Missing> source) {
        Map<Object, CraftPlan.Missing> result = new LinkedHashMap<>();
        for (var item : source) {
            var identity = item.alternatives().stream().map(ResourceLedger.Key::of).toList();
            var old = result.get(identity);
            result.put(identity, old == null ? item : new CraftPlan.Missing(old.path(), old.alternatives(),
                    Math.addExact(old.count(), item.count())));
        }
        return List.copyOf(result.values());
    }

    /** Strictly more of the same shortages, possibly with extra materials.
     * Different ingredient choices remain incomparable: they can still be a
     * legitimate route whose surplus fits when the preferred route does not. */
    private static boolean addsMissing(List<CraftPlan.Missing> candidate, List<CraftPlan.Missing> baseline) {
        var next = missingCounts(candidate);
        var previous = missingCounts(baseline);
        boolean additional = next.size() > previous.size();
        for (var entry : previous.entrySet()) {
            int count = next.getOrDefault(entry.getKey(), 0);
            if (count < entry.getValue()) return false;
            additional |= count > entry.getValue();
        }
        return additional;
    }

    private static Map<Object, Integer> missingCounts(List<CraftPlan.Missing> missing) {
        var counts = new HashMap<Object, Integer>();
        for (var item : missing) counts.merge(Set.copyOf(item.alternatives().stream().map(ResourceLedger.Key::of).toList()),
                item.count(), Math::addExact);
        return counts;
    }

    private record CapacityDiagnosis(CraftingResultCode code, List<CraftPlan.Missing> missing) {}

    private sealed interface Task permits Root, Need, Finish, Supply {}
    private record Root(Entry recipe) implements Task {}
    private record Need(Ingredient ingredient, int grid, int slot, String path, int depth, boolean producersOnly) implements Task {
        Need(Ingredient ingredient, int grid, int slot, String path, int depth) { this(ingredient, grid, slot, path, depth, false); }
    }
    private record Finish(Entry recipe, int grid, String path, boolean root) implements Task {}
    private record Supply(Need need, int producer) implements Task {}

    private static final class Grid {
        final Entry recipe;
        final ResourceLedger.Taken[] taken;
        boolean missing;
        Need parent;
        ResourceLedger before;
        int startSteps;
        int startMissing;
        int startBindings;
        Grid(Entry recipe) {
            this.recipe = recipe;
            taken = new ResourceLedger.Taken[recipe.gridSize() * recipe.gridSize()];
        }
        Grid copy() {
            Grid copy = new Grid(recipe);
            System.arraycopy(taken, 0, copy.taken, 0, taken.length);
            copy.missing = missing;
            copy.parent = parent;
            copy.before = before;
            copy.startSteps = startSteps;
            copy.startMissing = startMissing;
            copy.startBindings = startBindings;
            return copy;
        }
        List<ItemStack> items() {
            return java.util.Arrays.stream(taken).map(t -> t == null ? ItemStack.EMPTY : t.stack().copy()).toList();
        }
        Object identity() {
            return List.of(missing, java.util.Arrays.stream(taken).map(t -> t == null ? List.of()
                    : List.of(t.source(), t.lot(), ResourceLedger.Key.of(t.stack()))).toList());
        }
    }

    private static final class State {
        ResourceLedger ledger;
        final ArrayDeque<Task> tasks = new ArrayDeque<>();
        final Map<Integer, Grid> grids = new HashMap<>();
        final Map<Integer, Integer> results = new HashMap<>();
        final Set<ResourceLocation> active = new HashSet<>();
        final List<CraftPlan.Step> steps = new ArrayList<>();
        final List<CraftPlan.Missing> missing = new ArrayList<>();
        int nextGrid;
        int completed;
        int bindings;
        CraftingResultCode rejected;

        State(ResourceLedger ledger) { this.ledger = ledger; }
        long retainedBytes() {
            long bytes = 256L + ledger.retainedBytes() + tasks.size() * 128L + steps.size() * 2048L
                    + missing.size() * 2048L + results.size() * 64L;
            // Count shared preimages repeatedly rather than undercounting them.
            for (var grid : grids.values()) bytes += 512L + (grid.before == null ? 0 : grid.before.retainedBytes());
            return bytes;
        }
        State copy() {
            State copy = new State(ledger.copy());
            copy.tasks.addAll(tasks);
            grids.forEach((id, grid) -> copy.grids.put(id, grid.copy()));
            copy.results.putAll(results);
            copy.active.addAll(active);
            copy.steps.addAll(steps);
            copy.missing.addAll(missing);
            copy.nextGrid = nextGrid;
            copy.completed = completed;
            copy.bindings = bindings;
            copy.rejected = rejected;
            return copy;
        }
        Object identity(Need current) {
            var gridKeys = new java.util.TreeMap<Integer, Object>();
            grids.forEach((id, grid) -> gridKeys.put(id, grid.identity()));
            return List.of(current, List.copyOf(tasks), ledger.identity(), gridKeys, Set.copyOf(active),
                    completed, bindings, steps.stream().map(s -> List.of(s.recipe().id(), s.path())).toList(), Map.copyOf(results));
        }
    }
}
