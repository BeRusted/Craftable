package org.berusted.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.client.mixin.RecipeBookPageAccessor;

/** Collect UI demand only. The single client scheduler owns passive searches;
 * render events never start a search or send per-target requests. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class RecipeBookStatusHandler {
    private static long lastTick = Long.MIN_VALUE, displayedRevision = -1;
    private static long displayedAt = Long.MIN_VALUE, displayedAuthority = -1;
    private static Object currentView;
    private RecipeBookStatusHandler() {}

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        var mc = Minecraft.getInstance();
        if (org.berusted.craftable.client.CraftingPlanOverlay.active()) return;
        var component = RecipeBookProjection.component(event.getScreen());
        if (!RecipeBookProjection.active() || component == null || mc.level == null || mc.player == null) return;
        long now = mc.level.getGameTime();
        if (lastTick == now) return;
        lastTick = now;
        if (currentView != component) { currentView = component; displayedRevision = -1; }
        if (!component.isVisible()) { ClientBrowsePlanner.scope(List.of(), List.of(), List.of()); return; }
        if (displayedRevision != ClientRecipeStatusStore.revision()
                && (displayedRevision == -1 || displayedAuthority != ClientRecipeStatusStore.authorityRevision()
                    || now < displayedAt || now - displayedAt >= 4)) {
            // Merge results before render, never from a network callback while
            // vanilla is indexing an animated button's previous collection.
            var contents = new net.minecraft.world.entity.player.StackedContents();
            for (var collection : mc.player.getRecipeBook().getCollections())
                collection.canCraft(contents, 3, 3, mc.player.getRecipeBook());
            component.recipesUpdated();
            displayedRevision = ClientRecipeStatusStore.revision();
            displayedAuthority = ClientRecipeStatusStore.authorityRevision(); displayedAt = now;
        }
        var page = ((RecipeBookComponentAccessor) component).craftable$getRecipeBookPage();
        var access = (RecipeBookPageAccessor) page;
        List<ResourceLocation> foreground = new ArrayList<>(), hovered = new ArrayList<>();
        if (access.craftable$getHoveredButton() != null && access.craftable$getHoveredButton().visible)
            RecipeButtonTargetResolver.candidates(access.craftable$getHoveredButton()).forEach(r -> hovered.add(r.id()));
        foreground.addAll(hovered);
        access.craftable$getButtons().stream().filter(b -> b.visible).forEach(b ->
                RecipeButtonTargetResolver.candidates(b).forEach(r -> foreground.add(r.id())));
        List<ResourceLocation> scope = mc.player.getRecipeBook().isFiltering(mc.player.containerMenu instanceof
                net.minecraft.world.inventory.RecipeBookMenu<?, ?> menu ? menu : mc.player.inventoryMenu)
                ? RecipeBookProjection.scope(component).stream().flatMap(c -> RecipeBookProjection.candidates(c).stream())
                    .map(r -> r.id()).distinct().toList() : foreground;
        ClientBrowsePlanner.scope(foreground, scope, hovered);
    }

    public static void afterCreate(long barrier) {
        ClientRecipeStatusStore.invalidate(barrier);
        ClientBrowsePlanner.invalidate();
    }
    public static void clearRequestState() {
        lastTick = Long.MIN_VALUE; displayedRevision = -1; currentView = null;
        ClientBrowsePlanner.invalidate();
    }
}
