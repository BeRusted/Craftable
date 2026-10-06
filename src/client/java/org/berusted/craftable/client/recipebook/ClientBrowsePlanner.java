package org.berusted.craftable.client.recipebook;

import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.CraftingPlanOverlay;
import org.berusted.craftable.client.config.CraftableClientConfig;
import org.berusted.craftable.environment.BrowsingSnapshot;
import org.berusted.craftable.execution.MainInventoryInsertion;
import org.berusted.craftable.client.network.CraftingDetailPayloads;
import org.berusted.craftable.client.network.CraftingWire;
import org.berusted.craftable.planner.*;
import org.berusted.craftable.recipe.PlanningInput;

/** One game-thread scheduler over the existing value solver. Static knowledge
 * lives across menus; dynamic evidence belongs to exactly one authorized value
 * identity. Neither previews nor client failure claims authorize execution. */
public final class ClientBrowsePlanner {
    private static final PlanningInput.Catalog CATALOG = new PlanningInput.Catalog();
    private static Object connection, recipeCollection, generation = new Object(), menu;
    private static long recipeVersion = -1, revision, sentAt = Long.MIN_VALUE;
    private static int menuId;
    private static final Turns TURNS = new Turns();
    private static CraftingDetailPayloads.BrowseLease grant;
    private static CraftingWire.SnapshotReceiver receiver;
    private static CraftingWire.SnapshotDecoder decoder;
    private static CraftingWire.SnapshotHeader receiving;
    private static BrowsingSnapshot snapshot;
    private static PlanningInput input;
    private static CraftSearch.Reachability closure;
    private static List<ResourceLocation> foreground = List.of(), hidden = List.of(), hovered = List.of();
    private static Task front, background;
    private static ResourceLocation lastCrafted;
    private static boolean drops;
    private static CraftRequest.PartialPolicy policy;
    private static long searches, slices;
    private static DetailWork detail;
    private static CraftRequest retainedRequest;
    private static SearchResult retainedResult;
    // One presentation-only material ceiling within the same immutable scope.
    // Larger unknown verdicts remain unknown; they are not full-failure proof.
    private static CraftRequest ceilingRequest;
    private static java.util.OptionalInt materialCeiling = java.util.OptionalInt.empty();
    private static boolean ceilingAttempted;
    private static long scopeVersion;
    private static long lastTickNanos, foregroundSlices, hiddenSlices;
    private ClientBrowsePlanner() {}

    public static void scope(List<ResourceLocation> visible, List<ResourceLocation> candidates, List<ResourceLocation> hover) {
        foreground = List.copyOf(visible); hidden = List.copyOf(candidates); hovered = List.copyOf(hover);
        // A scope change may cancel irrelevant unfinished work, but cannot
        // manufacture a fresh budget for it when the player returns later.
        if (background != null && !hidden.contains(background.id) && !foreground.contains(background.id)) {
            abandon(background); background = null;
        }
    }

