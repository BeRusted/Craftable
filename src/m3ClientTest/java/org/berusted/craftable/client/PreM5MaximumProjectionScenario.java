package org.berusted.craftable.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.environment.BrowsingSnapshot;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.SearchResult;

/** Exercises the production completion/projection hooks over a real authorized
 * scope and a real solver result. Reflection isolates the same-tick boundary;
 * it does not inject resources, authority, a search result or a larger budget. */
final class PreM5MaximumProjectionScenario {
    private static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    private int phase, age;
    private volatile boolean configured;
    private Object completedTask, owner;
    private SearchResult result;
    private long projectedBefore, foregroundBefore;

    boolean tick(Minecraft mc) throws Exception {
        if (++age > 400) throw new AssertionError("Deferred MAX fixture timeout phase=" + phase);
        if (phase == 0) {
            mc.setScreen(null);
            mc.getSingleplayerServer().execute(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.closeContainer(); player.getInventory().clearContent();
                player.inventoryMenu.setCarried(ItemStack.EMPTY);
                for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
                if (player.serverLevel().getBlockEntity(new BlockPos(1, -60, 1)) instanceof ChestBlockEntity chest) chest.clearContent();
                player.serverLevel().setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                player.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
                player.containerMenu.broadcastChanges(); configured = true;
            });
            next(); return false;
        }
        if (phase == 1 && configured && age > 20 && mc.player.getInventory().countItem(Items.DIAMOND) == 3) {
            mc.setScreen(new InventoryScreen(mc.player)); next(); return false;
        }
        if (phase == 2 && age > 15) {
            CraftingPlanOverlay.open(mc.screen, PICK, false); next(); return false;
        }
        if (phase == 3 && ClientBrowsePlanner.ready()) {
            var snapshot = (BrowsingSnapshot) read("snapshot");
            if (snapshot.sources().stream().mapToInt(s -> s.stack().is(Items.DIAMOND) ? s.stack().getCount() : 0).sum() != 3) return false;
            ClientBrowsePlanner.cancelDetails();
            ClientBrowsePlanner.scope(List.of(), List.of(), List.of());
            // Existing stock satisfies this direct recipe before closure/MAX
            // certificate work; larger quantities remain separate queries.
            ClientBrowsePlanner.maximum(ClientRequestSequence.next(), CraftRequest.one(PICK));
            owner = read("detail");
            var request = (CraftRequest) member(owner, "request");
            completedTask = invoke("task", request, false, owner);
            var search = (CraftSearch) member(completedTask, "search");
            Optional<SearchResult> solved = Optional.empty();
            for (int slice = 0; slice < 32 && solved.isEmpty(); slice++) solved = search.advance(2_000_000L);
            result = solved.orElseThrow();
            require(result.plan().isPresent() && !result.plan().orElseThrow().partial(), "Real simple MAX solver did not produce a positive witness");
            projectedBefore = (long) read("maximumProjections"); foregroundBefore = ClientBrowsePlanner.foregroundSlices();
            invoke("finishDetail", completedTask, result);
            require(read("maximumProjection") != null && (long) read("maximumProjections") == projectedBefore,
                    "MAX completion projected immediately in the spent solver turn");
            require(!(boolean) invoke("dispatch", System.nanoTime()), "Not-before wait consumed a scheduler turn");
            require(ClientBrowsePlanner.foregroundSlices() == foregroundBefore, "Waiting MAX projection consumed foreground credit");
            next(); return false;
        }
        if (phase == 4) {
            // The ordinary planner callback ran before this fixture on the
            // next real game tick. Larger counts remain pending, so detail is
            // still the same object; the actual projection must still count.
            require((long) read("maximumProjections") > projectedBefore, "Deferred positive MAX projection never ran");
            require(ClientBrowsePlanner.foregroundSlices() > foregroundBefore, "Actual projection did not consume foreground credit");
            require(read("retainedResult") == result, "Positive projection was not retained for quantity review");
            if (read("detail") == owner) {
                invoke("finishDetail", completedTask, result);
                require(read("maximumProjection") != null, "Same owner did not schedule the projection boundary probe");
            }
            ClientBrowsePlanner.preview(ClientRequestSequence.next(), CraftRequest.one(PICK), "");
            require(read("maximumProjection") == null, "Replacing MAX with preview retained stale projection");
            invoke("finishDetail", completedTask, result);
            require(read("maximumProjection") == null, "Old completion published into a new intent");
            ClientBrowsePlanner.maximum(ClientRequestSequence.next(), CraftRequest.one(PICK));
            require(read("maximumProjection") == null, "Replacing MAX retained stale projection");
            ClientBrowsePlanner.cancelDetails();
            require(read("maximumProjection") == null, "Cancellation retained stale projection");
            Craftable.LOGGER.warn("PRE_M5_MAX_PROJECTION PASS: real scope/solver, no same-turn projection, no waiting credit, later projection charged, replacement/cancellation isolation");
            return true;
        }
        return false;
    }

    private static Object read(String name) throws Exception { return member(ClientBrowsePlanner.class, name); }
    private static Object member(Object object, String name) throws Exception {
        var type = object instanceof Class<?> cls ? cls : object.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(object instanceof Class<?> ? null : object);
    }
    private static Object invoke(String name, Object... args) throws Exception {
        for (Method method : ClientBrowsePlanner.class.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            var types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) if (!types[i].isPrimitive() && !types[i].isInstance(args[i])) matches = false;
            if (!matches) continue;
            method.setAccessible(true); return method.invoke(null, args);
        }
        throw new NoSuchMethodException(name);
    }
    private void next() { phase++; age = 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
