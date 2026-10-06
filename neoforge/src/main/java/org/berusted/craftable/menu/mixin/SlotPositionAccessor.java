package org.berusted.craftable.menu.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reposition existing slots without replacing ResultSlot or its crafting callbacks. */
@Mixin(Slot.class)
public interface SlotPositionAccessor {
    @Mutable @Accessor("x") void craftable$setX(int x);
    @Mutable @Accessor("y") void craftable$setY(int y);
}
