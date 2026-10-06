package org.berusted.craftable.recipe;

import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.berusted.craftable.Craftable;

/** Opt-in isolated dedicated-server restart/reload harness, excluded from the mod JAR. */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class M47ServerSmoke {
    private static int stage;
    private static Object generation;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static long started;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("craftable.m47Smoke") || stage < 0) return;
        var server = event.getServer();
        if (server.getTickCount() < 5) return;
        try {
            if (started == 0) started = System.nanoTime();
            if (System.nanoTime() - started > 30_000_000_000L) throw new AssertionError("reload timeout");
            var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(server.overworld());
            if (stage == 0) {
                var catalog = new CraftingRecipes(player, true);
                Craftable.LOGGER.warn("M47_SERVER_SMOKE initial {}", catalog.knowledgeStats());
                var path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("craftable/cache/recipe-knowledge-v1.json");
                if (java.nio.file.Files.exists(path) && catalog.knowledgeStats().relations() == 0)
                    throw new AssertionError("existing restart cache did not load");
                catalog.producing(Ingredient.of(Items.STICK));
                var request = org.berusted.craftable.planner.CraftRequest.one(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
                var sources = List.of(new org.berusted.craftable.planner.ResourceLedger.Source("fixture", 0, new ItemStack(Items.OAK_LOG)),
                        new org.berusted.craftable.planner.ResourceLedger.Source("fixture", 1, new ItemStack(Items.DIAMOND, 3)));
                var result = new org.berusted.craftable.planner.CraftSearch(catalog, request, sources,
                        new org.berusted.craftable.planner.SearchBudget(1_000_000_000L), plan -> null).run();
                if (result.plan().isEmpty() || result.plan().get().steps().size() != 3)
                    throw new AssertionError("restart recursive plan incorrect");
                generation = catalog.generation();
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
                stage = 1;
            } else if (stage == 1 && reload.isDone()) {
                reload.join();
                var catalog = new CraftingRecipes(player, true);
                if (catalog.generation() == generation) throw new AssertionError("real reload retained old generation");
                catalog.producing(Ingredient.of(Items.STICK));
                Craftable.LOGGER.warn("M47_SERVER_SMOKE PASS: dedicated start, read-only recursive plan, actual reload, graceful shutdown");
                stage = -1;
                server.halt(false);
            }
        } catch (Throwable failure) {
            Craftable.LOGGER.error("M47_SERVER_SMOKE FAIL", failure);
            stage = -1;
            server.halt(false);
        }
    }

}
