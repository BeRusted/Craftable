package org.berusted.craftable.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.ClientRequestSequence;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.network.OpenInventoryRequestPayload;
import org.berusted.craftable.config.EnvironmentScanSettings;

@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class AmbientInventoryEvents {
    private static Screen requestedFrom;
    private static long requestedAt = Long.MIN_VALUE;
    private static EnvironmentScanSettings rules;
    private static net.minecraft.world.level.GameType observedMode;
    private static boolean openCreativeAfterClose;
    private AmbientInventoryEvents() {}

    public static void request(Screen screen) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != screen
                || !org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        // Vanilla may construct InventoryScreen while leaving its creative
        // catalog. That automatic conversion is not a new player open action.
        if (observedMode != null && !org.berusted.craftable.api.CraftableModePolicy.allows(observedMode)) return;
        long now = mc.level.getGameTime();
        if (screen == requestedFrom && now - requestedAt >= 0 && now - requestedAt < 20) return;
        requestedFrom = screen;
        requestedAt = now;
        PacketDistributor.sendToServer(new OpenInventoryRequestPayload(ClientRequestSequence.next()));
    }

    public static void receiveRules(EnvironmentScanSettings settings, boolean workbench) {
        rules = settings;
        var screen = Minecraft.getInstance().screen;
        if (workbench && screen instanceof InventoryScreen) request(screen);
    }

    public static void clear() { requestedFrom = null; requestedAt = Long.MIN_VALUE; rules = null; observedMode = null; openCreativeAfterClose = false; }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        var mode = mc.gameMode.getPlayerMode();
        if (mode != observedMode) {
            boolean returning = observedMode != null && !org.berusted.craftable.api.CraftableModePolicy.allows(observedMode)
                    && org.berusted.craftable.api.CraftableModePolicy.allows(mode);
            org.berusted.craftable.client.recipebook.RecipeBookProjection.restoreVanilla();
            org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.clear();
            org.berusted.craftable.client.recipebook.ClientRecipeStatusStore.invalidate(ClientRequestSequence.next());
            org.berusted.craftable.client.recipebook.RecipeBookStatusHandler.clearRequestState();
            requestedFrom = null;
            observedMode = mode;
            var book = org.berusted.craftable.client.recipebook.RecipeBookProjection.component(mc.screen);
            if (book != null && book.isVisible()) book.recipesUpdated();
            // Returning to survival does not open an inventory or request a menu.
            if (returning && (mc.screen instanceof InventoryScreen || mc.screen instanceof CreativeModeInventoryScreen)) {
                mc.player.closeContainer();
            }
        }
        if (openCreativeAfterClose) {
            openCreativeAfterClose = false;
            if (mode == net.minecraft.world.level.GameType.CREATIVE && mc.screen == null
                    && mc.player.containerMenu == mc.player.inventoryMenu) {
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(),
                        mc.options.operatorItemsTab().get()));
            }
        }
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        // Opening is NOT fired when Minecraft closes a screen to null. Wait
        // for the server's normal close/return packet, then enter vanilla UI.
        if (org.berusted.craftable.client.recipebook.RecipeBookProjection.component(event.getScreen()) != null) {
            org.berusted.craftable.client.recipebook.RecipeBookProjection.restoreVanilla();
        }
        var mc = Minecraft.getInstance();
        if (event.getScreen() instanceof AmbientInventoryScreen && mc.gameMode != null
                && mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE) openCreativeAfterClose = true;
    }

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening event) {
        var mc = Minecraft.getInstance();
        boolean allowed = org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed();
        if (org.berusted.craftable.client.recipebook.RecipeBookProjection.active()
                && org.berusted.craftable.client.recipebook.RecipeBookProjection.component(event.getNewScreen()) == null) {
            org.berusted.craftable.client.recipebook.RecipeBookProjection.restoreVanilla();
        }
        // A delayed server reply must not reopen a screen the player has already
        // dismissed. Close the corresponding server menu using its vanilla ID.
        if (event.getNewScreen() instanceof AmbientInventoryScreen
                && (!allowed || Minecraft.getInstance().screen != requestedFrom)) {
            Screen previous = mc.screen;
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.closeContainer();
            event.setNewScreen(previous instanceof AmbientInventoryScreen ? null : previous);
        }
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (CraftableClientConfig.recipeBookEnhancementsEnabled() && screen instanceof InventoryScreen
                && org.berusted.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) request(screen);
        if (screen instanceof OptionsScreen) {
            event.addListener(Button.builder(Component.literal("Craftable"), b -> {
                var container = ModList.get().getModContainerById(Craftable.MOD_ID).orElseThrow();
                Minecraft.getInstance().setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(container, screen));
            }).bounds(screen.width - 104, 6, 98, 20).tooltip(Tooltip.create(ruleSummary())).build());
        }
    }

    public static Component ruleSummary() {
        return rules == null ? Component.translatable("tooltip.craftable.rules_unknown")
                : Component.translatable("tooltip.craftable.rules", rules.horizontalRadius(), rules.verticalRadius(),
                    rules.previewCacheTicks(), rules.includeEnderChest());
    }
}
