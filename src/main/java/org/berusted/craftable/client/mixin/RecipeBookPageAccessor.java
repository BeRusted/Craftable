package org.berusted.craftable.client.mixin;

import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {
    @Accessor("hoveredButton")
    @Nullable
    RecipeButton craftable$getHoveredButton();
}
