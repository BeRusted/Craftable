package org.berusted.craftable.client.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import org.berusted.craftable.client.compat.malilib.MalilibCompat;

public class ModMenuImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // 优先使用已安装的 malilib；缺失时使用原版组件配置界面
        if (!FabricLoader.getInstance().isModLoaded(MalilibCompat.MALILIB_MOD_ID)) {
            return org.berusted.craftable.client.config.CraftableConfigScreen::new;
        }
        return MalilibCompat::createConfigScreen;
    }
}
