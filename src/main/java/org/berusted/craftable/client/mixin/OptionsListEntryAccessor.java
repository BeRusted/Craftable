package org.berusted.craftable.client.mixin;

import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Preserve the native OptionEntry and its option/slider mappings when filling
 * an unused right-hand slot. Vanilla exposes the row but not a slot setter. */
@Mixin(targets = "net.minecraft.client.gui.components.OptionsList$Entry")
public interface OptionsListEntryAccessor {
    @Mutable
    @Accessor("children")
    void craftable$setWidgets(List<AbstractWidget> widgets);
}
