package org.berusted.craftable.client.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {
    @Accessor("selectedTab")
    net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton craftable$getSelectedTab();

    @Accessor("recipeBookPage")
    RecipeBookPage craftable$getRecipeBookPage();

    @Accessor("searchBox")
    @Nullable
    EditBox craftable$getSearchBox();
}
