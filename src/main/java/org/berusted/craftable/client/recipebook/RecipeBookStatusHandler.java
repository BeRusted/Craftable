package org.berusted.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;
import org.berusted.craftable.network.RecipeStatusRequestPayload;

/** At most one in-flight bounded batch. Timeouts retry instead of fabricating blocked states. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class RecipeBookStatusHandler {
    private static final PreviewRequestQueue QUEUE = new PreviewRequestQueue();
    private static long lastTick = Long.MIN_VALUE;
    private static long sentAt = Long.MIN_VALUE;
    private static long inFlight = -1;
    private static List<ResourceLocation> sentBatch = List.of();
    private static long displayedRevision = -1;
    private static Object currentView;
    private static Object recipeManager;
    private static Object currentLevel;
    private RecipeBookStatusHandler() {}

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        var mc = Minecraft.getInstance();
        var component = RecipeBookProjection.component(event.getScreen());
        if (!RecipeBookProjection.active() || component == null || mc.level == null || mc.player == null) return;
        long now = mc.level.getGameTime();
        if (lastTick == now) return;
        lastTick = now;
        if (recipeManager != mc.level.getRecipeManager() || currentLevel != mc.level) {
            currentLevel = mc.level;
            recipeManager = mc.level.getRecipeManager();
            ClientRecipeStatusStore.clear();
            ClientRecipeStatusStore.invalidate(ClientRequestSequence.next());
            inFlight = -1;
        }
        if (currentView != component) {
            currentView = component;
            displayedRevision = -1;
        }
        if (!component.isVisible()) {
            // Inventory can gain a workstation even while its book is folded.
            if (event.getScreen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                    && (now - sentAt < 0 || now - sentAt >= 20)) {
                inFlight = ClientRequestSequence.next();
                sentAt = now;
                sentBatch = List.of();
                PacketDistributor.sendToServer(new RecipeStatusRequestPayload(List.of(), inFlight));
            }
            return;
        }
        if (displayedRevision != ClientRecipeStatusStore.revision()) {
            // Refresh before render, not from the packet handler midway through
            // a button animation. Every category gets a consistent projection.
            var contents = new net.minecraft.world.entity.player.StackedContents();
            for (var collection : mc.player.getRecipeBook().getCollections()) {
                collection.canCraft(contents, 3, 3, mc.player.getRecipeBook());
            }
            component.recipesUpdated();
            displayedRevision = ClientRecipeStatusStore.revision();
        }
        if (inFlight >= 0 && now - sentAt >= 0 && now - sentAt < 40) return;
        if (now - sentAt >= 0 && now - sentAt < 4) return;

        var page = ((RecipeBookComponentAccessor) component).craftable$getRecipeBookPage();
        var access = (RecipeBookPageAccessor) page;
        List<ResourceLocation> foreground = new ArrayList<>();
        if (access.craftable$getHoveredButton() != null && access.craftable$getHoveredButton().visible) {
            RecipeButtonTargetResolver.candidates(access.craftable$getHoveredButton()).forEach(r -> foreground.add(r.id()));
        }
        access.craftable$getButtons().stream().filter(b -> b.visible).forEach(b ->
                RecipeButtonTargetResolver.candidates(b).forEach(r -> foreground.add(r.id())));
        // A filtered-out unknown recipe needs background discovery to ever
        // become visible. Only the active category/search is eligible.
        List<ResourceLocation> scope = mc.player.getRecipeBook().isFiltering(mc.player.containerMenu instanceof
                net.minecraft.world.inventory.RecipeBookMenu<?, ?> menu ? menu : mc.player.inventoryMenu)
                ? RecipeBookProjection.scope(component).stream().flatMap(c -> RecipeBookProjection.candidates(c).stream())
                    .map(r -> r.id()).distinct().toList()
                : foreground;
        var selected = QUEUE.select(foreground, scope,
                id -> !ClientRecipeStatusStore.isFresh(id, now, 10), RecipeStatusRequestPayload.MAX_RECIPES);
        // An empty recipe page still needs workstation/rule discovery.
        if (selected.isEmpty() && now - sentAt >= 0 && now - sentAt < 20) return;
        inFlight = ClientRequestSequence.next();
        sentAt = now;
        sentBatch = selected;
        PacketDistributor.sendToServer(new RecipeStatusRequestPayload(selected, inFlight));
    }

    public static void received(org.berusted.craftable.network.RecipeStatusResponsePayload payload) {
        if (payload.requestId() == inFlight) {
            QUEUE.received(sentBatch, payload.entries().stream().map(
                    org.berusted.craftable.network.RecipeStatusResponsePayload.Entry::recipeId).toList());
            inFlight = -1;
        }
    }

    public static void afterCreate(long barrier) {
        ClientRecipeStatusStore.invalidate(barrier);
        inFlight = -1;
        sentAt = Long.MIN_VALUE;
    }

    public static void clearRequestState() {
        lastTick = sentAt = Long.MIN_VALUE;
        inFlight = -1;
        currentView = recipeManager = currentLevel = null;
        displayedRevision = -1;
        sentBatch = List.of();
        QUEUE.clear();
    }
}
