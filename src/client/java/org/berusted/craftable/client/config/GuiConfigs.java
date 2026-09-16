package org.berusted.craftable.client.config;

import fi.dy.masa.malilib.MaLiLibConfigGui;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.CraftableClient;

import java.util.Collections;
import java.util.List;

public class GuiConfigs extends GuiConfigsBase {
    public static ConfigGuiTab tab = ConfigGuiTab.GENERIC;

    public GuiConfigs() {
        super(10, 50, CraftableClient.MOD_ID, null, "craftable.config.title", String.format("%s", "0.0.1"));
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;


        for (ConfigGuiTab tab : ConfigGuiTab.values()) {

            x += this.createButton(x, y, -1, tab) + 2;
        }

    }

    private int createButton(int x, int y, int width, ConfigGuiTab tab) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getDisplayName());
        button.setEnabled(GuiConfigs.tab != tab);
        this.addButton(button, new ButtonListener(tab, this));

        return button.getWidth();
    }

    @Override
    protected int getConfigWidth() {
        ConfigGuiTab tab = GuiConfigs.tab;

        if (tab == ConfigGuiTab.GENERIC) {
            return 200;
        }

        return super.getConfigWidth();
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {

        List<? extends IConfigBase> configs = List.of();
        ConfigGuiTab tab = GuiConfigs.tab;

        if (tab == ConfigGuiTab.GENERIC) {
            configs = CraftableClientConfig.Generic.OPTIONS;
        } else {
            return Collections.emptyList();
        }

        return ConfigOptionWrapper.createFor(configs);
    }

    public enum ConfigGuiTab {
        GENERIC("craftable.config.title.generic");

        private final String translationKey;

        ConfigGuiTab(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getDisplayName() {
            return StringUtils.translate(this.translationKey);
        }
    }

    private record ButtonListener(GuiConfigs parent, ConfigGuiTab tab) implements IButtonActionListener {
            public ButtonListener(ConfigGuiTab tab, GuiConfigs parent) {
                this(parent, tab);
            }

            @Override
            public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
                GuiConfigs.tab = this.tab;

                this.parent.reCreateListWidget(); // apply the new config width
                this.parent.getListWidget().resetScrollbarPosition();
                this.parent.initGui();
            }
        }

}
