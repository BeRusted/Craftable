package org.berusted.craftable.environment;

import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/** Short-lived input view, not an extra inventory or an output destination. */
public final class PlayerMenuInputs implements Container {
    private final ServerPlayer owner;
    private final AbstractContainerMenu menu;
    private final CraftingContainer grid;

    private PlayerMenuInputs(ServerPlayer owner, AbstractContainerMenu menu, CraftingContainer grid) {
        this.owner = owner;
        this.menu = menu;
        this.grid = grid;
    }

    @Nullable
    public static PlayerMenuInputs current(ServerPlayer player) {
        var menu = player.containerMenu;
        if (menu instanceof InventoryMenu inventory)
            return new PlayerMenuInputs(player, menu, inventory.getCraftSlots());
        // CraftingMenu (including our backpack) has no public grid getter.
        // Only its real input slots are included; slot 0 is a derived result.
        if (menu instanceof CraftingMenu && menu.getSlot(1).container instanceof CraftingContainer grid
                && grid.getContainerSize() == 9) {
            for (int slot = 1; slot <= 9; slot++)
                if (menu.getSlot(slot).container != grid) return null;
            return new PlayerMenuInputs(player, menu, grid);
        }
        return null;
    }

    /** Stable menu-owned identity across scans; never use this adapter's identity. */
    public CraftingContainer grid() { return grid; }

    @Override public int getContainerSize() { return grid.getContainerSize() + 1; }

    @Override public boolean isEmpty() { return grid.isEmpty() && menu.getCarried().isEmpty(); }

    @Override public ItemStack getItem(int slot) {
        checkSlot(slot);
        return slot == grid.getContainerSize() ? menu.getCarried() : grid.getItem(slot);
    }

    @Override public ItemStack removeItem(int slot, int count) {
        if (count <= 0) return ItemStack.EMPTY;
        var removed = getItem(slot).split(count);
        // Defer derived-result recomputation until the existing transaction's
        // setChanged/broadcast boundary, instead of publishing half a commit.
        if (getItem(slot).isEmpty()) removeItemNoUpdate(slot);
        return removed;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        checkSlot(slot);
        if (slot != grid.getContainerSize()) return grid.removeItemNoUpdate(slot);
        var stack = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        return stack;
    }

    @Override public void setItem(int slot, ItemStack stack) {
        checkSlot(slot);
        if (slot == grid.getContainerSize()) menu.setCarried(stack);
        else grid.setItem(slot, stack);
    }

    @Override public void setChanged() { menu.slotsChanged(grid); }

    @Override public boolean stillValid(Player player) {
        return player == owner && owner.containerMenu == menu && menu.stillValid(player);
    }

    @Override public void clearContent() {
        grid.clearContent();
        menu.setCarried(ItemStack.EMPTY);
        setChanged();
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= getContainerSize()) throw new IndexOutOfBoundsException(slot);
    }
}
