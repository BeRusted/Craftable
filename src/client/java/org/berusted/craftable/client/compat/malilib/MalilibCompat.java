package org.berusted.craftable.client.compat.malilib;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.minecraft.client.gui.screens.Screen;
import org.berusted.craftable.client.CraftableClient;

/**
 * malilib 集成的唯一入口。所有 malilib 类型引用都收敛在本包内，
 * 外部调用方必须先用 {@code FabricLoader.isModLoaded("malilib")} 判断后再触碰本类，
 * 否则在 malilib 缺失的环境会触发 NoClassDefFoundError。
 */
public final class MalilibCompat {

    public static final String MALILIB_MOD_ID = "malilib";

    private MalilibCompat() {
    }

    /**
     * 注册配置处理器与配置界面工厂（供 malilib 自身的配置列表使用）。
     */
    public static void register() {
        ConfigManager.getInstance().registerConfigHandler(
                CraftableClient.MOD_ID,
                new MalilibConfigHandler()
        );

        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(
                        CraftableClient.MOD_ID,
                        CraftableClient.MOD_NAME,
                        CraftableGuiConfigs::new
                )
        );
    }

    /**
     * 创建配置界面（供 ModMenu 集成使用）。
     */
    public static Screen createConfigScreen(Screen parent) {
        CraftableGuiConfigs gui = new CraftableGuiConfigs();
        gui.setParent(parent);
        return gui;
    }
}
