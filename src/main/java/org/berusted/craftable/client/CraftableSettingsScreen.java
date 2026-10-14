package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.network.PacketDistributor;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.config.ConfigSettings;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.network.SettingsPayloads;

/** Vanilla presentation of the registered config, plus authoritative remote
 * rule inspection. Opening settings never obtains or renews a crafting grant. */
public final class CraftableSettingsScreen extends OptionsSubScreen {
    private enum Page { MAIN, WORLD }
    private final Page page;
    private final RulesState rules;
    private final ConfigSettings.Draft draft;
    private final List<NativeConfigOption<?>> configOptions = new ArrayList<>();
    private final List<Button> ruleRows = new ArrayList<>();
    private StringWidget heading;
    private Button retry, worldEntry, advanced, done, defaults, directPreparationEntry;

    public CraftableSettingsScreen(Screen parent) {
        this(parent, Page.MAIN, new RulesState(), new ConfigSettings.Draft(CraftableClientConfig.SPEC));
    }

    private CraftableSettingsScreen(Screen parent, Page page, RulesState rules, ConfigSettings.Draft draft) {
        super(parent, Minecraft.getInstance().options, text(page == Page.MAIN ? "title"
                : draft == null ? "world" : "edit_world"));
        this.page = page;
        this.rules = rules;
        this.draft = draft;
    }

    @Override protected void init() {
        configOptions.clear(); ruleRows.clear(); retry = null; worldEntry = null; directPreparationEntry = null;
        if (draft != null) draft.refreshUnchanged();
        super.init();
        rules.request(false);
        updateRules();
    }

    @Override protected void addOptions() {
        if (draft != null) {
            var spec = page == Page.MAIN ? CraftableClientConfig.SPEC : CraftableServerConfig.SPEC;
            for (var value : NativeConfigOption.definitions(spec))
                if (NativeConfigOption.scalar(value)) configOptions.add(NativeConfigOption.create(value, draft));
            list.addSmall(configOptions.stream().map(NativeConfigOption::option).toArray(net.minecraft.client.OptionInstance<?>[]::new));
        }
        if (page == Page.MAIN) {
            worldEntry = Button.builder(text("view_world"), b -> openWorld()).width(150).build();
            advanced = Button.builder(text("advanced"), b -> openAdvanced()).width(150)
                    .tooltip(Tooltip.create(text("advanced.tooltip"))).build();
            list.addSmall(worldEntry, advanced);
            var preferences = label(text("preferences_only"));
            preferences.setTooltip(Tooltip.create(text("preferences_only.tooltip")));
        } else {
            if (draft == null) {
                // Captions share the spec, but values never come from a local
                // SERVER spec pretending to be the remote server's authority.
                for (var value : NativeConfigOption.definitions(CraftableServerConfig.SPEC)) {
                    if (!NativeConfigOption.scalar(value)) continue;
                    var row = Button.builder(text("rules_pending"), b -> {}).width(150)
                            .tooltip(Tooltip.create(Component.translatable(value.getSpec().getTranslationKey() + ".tooltip"))).build();
                    row.active = false;
                    ruleRows.add(row);
                }
                for (int i = 0; i < ruleRows.size(); i += 2)
                    list.addSmall(ruleRows.get(i), i + 1 < ruleRows.size() ? ruleRows.get(i + 1) : null);
            }
            directPreparationEntry = Button.builder(text("direct_preparation"), b -> openDirectPreparation())
                    .width(150).tooltip(Tooltip.create(text("direct_preparation.tooltip"))).build();
            list.addSmall(directPreparationEntry, null);
        }
    }

    @Override protected void addTitle() {
        heading = layout.addToHeader(new StringWidget(Math.max(0, width - 20), 9, title, font));
        heading.setTooltip(Tooltip.create(title));
    }

    @Override protected void repositionElements() {
        if (heading != null) heading.setWidth(Math.max(0, width - 20));
        super.repositionElements();
    }

