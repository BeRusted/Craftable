package org.berusted.craftable.client.menu;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.level.GameType;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.network.payload.OpenInventoryRequestPayload;
import org.berusted.craftable.network.payload.RecipeStatusResponsePayload;


public class AmbientInventoryEvents {
    private static long requestedAt = Long.MIN_VALUE;
    private static Screen requestedFrom;
    private static RecipeStatusResponsePayload rules;
    private static GameType observedMode;
    private static boolean openCreativeAfterClose;

    private AmbientInventoryEvents() {
    }

    public static void request(Screen screen) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != screen
                || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        if (observedMode != null && !org.berusted.craftable.api.CraftableModePolicy.allows(observedMode)) return;
        long now = mc.level.getGameTime();
        if (screen == requestedFrom && now - requestedAt >= 0 && now - requestedAt < 20) return;
        requestedFrom = screen;
        requestedAt = now;

        ClientPlayNetworking.send(
                new OpenInventoryRequestPayload(
                        ClientRequestSequence.next()
                )
        );

    }

    public static void receiveRules(RecipeStatusResponsePayload payload) {
        rules = payload;
        var screen = Minecraft.getInstance().screen;
        if (payload.craftingTable() && screen instanceof InventoryScreen) request(screen);
    }

    public static void clear() {
        requestedFrom = null;
        requestedAt = Long.MIN_VALUE;
        rules = null;
        observedMode = null;
        openCreativeAfterClose = false;
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AmbientInventoryEvents::onTick);
    }

    public static void onTick(Minecraft mc) {
        if (mc.gameMode == null || mc.player == null) return;

        var mode = mc.gameMode.getPlayerMode();

        if (mode != observedMode) {
            boolean returning = observedMode != null
                    && !org.berusted.craftable.api.CraftableModePolicy.allows(observedMode)
                    && org.berusted.craftable.api.CraftableModePolicy.allows(mode);

            org.berusted.craftable.client.recipebook.RecipeBookProjection.restoreVanilla();
            org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.clear();
            org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.invalidate(
                    ClientRequestSequence.next()
            );
            org.berusted.craftable.client.recipebook.RecipeBookStatusHandler.clearRequestState();

            requestedFrom = null;
            observedMode = mode;

            var book = org.berusted.craftable.client.recipebook.RecipeBookProjection.component(mc.screen);
            if (book != null && book.isVisible()) {
                book.recipesUpdated();
            }

            if (returning && (mc.screen instanceof InventoryScreen || mc.screen instanceof CreativeModeInventoryScreen)) {
                mc.player.closeContainer();
            }
        }

        if (openCreativeAfterClose) {
            openCreativeAfterClose = false;
            if (mode == GameType.CREATIVE
                    && mc.screen == null
                    && mc.player.containerMenu == mc.player.inventoryMenu) {

                mc.setScreen(new CreativeModeInventoryScreen(
                        mc.player,
                        mc.player.connection.enabledFeatures(),
                        mc.options.operatorItemsTab().get()
                ));
            }
        }
    }


}
