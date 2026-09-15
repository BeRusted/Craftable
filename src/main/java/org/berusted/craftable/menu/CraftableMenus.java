package org.berusted.craftable.menu;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import org.berusted.craftable.Craftable;

public final class CraftableMenus {

    public static final MenuType<AmbientInventoryMenu> AMBIENT_INVENTORY =
            Registry.register(
                    BuiltInRegistries.MENU,
                    ResourceLocation.fromNamespaceAndPath(
                            Craftable.MOD_ID,
                            "ambient_inventory"
                    ),
                    new MenuType<>(
                            AmbientInventoryMenu::new,
                            FeatureFlags.DEFAULT_FLAGS
                    )
            );

    private CraftableMenus() {
    }

    /**
     * Loads this holder class so the static initializer above registers the menu type.
     * Must be called from mod initialization: {@code BuiltInRegistries.MENU} is frozen
     * afterwards, so a lazy first touch at menu-open time would throw.
     */
    public static void register() {
    }
}