    @Override protected void addFooter() {
        var footer = layout.addToFooter(LinearLayout.horizontal().spacing(5));
        if (draft == null) {
            retry = footer.addChild(Button.builder(text("retry"), b -> { rules.request(true); updateRules(); })
                    .width(150).build());
            done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(150).build());
            return;
        }
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(100).build());
        defaults = footer.addChild(Button.builder(text("defaults"), b -> {
            if (!editable()) { updateRules(); return; }
            list.applyUnsavedChanges();
            draft.resetDefaults();
            // Rebuild the native controls on a fresh screen, without adding
            // duplicate rows to the retained OptionsSubScreen layout.
            minecraft.setScreen(new CraftableSettingsScreen(lastScreen, page, rules, draft));
        }).width(100).tooltip(Tooltip.create(text("defaults.tooltip"))).build());
        done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> save()).width(100).build());
    }

    private boolean editable() { return page == Page.MAIN || canEditWorld(); }

    private void save() {
        if (draft == null || !editable()) { updateRules(); return; }
        list.applyUnsavedChanges();
        // Recheck after flushing delayed sliders. There is no network path
        // for changing rules on remote or published LAN servers.
        if (!editable()) { updateRules(); return; }
        draft.save();
        if (page == Page.WORLD) rules.invalidate();
        minecraft.setScreen(lastScreen);
    }

    @Override public void onClose() {
        // Escape/Cancel discards this page's draft without touching the spec.
        minecraft.setScreen(lastScreen);
    }

    private void openWorld() {
        if (!rules.connected()) { updateRules(); return; }
        list.applyUnsavedChanges();
        var worldDraft = canEditWorld() ? new ConfigSettings.Draft(CraftableServerConfig.SPEC,
                NativeConfigOption.definitions(CraftableServerConfig.SPEC)) : null;
        minecraft.setScreen(new CraftableSettingsScreen(this, Page.WORLD, rules, worldDraft));
    }

    private void openAdvanced() {
        list.applyUnsavedChanges();
        rules.invalidate();
        var mod = ModList.get().getModContainerById(Craftable.MOD_ID).orElseThrow();
        // Refresh untouched values when returning from the professional UI,
        // while keeping explicitly edited, unsaved native preferences.
        var parent = new CraftableSettingsScreen(lastScreen, Page.MAIN, rules, draft);
        minecraft.setScreen(CraftableClient.configurationScreen(mod, parent));
    }

    private void openDirectPreparation() {
        if (!rules.connected() || draft == null && rules.crafting == null) return;
        list.applyUnsavedChanges();
        minecraft.setScreen(new DirectPreparationSettingsScreen(this, draft, this::canEditWorld,
                () -> rules.sameContext() && rules.crafting != null ? rules.crafting.directPreparation() : null));
    }

    private StringWidget label(Component value) {
        var row = new StringWidget(310, 20, value, font).alignLeft();
        list.addSmall(row, null);
        return row;
    }

    @Override public void tick() {
        if (!rules.sameContext()) rules.invalidate();
        // Returning can reuse an initialized parent. Fetch once after an
        // editor invalidates the read, not periodically while it is valid.
        rules.request(false);
        if (rules.pending && System.nanoTime() - rules.requestedAt >= 5_000_000_000L) {
            rules.pending = false; rules.unavailable = true;
        }
        updateRules();
    }

    private void updateRules() {
        if (worldEntry != null) {
            worldEntry.active = rules.connected();
            String key = canEditWorld() ? "edit_world" : "view_world";
            worldEntry.setMessage(text(key));
            worldEntry.setTooltip(Tooltip.create(text(worldEntry.active ? key + ".tooltip" : "world_requires_level")));
        }
        var values = worldValues();
        for (int i = 0; i < ruleRows.size(); i++) ruleRows.get(i).setMessage(i < values.size() ? values.get(i) : text("rules_pending"));
        if (retry != null) {
            retry.active = rules.connected() && !rules.pending;
            // Keep request/error feedback in the footer tooltip and narration,
            // rather than adding a persistent status row to the options list.
            retry.setTooltip(Tooltip.create(Component.empty().append(text("retry.tooltip"))
                    .append("\n").append(rules.status())));
        }
        if (draft != null && page == Page.WORLD) {
            boolean allowed = canEditWorld();
            for (var option : configOptions) list.findOption(option.option()).active = allowed;
            done.active = allowed; defaults.active = allowed;
            done.setTooltip(allowed ? null : Tooltip.create(text("world_changed")));
        }
        if (directPreparationEntry != null) directPreparationEntry.active = rules.connected()
                && (draft != null ? canEditWorld() : rules.crafting != null);
    }

    private List<Component> worldValues() {
        if (!rules.sameContext() || rules.scan == null || rules.crafting == null) return List.of();
        Map<String, Object> values = Map.of("horizontalRadius", rules.scan.horizontalRadius(),
                "verticalRadius", rules.scan.verticalRadius(), "includeEnderChest", rules.scan.includeEnderChest(),
                "partialExecution", rules.crafting.partialPolicy(), "surplusDelivery", rules.crafting.surplusDelivery(),
                "maxCraftBatch", rules.crafting.maxBatches(), "maxPlanSteps", rules.crafting.maxPlanSteps(),
                "directPreparationMode", rules.crafting.directPreparation().mode());
        return NativeConfigOption.definitions(CraftableServerConfig.SPEC).stream().filter(NativeConfigOption::scalar).<Component>map(value -> {
            Object current = values.get(value.getPath().getLast());
            return CommonComponents.optionNameValue(Component.translatable(value.getSpec().getTranslationKey()),
                    current == null ? text("rules_unavailable") : NativeConfigOption.display(value, current));
        }).toList();
    }

    private boolean canEditWorld() {
        return minecraft != null && rules.connected() && minecraft.hasSingleplayerServer()
                && !minecraft.getSingleplayerServer().isPublished() && CraftableServerConfig.SPEC.isLoaded();
    }

    @Override public Component getNarrationMessage() {
        var result = Component.empty().append(super.getNarrationMessage());
        if (page == Page.WORLD && draft == null) {
            result.append(". ").append(rules.status());
            result.append(". ").append(text("world_read_only"));
            worldValues().forEach(value -> result.append(". ").append(value));
        } else if (page == Page.WORLD && !canEditWorld()) {
            result.append(". ").append(text("world_changed"));
        }
        return result;
    }

    /** Purpose-built settings reply, never a resource/version or execution promise. */
    public static void receiveRules(long revision, EnvironmentScanSettings scan, CraftableServerConfig.CraftingRules crafting) {
        var mc = Minecraft.getInstance();
        CraftableSettingsScreen screen;
        if (mc.screen instanceof CraftableSettingsScreen settings) screen = settings;
        else if (mc.screen instanceof DirectPreparationSettingsScreen editor) screen = editor.settingsParent();
        else return;
        var state = screen.rules;
        if (!state.sameContext() || !state.connected() || !state.pending || state.revision != revision) return;
        state.scan = java.util.Objects.requireNonNull(scan);
        state.crafting = java.util.Objects.requireNonNull(crafting);
        state.pending = false; state.unavailable = false;
        screen.updateRules();
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.craftable.settings." + key, args);
    }

    private static final class RulesState {
        private final Object connection = Minecraft.getInstance().getConnection();
        private final Object level = Minecraft.getInstance().level;
        private final Object server = Minecraft.getInstance().getSingleplayerServer();
        private long revision = -1, requestedAt;
        private boolean requested, pending, unavailable;
        private EnvironmentScanSettings scan;
        private CraftableServerConfig.CraftingRules crafting;
        boolean sameContext() {
            var mc = Minecraft.getInstance();
            return connection == mc.getConnection() && level == mc.level && server == mc.getSingleplayerServer();
        }
        boolean connected() { return connection != null && level != null && sameContext(); }
        void request(boolean retry) {
            if (!connected() || pending || requested && !retry) return;
            invalidate();
            requested = true; pending = true; revision = ClientRequestSequence.next(); requestedAt = System.nanoTime();
            PacketDistributor.sendToServer(new SettingsPayloads.RulesRequest(revision));
        }
        void invalidate() {
            revision = -1; requested = false; pending = false; unavailable = false; scan = null; crafting = null;
        }
        Component status() {
            if (!connected()) return text("rules_not_connected");
            if (pending) return text("rules_pending");
            if (scan != null && crafting != null) return text(Minecraft.getInstance().hasSingleplayerServer()
                    ? "rules_from_world" : "rules_from_server");
            return text(unavailable ? "rules_unavailable" : "rules_pending");
        }
    }
}
