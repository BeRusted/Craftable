package org.berusted.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Items;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;

/** One scenario owned by M4ClientSmoke, not another event subscriber or test runner.
 * All sample counts, production calls and assertions are preserved from stage 3. */
final class M4BrowsingScenario {
    private int browsePhase, browseStarted;
    private long browseSearches, browseCompleted;
    private long browseStartNanos;
    private int rangeReturns;
    private final java.util.List<Double> filterTimings = new java.util.ArrayList<>();
    private final java.util.List<Double> filterCpu = new java.util.ArrayList<>();
    private int browseBuilds;

    boolean tick(Minecraft mc, int age, ResourceLocation PICK) {
        var liveBook = ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent();
        var liveSearch = ((RecipeBookComponentAccessor) liveBook).craftable$getSearchBox();
        if (browsePhase == 0) {
            browseSearches = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches();
            browseBuilds = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds();
            browseStarted = age;
            browseStartNanos = System.nanoTime();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
            liveSearch.setValue(""); liveBook.recipesUpdated();
            org.berusted.craftable.client.recipebook.ClientBrowsePlanner.invalidate();
            browsePhase = 5; return false;
        }
        if (browsePhase == 1) {
            filterCpu.add(org.berusted.craftable.client.recipebook.ClientBrowsePlanner.lastTickNanos() / 1e6);
            var ids = org.berusted.craftable.client.recipebook.RecipeBookProjection.scope(liveBook).stream()
                    .flatMap(c -> org.berusted.craftable.client.recipebook.RecipeBookProjection.candidates(c).stream())
                    .map(r -> r.id()).distinct().toList();
            long completed = ids.stream().filter(org.berusted.craftable.client.recipebook.ClientRecipeStatusStore::computed).count();
            if (completed != ids.size() && age - browseStarted < 400) return false;
            require(completed == ids.size(), "Local hidden filtering did not finish within 400 ticks: " + completed + "/" + ids.size());
            var unknownIds = ids.stream().filter(id -> org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.reason(id)
                    == CraftingResultCode.SEARCH_BUDGET_EXCEEDED).toList();
            long unknown = unknownIds.size();
            var cpu = filterCpu.stream().sorted().toList();
            Craftable.LOGGER.warn("M48_LIVE_FILTER targets={} ticks={} quantityTasks={} unknown={} catalogBuilds={} cpuP95Ms={} cpuMaxMs={}",
                    ids.size(), age - browseStarted, org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches() - browseSearches,
                    unknown, org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds(),
                    cpu.get((int) Math.ceil(cpu.size() * .95) - 1), cpu.getLast());
            filterCpu.clear();
            filterTimings.add((System.nanoTime() - browseStartNanos) / 1e6);
            require(unknown == 0, "Stopped unknown work is not filter convergence: " + unknownIds);
            if (filterTimings.size() < 5) {
                // A fresh dynamic session must capture/transfer/bind again,
                // but the connection's static catalog must survive.
                org.berusted.craftable.client.recipebook.ClientBrowsePlanner.invalidate();
                browseStarted = age; browseStartNanos = System.nanoTime();
                browseSearches = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches();
                browsePhase = 5;
                return false;
            }
            var sorted = filterTimings.stream().sorted().toList();
            Craftable.LOGGER.warn("M48_FILTER_SESSIONS samples=5 ms={} P50={} P95={} max={} catalogBuilds={}",
                    filterTimings, sorted.get(2), sorted.get(4), sorted.get(4),
                    org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds());
            browseCompleted = org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches();
            browseStarted = age; browsePhase = 2;
            liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated(); return false;
        }
        if (browsePhase == 5) {
            // invalidate() hides authority immediately; wait for the new
            // snapshot so old conclusions cannot finish a cold sample.
            if (!org.berusted.craftable.client.recipebook.ClientBrowsePlanner.ready()) return false;
            browsePhase = 1; return false;
        }
        if (browsePhase == 2) {
            if (age - browseStarted < 1) return false;
            liveSearch.setValue(""); liveBook.recipesUpdated(); browseStarted = age; browsePhase = 3; return false;
        }
        if (browsePhase == 3) {
            if (age - browseStarted < 1) return false;
            require(org.berusted.craftable.client.recipebook.ClientBrowsePlanner.searches() == browseCompleted,
                    "Same-version category/search return restarted searches");
            require(org.berusted.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds() == browseBuilds,
                    "Category/search changed static catalog generation");
            if (++rangeReturns < 100) {
                liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated();
                browseStarted = age; browsePhase = 2; return false;
            }
            Craftable.LOGGER.warn("M48_LIVE_REUSE completeRangeReturns=100 newSearches=0 unchangedLease=0Rebuilds");
            var ids = org.berusted.craftable.client.recipebook.RecipeBookProjection.scope(liveBook).stream()
                    .flatMap(c -> org.berusted.craftable.client.recipebook.RecipeBookProjection.candidates(c).stream())
                    .map(r -> r.id()).distinct().toList();
            M48SchedulingProbe.start(ids); browsePhase = 6; return false;
        }
        if (browsePhase == 6) {
            if (!M48SchedulingProbe.complete()
                    || !org.berusted.craftable.client.recipebook.ClientBrowsePlanner.ready()
                    || !org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.computed(PICK)) return false;
            liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated();
            browsePhase = 4;
        }
        return true;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
