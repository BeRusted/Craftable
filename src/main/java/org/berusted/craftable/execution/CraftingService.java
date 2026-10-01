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
        return preview(player, request, choicePath, null, -1);
    }

    public static Draft preview(ServerPlayer player, CraftRequest request, String choicePath,
            CraftPlan.Witness witness, long sequence) {
        if (!validContext(player)) return new Draft(new UUID(0, 0),
                org.berusted.craftable.planner.PlanView.failed(CraftingResultCode.INVALID_CONTEXT),
                new org.berusted.craftable.planner.PlanView.Choices(List.of(), false));
        CraftingSessions.discardOffer(player);
        if (witness != null && (!CraftingSessions.acceptSequence(player, sequence)
                || !CraftingSessions.acceptsWitness(player, witness))) return failedDraft(CraftingResultCode.ENVIRONMENT_CHANGED);
        try {
            var snapshot = EnvironmentSnapshotService.fresh(player);
            var prepared = witness == null ? prepare(player, request, snapshot, new SearchBudget(8_000_000L))
                    : prepareWitness(player, request, snapshot, witness, new SearchBudget(8_000_000L));
            var view = org.berusted.craftable.planner.PlanView.from(prepared.recipes(), prepared.request(),
                    prepared.result(), prepared.delivery().drops(), sources(snapshot).stream().map(ResourceLedger.Source::stack)
                            .filter(s -> !CraftingRecipes.protectedStack(s)).toList());
            // An incomplete display must never authorize an undisclosed plan.
            UUID token = view.complete() && (!view.primary().isEmpty())
                    && (prepared.result().plan().filter(CraftPlan::partial).isEmpty()
                        || request.partial() && prepared.request().policy() != CraftRequest.PartialPolicy.NEVER)
                    ? CraftingSessions.offer(player, prepared, witness != null) : new UUID(0, 0);
            return new Draft(token, view, view.choices(prepared.recipes(), prepared.request(), choicePath));
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
        if (!validContext(player)) {
            CraftingSessions.discardOffer(player);
            return Outcome.failed(CraftingResultCode.CONFIRMATION_EXPIRED);
        }
        if (witness != null && !CraftingSessions.acceptSequence(player, sequence))
            return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        var confirmation = CraftingSessions.take(player, token);
        if (confirmation == null) return Outcome.failed(CraftingResultCode.CONFIRMATION_EXPIRED);
        if (confirmation.witness() != (witness != null)
                || witness != null && !CraftingSessions.acceptsWitness(player, witness))
            return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
        return create(player, confirmation.request(), true, null, confirmation, witness);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget) {
        return create(player, request, confirmed, budget, null);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget,
            CraftingSessions.Confirmation confirmation) {
        return create(player, request, confirmed, budget, confirmation, null);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget,
            CraftingSessions.Confirmation confirmation, CraftPlan.Witness witness) {
        if (!validContext(player)) return Outcome.failed(CraftingResultCode.INVALID_CONTEXT);
        if (!ACTIVE.add(player.getUUID())) return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        try {
            var snapshot = EnvironmentSnapshotService.fresh(player);
            // Scanning has its own bounded M1 radius/volume. Start the search
            // deadline after scanning, while total server admission accounts
            // for the complete request separately.
            var allowance = budget == null ? new SearchBudget(8_000_000L) : budget;
            Prepared prepared = witness == null ? prepare(player, request, snapshot, allowance)
                    : prepareWitness(player, request, snapshot, witness, allowance);
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
        if (previous == null) CraftingSessions.rememberFailure(player, identity, scope, search.fullEvidence());
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
