package org.berusted.craftable.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.client.menu.AmbientInventoryEvents;
import org.berusted.craftable.client.menu.AmbientInventoryScreen;
import org.berusted.craftable.client.network.ClientPayloadHandler;
import org.berusted.craftable.menu.CraftableMenus;
import org.berusted.craftable.client.recipebook.RecipeBookInputHandler;
import org.berusted.craftable.client.recipebook.RecipeBookStatusHandler;
import org.berusted.craftable.network.RecipeStatusResponsePayload;

public class CraftableClient implements ClientModInitializer {

    private static final String MOD_ID = "craftable";

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        // 菜单类型本身在主入口点注册，这里只负责把它绑定到客户端屏幕
        MenuScreens.register(CraftableMenus.AMBIENT_INVENTORY, AmbientInventoryScreen::new);

        // 必须先于任何会用到按键的逻辑注册，且必须在 GameOptions 初始化之前
        CraftableKeyMappings.register();

        ClientSessionEvents.register();
        AmbientInventoryEvents.register();
        ClientPayloadHandler.register();
        RecipeBookInputHandler.register();
        RecipeBookStatusHandler.register();
    }
}
