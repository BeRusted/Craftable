package org.berusted.craftable.api;

import java.util.List;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.Craftable;

public final class CraftableEvents {
    private CraftableEvents() {
    }

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

    @FunctionalInterface
    public interface ItemCrafted {
        void onItemCrafted(ServerPlayer player, ItemStack crafted, List<ItemStack> gridItems);
    }
}
