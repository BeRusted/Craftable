package org.berusted.craftable.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;

/** Drives the production scheduler on 400 real game ticks, not a replacement
 * scheduler or a loop of synthetic ticks. Reflection observes continuations;
 * all demands enter the same public browsing API as the real overlay. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class M48SchedulingProbe {
    private static boolean active, sampling, complete, renewalProbe;
    private static int ticks, blocked, workBlocked, eligibleWait, maxWait, headTicks;
    private static long scope = -1, beforeHidden, beforeForeground, firstHidden, firstForeground, lastFrame;
    private static long hiddenWhileExpired;
    private static Object head;
    private static List<ResourceLocation> ids;
    private static final List<Double> cpu = new ArrayList<>(), frames = new ArrayList<>();
    private static final java.util.Map<ResourceLocation, Integer> finishes = new java.util.HashMap<>();
    private static final java.util.Map<ResourceLocation, CraftSearch> observedHeads = new java.util.HashMap<>();
    private static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    public static void start(List<ResourceLocation> candidates) {
        ids = List.copyOf(candidates);
        CraftingPlanOverlay.open(Minecraft.getInstance().screen, PICK, false);
        ClientBrowsePlanner.invalidate();
        active = true;
        firstHidden = ClientBrowsePlanner.hiddenSlices();
        firstForeground = ClientBrowsePlanner.foregroundSlices();
    }

    public static boolean complete() { return complete; }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void demand(ClientTickEvent.Post event) {
        if (!active) return;
        sampling = false;
        if (!ClientBrowsePlanner.ready() && (scope == -1 || !renewalProbe || ticks >= 8)) { blocked++; return; }
        if (scope == -1) scope = ClientBrowsePlanner.scopeVersion();
        check(scope == ClientBrowsePlanner.scopeVersion(), "Pressure fixture changed resource identity");
        // Hover churn and changing detail quantities compete with the full
        // hidden range, without an automatic server fallback or confirmation.
        var hover = ids.get(ticks % ids.size());
        ClientBrowsePlanner.scope(List.of(hover), ids, List.of(hover));
        ClientBrowsePlanner.preview(ClientRequestSequence.next(), CraftRequest.one(PICK).withBatches(1 + ticks % 64), "");
        if (renewalProbe && ticks < 8) {
            // Simulate delayed authorization renewal, without replacing values
            // or clearing completed mathematics. Passive green must disappear,
            // while an un-runnable detail intent cannot starve hidden work.
            var lease = (org.berusted.craftable.network.CraftingDetailPayloads.BrowseLease)
                    field(ClientBrowsePlanner.class, null, "grant");
            ClientBrowsePlanner.receive(new org.berusted.craftable.network.CraftingDetailPayloads.BrowseLease(
                    lease.menuId(), lease.revision(), lease.session(), lease.recipes(), lease.resources(),
                    Minecraft.getInstance().level.getGameTime() - 1, lease.fingerprint(), null,
                    lease.settings(), lease.workbench()));
        }
        head = field(ClientBrowsePlanner.class, null, "background");
        if (head != null) {
            var id = (ResourceLocation) field(head.getClass(), head, "id");
            var search = (CraftSearch) field(head.getClass(), head, "search");
            var previous = observedHeads.putIfAbsent(id, search);
            check(previous == null || previous == search, "Same-version hidden continuation was replaced");
        }
        beforeHidden = ClientBrowsePlanner.hiddenSlices();
        beforeForeground = ClientBrowsePlanner.foregroundSlices();
        sampling = true;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void observe(ClientTickEvent.Post event) {
        if (!active || !sampling) return;
        cpu.add(ClientBrowsePlanner.lastTickNanos() / 1e6);
        if (renewalProbe && ticks < 8) {
            hiddenWhileExpired += ClientBrowsePlanner.hiddenSlices() - beforeHidden;
            check(ClientRecipeStatusStore.lifecycle(PICK) != ClientRecipeStatusStore.Lifecycle.KNOWN,
                    "Expired authority still exposed green verdicts");
        }
        if (head != null) {
            var search = (CraftSearch) field(head.getClass(), head, "search");
            check(search.initializations() <= 1, "Hover churn restarted a hidden root");
            headTicks++;
            if (ClientBrowsePlanner.hiddenSlices() > beforeHidden) {
                maxWait = Math.max(maxWait, ++eligibleWait); eligibleWait = 0;
            } else if (ClientBrowsePlanner.foregroundSlices() > beforeForeground) {
                maxWait = Math.max(maxWait, ++eligibleWait);
            } else workBlocked++;
            check(eligibleWait <= 4, "Hidden head starved under real detail demand");
        } else eligibleWait = 0;
        ++ticks;
        for (var id : ids) if (ClientRecipeStatusStore.computed(id)) finishes.putIfAbsent(id, ticks);
        if (ticks < (renewalProbe ? 40 : 400)) return;
        check(finishes.size() == ids.size(), "Hidden range did not finish under foreground churn");
        check(ClientBrowsePlanner.hiddenSlices() > firstHidden, "Pressure test never dispatched hidden work");
        if (renewalProbe) check(hiddenWhileExpired > 0, "A waiting detail intent starved hidden work during lease renewal");
        long unknown = ids.stream().filter(id -> ClientRecipeStatusStore.reason(id)
                == org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED).count();
        Craftable.LOGGER.warn("M48_SCHEDULING mode={} ticks={} blockedBeforeReady={} headBlockedTicks={} expiredLeaseTicks={} hiddenWhileExpired={} hiddenSlices={} foregroundSlices={} headObservedTicks={} maxHeadWait={} completed={}/{} unknown={} maxQueueTicks={} cpuP95Ms={} cpuMaxMs={} frameIntervalP95Ms={} frameIntervalMaxMs={}",
                renewalProbe ? "renewal" : "foreground", ticks, blocked, workBlocked, renewalProbe ? 8 : 0,
                hiddenWhileExpired, ClientBrowsePlanner.hiddenSlices() - firstHidden, ClientBrowsePlanner.foregroundSlices() - firstForeground,
                headTicks, maxWait, finishes.size(), ids.size(), unknown, finishes.values().stream().max(Integer::compare).orElse(0),
                percentile(cpu, .95), percentile(cpu, 1), percentile(frames, .95), percentile(frames, 1));
        if (!renewalProbe) {
            // Test authorization blocking separately; withholding foreground
            // authority during the main sample would weaken contention there.
            renewalProbe = true;
            ticks = blocked = workBlocked = eligibleWait = maxWait = headTicks = 0;
            scope = -1; lastFrame = 0;
            firstHidden = ClientBrowsePlanner.hiddenSlices();
            firstForeground = ClientBrowsePlanner.foregroundSlices();
            cpu.clear(); frames.clear(); finishes.clear(); observedHeads.clear();
            ClientBrowsePlanner.invalidate();
            return;
        }
        active = false; complete = true;
        ((CraftingPlanOverlay) field(CraftingPlanOverlay.class, null, "current")).onClose();
        ClientBrowsePlanner.invalidate(); // Do not contaminate subsequent UI fixtures with pressure intents.
    }

    @SubscribeEvent
    public static void frame(ScreenEvent.Render.Post event) {
        if (!active) return;
        long now = System.nanoTime();
        if (lastFrame != 0) frames.add((now - lastFrame) / 1e6);
        lastFrame = now;
    }

    private static double percentile(List<Double> values, double fraction) {
        return values.isEmpty() ? 0 : values.stream().sorted().toList().get((int) Math.ceil(values.size() * fraction) - 1);
    }
    private static Object field(Class<?> type, Object instance, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
