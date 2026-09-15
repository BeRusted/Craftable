package org.berusted.craftable.api;

import java.util.List;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.Craftable;

/**
 * Fabric-native notifications for crafts Craftable commits on a player's behalf.
 *
 * <p>Every callback runs on the server main thread after the craft has been
 * committed. The notifications are deliberately observational: extracted
 * ingredients and the produced output already sit in their final inventories, so
 * changing the stacks handed to a listener cannot change the outcome of a craft.
 */
public final class CraftableEvents {
    private CraftableEvents() {
    }

    /**
     * Fired once per committed craft, after {@code Item#onCraftedBy} and before the
     * vanilla recipe-book and advancement notifications.
     */
    public static final Event<ItemCrafted> ITEM_CRAFTED = EventFactory.createArrayBacked(
            ItemCrafted.class,
            listeners -> (player, crafted, gridItems) -> {
                for (ItemCrafted listener : listeners) {
                    try {
                        listener.onItemCrafted(player, crafted, gridItems);
                    } catch (RuntimeException exception) {
                        // A broken listener must not suppress the vanilla notifications
                        // that follow, nor the other listeners of this craft.
                        Craftable.LOGGER.error(
                                "Craftable item-crafted listener {} failed for {}",
                                listener.getClass().getName(),
                                player.getGameProfile().getName(),
                                exception);
                    }
                }
            });

    /** Receives committed direct crafts. */
    @FunctionalInterface
    public interface ItemCrafted {
        /**
         * @param player    the crafting player, always a server player on the main thread
         * @param crafted   the output stack the craft produced
         * @param gridItems an immutable copy of the crafting grid that matched the recipe
         */
        void onItemCrafted(ServerPlayer player, ItemStack crafted, List<ItemStack> gridItems);
    }
}
