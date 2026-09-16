package org.berusted.craftable.client.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import org.berusted.craftable.client.config.CraftableGuiConfigs;

public class ModMenuImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (screen) -> {
            CraftableGuiConfigs gui = new CraftableGuiConfigs();
            gui.setParent(screen);
            return gui;
        };
    }
}
