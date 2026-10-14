package org.berusted.craftable.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.DirectPreparationPolicy;

/** Client-only integration points that must never be class-loaded by a dedicated server. */
@Mod(value = Craftable.MOD_ID, dist = Dist.CLIENT)
public final class CraftableClient {
    public CraftableClient(ModContainer container, net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) ->
                event.register(org.berusted.craftable.menu.CraftableMenus.AMBIENT_INVENTORY.get(),
                        org.berusted.craftable.client.menu.AmbientInventoryScreen::new));
        // Mods keeps the complete NeoForge configuration UI as an advanced
        // fallback. The ordinary Options landing uses native option widgets
        // backed by these same specs, not a parallel persistence format.
        container.registerExtensionPoint(IConfigScreenFactory.class, CraftableClient::configurationScreen);
    }

    static ConfigurationScreen configurationScreen(ModContainer container, Screen parent) {
        return new ConfigurationScreen(container, parent, (screen, type, config, title) -> new Section(screen, type, config, title));
    }

    /** Only the two whole-list validated values need a specialized professional
     * control. NeoForge still owns section navigation, undo and persistence. */
    private static final class Section extends ConfigurationScreen.ConfigurationSectionScreen {
        private final Object connection = Minecraft.getInstance().getConnection(), level = Minecraft.getInstance().level;
        private final List<net.minecraft.client.gui.components.AbstractWidget> policyControls = new ArrayList<>();
        Section(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
            super(parent, type, config, title);
        }
        Section(Context context, Component title) { super(context, title); }

        private boolean editablePolicy() { return worldEditable() && connection == minecraft.getConnection() && level == minecraft.level; }

        @Override protected ConfigurationScreen.ConfigurationSectionScreen rebuild() {
            policyControls.clear(); return super.rebuild();
        }

        @Override public void tick() {
            for (var control : policyControls) control.active = editablePolicy();
            // A publication/disconnect can happen between rendered frames.
            // Recompute from the real undo/default state; never blindly
            // re-enable a footer when the current context becomes editable.
            setUndoButtonstate(undoManager.canUndo());
            setResetButtonstate(isAnyNondefault());
        }

        @Override protected void setUndoButtonstate(boolean enabled) {
            super.setUndoButtonstate(enabled && (context.modSpec() != CraftableServerConfig.SPEC || editablePolicy()));
        }

        @Override protected void setResetButtonstate(boolean enabled) {
            super.setResetButtonstate(enabled && (context.modSpec() != CraftableServerConfig.SPEC || editablePolicy()));
        }

        @Override protected void createUndoButton() {
            super.createUndoButton(); undoButton = guardedFooter(undoButton);
        }

        @Override protected void createResetButton() {
            super.createResetButton(); resetButton = guardedFooter(resetButton);
        }

        private Button guardedFooter(Button original) {
            var guarded = Button.builder(original.getMessage(), button -> {
                if (context.modSpec() != CraftableServerConfig.SPEC || editablePolicy()) original.onPress();
            }).width(original.getWidth()).tooltip(original.getTooltip()).build();
            guarded.active = original.active;
            return guarded;
        }

        @Override protected Element createSection(String key, UnmodifiableConfig metadata, UnmodifiableConfig values) {
            if (metadata.isEmpty()) return null;
            return new Element(Component.translatable("neoforge.configuration.uitext.section", getTranslationComponent(key)),
                    getTooltipComponent(key, null), Button.builder(Component.translatable(getTranslationKey(key) + ".button"), button ->
                        minecraft.setScreen(sectionCache.computeIfAbsent(key, ignored -> new Section(
                                Context.section(context, this, values.entrySet(), metadata.valueMap(), key),
                                Component.translatable("neoforge.configuration.uitext.breadcrumb.order", title,
                                        ConfigurationScreen.CRUMB_SEPARATOR, getTranslationComponent(key))))))
                        .tooltip(Tooltip.create(getTooltipComponent(key, null))).build(), false);
        }

        @SuppressWarnings("unchecked")
        @Override protected Element createOtherValue(String key, ModConfigSpec.ConfigValue<?> value) {
            if (!CraftableServerConfig.directPreparationValues().contains(value)) return super.createOtherValue(key, value);
            var registered = (ModConfigSpec.ConfigValue<List<String>>) value;
            var control = Button.builder(Component.translatable("screen.craftable.settings.direct.edit_ids"), button -> {
                    if (!editablePolicy()) return;
                        minecraft.setScreen(new RecipeIdsScreen(this, registered, getTranslationComponent(key), proposed -> {
                            var previous = List.copyOf(registered.getRaw());
                            if (previous.equals(proposed) || !editablePolicy()) return;
                            undoManager.add(candidate -> {
                                if (editablePolicy()) { registered.set(candidate); onChanged(key); }
                            }, proposed, candidate -> {
                                if (editablePolicy()) { registered.set(candidate); onChanged(key); }
                            }, previous);
                            rebuild();
                        }));
                    }).tooltip(Tooltip.create(getTooltipComponent(key, null))).build();
            control.active = editablePolicy(); policyControls.add(control);
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), control);
        }

        @Override protected <T extends Enum<T>> Element createEnumValue(String key, ModConfigSpec.ValueSpec metadata,
                Supplier<T> source, Consumer<T> target) {
            if (!(source.get() instanceof DirectPreparationPolicy.Mode)) return super.createEnumValue(key, metadata, source, target);
            var value = CraftableServerConfig.directPreparationValues().getFirst();
            var candidates = List.of(source.get().getDeclaringClass().getEnumConstants());
            var option = new OptionInstance<>(
                    getTranslationKey(key), getTooltip(key, null), (caption, mode) -> NativeConfigOption.display(value, mode),
                    new Custom<>(candidates), source.get(), next -> {
                        if (!editablePolicy()) return;
                        undoManager.add(mode -> { if (editablePolicy()) { target.accept(mode); onChanged(key); } }, next,
                                mode -> { if (editablePolicy()) { target.accept(mode); onChanged(key); } }, source.get());
                    });
            var control = option.createButton(options); control.active = editablePolicy(); policyControls.add(control);
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), control);
        }
    }

    private static boolean worldEditable() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.getConnection() != null && mc.hasSingleplayerServer()
                && !mc.getSingleplayerServer().isPublished() && CraftableServerConfig.SPEC.isLoaded();
    }

    /** A professional ID-list control; registered metadata validates the whole
     * list before the ordinary NeoForge section's undo buffer receives it. */
    private static final class RecipeIdsScreen extends OptionsSubScreen {
        private final ModConfigSpec.ConfigValue<List<String>> value;
        private final Consumer<List<String>> commit;
        private final List<String> proposed;
        private final Object connection = Minecraft.getInstance().getConnection(), level = Minecraft.getInstance().level;
        private final List<EditBox> boxes = new ArrayList<>();
        private final List<Button> removes = new ArrayList<>();
        private Button add, defaults, done;
        RecipeIdsScreen(Screen parent, ModConfigSpec.ConfigValue<List<String>> value, Component title, Consumer<List<String>> commit) {
            super(parent, Minecraft.getInstance().options, title);
            this.value = value; this.commit = commit; proposed = new ArrayList<>(value.getRaw());
        }
        private boolean editable() { return worldEditable() && connection == minecraft.getConnection() && level == minecraft.level; }
        @Override protected void addOptions() {
            list.setFocused(null); list.setSelected(null); list.children().clear(); boxes.clear(); removes.clear();
            for (int i = 0; i < proposed.size(); i++) {
                final int index = i;
                var box = new EditBox(font, 150, 20, Component.literal("namespace:recipe"));
                box.setMaxLength(128); box.setValue(proposed.get(i));
                box.setTooltip(Tooltip.create(Component.translatable("screen.craftable.settings.direct.recipe_id.tooltip")));
                box.setResponder(candidate -> { proposed.set(index, candidate); updatePermissions(); });
                var remove = Button.builder(Component.translatable("screen.craftable.settings.direct.remove"), button -> {
                    if (!editable()) return; proposed.remove(index); addOptions();
                }).width(150).build();
                boxes.add(box); removes.add(remove); list.addSmall(box, remove);
            }
            add = Button.builder(Component.translatable("screen.craftable.settings.direct.add"), button -> {
                if (!editable() || proposed.size() >= DirectPreparationPolicy.MAX_RECIPES) return;
                proposed.add("minecraft:"); addOptions();
            }).width(150).build();
            list.addSmall(add, null); updatePermissions();
        }
        @Override protected void addFooter() {
            var footer = layout.addToFooter(LinearLayout.horizontal().spacing(5));
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(100).build());
            defaults = footer.addChild(Button.builder(Component.translatable("screen.craftable.settings.defaults"), button -> {
                if (!editable()) return; proposed.clear(); proposed.addAll(value.getDefault()); addOptions();
            }).width(100).build());
            done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
                if (!editable() || !value.getSpec().test(proposed)) return;
                commit.accept(List.copyOf(proposed)); minecraft.setScreen(lastScreen);
            }).width(100).build());
            updatePermissions();
        }
        @Override public void tick() { updatePermissions(); }
        private void updatePermissions() {
            boolean permitted = minecraft != null && editable();
            for (int i = 0; i < boxes.size(); i++) {
                boxes.get(i).setEditable(permitted);
                boxes.get(i).setTextColor(DirectPreparationPolicy.validRecipeId(proposed.get(i)) ? EditBox.DEFAULT_TEXT_COLOR : 0xFFFF0000);
            }
            for (var remove : removes) remove.active = permitted;
            if (add != null) add.active = permitted && proposed.size() < DirectPreparationPolicy.MAX_RECIPES;
            if (defaults != null) defaults.active = permitted;
            if (done != null) done.active = permitted && value.getSpec().test(proposed);
        }
        @Override public void onClose() { minecraft.setScreen(lastScreen); }
    }
}
