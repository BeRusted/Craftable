package org.berusted.craftable.client;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.client.menu.AmbientInventoryEvents;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.execution.CraftingService;
import org.berusted.craftable.network.CraftingDetailPayloads;
import org.berusted.craftable.planner.CraftRequest;

/** Controlled lease-renewal regression after the unchanged natural stage-7 assertion passes.
 * Same-value leases enter the production receiver and overlay tick, without a
 * server packet, resource mutation, replacement plan or enlarged search budget.
 * This guards immediate control refresh, not historical reproduction of the old failure. */
public final class Stage7ConfirmDiagnosticScenario {
    private Stage7ConfirmDiagnosticScenario() {}

    public static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        var overlay = (CraftingPlanOverlay) get(CraftingPlanOverlay.class, null, "current");
        require(overlay != null && mc.screen instanceof AmbientInventoryScreen,
                "Controlled stage-7 regression needs the real ambient detail parent");
        var draft = (CraftingService.Draft) get(CraftingPlanOverlay.class, overlay, "draft");
        var intent = (CraftRequest) get(CraftingPlanOverlay.class, overlay, "intent");
        var create = (Button) get(CraftingPlanOverlay.class, overlay, "create");
        var grant = (CraftingDetailPayloads.BrowseLease) get(ClientBrowsePlanner.class, null, "grant");
        require(fixtureReady() && ClientBrowsePlanner.ready() && create.active && expectedActive(overlay),
                "Controlled regression must follow a naturally enabled full-confirm assertion");
        require(draft.view().code() == CraftingResultCode.CREATED && draft.view().complete()
                        && !intent.partial() && (boolean) get(CraftingPlanOverlay.class, overlay, "localDraft")
                        && draft.token().equals(CraftingDetailPayloads.NO_TOKEN) && grant != null,
                "Controlled regression must not replace a failed preview or use a server token");
        require(Objects.equals(get(AmbientInventoryEvents.class, null, "rules"), grant.settings()),
                "Same-value receiver fixture must already have the live lease's scan rules");

