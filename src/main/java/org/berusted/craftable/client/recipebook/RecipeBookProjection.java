package org.berusted.craftable.client.recipebook;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.berusted.craftable.client.mixin.RecipeBookComponentAccessor;
import org.berusted.craftable.config.CraftableClientConfig;

/** One scoped projection over vanilla's already-synchronized recipe catalog. */
public final class RecipeBookProjection {
    private static boolean restoring;
    private RecipeBookProjection() {}
    public static RecipeBookComponent component(Screen screen) {
        if (screen instanceof InventoryScreen inventory) return inventory.getRecipeBookComponent();
        if (screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen crafting) return crafting.getRecipeBookComponent();
        return null;
    }
    public static boolean active() {
        return !restoring && modeAllowed() && CraftableClientConfig.recipeBookEnhancementsEnabled()
                && component(Minecraft.getInstance().screen) != null;
    }
    public static boolean modeAllowed() {
        var mode = Minecraft.getInstance().gameMode;
        return mode != null && org.berusted.craftable.api.CraftableModePolicy.allows(mode.getPlayerMode());
    }
    public static void restoreVanilla() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        restoring = true;
        try {
            var contents = new net.minecraft.world.entity.player.StackedContents();
            mc.player.getInventory().fillStackedContents(contents);
            for (var collection : mc.player.getRecipeBook().getCollections()) {
                collection.canCraft(contents, 2, 2, mc.player.getRecipeBook());
            }
        } finally { restoring = false; }
    }
    public static boolean visible(RecipeHolder<?> recipe) {
        var mc = Minecraft.getInstance();
        return recipe.value() instanceof CraftingRecipe && !recipe.value().isSpecial()
                && !recipe.value().isIncomplete() && recipe.value().canCraftInDimensions(3, 3)
                && (!CraftableClientConfig.unlockedOnly() || mc.player.getRecipeBook().contains(recipe));
    }
    public static List<RecipeHolder<?>> candidates(RecipeCollection collection) {
        return collection.getRecipes().stream().filter(RecipeBookProjection::visible).toList();
    }
    public static List<RecipeCollection> scope(RecipeBookComponent component) {
        var mc = Minecraft.getInstance();
        var access = (RecipeBookComponentAccessor) component;
        var tab = access.craftable$getSelectedTab();
        if (tab == null) return List.of();
        var list = mc.player.getRecipeBook().getCollection(tab.getCategory());
        var search = access.craftable$getSearchBox();
        if (search == null || search.getValue().isEmpty()) return list;
        var matches = mc.getConnection().searchTrees().recipes().search(search.getValue().toLowerCase(Locale.ROOT));
        return list.stream().filter(matches::contains).toList();
    }
}