    public static void tick(net.minecraft.client.Minecraft client) {
        long sliceStart = System.nanoTime();
        try { advanceTick(sliceStart); }
        finally { lastTickNanos = System.nanoTime() - sliceStart; }
    }

    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(ClientBrowsePlanner::tick);
    }

    private static void advanceTick(long sliceStart) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.level == null || mc.player == null) {
            if (connection != null) disconnect();
            return;
        }
        if (connection != mc.getConnection()) { disconnect(); connection = mc.getConnection(); }
        var collection = mc.level.getRecipeManager().getRecipes();
        if (recipeCollection != collection) { recipeCollection = collection; recipesChanged(); }
        boolean open = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(CraftingDetailPayloads.BrowseRequest.TYPE)
                && RecipeBookProjection.modeAllowed() && CraftableClientConfig.recipeBookEnhancementsEnabled()
                && (RecipeBookProjection.component(mc.screen) != null || CraftingPlanOverlay.active());
        if (!open) { closeView(); return; }
        if (menu != mc.player.containerMenu) {
            closeView(); menu = mc.player.containerMenu; menuId = mc.player.containerMenu.containerId;
            revision = ClientRequestSequence.next();
            ClientRecipeStatusStore.beginLocal();
        }
        long now = mc.level.getGameTime();
        if (sentAt == Long.MIN_VALUE || now - sentAt >= 8 || now < sentAt) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new CraftingDetailPayloads.BrowseRequest(menuId, revision, true,
                    snapshot == null ? CraftingDetailPayloads.NO_TOKEN : snapshot.session(), snapshot == null ? -1 : snapshot.resources()));
            sentAt = now;
        }
        if (receiver != null && receiver.expired(System.nanoTime())) {
            receiver.close(); receiver = null; // Remain unknown; no periodic new search allowance.
        }
        if (decoder != null) {
            try {
                if (!decoder.advance(Math.max(1, 2_000_000L - (System.nanoTime() - sliceStart)))) return;
                var decoded = decoder.result();
                // Reserve both bounded continuations and the entire minimal
                // verdict table before publishing this dynamic value scope.
                long estimate = 2L * receiving.bytes() + 256L * decoded.inputs().size()
                        + 128L * decoded.unlocked().size() + 36L * 1024 + 64L * 1024
                        + 2L * 4 * 1024 * 1024 + 16_384L * 128;
                if (estimate > 16L * 1024 * 1024) throw new IllegalStateException("Local browsing memory bound");
                if (grant != null && receiving != null && decoded.session().equals(receiving.session())
                        && decoded.recipes() == receiving.recipes() && decoded.resources() == receiving.resources()
                        && decoded.session().equals(grant.session()) && decoded.resources() == grant.resources()
                        && decoded.recipes() == grant.recipes()) {
                    snapshot = decoded; input = null; resetCalculations();
                }
                decoder.close(); decoder = null;
            } catch (RuntimeException failure) {
                decoder.close(); decoder = null;
                Craftable.LOGGER.warn("Rejected browsing snapshot ({})", failure.getClass().getSimpleName());
            }
            return;
        }
        boolean fresh = grant != null && snapshot != null && grant.session().equals(snapshot.session())
                && grant.resources() == snapshot.resources() && now <= grant.expires();
        if (grant == null) { ClientRecipeStatusStore.authorizeLocal(false); return; }
        CATALOG.observe(connection, generation, collection, mc.level.registryAccess());
        if (CATALOG.state() == PlanningInput.Catalog.State.BUILDING) {
            ClientRecipeStatusStore.authorizeLocal(false);
            long remaining = 2_000_000L - (System.nanoTime() - sliceStart);
            if (remaining > 0) CATALOG.advance(remaining);
            return; // Catalog and search never each receive a 2 ms slice in one tick.
        }
        if (CATALOG.state() != PlanningInput.Catalog.State.READY || snapshot == null || grant == null) return;
        if (input == null) input = CATALOG.bind(snapshot.workbench(), snapshot.limitedCrafting(), snapshot.unlocked());
        boolean agrees = input.fingerprint().equals(grant.fingerprint());
        if (!agrees) { ClientRecipeStatusStore.authorizeLocal(false); return; }
        boolean effectiveDrops = snapshot.allowDrops() && CraftableClientConfig.allowSurplusDrops();
        var effectivePolicy = snapshot.partialPolicy().restrict(CraftableClientConfig.partialPolicy());
        if (closure == null || drops != effectiveDrops || policy != effectivePolicy) {
            resetCalculations(); drops = effectiveDrops; policy = effectivePolicy;
            ClientRecipeStatusStore.defaults(drops, policy);
            closure = CraftSearch.Reachability.resumable();
        }
        // Resetting a value scope clears its store. Authorize *after* that
        // reset, so a freshly bound valid snapshot does not blink for one tick.
        ClientRecipeStatusStore.authorizeLocal(fresh);
        if (!CraftingPlanOverlay.active()) cancelDetails();
        // A completed cheap target need not idle until the next game tick.
        // Every dispatch keeps the same deadline and the same 3:1 fairness;
        // continuations keep their cumulative budgets. The count bound also
        // caps cached projections/queue scans on unusually fast machines.
        for (int dispatched = 0; dispatched < 64 && System.nanoTime() - sliceStart < 2_000_000L; dispatched++)
            if (!dispatch(sliceStart)) break;
    }

    private static boolean dispatch(long sliceStart) {
        if (front == null) {
            // Revalidate the target under the pointer before the rest of the
            // visible page, without bypassing the existing hidden-task turn.
            if (lastCrafted != null && detail == null) {
                if ((background == null || !background.id.equals(lastCrafted)) && needsSearch(lastCrafted))
                    front = task(lastCrafted, false);
                lastCrafted = null;
            }
            for (var id : hovered) if (front == null && detail == null && ClientRecipeStatusStore.needsDiagnostic(id)
                    && (background == null || !background.id.equals(id))) { front = task(id, true); break; }
            if (front == null && detail == null) for (var id : foreground) {
                if (System.nanoTime() - sliceStart >= 2_000_000L) return false;
                if ((background == null || !background.id.equals(id)) && needsSearch(id)) { front = task(id, false); break; }
            }
        }
        if (background == null) for (var id : hidden) {
            if (System.nanoTime() - sliceStart >= 2_000_000L) return false;
            if ((front == null || !front.id.equals(id)) && (detail == null || !detail.request.recipe().equals(id))
                    && needsSearch(id)) { background = task(id, false); break; }
        }
        // Keep a running foreground task when the mouse moves. Every fourth
        // eligible slice belongs to the hidden head, even under hover churn.
        // An intent waiting for renewed authority is not runnable foreground
        // work. It must not block same-value hidden mathematics indefinitely;
        // presentation/execution still require the fresh lease.
        boolean detailReady = detail != null && ready();
        boolean hiddenTurn = TURNS.hidden(front != null || detailReady, background != null);
        if (!hiddenTurn && front == null && detailReady) {
            if (System.nanoTime() - sliceStart >= 2_000_000L) return false;
            var previous = detail;
            front = detailTask();
            if (front == null) {
                // Cached projection also uses a foreground slice, never the
                // hidden head's reserved fourth turn.
                if (detail == previous) return false; // Waiting for authority is not completed work.
                TURNS.served(false); slices++; foregroundSlices++; return true;
            }
        }
        var selected = hiddenTurn ? background : front;
        if (selected == null) return false;
        long remaining = 2_000_000L - (System.nanoTime() - sliceStart);
        if (remaining <= 0) return false;
        TURNS.served(hiddenTurn); slices++;
        if (hiddenTurn) hiddenSlices++; else foregroundSlices++;
        java.util.Optional<SearchResult> result;
        do {
            result = selected.search.advance(remaining);
            remaining = 2_000_000L - (System.nanoTime() - sliceStart);
        } while (result.isEmpty() && remaining > 0);
        if (result.isPresent()) {
            if (hiddenTurn) background = null; else front = null;
            if (selected.owner != null) finishDetail(selected, result.get());
            else ClientRecipeStatusStore.completeLocal(selected.request, result.get(), selected.search.fullEvidence(), selected.diagnostic);
        }
        return result.isPresent();
    }

    private static boolean needsSearch(ResourceLocation id) {
        if (ClientRecipeStatusStore.computed(id) || !ClientRecipeStatusStore.canRecord(id)) return false;
        // Test the shared necessary condition before *every* allocation, not
        // only an earlier batch of 64 IDs. Faster dispatch must not bypass the
        // closure and turn hundreds of excluded roots into quantity searches.
        var request = new CraftRequest(id, 1, false, drops, policy, Map.of());
        var evidence = closure.excludes(input, mathematical(request, false));
        if (evidence == null) return true;
        ClientRecipeStatusStore.completeLocal(id,
                SearchResult.blocked(org.berusted.craftable.api.CraftingResultCode.MISSING_INGREDIENTS), evidence, false);
        return false;
    }

    private static Task task(ResourceLocation id, boolean diagnostic) {
        var request = new CraftRequest(id, 1, diagnostic, drops, policy, Map.of());
        return task(request, diagnostic, null);
    }

    private static Task task(CraftRequest request, boolean diagnostic, DetailWork owner) {
        searches++;
        // Values are copied once per resource scope by BrowsingSnapshot. No
        // Level/container reads and no alternate client allocation algorithm.
        var inventory = snapshot.inventory();
        var playerRefs = snapshot.playerReferences();
        var allRefs = snapshot.references();
        var search = new CraftSearch(input, mathematical(request, diagnostic), snapshot.sources(), SearchBudget.resumable(8_000_000L),
                plan -> MainInventoryInsertion.simulate(inventory, snapshot.inventoryMaximum(), plan,
                        playerRefs, allRefs, request.allowDrops()).failure()).withReachability(closure);
        requestCeiling(search, owner);
        var evidence = ClientRecipeStatusStore.evidence(request);
        if (diagnostic) search.withFullEvidence(evidence)
                .withDiagnosticBudget(SearchBudget.resumable(8_000_000L));
        return new Task(request.recipe(), diagnostic, search, owner, request);
    }

    /** Read-only preparation must remain available under NEVER. Keep the
     * mathematical policy identical in both phases so full evidence matches;
     * the original intent still controls display, witness and server actions. */
    private static CraftRequest mathematical(CraftRequest request, boolean diagnostic) {
        return new CraftRequest(request.recipe(), request.batches(), diagnostic, request.allowDrops(),
                CraftRequest.PartialPolicy.EXPLICIT_SAFE, request.selections());
    }

    public static long scopeVersion() { return scopeVersion; }

    /** Presentation-only full OR identity; no new query or catalog build. */
    public static Map<String, Object> alternativeGroups(PlanView view) {
        return ready() ? view.alternativeGroups(input) : Map.of();
    }

    /** Export only an already published review or matching retained complete plan. A
     * missing/partial plan uses the existing explicit server path, not another
     * local search or a second plan cache. */
    public static org.berusted.craftable.planner.CraftPlan.Witness witness(CraftRequest request) {
        if (!ready() || request.partial()) return null;
        var displayed = org.berusted.craftable.client.CraftingPlanOverlay.reviewWitness(effective(request), scopeVersion);
        if (displayed != null) return displayed;
        if (retainedRequest == null || retainedResult == null
                || !effective(request).withPartial(false).equals(retainedRequest.withPartial(false))) return null;
        return boundedWitness(request, retainedResult);
    }

    /** A bounded, untrusted replay description of the actual displayed result,
     * not another retained plan or an execution authorization. */
    private static CraftPlan.Witness boundedWitness(CraftRequest request, SearchResult result) {
        if (!ready() || request.partial() || result.code() != org.berusted.craftable.api.CraftingResultCode.CREATED
                || !result.completeSearch()) return null;
        try {
            var witness = result.plan().filter(plan -> !plan.partial())
                    .map(plan -> org.berusted.craftable.planner.CraftPlan.Witness.from(plan, snapshot)).orElse(null);
            return witness != null && CraftingDetailPayloads.fitsWitness(request, witness, Minecraft.getInstance().level.registryAccess())
                    ? witness : null;
        } catch (IllegalArgumentException unsupported) { return null; }
    }
    public static boolean ready() {
        var mc = Minecraft.getInstance();
        return input != null && closure != null && snapshot != null && grant != null && mc.level != null
                && snapshot.session().equals(grant.session()) && snapshot.resources() == grant.resources()
                && mc.level.getGameTime() <= grant.expires() && input.fingerprint().equals(grant.fingerprint());
    }

    /** Bounded root cards from the vanilla button's group, not recursive OR
     * demands. All verdicts still use preview(candidateIntent) and execution
     * still validates the selected root against the current server catalog. */
    public static PlanView.Choices outputChoices(PlanView view, CraftRequest request, List<ResourceLocation> variants) {
        if (!ready() || view.nodes().stream().noneMatch(n -> n.path().equals("0")))
            return new PlanView.Choices(List.of(), false);
        var sameOutput = view.choices(input, request, "0");
        var ids = new java.util.LinkedHashSet<ResourceLocation>();
        ids.add(request.recipe()); // Selected variant must survive the card cap.
        variants.stream().limit(17).forEach(ids::add);
        sameOutput.candidates().forEach(c -> ids.add(c.recipe()));
        var entries = ids.stream().map(input::find).filter(java.util.Objects::nonNull).toList();
        return new PlanView.Choices(entries.stream().limit(SearchBudget.MAX_CANDIDATES)
                .map(e -> new PlanView.Candidate(e.id(), e.output(), e.gridSize(), input.unavailable(e))).toList(),
                entries.size() > SearchBudget.MAX_CANDIDATES || sameOutput.truncated());
    }

    /** One latest UI intent, never a second scheduler or a server request. */
    public static void preview(long sequence, CraftRequest request, String path) {
        detail = new DetailWork(sequence, effective(request), path, false);
        if (front != null && front.request.withPartial(false).equals(detail.request.withPartial(false)))
            front = new Task(front.id, front.diagnostic, front.search, detail, front.request);
    }
    public static void maximum(long sequence, CraftRequest request) {
        var effective = effective(request).withBatches(1).withPartial(false);
        if (!effective.equals(ceilingRequest)) {
            ceilingRequest = effective; materialCeiling = java.util.OptionalInt.empty(); ceilingAttempted = false;
        }
        detail = new DetailWork(sequence, effective, "", true);
    }

    private static void requestCeiling(CraftSearch search, DetailWork work) {
        if (work != null && work.maximum && work.request.equals(ceilingRequest) && !ceilingAttempted && materialCeiling.isEmpty()) {
            search.withMaterialUpperBound();
        }
    }

    private static int maximumSearchCap(DetailWork work) {
        return work.request.equals(ceilingRequest) && materialCeiling.isPresent()
                ? Math.min(snapshot.maxBatches(), materialCeiling.getAsInt()) : snapshot.maxBatches();
    }
    private static CraftRequest effective(CraftRequest request) {
        return new CraftRequest(request.recipe(), request.batches(), request.partial(),
                request.allowDrops() && drops, request.policy().restrict(policy), request.selections());
    }

    public static void cancelDetails() {
        detail = null;
        if (front != null && front.owner != null) {
            // Cancellation is terminal for this identity, not a fresh 8 ms
            // allowance when a slider or mouse returns to the same request.
            ClientRecipeStatusStore.completeLocal(front.request,
                    SearchResult.blocked(org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED),
                    front.search.fullEvidence(), front.diagnostic);
            front.search.cancel(); front = null;
        }
    }

    private static Task detailTask() {
        var work = detail;
        if (!ready()) return null;
        CraftRequest request = work.request;
        if (work.maximum) {
            if (!maximumView(request, snapshot.maxBatches(), maximumSearchCap(work)).pending()) {
                publishMaximum(work); return null;
            }
            boolean found = false;
            boolean bounded = work.request.equals(ceilingRequest) && materialCeiling.isPresent();
            int cap = maximumSearchCap(work);
            // After a certified material ceiling, try the largest quantity
            // first. One positive witness there subsumes all smaller counts;
            // failures still require every larger count to be checked.
            for (int visit = 1; visit <= cap; visit++) {
                int count = bounded ? cap - visit + 1 : visit;
                if (!ClientRecipeStatusStore.computed(request.withBatches(count))) {
                    request = request.withBatches(count); found = true; break;
                }
            }
            if (!found) { publishMaximum(work); return null; }
        } else if (retainedRequest != null && request.withPartial(false).equals(retainedRequest.withPartial(false))
                && retainedResult != null) {
            publishPreview(work, retainedResult); return null;
        }
        if (!ClientRecipeStatusStore.canRecord(request)) {
            if (work.maximum) {
                detail = null;
                CraftingPlanOverlay.receiveMaximum(
                        menuId, work.sequence, new org.berusted.craftable.execution.CraftingService.Maximum(
                                0, false, snapshot.maxBatches(), true, false));
            } else publishPreview(work, SearchResult.blocked(org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
            return null; // A full result store is terminal, not an infinite MAX loop.
        }
        if (ClientRecipeStatusStore.exhausted(request) || request.batches() > snapshot.maxBatches()) {
            var unknown = SearchResult.blocked(org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            if (work.maximum) {
                ClientRecipeStatusStore.completeLocal(request, unknown, null, false); publishMaximum(work);
            }
            else publishPreview(work, unknown);
            return null;
        }
        if (!work.maximum && ClientRecipeStatusStore.diagnosticExhausted(request)) {
            // An unchanged lease, a re-opened detail, or the overlay's intent
            // promotion cannot replenish a completed diagnostic allowance.
            publishPreview(work, SearchResult.blocked(ClientRecipeStatusStore.diagnosticReason(request)));
            return null;
        }
        // A browsing full-failure proof is valid for the identical intent only;
        // CraftSearch checks it again before entering its diagnostic phase.
        if (background != null && background.request.withPartial(false).equals(request.withPartial(false))) {
            var promoted = new Task(background.id, background.diagnostic, background.search, work, background.request);
            requestCeiling(promoted.search, work);
            background = null;
            return promoted; // Transfer the same continuation; never duplicate the hidden search.
        }
        return task(request.withPartial(!work.maximum), !work.maximum, work);
    }

    private static void finishDetail(Task task, SearchResult result) {
        ClientRecipeStatusStore.completeLocal(task.request, result, task.search.fullEvidence(), task.diagnostic);
        if (task.request.withBatches(1).withPartial(false).equals(ceilingRequest)) {
            // Existing-stock completion does not build a closure or attempt
            // this certificate. The next quantity keeps its own old allowance.
            ceilingAttempted |= task.search.materialUpperBoundAttempted();
            if (task.search.materialUpperBound().isPresent()) materialCeiling = task.search.materialUpperBound();
        }
        if (task.owner.maximum) retainMaximum(task.request, result);
        if (task.owner != detail) return; // Changed UI intent cannot receive an old result.
        if (task.owner.maximum) {
            publishMaximum(task.owner);
        } else {
            if (!task.diagnostic && result.plan().isEmpty() && task.search.fullEvidence() != null)
                return; // Next foreground slice upgrades the same proof to diagnostic, without full re-search.
            retainedRequest = task.owner.request; retainedResult = result;
            // Project in a later foreground slice, not after a solver slice
            // that may already have spent the entire 2 ms allowance.
        }
    }

    private static void retainMaximum(CraftRequest request, SearchResult result) {
        if (result.plan().isEmpty() || result.plan().orElseThrow().partial()) return;
        var plan = result.plan().orElseThrow();
        var delivery = MainInventoryInsertion.simulate(snapshot.inventory(), snapshot.inventoryMaximum(),
                plan, snapshot.playerReferences(), snapshot.references(), request.allowDrops());
        var view = PlanView.from(input, request, result, delivery.drops(),
                snapshot.sources().stream().map(ResourceLedger.Source::stack).toList());
        var candidate = new org.berusted.craftable.execution.CraftingService.Draft(
                CraftingDetailPayloads.NO_TOKEN, view, new PlanView.Choices(List.of(), false));
        // MAX may never be rendered. Check its complete projection before
        // keeping the one reusable plan, not just its smaller input witness.
        // Failure leaves the previous slot and the proved full verdict intact;
        // actual previews still encode their own choice cards below.
        if (view.code() != org.berusted.craftable.api.CraftingResultCode.CREATED
                || !view.complete() || CraftingDetailPayloads.boundedLocal(candidate,
                        Minecraft.getInstance().level.registryAccess()) != candidate) return;
        retainedRequest = request; retainedResult = result;
    }

    private static void publishPreview(DetailWork work, SearchResult result) {
        detail = null;
        var delivery = result.plan().map(plan -> MainInventoryInsertion.simulate(snapshot.inventory(),
                snapshot.inventoryMaximum(), plan, snapshot.playerReferences(), snapshot.references(), work.request.allowDrops()));
        var view = PlanView.from(input, work.request, result,
                delivery.map(MainInventoryInsertion.Delivery::drops).orElse(List.of()),
                snapshot.sources().stream().map(ResourceLedger.Source::stack).toList());
        // Local displays deliberately have no token. The only authority path
        // remains a fresh server review followed by its one-use confirmation.
        var draft = CraftingDetailPayloads.boundedLocal(new org.berusted.craftable.execution.CraftingService.Draft(
                CraftingDetailPayloads.NO_TOKEN, view, view.choices(input, work.request, work.path)),
                Minecraft.getInstance().level.registryAccess());
        if (draft.view() != view) {
            retainedRequest = null; retainedResult = null;
            ClientRecipeStatusStore.completeLocal(work.request, SearchResult.blocked(draft.view().code()), null, true);
        }
        CraftingPlanOverlay.receiveLocal(
                new CraftingDetailPayloads.PreviewResponse(menuId, work.sequence, draft), work.request,
                draft.view() == view && view.complete() ? boundedWitness(work.request, result) : null, scopeVersion);
    }

    private static void publishMaximum(DetailWork work) {
        var maximum = maximumView(work.request, snapshot.maxBatches(), maximumSearchCap(work));
        if (!maximum.pending()) detail = null;
        CraftingPlanOverlay.receiveMaximum(menuId, work.sequence, maximum);
    }

    static org.berusted.craftable.execution.CraftingService.Maximum maximumView(CraftRequest intent, int cap) {
        return maximumView(intent, cap, cap);
    }

    static org.berusted.craftable.execution.CraftingService.Maximum maximumView(CraftRequest intent, int cap, int materialCap) {
        int lower = 0, highestUnknown = 0, highestPending = 0;
        for (int count = 1; count <= Math.min(cap, materialCap); count++) {
            var request = intent.withBatches(count);
            if (!ClientRecipeStatusStore.computed(request)) highestPending = count;
            else if (ClientRecipeStatusStore.reason(request) == org.berusted.craftable.api.CraftingResultCode.CREATED) lower = count;
            else if (ClientRecipeStatusStore.exhausted(request)) highestUnknown = count;
        }
        // Capacity is non-monotone. Every larger count must be definitively
        // excluded; an unknown lower count is subsumed by a proven larger one.
        boolean limited = highestUnknown > lower;
        boolean pending = highestPending > lower;
        return new org.berusted.craftable.execution.CraftingService.Maximum(lower, !pending && !limited, cap, limited, pending);
    }

    public static void receive(CraftingDetailPayloads.BrowseLease value) {
        if (!accepts(value.menuId(), value.revision())) return;
        if (value.recipes() < recipeVersion) return;
        if (snapshot != null && snapshot.session().equals(value.session()) && value.resources() < snapshot.resources()) return;
        if (recipeVersion != value.recipes()) {
            recipeVersion = value.recipes(); generation = new Object(); CATALOG.clear(); input = null;
            ClientRecipeStatusStore.beginLocal(); // A recipe reload is not an inventory refresh.
            resetCalculations();
        }
        grant = value;
        org.berusted.craftable.client.menu.AmbientInventoryEvents.receiveRules(value.settings(), value.workbench());
        if (snapshot != null && (!snapshot.session().equals(value.session()) || snapshot.resources() != value.resources())) {
            if (!snapshot.session().equals(value.session())) ClientRecipeStatusStore.beginLocal();
            snapshot = null; input = null; resetCalculations();
        }
        if (value.header() != null) {
            if (receiver != null) receiver.close();
            if (decoder != null) decoder.close(); decoder = null;
            receiving = value.header(); receiver = new CraftingWire.SnapshotReceiver(receiving, System.nanoTime());
        }
    }

    public static void receive(CraftingDetailPayloads.BrowseChunk value) {
        if (!accepts(value.menuId(), value.revision()) || receiver == null) return;
        long now = System.nanoTime();
        if (!receiver.accept(value.session(), value.transfer(), value.index(), value.data(), now) || !receiver.complete()) return;
        try {
            if (decoder != null) decoder.close();
            decoder = new CraftingWire.SnapshotDecoder(receiver.take(now), Minecraft.getInstance().level.registryAccess());
        } catch (RuntimeException failure) {
            Craftable.LOGGER.warn("Rejected browsing snapshot ({})", failure.getClass().getSimpleName());
        } finally { receiver.close(); receiver = null; }
    }

    private static boolean accepts(int id, long version) {
        var mc = Minecraft.getInstance();
        return menu != null && mc.player != null && mc.player.containerMenu == menu && id == menuId && version == revision;
    }

    private static void abandon(Task task) {
        task.search.cancel();
        ClientRecipeStatusStore.completeLocal(task.request,
                SearchResult.blocked(org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED),
                task.search.fullEvidence(), task.diagnostic);
    }

    private static void resetCalculations() {
        if (front != null) front.search.cancel();
        if (background != null) background.search.cancel();
        front = background = null; closure = null;
        resetDetails();
        ClientRecipeStatusStore.beginRefresh();
    }

    private static void resetDetails() {
        detail = null; retainedRequest = null; retainedResult = null; scopeVersion++;
        ceilingRequest = null; materialCeiling = java.util.OptionalInt.empty(); ceilingAttempted = false;
    }

    public static void invalidate() {
        // Hard reset for explicit refresh/lifecycle boundaries only. Ordinary
        // crafting keeps this session and follows afterCreate's lease barrier.
        closeView();
        ClientRecipeStatusStore.beginLocal();
    }

    public static void afterCreate(ResourceLocation recipe) {
        if (menu == null) return;
        lastCrafted = recipe;
        // Keep the browse nonce and the last page. Ordered play packets ensure
        // pre-action leases precede the result; only a later lease can renew
        // authority. Pending old chunks cannot publish without that new lease.
        grant = null;
        ClientRecipeStatusStore.authorizeLocal(false);
        resetDetails();
    }

    /** Request a fresh authorization through the existing browse session.
     * Unchanged resources renew the lease, not the graph or search budgets. */
    public static void requestRefresh() {
        if (menu == null) return;
        grant = null;
        sentAt = Long.MIN_VALUE;
        ClientRecipeStatusStore.authorizeLocal(false);
    }

    public static void recipesChanged() {
        generation = new Object(); CATALOG.clear(); input = null;
        invalidate();
    }

    private static void closeView() {
        if (menu != null && connection == Minecraft.getInstance().getConnection() && connection != null)
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new CraftingDetailPayloads.BrowseRequest(menuId, revision, false,
                    CraftingDetailPayloads.NO_TOKEN, -1));
        menu = null; grant = null; snapshot = null; input = null; sentAt = Long.MIN_VALUE;
        if (receiver != null) receiver.close(); receiver = null; receiving = null;
        if (decoder != null) decoder.close(); decoder = null;
        if (front != null) front.search.cancel(); if (background != null) background.search.cancel();
        front = background = null; closure = null;
        lastCrafted = null;
        resetDetails();
        ClientRecipeStatusStore.authorizeLocal(false);
    }

    public static void disconnect() {
        closeView(); CATALOG.clear(); connection = recipeCollection = null; recipeVersion = -1;
        generation = new Object(); foreground = hidden = hovered = List.of();
        ClientRecipeStatusStore.clear();
    }

    /** Counters used by real-client acceptance, never an authority interface. */
    public static long searches() { return searches; }
    public static long slices() { return slices; }
    public static long lastTickNanos() { return lastTickNanos; }
    public static long foregroundSlices() { return foregroundSlices; }
    public static long hiddenSlices() { return hiddenSlices; }
    public static int catalogBuilds() { return CATALOG.builds(); }
    static final class Turns {
        private int consecutive;
        boolean hidden(boolean front, boolean background) { return background && (!front || consecutive >= 3); }
        void served(boolean hidden) { consecutive = hidden ? 0 : Math.min(3, consecutive + 1); }
    }
    private record Task(ResourceLocation id, boolean diagnostic, CraftSearch search, DetailWork owner, CraftRequest request) {}
    private record DetailWork(long sequence, CraftRequest request, String path, boolean maximum) {}
}