        long sentAt = (long) get(CraftingPlanOverlay.class, overlay, "sentAt");
        long unavailableSince = (long) get(CraftingPlanOverlay.class, overlay, "unavailableSince");
        Object originalPending = get(CraftingPlanOverlay.class, overlay, "pending");
        require(originalPending == null, "Controlled regression requires no real pending operation");
        var overlayState = save(CraftingPlanOverlay.class, overlay,
                "intent", "draft", "maximum", "pane", "pending", "dirty", "localDraft", "queuedAction",
                "generation", "sentGeneration", "inFlight", "resourceScope", "changedAt", "fallbackUsed",
                "displayedWitness", "displayedWitnessRequest", "displayedWitnessScope", "submittedWitness",
                "reviewedIdentity", "displayedView", "displayedAlternativeGroups", "choicePath", "selectedPaths",
                "singleRoute", "routeChanged");
        var plannerState = save(ClientBrowsePlanner.class, null,
                "snapshot", "input", "closure", "scopeVersion", "detail", "front", "background",
                "retainedRequest", "retainedResult", "maximumProjection", "searches", "slices");
        var menuState = save(AmbientInventoryEvents.class, null, "rules", "requestedFrom", "requestedAt");
        long revision = ClientRecipeStatusStore.revision();
        long authority = ClientRecipeStatusStore.authorityRevision();
        long presentation = ClientRecipeStatusStore.presentationRevision();
        boolean expiredObserved = false, renewImmediateObserved = false;
        boolean maximumExpiredObserved = false, maximumRenewImmediateObserved = false;
        try {
            var expired = sameValueLease(grant, mc.level.getGameTime() - 1);
            ClientBrowsePlanner.receive(expired);
            require(get(ClientBrowsePlanner.class, null, "grant") == expired,
                    "Production receiver did not accept the same-value expired lease");
            overlay.tick();
            require(!ClientBrowsePlanner.ready() && !create.active && fixtureReady() && !expectedActive(overlay),
                    "Expired lease did not expose the original fixture-ready/authority-ready distinction");
            expiredObserved = true;
            verifyUnchanged(overlay, overlayState, plannerState, menuState, revision, authority, presentation);
            Craftable.LOGGER.warn("M4_STAGE7_LEASE_REGRESSION injected=true naturalHistoricalReproduction=false"
                    + " phase=expired fixtureReady=true plannerReady=false expectedCreate=false create=false");

            var renewed = sameValueLease(grant, grant.expires());
            ClientBrowsePlanner.receive(renewed);
            require(get(ClientBrowsePlanner.class, null, "grant") == renewed && ClientBrowsePlanner.ready(),
                    "Same-value original fresh lease did not renew production authority");
            // Only cadence is controlled. No pending request, dirty flag,
            // mathematical result, witness or original assertion is replaced.
            set(CraftingPlanOverlay.class, overlay, "sentAt", System.nanoTime());
            overlay.tick();
            boolean expectedCreate = expectedActive(overlay);
            renewImmediateObserved = fixtureReady() && ClientBrowsePlanner.ready() && expectedCreate && create.active;
            Craftable.LOGGER.warn("M4_STAGE7_LEASE_REGRESSION injected=true naturalHistoricalReproduction=false"
                    + " phase=renewImmediate fixtureReady={} plannerReady={} expectedCreate={} create={}"
                    + " sentAgeMs={}", fixtureReady(), ClientBrowsePlanner.ready(), expectedCreate, create.active,
                    (System.nanoTime() - (long) get(CraftingPlanOverlay.class, overlay, "sentAt")) / 1_000_000.0);
            require(renewImmediateObserved,
                    "Same-value lease renewal must immediately enable full confirm inside the 200-ms cadence window");
            verifyUnchanged(overlay, overlayState, plannerState, menuState, revision, authority, presentation);

            // Model only the MAXIMUM early-return boundary. This is not a real
            // MAX request: no planner advance, packet, generation or result is
            // changed, and the full planning smoke still covers real MAX work.
            long boundarySentAt = (long) get(CraftingPlanOverlay.class, overlay, "sentAt");
            set(CraftingPlanOverlay.class, overlay, "pending", maximumWork());
            try {
                ClientBrowsePlanner.receive(expired);
                overlay.tick();
                boolean expiredExpected = expectedActive(overlay);
                maximumExpiredObserved = !ClientBrowsePlanner.ready() && !expiredExpected && !create.active;
                Craftable.LOGGER.warn("M4_STAGE7_LEASE_REGRESSION injected=true naturalHistoricalReproduction=false"
                        + " pendingBoundaryModel=MAXIMUM phase=expired plannerReady={} expectedCreate={} create={}",
                        ClientBrowsePlanner.ready(), expiredExpected, create.active);
                require(maximumExpiredObserved,
                        "Expired lease must disable full confirm while the modeled MAXIMUM boundary is pending");

                ClientBrowsePlanner.receive(renewed);
                overlay.tick();
                boolean renewedExpected = expectedActive(overlay);
                maximumRenewImmediateObserved = ClientBrowsePlanner.ready() && renewedExpected && create.active;
                Craftable.LOGGER.warn("M4_STAGE7_LEASE_REGRESSION injected=true naturalHistoricalReproduction=false"
                        + " pendingBoundaryModel=MAXIMUM phase=renewImmediate plannerReady={} expectedCreate={} create={}",
                        ClientBrowsePlanner.ready(), renewedExpected, create.active);
                require(maximumRenewImmediateObserved,
                        "Same-value lease renewal must immediately enable full confirm while the modeled MAXIMUM boundary is pending");
            } finally {
                set(CraftingPlanOverlay.class, overlay, "pending", originalPending);
                verifyUnchanged(overlay, overlayState, plannerState, menuState, revision, authority, presentation);
                require((long) get(CraftingPlanOverlay.class, overlay, "sentAt") == boundarySentAt,
                        "Modeled MAXIMUM boundary changed the existing send cadence");
            }
        } finally {
            // Restore the exact original lease without re-opening a header's
            // byte transfer. The clock fields and derived UI are also restored
            // even if a controlled assertion above fails.
            set(ClientBrowsePlanner.class, null, "grant", grant);
            set(CraftingPlanOverlay.class, overlay, "pending", originalPending);
            set(CraftingPlanOverlay.class, overlay, "sentAt", sentAt);
            set(CraftingPlanOverlay.class, overlay, "unavailableSince", unavailableSince);
            var controls = CraftingPlanOverlay.class.getDeclaredMethod("controls");
            controls.setAccessible(true); controls.invoke(overlay);
            verifyUnchanged(overlay, overlayState, plannerState, menuState, revision, authority, presentation);
            require(get(ClientBrowsePlanner.class, null, "grant") == grant
                            && (long) get(CraftingPlanOverlay.class, overlay, "sentAt") == sentAt
                            && (long) get(CraftingPlanOverlay.class, overlay, "unavailableSince") == unavailableSince
                            && fixtureReady() && ClientBrowsePlanner.ready() && expectedActive(overlay) && create.active,
                    "Controlled regression failed to restore original ready/full-confirm state");
            Craftable.LOGGER.warn("M4_STAGE7_LEASE_REGRESSION injected=true naturalHistoricalReproduction=false"
                    + " restored=true expiredObserved={} renewImmediateObserved={}"
                    + " maximumExpiredObserved={} maximumRenewImmediateObserved={}"
                    + " fixtureReady=true plannerReady=true expectedCreate=true create=true resourcesUnchanged=true",
                    expiredObserved, renewImmediateObserved, maximumExpiredObserved, maximumRenewImmediateObserved);
        }
    }

    private static Object maximumWork() throws Exception {
        for (Object work : field(CraftingPlanOverlay.class, "pending").getType().getEnumConstants())
            if (work.toString().equals("MAXIMUM")) return work;
        throw new AssertionError("MAXIMUM pending boundary is unavailable");
    }

    private static CraftingDetailPayloads.BrowseLease sameValueLease(CraftingDetailPayloads.BrowseLease lease, long expires) {
        return new CraftingDetailPayloads.BrowseLease(lease.menuId(), lease.revision(), lease.session(),
                lease.recipes(), lease.resources(), expires, lease.fingerprint(), null,
                lease.settings(), lease.workbench(), lease.rules());
    }

    private static boolean fixtureReady() throws Exception {
        var method = M4ClientSmoke.class.getDeclaredMethod("ready");
        method.setAccessible(true); return (boolean) method.invoke(null);
    }

    private static boolean expectedActive(CraftingPlanOverlay overlay) throws Exception {
        var draft = (CraftingService.Draft) get(CraftingPlanOverlay.class, overlay, "draft");
        var intent = (CraftRequest) get(CraftingPlanOverlay.class, overlay, "intent");
        var pending = get(CraftingPlanOverlay.class, overlay, "pending");
        boolean ready = draft != null && !(boolean) get(CraftingPlanOverlay.class, overlay, "dirty")
                && !(boolean) get(CraftingPlanOverlay.class, overlay, "queuedAction")
                && (pending == null || pending.toString().equals("MAXIMUM"))
                && !get(CraftingPlanOverlay.class, overlay, "pane").toString().equals("CHOICES");
        boolean token = ready && ((boolean) get(CraftingPlanOverlay.class, overlay, "localDraft") && ClientBrowsePlanner.ready()
                || !draft.token().equals(CraftingDetailPayloads.NO_TOKEN));
        return token && (draft.view().code() == CraftingResultCode.CREATED
                || intent.partial() && intent.policy() != CraftRequest.PartialPolicy.NEVER
                        && draft.view().code() == CraftingResultCode.PARTIAL_CREATED);
    }

    private static void verifyUnchanged(CraftingPlanOverlay overlay, Map<String, Object> overlayState,
            Map<String, Object> plannerState, Map<String, Object> menuState,
            long revision, long authority, long presentation) throws Exception {
        unchanged(CraftingPlanOverlay.class, overlay, overlayState);
        unchanged(ClientBrowsePlanner.class, null, plannerState);
        unchanged(AmbientInventoryEvents.class, null, menuState);
        require(ClientRecipeStatusStore.revision() == revision && ClientRecipeStatusStore.authorityRevision() == authority
                        && ClientRecipeStatusStore.presentationRevision() == presentation,
                "Controlled regression modified recipe verdicts/authorization presentation");
    }

    private static Map<String, Object> save(Class<?> type, Object instance, String... names) throws Exception {
        var saved = new LinkedHashMap<String, Object>();
        for (String name : names) saved.put(name, get(type, instance, name));
        return saved;
    }
    private static void unchanged(Class<?> type, Object instance, Map<String, Object> saved) throws Exception {
        for (var entry : saved.entrySet()) require(Objects.equals(entry.getValue(), get(type, instance, entry.getKey())),
                "Controlled regression unexpectedly changed " + type.getSimpleName() + "." + entry.getKey());
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Class<?> type, Object instance, String name) throws Exception { return field(type, name).get(instance); }
    private static void set(Class<?> type, Object instance, String name, Object value) throws Exception { field(type, name).set(instance, value); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
