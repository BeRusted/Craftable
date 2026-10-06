package org.berusted.craftable.testsupport;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Minimal non-initializing container used for range-contract unit tests. */
public final class SizedContainerStub implements Container {
    private final int size;

    public SizedContainerStub(int size) {
        this.size = size;
    }

    @Override
    public int getContainerSize() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {}
}
