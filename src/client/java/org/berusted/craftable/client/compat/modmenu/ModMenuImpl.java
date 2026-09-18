package org.berusted.craftable.client.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import org.berusted.craftable.client.compat.malilib.MalilibCompat;

public class ModMenuImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // 配置界面由可选模组 malilib 提供；未安装时不提供配置按钮
        if (!FabricLoader.getInstance().isModLoaded(MalilibCompat.MALILIB_MOD_ID)) {
            return null;
        }
        return MalilibCompat::createConfigScreen;
    }
}
