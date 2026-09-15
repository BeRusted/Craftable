package org.berusted.craftable.menu;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.menu.mixin.SlotPositionAccessor;

import java.util.List;

public class AmbientInventoryMenu extends CraftingMenu {
    public static final int EQUIPMENT_START = 46;
    public static final int SLOT_COUNT = 51;
    private final List<BlockPos> tables;
    private final Level openedLevel;
    private final GameType openedGameMode;

    public AmbientInventoryMenu(int id, Inventory inventory) {
        this(id, inventory, List.of());
    }

    public AmbientInventoryMenu(int id, Inventory inventory, List<BlockPos> tables) {
        super(
                id,
                inventory,
                inventory.player.level().isClientSide ? ContainerLevelAccess.NULL : ContainerLevelAccess.create(inventory.player.level(), inventory.player.blockPosition())
        );

        this.tables = List.copyOf(tables);
        this.openedLevel = inventory.player.level();
        this.openedGameMode = inventory.player instanceof ServerPlayer serverPlayer ? serverPlayer.gameMode.getGameModeForPlayer() : null;

        reposition(getSlot(0), 134, 62);
        // CraftingMenu lays the grid out for the workbench screen. Move it onto
        // the 2x2 area of the inventory artwork, which is where this menu renders.
        for (int i = 0; i < 9; i++) {
            reposition(getSlot(1 + i), 98 + i % 3 * 18, 8 + i / 3 * 18);
        }
        for (int i = 0; i < 5; i++) {
            // Delegate restrictions/equip callbacks to the actual vanilla inventory
            // slot: binding curse, component rules and offhand semantics stay intact.
            Slot vanilla = inventory.player.inventoryMenu.getSlot(i < 4 ? 5 + i : 45);
            this.addSlot(new Slot(inventory, i < 4 ? 39 - i : 40, vanilla.x, vanilla.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return vanilla.mayPlace(stack);
                }

                @Override
                public boolean mayPickup(Player player) {
                    return vanilla.mayPickup(player);
                }

                @Override
                public int getMaxStackSize() {
                    return vanilla.getMaxStackSize();
                }

                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return vanilla.getNoItemIcon();
                }

                @Override
                public void setByPlayer(ItemStack stack, ItemStack old) {
                    vanilla.setByPlayer(stack, old);
                }
            });
        }
    }

    private static void reposition(Slot slot, int x, int y) {
        var position = (SlotPositionAccessor) slot;
        position.craftable$setX(x);
        position.craftable$setY(y);
    }

    public static boolean withinRange(BlockPos origin, BlockPos table, int horizontal, int vertical) {
        return Math.abs((long) origin.getX() - table.getX()) <= horizontal
                && Math.abs((long) origin.getZ() - table.getZ()) <= horizontal
                && Math.abs((long) origin.getY() - table.getY()) <= vertical;
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player)) {
            if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();
            return;
        }
        super.clicked(slot, button, type, player);
    }

    @Override
    public void handlePlacement(boolean all, net.minecraft.world.item.crafting.RecipeHolder<?> recipe, ServerPlayer player) {
        if (!stillValid(player)) {
            player.closeContainer();
            return;
        }
        super.handlePlacement(all, recipe, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
        if (index >= 10 && index < 46) {
            Slot source = slots.get(index);
            for (int target = EQUIPMENT_START; target < SLOT_COUNT - 1; target++) {
                Slot equipment = slots.get(target);
                if (source.hasItem() && !equipment.hasItem() && equipment.mayPlace(source.getItem())) {
                    ItemStack original = source.getItem().copy();
                    if (moveItemStackTo(source.getItem(), target, target + 1, false)) {
                        source.setChanged();
                        source.onTake(player, source.getItem());
                        return original;
                    }
                }
            }
        }
        return super.quickMoveStack(player, index);
    }

    @Override
    public MenuType<?> getType() {
        return CraftableMenus.AMBIENT_INVENTORY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) return true;
        if (!player.isAlive() || player.isSpectator() || player.level() != openedLevel
                || !(player instanceof ServerPlayer serverPlayer)
                || !org.berusted.craftable.api.CraftableModePolicy.allows(serverPlayer.gameMode.getGameModeForPlayer())
                || serverPlayer.gameMode.getGameModeForPlayer() != openedGameMode) return false;
        var settings = CraftableServerConfig.scanSettings();
        BlockPos origin = player.blockPosition();
        return tables.stream().anyMatch(pos -> withinRange(origin, pos, settings.horizontalRadius(), settings.verticalRadius())
                && openedLevel.isLoaded(pos) && openedLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE));
    }


}
