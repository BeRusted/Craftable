package org.berusted.craftable.client;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.client.menu.AmbientInventoryEvents;
import org.berusted.craftable.client.mixin.OptionsListEntryAccessor;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.ConfigSettings;
import org.berusted.craftable.config.DirectPreparationPolicy;

/** Real native options controls and settings request/response. The integrated
 * fixture proves response sourcing, not remote/LAN permission coverage. */
final class PreM5SettingsScenario {
    private int phase, age, languageReadyTicks;
    private int entryStage, entryAge, entryScale = 1;
    private volatile boolean configured;
    private volatile Throwable serverFailure, captureFailure;
    private CraftableClientConfig.Preferences before, saved;
    private int oldSteps, oldRadius, oldScale, oldWidth, oldHeight;
    private String oldLanguage;
    private InputType oldInputType;
    private boolean initialized, restored;
    private boolean directPreparationChecked;
    private boolean professionalPolicyChecked;
    private Object professionalListBefore;
    private Screen policyWorld;
    private DirectPreparationPolicy policyBefore;
    private Map<ModConfigSpec.ConfigValue<?>, Object> policyDraftBefore;
    private final Map<ModConfigSpec.ConfigValue<?>, Object> oldPolicyValues = new IdentityHashMap<>();
    private long firstRevision;
    private int oldTtl;
    private Screen home;
    private int expectedAccessibilityRows;
    private AbstractWidget accessibilityTrailingLeft;
    private final Map<AbstractWidget, Bounds> originalOptions = new IdentityHashMap<>();
    private Screen captureScreen;
    private AbstractWidget tooltipFocus;
    private String captureName;
    private volatile boolean captured;
    private Screen deferredScreen;
    private Runnable afterRender;
    private int deferredRenderedFrames;
    private final Consumer<ScreenEvent.Init.Post> initListener = this::captureOriginalOptions;
    private final Consumer<ScreenEvent.Render.Post> captureListener = this::captureFrame;

    boolean tick(Minecraft mc) throws Exception {
        try { return advance(mc); }
        catch (Throwable error) {
            restore(mc);
            if (error instanceof Exception exception) throw exception;
            throw new AssertionError("Settings fixture failed", error);
        }
    }

    private boolean advance(Minecraft mc) throws Exception {
        if (serverFailure != null) throw new AssertionError("Settings server fixture failed", serverFailure);
        if (captureFailure != null) throw new AssertionError("Settings screenshot failed", captureFailure);
        if (++age > 400) throw new AssertionError("Settings timeout phase=" + phase
                + ", liveSteps=" + (initialized ? serverSteps().get() : "uninitialized")
                + ", screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
        if (afterRender != null) {
            require(mc.screen == deferredScreen, "Deferred native interaction lost its expected screen");
            if (deferredRenderedFrames < 2) return false;
            var action = afterRender; afterRender = null; deferredScreen = null;
            action.run(); age = 0;
            return false;
        }
        if (phase == 20 || phase == 24) languageReadyTicks = mc.getOverlay() == null ? languageReadyTicks + 1 : 0;
        if (phase == 0) {
            before = CraftableClientConfig.preferences();
            oldSteps = serverSteps().get(); oldRadius = serverRadius().get();
            for (var value : CraftableServerConfig.directPreparationValues()) {
                Object original = value.getRaw();
                oldPolicyValues.put(value, original instanceof List<?> ids ? List.copyOf(ids) : original);
            }
            oldTtl = serverValue("environment", "previewCacheTicks").get();
            oldScale = mc.options.guiScale().get(); oldLanguage = mc.getLanguageManager().getSelected();
            oldInputType = mc.getLastInputType();
            oldWidth = mc.getWindow().getScreenWidth(); oldHeight = mc.getWindow().getScreenHeight();
            initialized = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ScreenEvent.Init.Post.class, initListener);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ScreenEvent.Render.Post.class, captureListener);
            verifyAccessibilityAppendBoundaries(mc);
            mc.player.closeContainer(); mc.setScreen(null);
            configure(mc, 192, 3);
            mc.getWindow().setWindowed(1280, 720); mc.options.guiScale().set(1); mc.resizeDisplay();
            next(); return false;
        }
        if (phase == 1 && configured && age > 4) {
            if (testSettingsEntry(mc)) { phase = 5; age = 0; }
            return false;
        }
        if (phase == 5 && rulesReady(mc, 192, 3)) {
            firstRevision = (long) read(read(mc.screen, "rules"), "revision");
            home = mc.screen;
            require(((Button) read(home, "worldEntry")).getMessage().getString().equals(translated("edit_world")),
                    "Unpublished integrated world did not offer Edit World Rules");
            openReadOnlyWorld(mc); next(); return false;
        }
        if (phase == 6 && age > 2 && rulesReady(mc, 192, 3)) {
            verifyWorldPage(mc, 192);
            policyWorld = mc.screen;
            click(mc.screen, (Button) read(mc.screen, "directPreparationEntry"));
            phase = 48; age = 0; return false;
        }
        if (phase == 48 && age > 2 && !((Map<?, ?>) read(mc.screen, "recipes")).isEmpty()) {
            require(mc.screen instanceof DirectPreparationSettingsScreen && read(mc.screen, "draft") == null,
                    "Read-only recipe rules acquired a local server draft");
            var effective = ((CraftableServerConfig.CraftingRules) read(read(policyWorld, "rules"), "crafting")).directPreparation();
            require(read(mc.screen, "observedPolicy").equals(effective), "Read-only recipe editor substituted the local SERVER spec");
            require(((List<?>) read(mc.screen, "choices")).stream().allMatch(button -> !((Button) button).active)
                    && !rowWidgets((OptionsList) read(mc.screen, "list")).contains(read(mc.screen, "add")),
                    "Read-only recipe rules exposed writable recipe overrides");
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == policyWorld, "Read-only recipe editor lost its world parent");
            configure(mc, 1, 3); phase = 7; age = 0; return false;
        }
        if (phase == 7 && configured && age > 4) {
            // In one integrated process the mutable spec changes on both
            // sides. The displayed response must nevertheless remain 192,
            // proving it is not silently reading the client's current spec.
            require(serverSteps().get() == 1 && rulesReady(mc, 192, 3), "World page substituted local config for the last response");
            CraftableSettingsScreen.receiveRules(firstRevision - 1, CraftableServerConfig.scanSettings(), CraftableServerConfig.craftingRules());
            require(rulesReady(mc, 192, 3), "Stale settings response replaced the displayed rules");
            click(mc.screen, translated("retry"));
            require((boolean) read(read(mc.screen, "rules"), "pending"), "Native retry did not request a fresh settings response");
            require(!((Button) read(mc.screen, "retry")).active,
                    "Footer retry remained active while its authoritative reply was pending");
            require(tooltipText((Button) read(mc.screen, "retry"), mc)
                    .contains(tooltipText(Tooltip.create(Component.translatable("screen.craftable.settings.rules_pending")), mc)),
                    "Pending authoritative status is missing from the disabled footer retry tooltip");
            next(); return false;
        }
        if (phase == 8 && rulesReady(mc, 1, 3)) {
            verifyWorldPage(mc, 1);
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == home, "Read-only rules did not return to their main page");
            next(); return false;
        }
        if (phase == 9 && age > 2) {
            toggleEnhancements(mc);
            require(CraftableClientConfig.preferences().equals(before), "Editing a personal draft saved before Done");
            mc.screen.keyPressed(256, 0, 0);
            require(CraftableClientConfig.preferences().equals(before), "Escape saved an unconfirmed personal draft");
            reopenMain(mc); next(); return false;
        }
        if (phase == 10 && age > 2) {
            toggleEnhancements(mc); saved = personalDraft(mc.screen);
            click(mc.screen, CommonComponents.GUI_DONE.getString()); next(); return false;
        }
        if (phase == 11 && age > 2) {
            require(CraftableClientConfig.preferences().equals(saved), "Done did not apply the registered client spec");
            verifySavedFile(saved);
            reopenMain(mc); next(); return false;
        }
        if (phase == 12 && age > 2) {
            require(personalDraft(mc.screen).equals(saved), "Reopened native settings did not reload saved preferences");
            click(mc.screen, translated("defaults"));
            require(personalDraft(mc.screen).equals(CraftableClientConfig.defaultPreferences()), "Defaults did not use registered spec defaults");
            require(CraftableClientConfig.preferences().equals(saved), "Defaults saved without Done");
            click(mc.screen, CommonComponents.GUI_CANCEL.getString());
            require(CraftableClientConfig.preferences().equals(saved), "Cancel saved defaults");
            reopenMain(mc); next(); return false;
        }
        if (phase == 13 && age > 2) {
            click(mc.screen, translated("defaults")); click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(CraftableClientConfig.preferences().equals(CraftableClientConfig.defaultPreferences()), "Done did not persist defaults");
            verifySavedFile(CraftableClientConfig.defaultPreferences());
            CraftableClientConfig.savePreferences(before);
            reopenMain(mc); next(); return false;
        }
        if (phase == 14 && age > 2) {
            home = mc.screen;
            click(mc.screen, (Button) read(mc.screen, "worldEntry"));
            require(mc.screen instanceof CraftableSettingsScreen && read(mc.screen, "draft") != null,
                    "World entry did not open the ordinary native editor");
            next(); return false;
        }
        if (phase == 15 && age > 2) {
            verifyConfigPresentation(mc, 8);
            if (!directPreparationChecked) {
                policyWorld = mc.screen; policyBefore = CraftableServerConfig.craftingRules().directPreparation();
                policyDraftBefore = ((ConfigSettings.Draft) read(mc.screen, "draft")).snapshot();
                click(mc.screen, (Button) read(mc.screen, "directPreparationEntry"));
                phase = 43; age = 0; return false;
            }
            setSliderEndpoint(mc, "maxPlanSteps", true);
            require(serverSteps().get() == 1, "Native world draft changed the live config before Done");
            click(mc.screen, CommonComponents.GUI_CANCEL.getString());
            require(mc.screen == home && serverSteps().get() == 1, "World Cancel committed an unconfirmed draft");
            openWorldAfterRender(mc);
            next(); return false;
        }
        if (phase == 43 && age > 2 && directCatalogReady(mc)) {
            verifyDirectPreparationEditor(mc);
            addUnknownRecipe(mc);
            require(CraftableServerConfig.craftingRules().directPreparation().equals(policyBefore),
                    "Recipe row editing changed registered world rules before Done");
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == policyWorld && ((ConfigSettings.Draft) read(mc.screen, "draft")).snapshot().equals(policyDraftBefore),
                    "Recipe editor Escape leaked unsaved list changes to the world draft");
            phase = 44; age = 0; return false;
        }
        if (phase == 44 && age > 2) {
            click(mc.screen, (Button) read(mc.screen, "directPreparationEntry")); phase = 45; age = 0; return false;
        }
        if (phase == 45 && age > 2 && directCatalogReady(mc)) {
            addUnknownRecipe(mc);
            verifyUnreadyRecipeCatalogPresentation(mc);
            click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(mc.screen == policyWorld && CraftableServerConfig.craftingRules().directPreparation().equals(policyBefore),
                    "Recipe editor Done saved rather than updating only the parent world draft");
            var parentDraft = (ConfigSettings.Draft) read(mc.screen, "draft");
            require(((List<?>) parentDraft.snapshot().get(directList("directPreparationAllowedRecipes")))
                    .contains("craftable:unknown_settings_probe"), "Recipe editor Done lost the exact unknown recipe ID");
            setOption(binding(mc.screen, "directPreparationMode").option(), DirectPreparationPolicy.Mode.ALL_SUPPORTED);
            require(((List<?>) parentDraft.snapshot().get(directList("directPreparationAllowedRecipes")))
                    .contains("craftable:unknown_settings_probe"), "Mode switching erased a custom recipe override");
            phase = 46; age = 0; return false;
        }
        if (phase == 46 && age > 2) {
            click(mc.screen, (Button) read(mc.screen, "directPreparationEntry")); phase = 47; age = 0; return false;
        }
        if (phase == 47 && age > 2) {
            require(((List<?>) read(mc.screen, "allowed")).contains("craftable:unknown_settings_probe"),
                    "Mode shortcut failed to preserve recipe lists on reopening");
            click(mc.screen, translated("defaults"));
            require(((List<?>) read(mc.screen, "allowed")).isEmpty() && ((List<?>) read(mc.screen, "confirmation")).isEmpty(),
                    "Recipe Defaults did not restore structural-default inheritance");
            mc.screen.keyPressed(256, 0, 0);
            require(((List<?>) ((ConfigSettings.Draft) read(mc.screen, "draft")).snapshot()
                    .get(directList("directPreparationAllowedRecipes"))).contains("craftable:unknown_settings_probe"),
                    "Recipe Defaults followed by Escape discarded the parent's prior accepted draft");
            require(CraftableServerConfig.craftingRules().directPreparation().equals(policyBefore),
                    "Recipe defaults or mode shortcut bypassed World Done");
            directPreparationChecked = true; phase = 15; age = 0; return false;
        }
        if (phase == 16 && age > 2) {
            require(binding(mc.screen, "maxPlanSteps").option().get().equals(1), "World Cancel leaked a draft into reopening");
            click(mc.screen, translated("defaults"));
            require(binding(mc.screen, "maxPlanSteps").option().get().equals(serverSteps().getDefault()),
                    "World Defaults did not use the registered spec default");
            require(serverSteps().get() == 1, "World Defaults saved before Done");
            mc.screen.keyPressed(256, 0, 0);
            openWorldAfterRender(mc);
            next(); return false;
        }
        if (phase == 17 && age > 2) {
            setSliderEndpoint(mc, "maxPlanSteps", true);
            var worldDraft = (ConfigSettings.Draft) read(mc.screen, "draft");
            var allowedValue = directList("directPreparationAllowedRecipes");
            var original = List.copyOf((List<?>) allowedValue.getRaw());
            var proposed = new ArrayList<String>();
            for (var id : (List<?>) worldDraft.snapshot().get(allowedValue)) proposed.add((String) id);
            if (!proposed.contains("craftable:native_persist_probe")) proposed.add("craftable:native_persist_probe");
            worldDraft.set(allowedValue, List.copyOf(proposed));
            require(allowedValue.getRaw().equals(original), "World recipe draft wrote the registered list before parent Done");
            click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(mc.screen == home, "World Done did not return to the ordinary main page");
            next(); return false;
        }
        if (phase == 18 && rulesReady(mc, CraftableServerConfig.MAX_MAX_PLAN_STEPS, 3)
                && rulesContainRecipe(mc, "craftable:native_persist_probe")) {
            require(serverSteps().get() == CraftableServerConfig.MAX_MAX_PLAN_STEPS, "World Done did not update the registered server spec");
            verifyServerSavedFile(CraftableServerConfig.MAX_MAX_PLAN_STEPS);
            require(((List<?>) directList("directPreparationAllowedRecipes").getRaw()).contains("craftable:native_persist_probe"),
                    "Native parent Done did not apply the registered recipe list");
            require(serverValue("environment", "previewCacheTicks").get() == oldTtl, "Ordinary world save changed professional-only TTL");
            configure(mc, oldSteps, oldRadius); next(); return false;
        }
        if (phase == 19 && configured && age > 4) {
            var rules = read(home, "rules");
            var request = rules.getClass().getDeclaredMethod("request", boolean.class);
            request.setAccessible(true); request.invoke(rules, true);
            setLanguage(mc, "zh_cn"); next(); return false;
        }
        if (phase == 20 && languageReadyTicks > 4) {
            require(translated("edit_world").contains("编辑"), "Chinese settings strings did not load");
            verifySectionTranslations();
            verifyPersonalPresentation(mc);
            capture(mc, "pre-m5-native-home-zh-20261010"); next(); return false;
        }
        if (phase == 21 && captured) {
            home = mc.screen;
            focusPartialPolicyTooltip(mc);
            phase = 38; age = 0; return false;
        }
        if (phase == 38 && age > 2) {
            capture(mc, "pre-m5-native-partial-policy-tooltip-zh-20261010");
            next(); return false;
        }
        if (phase == 39 && captured) {
            tooltipFocus.setFocused(false); tooltipFocus = null;
            openReadOnlyWorld(mc);
            phase = 22; age = 0; return false;
        }
        if (phase == 22 && age > 2 && rulesReady(mc, oldSteps, oldRadius)) {
            verifyWorldPage(mc, oldSteps);
            capture(mc, "pre-m5-native-world-view-zh-20261010"); next(); return false;
        }
        if (phase == 23 && captured) {
            mc.screen.keyPressed(256, 0, 0);
            openWorldAfterRender(mc);
            phase = 36; age = 0; return false;
        }
        if (phase == 36 && age > 2) {
            verifyConfigPresentation(mc, 8);
            capture(mc, "pre-m5-native-world-editor-zh-20261010"); next(); return false;
        }
        if (phase == 37 && captured) {
            policyWorld = mc.screen;
            click(mc.screen, (Button) read(mc.screen, "directPreparationEntry"));
            phase = 60; age = 0; return false;
        }
        if (phase == 24 && languageReadyTicks > 4) {
            require(translated("edit_world").contains("Edit"), "English settings strings did not load");
            verifySectionTranslations();
            verifyPersonalPresentation(mc);
            capture(mc, "pre-m5-native-home-en-20261010"); next(); return false;
        }
        if (phase == 25 && captured) {
            home = mc.screen;
            focusPartialPolicyTooltip(mc);
            phase = 40; age = 0; return false;
        }
        if (phase == 40 && age > 2) {
            capture(mc, "pre-m5-native-partial-policy-tooltip-en-20261010");
            next(); return false;
        }
        if (phase == 41 && captured) {
            tooltipFocus.setFocused(false); tooltipFocus = null;
            click(mc.screen, (Button) read(mc.screen, "worldEntry"));
            phase = 26; age = 0; return false;
        }
        if (phase == 26 && age > 2) {
            verifyConfigPresentation(mc, 8);
            capture(mc, "pre-m5-native-world-editor-en-20261010"); next(); return false;
        }
        if (phase == 27 && captured) {
            policyWorld = mc.screen;
            click(mc.screen, (Button) read(mc.screen, "directPreparationEntry"));
            phase = 65; age = 0; return false;
        }
        if (phase == 28 && rulesReady(mc, oldSteps, oldRadius)) {
            verifyWorldPage(mc, oldSteps);
            capture(mc, "pre-m5-native-world-view-en-20261010"); next(); return false;
        }
        if (phase == 29 && captured) {
            policyWorld = mc.screen;
            click(mc.screen, (Button) read(mc.screen, "directPreparationEntry"));
            phase = 67; age = 0; return false;
        }
        if (phase == 30 && age > 2) {
            var config = registered(ModConfig.Type.CLIENT, CraftableClientConfig.SPEC);
            var professional = (ConfigurationScreen) mc.screen;
            click(mc.screen, Component.translatable("neoforge.configuration.uitext.section",
                    professional.translatableConfig(config, "", "neoforge.configuration.uitext.type.client")).getString());
            next(); return false;
        }
        if (phase == 31 && age > 2) {
            clickSection(mc.screen, "craftable.configuration.presentation"); next(); return false;
        }
        if (phase == 32 && age > 2) {
            capture(mc, "pre-m5-native-professional-presentation-en-20261010");
            phase = 42; age = 0; return false;
        }
        if (phase == 42 && captured) {
            verifyProfessionalFooter(mc);
            clickLabeledValue(mc.screen, "config.craftable.client.detailed_failure_feedback");
            require(CraftableClientConfig.detailedFailureFeedbackEnabled() != before.detailedFailureFeedback(),
                    "NeoForge control did not edit the shared registered value");
            mc.screen.keyPressed(256, 0, 0); phase = 33; age = 0; return false;
        }
        if (phase == 33 && age > 2) { mc.screen.keyPressed(256, 0, 0); next(); return false; }
        if (phase == 34 && age > 2) { mc.screen.keyPressed(256, 0, 0); next(); return false; }
        if (phase == 35 && age > 2) {
            require(mc.screen instanceof CraftableSettingsScreen && read(mc.screen, "page").toString().equals("MAIN"),
                    "Professional settings did not return to the flattened main page");
            var refreshed = personalDraft(mc.screen);
            require(refreshed.recipeBookEnhancements() == saved.recipeBookEnhancements()
                    && refreshed.detailedFailureFeedback() == CraftableClientConfig.detailedFailureFeedbackEnabled(),
                    "Returning from professional settings lost a dirty draft or retained an unchanged stale value");
            require(CraftableClientConfig.recipeBookEnhancementsEnabled() == before.recipeBookEnhancements(),
                    "Opening professional settings committed an ordinary unsaved draft");
            verifySavedFile(CraftableClientConfig.preferences());
            if (!professionalPolicyChecked) {
                click(mc.screen, (Button) read(mc.screen, "advanced")); phase = 50; age = 0; return false;
            }
            click(mc.screen, CommonComponents.GUI_CANCEL.getString());
            require(CraftableClientConfig.detailedFailureFeedbackEnabled() != before.detailedFailureFeedback()
                    && CraftableClientConfig.recipeBookEnhancementsEnabled() == before.recipeBookEnhancements(),
                    "Ordinary Cancel undid professional saves or committed its own draft");
            restore(mc);
            Craftable.LOGGER.warn("PRE_M5_SETTINGS PASS: unchanged main options, last-row accessibility fill and empty/full/narrow/big-row fallback preserving native option mapping and delayed slider flush, half-width entry scales1/2/3, scroll/resize/keyboard/Enter/Esc/mouse/return, flattened six-option main with trailing preference hint, zh/en half-width enum/range captions and multiline option descriptions without isolated Chinese closing-punctuation lines, authoritative rules192→1→restored, stale response rejection, read-only retry/Done footer without status rows, editable Cancel/Defaults/Done footer ordering and professional Undo/Reset/Done ordering, personal draft/cancel/default/save-file/reopen, eight-option native world editor and half-width recipe-list entry without status/retry, recipe shared registered identities and world draft, recipe search/bounded widgets/unknown ID/explicit namespace, child Cancel/Done/Defaults and parent cancellation, mode switch retains lists, authoritative read-only recipe lists without writable controls, native parent Done applies recipe list to registered raw value/TOML/fresh RulesResponse, zh/en rendered editable and read-only recipe-list screenshots plus English professional ID-editor screenshot, world draft/cancel/default/save-file/reply-refresh, professional-only TTL preservation, actual NeoForge edit/shared values and draft-safe return, professional recipe-list child Cancel/Done and native Undo, guarded Undo/Redo/Reset callbacks under an injected expired screen identity restored afterward (not actual LAN publication); remote/LAN/outside-world permissions require separate evidence");
            return true;
        }
        if (phase == 50 && age > 2) {
            var config = registered(ModConfig.Type.SERVER, CraftableServerConfig.SPEC);
            var professional = (ConfigurationScreen) mc.screen;
            click(mc.screen, Component.translatable("neoforge.configuration.uitext.section",
                    professional.translatableConfig(config, "", "neoforge.configuration.uitext.type.server")).getString());
            phase = 51; age = 0; return false;
        }
        if (phase == 51 && age > 2) {
            clickSection(mc.screen, "craftable.configuration.crafting"); phase = 52; age = 0; return false;
        }
        if (phase == 52 && age > 2) {
            professionalListBefore = List.copyOf((List<?>) directList("directPreparationAllowedRecipes").getRaw());
            clickLabeledValue(mc.screen, "config.craftable.server.direct_preparation_allowed_recipes");
            phase = 53; age = 0; return false;
        }
        if (phase == 53 && age > 2) {
            verifyEditableFooter(mc);
            require(read(mc.screen, "value") == directList("directPreparationAllowedRecipes"),
                    "Professional list control duplicated the setting identity");
            appendProfessionalUnknownId(mc);
            require(directList("directPreparationAllowedRecipes").getRaw().equals(professionalListBefore),
                    "Professional child editing bypassed its Done/undo lifecycle");
            deferUntilRendered(mc.screen, () -> capture(mc, "pre-m5-professional-preparation-ids-en-20261013"));
            phase = 69; age = 0; return false;
        }
        if (phase == 54 && age > 2) {
            require(directList("directPreparationAllowedRecipes").getRaw().equals(professionalListBefore),
                    "Professional list Escape saved an unconfirmed child edit");
            clickLabeledValue(mc.screen, "config.craftable.server.direct_preparation_allowed_recipes");
            phase = 55; age = 0; return false;
        }
        if (phase == 55 && age > 2) {
            appendProfessionalUnknownId(mc); click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(((List<?>) directList("directPreparationAllowedRecipes").getRaw()).contains("craftable:professional_settings_probe"),
                    "Professional list Done did not use its shared registered value");
            phase = 56; age = 0; return false;
        }
        if (phase == 56 && age > 2) {
            verifyProfessionalFooter(mc);
            verifyProfessionalPolicyContextExpiry(mc);
            click(mc.screen, ConfigurationScreen.UNDO.getString());
            require(directList("directPreparationAllowedRecipes").getRaw().equals(professionalListBefore),
                    "Professional recipe list edit bypassed NeoForge's real undo buffer");
            mc.screen.keyPressed(256, 0, 0); phase = 57; age = 0; return false;
        }
        if (phase == 57 && age > 2) { mc.screen.keyPressed(256, 0, 0); phase = 58; age = 0; return false; }
        if (phase == 58 && age > 2) {
            mc.screen.keyPressed(256, 0, 0); professionalPolicyChecked = true; phase = 35; age = 0; return false;
        }
        if (phase == 60 && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(mc, false, "pre-m5-native-preparation-recipes-zh-20261013");
            phase = 61; age = 0; return false;
        }
        if (phase == 61 && captured) {
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == policyWorld, "Chinese recipe editor lost its world draft parent");
            mc.screen.keyPressed(256, 0, 0);
            openReadOnlyWorld(mc); phase = 62; age = 0; return false;
        }
        if (phase == 62 && rulesReady(mc, oldSteps, oldRadius)) {
            deferUntilRendered(mc.screen, () -> {
                try {
                    policyWorld = mc.screen;
                    click(mc.screen, (Button) read(mc.screen, "directPreparationEntry"));
                } catch (Exception error) { throw new AssertionError("Deferred read-only recipe navigation failed", error); }
            });
            phase = 63; age = 0; return false;
        }
        if (phase == 63 && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(mc, true, "pre-m5-native-preparation-read-only-zh-20261013");
            phase = 64; age = 0; return false;
        }
        if (phase == 64 && captured) {
            mc.screen.keyPressed(256, 0, 0); mc.screen.keyPressed(256, 0, 0);
            require(personalDraft(mc.screen).equals(before), "Language/resize lost the restored personal config");
            setLanguage(mc, "en_us"); phase = 24; age = 0; languageReadyTicks = 0; return false;
        }
        if (phase == 65 && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(mc, false, "pre-m5-native-preparation-recipes-en-20261013");
            phase = 66; age = 0; return false;
        }
        if (phase == 66 && captured) {
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == policyWorld, "English recipe editor lost its world draft parent");
            mc.screen.keyPressed(256, 0, 0); openReadOnlyWorld(mc); phase = 28; age = 0; return false;
        }
        if (phase == 67 && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(mc, true, "pre-m5-native-preparation-read-only-en-20261013");
            phase = 68; age = 0; return false;
        }
        if (phase == 68 && captured) {
            mc.screen.keyPressed(256, 0, 0); mc.screen.keyPressed(256, 0, 0);
            deferUntilRendered(mc.screen, () -> {
                try {
                    toggleEnhancements(mc); saved = personalDraft(mc.screen);
                    click(mc.screen, (Button) read(mc.screen, "advanced"));
                    require(mc.screen instanceof ConfigurationScreen, "Advanced fallback did not use the existing NeoForge config UI");
                } catch (Exception error) { throw new AssertionError("Deferred professional navigation failed", error); }
            });
            phase = 30; age = 0; return false;
        }
        if (phase == 69 && captured) {
            mc.screen.keyPressed(256, 0, 0); phase = 54; age = 0; return false;
        }
        return false;
    }

    private void captureOriginalOptions(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof AccessibilityOptionsScreen) {
            var list = event.getListenersList().stream().filter(OptionsList.class::isInstance)
                    .map(OptionsList.class::cast).findFirst().orElseThrow();
            var rows = ((ContainerEventHandler) list).children();
            var lastWidgets = rows.isEmpty() ? List.<GuiEventListener>of()
                    : ((ContainerEventHandler) rows.getLast()).children();
            accessibilityTrailingLeft = lastWidgets.size() == 1 && lastWidgets.getFirst() instanceof AbstractWidget widget
                    && widget.getWidth() <= 150 ? widget : null;
            expectedAccessibilityRows = rows.size() + (accessibilityTrailingLeft == null ? 1 : 0);
            return;
        }
        if (!(event.getScreen() instanceof OptionsScreen)) return;
        originalOptions.clear();
        for (var listener : event.getListenersList()) if (listener instanceof AbstractWidget widget)
            originalOptions.put(widget, new Bounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
    }

    private boolean testSettingsEntry(Minecraft mc) throws Exception {
        entryAge++;
        if (entryStage == 0) {
            mc.options.guiScale().set(entryScale); mc.resizeDisplay();
            if (mc.screen instanceof AccessibilityOptionsScreen) verifyAccessibility(mc, false);
            mc.setScreen(new OptionsScreen(null, mc.options));
            verifyMainOptions(mc);
            if (entryScale == 1) capture(mc, "pre-m5-native-main-options-restored-20261010");
            entryStage++; entryAge = 0;
        } else if (entryStage == 1 && entryAge > 2 && (entryScale != 1 || captured)) {
            click(mc.screen, Component.translatable("options.accessibility").getString());
            require(mc.screen instanceof AccessibilityOptionsScreen, "Vanilla accessibility button opened the wrong page");
            entryStage++; entryAge = 0;
        } else if (entryStage == 2 && entryAge > 2) {
            verifyAccessibility(mc, false);
            var list = (OptionsList) read(mc.screen, "list");
            require(mc.screen.mouseScrolled(mc.screen.width / 2, list.getY() + list.getHeight() / 2, 0, -100),
                    "Native accessibility list ignored mouse wheel scrolling");
            entryStage++; entryAge = 0;
        } else if (entryStage == 3 && entryAge > 2) {
            verifyAccessibility(mc, true);
            capture(mc, "pre-m5-native-accessibility-scale-" + entryScale + "-20261010");
            entryStage++; entryAge = 0;
        } else if (entryStage == 4 && captured) {
            if (entryScale < 3) { entryScale++; entryStage = 0; entryAge = 0; return false; }
            var accessibility = mc.screen;
            var entry = button(accessibility, modName());
            // Screen.keyPressed bypasses KeyboardHandler's input-type marker;
            // vanilla only scrolls focused rows automatically for keyboard input.
            mc.setLastInputType(InputType.KEYBOARD_TAB);
            accessibility.clearFocus();
            ((OptionsList) read(accessibility, "list")).setScrollAmount(0);
            for (int i = 0; i < 100 && !entry.isFocused(); i++) accessibility.keyPressed(258, 0, 0);
            require(entry.isFocused(), "Tab did not reach the nested Craftable list entry");
            entryStage = 7; entryAge = 0;
        } else if (entryStage == 7 && entryAge > 2) {
            verifyAccessibility(mc, true);
            var accessibility = mc.screen;
            var list = (OptionsList) read(accessibility, "list");
            double scroll = list.getScrollAmount();
            require(scroll > 0, "Keyboard focus did not scroll the offscreen settings entry into view");
            accessibility.keyPressed(257, 0, 0);
            require(mc.screen instanceof CraftableSettingsScreen, "Enter did not open settings from accessibility");
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == accessibility, "Escape did not return to the same accessibility page");
            require(list.getScrollAmount() == scroll, "Returning from settings lost accessibility scroll position");
            verifyAccessibility(mc, true);
            click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(mc.screen instanceof OptionsScreen, "Accessibility Done did not return to vanilla options");
            verifyMainOptions(mc);
            entryStage = 5; entryAge = 0;
        } else if (entryStage == 5 && entryAge > 2) {
            click(mc.screen, Component.translatable("options.accessibility").getString());
            ((OptionsList) read(mc.screen, "list")).setScrollAmount(Double.MAX_VALUE);
            entryStage++; entryAge = 0;
        } else if (entryStage == 6 && entryAge > 2) {
            verifyAccessibility(mc, true);
            click(mc.screen, modName());
            require(mc.screen instanceof CraftableSettingsScreen, "Mouse did not open the nested settings entry");
            require(CraftableClientConfig.preferences().equals(before), "Opening settings changed personal values");
            mc.options.guiScale().set(2); mc.resizeDisplay();
            return true;
        }
        return false;
    }

    private void verifyMainOptions(Minecraft mc) {
        require(mc.screen instanceof OptionsScreen && !originalOptions.isEmpty(), "Original Options layout was not observed");
        require(widgets(mc.screen).stream().noneMatch(widget -> widget.getMessage().getString().equals(modName())),
                "Craftable still occupies the main Options page");
        for (var entry : originalOptions.entrySet())
            require(entry.getValue().equals(new Bounds(entry.getKey().getX(), entry.getKey().getY(),
                    entry.getKey().getWidth(), entry.getKey().getHeight())), "Settings insertion moved an original option: "
                    + entry.getKey().getMessage().getString() + " expected=" + entry.getValue() + " actual="
                    + new Bounds(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getWidth(), entry.getKey().getHeight()));
    }

    private void verifyAccessibility(Minecraft mc, boolean visible) throws Exception {
        require(mc.screen instanceof AccessibilityOptionsScreen, "Settings entry is not inside accessibility");
        var list = (OptionsList) read(mc.screen, "list");
        var entry = button(mc.screen, modName());
        var rows = ((ContainerEventHandler) list).children();
        require(rows.size() == expectedAccessibilityRows && widgets(list).stream()
                .filter(widget -> widget.getMessage().getString().equals(modName())).count() == 1,
                "Accessibility has missing native rows or duplicate settings entries");
        require(widgets((ContainerEventHandler) rows.getLast()).contains(entry),
                "Craftable is not a native scrollable list row");
        var lastWidgets = ((ContainerEventHandler) rows.getLast()).children();
        require(accessibilityTrailingLeft == null ? lastWidgets.size() == 1 && lastWidgets.getFirst() == entry
                : lastWidgets.size() == 2 && lastWidgets.getFirst() == accessibilityTrailingLeft && lastWidgets.getLast() == entry,
                "Craftable did not fill the available trailing slot without displacing its native left-hand option");
        require(list.findOption(mc.options.hideSplashTexts()) != null && list.findOption(mc.options.narratorHotkey()) != null,
                "Settings entry displaced vanilla accessibility options");
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        var guide = button(mc.screen, Component.translatable("options.accessibility.link").getString());
        require(done.getY() == guide.getY() && done.getX() == guide.getX() + guide.getWidth() + 8
                && done.getWidth() == 150 && guide.getWidth() == 150,
                "Settings entry changed the native accessibility footer");
        require(entry.active && entry.getTooltip() != null && entry.getWidth() == 150,
                "Settings list entry lacks native focus/tooltip state");
        if (visible) require(entry.getX() == mc.screen.width / 2 + (accessibilityTrailingLeft == null ? -155 : 5)
                && entry.getY() >= list.getY()
                && entry.getY() + entry.getHeight() <= list.getBottom() && entry.getY() + entry.getHeight() < done.getY(),
                "Scrolled settings entry is clipped or overlaps the native footer");
    }

    private static void verifyAccessibilityAppendBoundaries(Minecraft mc) {
        var parent = new AccessibilityOptionsScreen(null, mc.options);
        parent.width = mc.getWindow().getGuiScaledWidth(); parent.height = mc.getWindow().getGuiScaledHeight();
        var empty = new OptionsList(mc, parent.width, parent);
        AmbientInventoryEvents.appendSettingsButton(parent, empty);
        require(((ContainerEventHandler) empty).children().size() == 1 && rowWidgets(empty).size() == 1,
                "Appending to an empty native list did not create one left-hand settings slot");

        var draft = new org.berusted.craftable.config.ConfigSettings.Draft(CraftableServerConfig.SPEC);
        var slider = NativeConfigOption.create(serverSteps(), draft);
        var single = new OptionsList(mc, parent.width, parent);
        single.addSmall(slider.option());
        var originalRow = ((ContainerEventHandler) single).children().getFirst();
        var originalSlider = single.findOption(slider.option());
        AmbientInventoryEvents.appendSettingsButton(parent, single);
        require(((ContainerEventHandler) single).children().size() == 1
                && ((ContainerEventHandler) single).children().getFirst() == originalRow
                && ((ContainerEventHandler) originalRow).children().getFirst() == originalSlider
                && ((ContainerEventHandler) originalRow).children().size() == 2
                && single.findOption(slider.option()) == originalSlider,
                "Filling a trailing native option row lost its identity, option mapping or original control");
        AmbientInventoryEvents.appendSettingsButton(parent, single);
        require(((ContainerEventHandler) single).children().size() == 1
                && ((ContainerEventHandler) originalRow).children().size() == 2,
                "Appending accessibility settings twice duplicated the entry");
        var range = serverSteps().getSpec().<Integer>getRange();
        boolean maximum = !slider.option().get().equals(range.getMax());
        int x = originalSlider.getX() + (maximum ? originalSlider.getWidth() - 4 : 4);
        var input = mc.getLastInputType(); mc.setLastInputType(InputType.MOUSE);
        require(originalSlider.mouseClicked(x, originalSlider.getY() + 5, 0), "Synthetic native slider ignored its actual click");
        originalSlider.mouseReleased(x, originalSlider.getY() + 5, 0);
        single.applyUnsavedChanges();
        mc.setLastInputType(input);
        require(slider.option().get().equals(maximum ? range.getMax() : range.getMin())
                && draft.get(serverSteps()).equals(slider.option().get()),
                "Filling a trailing slot lost the native delayed-slider applyUnsavedChanges mapping");

        var leftOption = OptionInstance.createBoolean("options.hideSplashTexts", false);
        var rightOption = OptionInstance.createBoolean("options.narrator_hotkey", true);
        var full = new OptionsList(mc, parent.width, parent); full.addSmall(leftOption, rightOption);
        var originalFullRow = ((ContainerEventHandler) full).children().getFirst();
        var left = full.findOption(leftOption); var right = full.findOption(rightOption);
        AmbientInventoryEvents.appendSettingsButton(parent, full);
        require(((ContainerEventHandler) full).children().size() == 2
                && ((ContainerEventHandler) full).children().getFirst() == originalFullRow
                && ((ContainerEventHandler) originalFullRow).children().size() == 2
                && full.findOption(leftOption) == left && full.findOption(rightOption) == right,
                "Appending after a full native row displaced its existing option mappings");
        require(widgets(((ContainerEventHandler) full).children().getLast()).size() == 1,
                "A full trailing row did not fall back to a new left-hand slot");

        var big = new OptionsList(mc, parent.width, parent); big.addBig(leftOption);
        var originalBig = big.findOption(leftOption);
        AmbientInventoryEvents.appendSettingsButton(parent, big);
        require(((ContainerEventHandler) big).children().size() == 2 && big.findOption(leftOption) == originalBig
                && originalBig.getWidth() == 310 && ((ContainerEventHandler) ((ContainerEventHandler) big).children()
                    .getFirst()).children().size() == 1,
                "A full-width trailing widget was incorrectly treated as an empty right-hand slot");

        var narrow = new OptionsList(mc, parent.width, parent);
        var narrowButton = Button.builder(Component.literal("Narrow native slot"), button -> {}).width(120).build();
        narrow.addSmall(narrowButton, null);
        AmbientInventoryEvents.appendSettingsButton(parent, narrow);
        require(((ContainerEventHandler) narrow).children().size() == 1
                && ((ContainerEventHandler) ((ContainerEventHandler) narrow).children().getFirst()).children().size() == 2,
                "A valid narrow trailing widget was not kept while filling the right-hand slot");

        var emptyRow = new OptionsList(mc, parent.width, parent);
        emptyRow.addSmall(Button.builder(Component.literal("Empty row fixture"), button -> {}).width(150).build(), null);
        ((OptionsListEntryAccessor) ((ContainerEventHandler) emptyRow).children().getFirst()).craftable$setWidgets(List.of());
        AmbientInventoryEvents.appendSettingsButton(parent, emptyRow);
        require(((ContainerEventHandler) emptyRow).children().size() == 2
                && ((ContainerEventHandler) ((ContainerEventHandler) emptyRow).children().getFirst()).children().isEmpty(),
                "An unusual empty trailing row was not safely preserved with new-row fallback");
    }

    private static void verifySectionTranslations() {
        for (String section : List.of("presentation", "environment", "crafting")) {
            String key = "craftable.configuration." + section + ".button";
            require(net.minecraft.locale.Language.getInstance().has(key)
                    && !Component.translatable(key).getString().isBlank(), "Missing native config button translation: " + key);
        }
    }

    private static boolean rulesReady(Minecraft mc, int steps, int radius) throws Exception {
        if (!(mc.screen instanceof CraftableSettingsScreen)) return false;
        var state = read(mc.screen, "rules");
        if ((boolean) read(state, "pending")) return false;
        var crafting = (CraftableServerConfig.CraftingRules) read(state, "crafting");
        var scan = (org.berusted.craftable.config.EnvironmentScanSettings) read(state, "scan");
        return crafting != null && scan != null && crafting.maxPlanSteps() == steps && scan.horizontalRadius() == radius;
    }

    private static boolean rulesContainRecipe(Minecraft mc, String id) throws Exception {
        if (!(mc.screen instanceof CraftableSettingsScreen)) return false;
        var rules = (CraftableServerConfig.CraftingRules) read(read(mc.screen, "rules"), "crafting");
        return rules != null && rules.directPreparation().allowedRecipes().contains(id);
    }

    private static boolean directCatalogReady(Minecraft mc) throws Exception {
        return mc.screen instanceof DirectPreparationSettingsScreen
                && ((org.berusted.craftable.recipe.PlanningInput.Catalog) read(mc.screen, "catalog")).state()
                    == org.berusted.craftable.recipe.PlanningInput.Catalog.State.READY
                && !((Map<?, ?>) read(mc.screen, "recipes")).isEmpty();
    }

    private void prepareDirectRecipeCapture(Minecraft mc, boolean readOnly, String name) throws Exception {
        require(directCatalogReady(mc), "Recipe screenshot attempted before its bounded static catalog was ready");
        require((read(mc.screen, "draft") == null) == readOnly, "Recipe screenshot used the wrong editing/inspection state");
        verifyRecipeCatalogStatusWidth(mc);
        var search = (net.minecraft.client.gui.components.EditBox) read(mc.screen, "search");
        search.setValue("minecraft:stick");
        var choices = (List<?>) read(mc.screen, "choices");
        require(!choices.isEmpty(), "Recipe screenshot's real stick search produced no supported recipe rows");
        if (!readOnly) {
            // Native keyboard tooltip presentation shows the actual recipe ID,
            // default judgement, grid and cost overview, not synthetic text.
            mc.screen.clearFocus();
            mc.setLastInputType(InputType.KEYBOARD_TAB);
            ((Button) choices.getFirst()).setFocused(true);
        } else require(choices.stream().allMatch(choice -> !((Button) choice).active),
                "Read-only screenshot retained an editable recipe choice");
        deferUntilRendered(mc.screen, () -> capture(mc, name));
    }

    private static void verifyWorldPage(Minecraft mc, int steps) throws Exception {
        require(read(mc.screen, "page").toString().equals("WORLD"), "World rules opened the wrong settings page");
        require(read(mc.screen, "draft") == null, "Read-only world view accidentally acquired an editable local draft");
        var rows = (List<?>) read(mc.screen, "ruleRows"); require(rows.size() == 8, "Effective world values are missing");
        var list = (OptionsList) read(mc.screen, "list");
        require(((ContainerEventHandler) list).children().size() == 5 && rowWidgets(list).size() == 9,
                "Read-only rules retained a status or retry row among the eight world options and recipe-list entry");
        var retry = (Button) read(mc.screen, "retry");
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        require(retry != null && retry.active && retry.getTooltip() != null
                && retry.getWidth() == 150 && done.getWidth() == 150 && retry.getY() == done.getY()
                && retry.getX() + retry.getWidth() + 5 == done.getX() && retry.getY() >= list.getBottom()
                && !rowWidgets(list).contains(retry) && !rowWidgets(list).contains(done),
                "Read-only Reload Rules and Done do not share the native footer outside the scrollable content");
        require(mc.screen.children().indexOf(retry) >= 0
                && mc.screen.children().indexOf(retry) < mc.screen.children().indexOf(done),
                "Read-only footer listener registration does not follow Reload Rules / Done");
        require(tooltipText(retry, mc).contains(tooltipText(Tooltip.create(
                Component.translatable("screen.craftable.settings.rules_from_world")), mc)),
                "Read-only authoritative status disappeared instead of moving to the footer retry tooltip");
        String narration = mc.screen.getNarrationMessage().getString();
        require(narration.contains(Integer.toString(steps)) && !narration.contains("previewCacheTicks")
                && !narration.contains(Component.translatable("config.craftable.server.preview_cache_ticks").getString()),
                "Narration uses local/raw/diagnostic values instead of effective rules");
        for (var row : rows) {
            var widget = (AbstractWidget) row;
            require(widget.getTooltip() != null && widget.getWidth() == 150 && !widget.active,
                    "Effective rule lacks half-width read-only presentation or readable tooltip");
            require(mc.font.width(widget.getMessage()) <= 138, "Read-only half-width rule caption is clipped: " + widget.getMessage().getString());
        }
        String expectedTooltip = Component.translatable("config.craftable.server.max_plan_steps.tooltip").getString();
        var maxStepsRow = (AbstractWidget) rows.get(6);
        require(tooltipText(maxStepsRow, mc).startsWith(expectedTooltip.substring(0, Math.min(12, expectedTooltip.length()))),
                "World tooltip retained the previous language");
        var heading = (AbstractWidget) read(mc.screen, "heading");
        require(mc.font.width(mc.screen.getTitle()) <= heading.getWidth(), "Language reload clipped the world title to its old width");
    }

    private static void verifyPersonalPresentation(Minecraft mc) throws Exception {
        require(read(mc.screen, "page").toString().equals("MAIN"), "Personal options still require a redundant second page");
        verifyConfigPresentation(mc, 6);
        var list = (OptionsList) read(mc.screen, "list");
        var hints = rowWidgets(list).stream().filter(StringWidget.class::isInstance).toList();
        require(hints.size() == 1 && hints.getFirst().getMessage().getString().equals(translated("preferences_only"))
                && hints.getFirst().getY() > ((Button) read(mc.screen, "advanced")).getY(),
                "Main settings preference explanation is missing or no longer refers to the options above it");
        require(mc.font.width(hints.getFirst().getMessage()) <= hints.getFirst().getWidth(),
                "Main settings trailing preference explanation is clipped: " + hints.getFirst().getMessage().getString());
        var world = (Button) read(mc.screen, "worldEntry");
        var advanced = (Button) read(mc.screen, "advanced");
        require(world.getWidth() == 150 && advanced.getWidth() == 150 && world.getY() == advanced.getY(),
                "Navigation does not use a native half-width pair");
        require(mc.font.width(world.getMessage()) <= 138 && mc.font.width(advanced.getMessage()) <= 138,
                "Half-width navigation captions are clipped");
        require(widgets(mc.screen).stream().filter(w -> w instanceof Button
                && w.getMessage().equals(CommonComponents.GUI_DONE)).count() == 1, "Defaults accumulated native footer widgets");
    }

    private static void toggleEnhancements(Minecraft mc) throws Exception {
        var option = binding(mc.screen, "recipeBookEnhancements").option();
        var list = (OptionsList) read(mc.screen, "list"); var widget = list.findOption(option);
        require(widget != null && widget.active, "Native personal toggle is unavailable");
        var old = option.get();
        mc.setLastInputType(InputType.MOUSE);
        require(mc.screen.mouseClicked(widget.getX() + 6, widget.getY() + 5, 0), "Personal toggle did not accept an actual click");
        mc.screen.mouseReleased(widget.getX() + 6, widget.getY() + 5, 0);
        require(!option.get().equals(old), "Native personal option did not change its draft value");
    }

    private void focusPartialPolicyTooltip(Minecraft mc) throws Exception {
        mc.screen.clearFocus();
        var list = (OptionsList) read(mc.screen, "list");
        tooltipFocus = list.findOption(binding(mc.screen, "partialPolicy").option());
        require(tooltipFocus != null && tooltipFocus.getTooltip() != null, "Missing native multi-choice tooltip for screenshot");
        mc.setLastInputType(InputType.KEYBOARD_TAB);
        tooltipFocus.setFocused(true);
    }

    private static CraftableClientConfig.Preferences personalDraft(Screen screen) throws Exception {
        return new CraftableClientConfig.Preferences((boolean) binding(screen, "recipeBookEnhancements").option().get(),
                (boolean) binding(screen, "detailedFailureFeedback").option().get(), (boolean) binding(screen, "unlockedOnly").option().get(),
                (org.berusted.craftable.planner.CraftRequest.PartialPolicy) binding(screen, "partialPolicy").option().get(),
                (boolean) binding(screen, "allowSurplusDrops").option().get(), (int) binding(screen, "doublePressMillis").option().get());
    }

    @SuppressWarnings("unchecked")
    private static List<NativeConfigOption<?>> configOptions(Screen screen) throws Exception {
        return (List<NativeConfigOption<?>>) read(screen, "configOptions");
    }

    private static NativeConfigOption<?> binding(Screen screen, String pathTail) throws Exception {
        return configOptions(screen).stream().filter(binding -> binding.configValue().getPath().getLast().equals(pathTail))
                .findFirst().orElseThrow(() -> new AssertionError("Missing ordinary config control: " + pathTail));
    }

    private static void verifyConfigPresentation(Minecraft mc, int count) throws Exception {
        var bindings = configOptions(mc.screen);
        var list = (OptionsList) read(mc.screen, "list");
        var draft = (org.berusted.craftable.config.ConfigSettings.Draft) read(mc.screen, "draft");
        var beforeDraft = draft.snapshot();
        var spec = count == 6 ? CraftableClientConfig.SPEC : CraftableServerConfig.SPEC;
        require(bindings.size() == count && draft != null, "Flattened native settings controls are incomplete");
        verifyEditableFooter(mc);
        if (count == 8) {
            require(read(mc.screen, "page").toString().equals("WORLD") && read(mc.screen, "retry") == null
                    && ((ContainerEventHandler) list).children().size() == 5 && rowWidgets(list).size() == 9,
                    "Editable native world rules retained a redundant status label or reload control");
            require(button(mc.screen, CommonComponents.GUI_DONE.getString()).getWidth() == 100
                    && button(mc.screen, CommonComponents.GUI_CANCEL.getString()).getWidth() == 100
                    && button(mc.screen, translated("defaults")).getWidth() == 100,
                    "Removing editor status/reload changed its save/cancel/default footer");
        }
        for (var binding : bindings) {
            var value = binding.configValue();
            var option = binding.option();
            var widget = list.findOption(option);
            require(spec.getValues().get(value.getPath()) == value,
                    "Native settings introduced a duplicate config definition: " + value.getPath());
            require(!value.getPath().getLast().equals("previewCacheTicks"), "Ordinary settings exposed diagnostic TTL");
            require(widget != null && widget.active && widget.getWidth() == 150 && widget.getTooltip() != null,
                    "Config control does not use a vanilla half-width widget: " + value.getPath());
            require(widget.getX() == mc.screen.width / 2 - 155 || widget.getX() == mc.screen.width / 2 + 5,
                    "Config control is not aligned to the native two-column grid: " + value.getPath());
            var candidates = new ArrayList<Object>();
            candidates.add(option.get());
            if (value.getDefault() instanceof Boolean) { candidates.add(false); candidates.add(true); }
            else if (value.getDefault() instanceof Enum<?> enumeration)
                for (var candidate : enumeration.getDeclaringClass().getEnumConstants())
                    if (value.getSpec().test(candidate)) candidates.add(candidate);
            else if (value.getDefault() instanceof Integer) {
                candidates.add(value.getSpec().<Integer>getRange().getMin());
                candidates.add(value.getSpec().<Integer>getRange().getMax());
            }
            if (value.getDefault() instanceof Enum<?>) {
                String key = value.getSpec().getTranslationKey() + ".tooltip";
                String description = Component.translatable(key).getString();
                var paragraphs = description.lines().toList();
                require(paragraphs.size() >= candidates.stream().distinct().count(),
                        "Multi-option config description still runs its choices together: " + key);
                String rendered = tooltipText(widget, mc);
                require(paragraphs.stream().allMatch(line -> rendered.contains(tooltipText(Tooltip.create(Component.literal(line)), mc))),
                        "Native tooltip lost explicit choice paragraphs: " + key);
                verifyNoIsolatedClosingPunctuation(widget, mc, key);
            }
            Object old = option.get();
            for (var candidate : candidates) {
                setOption(option, candidate);
                var candidateWidget = option.createButton(mc.options, 0, 0, 150);
                String caption = Component.translatable(value.getSpec().getTranslationKey()).getString();
                String message = candidateWidget.getMessage().getString();
                require(message.contains(caption) && message.indexOf(caption) == message.lastIndexOf(caption),
                        "Native option omitted or duplicated its canonical caption: " + message);
                require(mc.font.width(candidateWidget.getMessage()) <= 138,
                        "Half-width candidate caption is clipped: " + value.getPath() + "=" + candidate + " (" + message + ")");
            }
            setOption(option, old);
        }
        require(draft.snapshot().equals(beforeDraft), "Presentation candidate sweep changed the user's draft");
        var heading = (AbstractWidget) read(mc.screen, "heading");
        require(mc.font.width(mc.screen.getTitle()) <= heading.getWidth(), "Language reload clipped the settings title");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setOption(OptionInstance<?> option, Object candidate) { ((OptionInstance) option).set(candidate); }

    private static ModConfigSpec.ConfigValue<?> directList(String tail) {
        return CraftableServerConfig.directPreparationValues().stream()
                .filter(value -> value.getPath().getLast().equals(tail)).findFirst().orElseThrow();
    }

    private static void verifyDirectPreparationEditor(Minecraft mc) throws Exception {
        require(mc.screen instanceof DirectPreparationSettingsScreen, "Native recipe-list entry did not use its bounded editor");
        var parent = ((DirectPreparationSettingsScreen) mc.screen).settingsParent();
        var draft = (ConfigSettings.Draft) read(mc.screen, "draft");
        require(draft == read(parent, "draft"), "Recipe editor introduced a second world draft or config store");
        require(read(mc.screen, "allowedValue") == directList("directPreparationAllowedRecipes")
                && read(mc.screen, "confirmationValue") == directList("directPreparationConfirmationRecipes"),
                "Recipe editor duplicated registered setting identities");
        verifyEditableFooter(mc);
        var search = (net.minecraft.client.gui.components.EditBox) read(mc.screen, "search");
        search.setValue("minecraft:stick");
        require(((List<?>) read(mc.screen, "choices")).size() <= 256, "Recipe editor rendered an unbounded widget list");
        search.setValue("");
    }

    private static void addUnknownRecipe(Minecraft mc) throws Exception {
        var input = (net.minecraft.client.gui.components.EditBox) read(mc.screen, "recipeId");
        input.setValue("unknown_without_namespace");
        require(!((Button) read(mc.screen, "add")).active, "Recipe list accepted an implicit namespace");
        input.setValue("craftable:unknown_settings_probe");
        click(mc.screen, (Button) read(mc.screen, "add"));
        require(((List<?>) read(mc.screen, "allowed")).contains("craftable:unknown_settings_probe"),
                "Bounded recipe editor lost a legal unknown ID");
        require(((net.minecraft.client.gui.components.StringWidget) read(mc.screen, "status")).getMessage().getString()
                .equals(Component.translatable("screen.craftable.settings.direct.added_unsupported").getString()),
                "Unknown ID was presented as an enabled supported recipe");
    }

    private static void verifyUnreadyRecipeCatalogPresentation(Minecraft mc) throws Exception {
        var screen = mc.screen;
        var catalog = (org.berusted.craftable.recipe.PlanningInput.Catalog) read(screen, "catalog");
        var originalState = catalog.state();
        var originalAllowed = List.copyOf((List<?>) read(screen, "allowed"));
        var originalConfirmation = List.copyOf((List<?>) read(screen, "confirmation"));
        var updateState = screen.getClass().getDeclaredMethod("updateCatalogState");
        var clearRecipes = screen.getClass().getDeclaredMethod("clearUnavailableRecipes", boolean.class);
        var rebuild = screen.getClass().getDeclaredMethod("rebuildRows");
        var refresh = screen.getClass().getDeclaredMethod("refreshRecipes");
        updateState.setAccessible(true); clearRecipes.setAccessible(true); rebuild.setAccessible(true); refresh.setAccessible(true);
        try {
            // Exercise the real presentation transitions without replacing
            // recipes, allocating a huge catalog, or claiming this is a
            // natural normalization failure. Restore the ready catalog below.
            for (var state : List.of(org.berusted.craftable.recipe.PlanningInput.Catalog.State.BUILDING,
                    org.berusted.craftable.recipe.PlanningInput.Catalog.State.LIMITED,
                    org.berusted.craftable.recipe.PlanningInput.Catalog.State.EMPTY)) {
                writeField(catalog, "state", state);
                boolean changed = (boolean) updateState.invoke(screen);
                require(changed && (boolean) clearRecipes.invoke(screen, changed),
                        "Unready catalog state transition failed to request a presentation refresh: " + state);
                rebuild.invoke(screen);
                require(((Map<?, ?>) read(screen, "recipes")).isEmpty() && ((List<?>) read(screen, "observedRecipes")).isEmpty(),
                        "Unready recipe catalog retained a previous generation's recipe rows");
                require(read(screen, "allowed").equals(originalAllowed) && read(screen, "confirmation").equals(originalConfirmation),
                        "Unready recipe catalog discarded configured ID overrides");
                String statusKey = state == org.berusted.craftable.recipe.PlanningInput.Catalog.State.BUILDING
                        ? "catalog_loading" : "catalog_unavailable";
                String expected = Component.translatable("screen.craftable.settings.direct." + statusKey).getString();
                require(((StringWidget) read(screen, "status")).getMessage().getString().equals(expected),
                        "Unready catalog was incorrectly presented as a zero recipe count: " + state);
                require(mc.font.width(((StringWidget) read(screen, "status")).getMessage()) <= 310,
                        "Unready catalog status exceeds its actual native row width: " + expected);
                var rows = rowWidgets((OptionsList) read(screen, "list"));
                require(rows.stream().filter(StringWidget.class::isInstance).noneMatch(widget -> widget.getMessage().getString()
                        .equals(Component.translatable("screen.craftable.settings.direct.unsupported").getString())),
                        "Unready catalog asserted that a configured recipe is unsupported");
                var choices = (List<?>) read(screen, "choices");
                require(!choices.isEmpty() && ((Button) read(screen, "add")).active,
                        "Unready catalog incorrectly disabled editing legal ID overrides in an owned world");
                for (var row : choices) {
                    String tooltip = tooltipText((Button) row, mc);
                    var expectedBaseline = Component.translatable("screen.craftable.settings.direct.baseline",
                            Component.translatable("screen.craftable.settings.direct." + statusKey));
                    require(tooltip.contains(tooltipText(Tooltip.create(expectedBaseline), mc)),
                            "Unready configured recipe tooltip invented a known default judgement: " + state);
                }
            }
        } finally {
            writeField(catalog, "state", originalState);
            refresh.invoke(screen); rebuild.invoke(screen);
        }
        require(directCatalogReady(mc) && read(screen, "allowed").equals(originalAllowed)
                && read(screen, "confirmation").equals(originalConfirmation),
                "Presentation-state regression failed to restore the real ready catalog or its overrides");
        Craftable.LOGGER.warn("PRE_M5_SETTINGS_CATALOG_STATE PASS: injected presentation states BUILDING/LIMITED/EMPTY refresh status, clear stale recipe rows but preserve/edit ID overrides, no unsupported/default qualification claim or zero-recipe count, actual 310px status width checked, original READY catalog restored; not a natural normalization failure");
    }

    private static void verifyRecipeCatalogStatusWidth(Minecraft mc) {
        for (String key : List.of("catalog_loading", "catalog_unavailable")) {
            var message = Component.translatable("screen.craftable.settings.direct." + key);
            require(mc.font.width(message) <= 310, "Native recipe catalog status is clipped in "
                    + mc.getLanguageManager().getSelected() + ": " + message.getString());
        }
    }

    private static void appendProfessionalUnknownId(Minecraft mc) throws Exception {
        click(mc.screen, (Button) read(mc.screen, "add"));
        require(!button(mc.screen, CommonComponents.GUI_DONE.getString()).active,
                "Professional list Done remained enabled for an invalid incomplete recipe ID");
        var boxes = (List<?>) read(mc.screen, "boxes");
        ((net.minecraft.client.gui.components.EditBox) boxes.getLast()).setValue("craftable:professional_settings_probe");
        require(button(mc.screen, CommonComponents.GUI_DONE.getString()).active,
                "Professional list rejected a valid explicit unknown recipe ID");
    }

    private static void verifyProfessionalPolicyContextExpiry(Minecraft mc) throws Exception {
        var screen = mc.screen;
        var originalConnection = read(screen, "connection");
        var current = List.copyOf((List<?>) directList("directPreparationAllowedRecipes").getRaw());
        try {
            // Model an obsolete screen identity without publishing the fixture
            // world or claiming this proves real LAN/server permissions.
            writeField(screen, "connection", null);
            screen.tick();
            var undo = button(screen, ConfigurationScreen.UNDO.getString());
            var reset = button(screen, ConfigurationScreen.RESET.getString());
            require(!undo.active && !reset.active, "Expired professional context retained active Undo/Reset before a render");
            undo.onPress(); reset.onPress();
            require(directList("directPreparationAllowedRecipes").getRaw().equals(current),
                    "Professional footer callback wrote a recipe list after context expiry");
            var manager = read(screen, "undoManager");
            manager.getClass().getMethod("undo").invoke(manager);
            require(directList("directPreparationAllowedRecipes").getRaw().equals(current),
                    "Recipe undo mutation callback lacked an independent context guard");
            manager.getClass().getMethod("redo").invoke(manager);
            require(directList("directPreparationAllowedRecipes").getRaw().equals(current),
                    "Recipe redo mutation callback lacked an independent context guard");
        } finally { writeField(screen, "connection", originalConnection); }
        screen.tick();
        require(button(screen, ConfigurationScreen.UNDO.getString()).active,
                "Restoring the same professional context failed to derive its real available undo state");
    }

    private static void writeField(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static String tooltipText(AbstractWidget widget, Minecraft mc) {
        require(widget.getTooltip() != null, "Expected a readable native tooltip");
        return tooltipText(widget.getTooltip(), mc);
    }

    private static String tooltipText(Tooltip tooltip, Minecraft mc) {
        // Use the same real-font split for both actual and expected text.
        // Font.split drops a separating space at wrapped English lines;
        // concatenating those lines cannot be compared to the raw sentence.
        return String.join("", tooltipLines(tooltip, mc));
    }

    private static List<String> tooltipLines(Tooltip tooltip, Minecraft mc) {
        return tooltip.toCharSequence(mc).stream().map(line -> {
            var result = new StringBuilder();
            line.accept((index, style, codePoint) -> { result.appendCodePoint(codePoint); return true; });
            return result.toString();
        }).toList();
    }

    private static void verifyNoIsolatedClosingPunctuation(AbstractWidget widget, Minecraft mc, String key) {
        if (!mc.getLanguageManager().getSelected().equals("zh_cn")) return;
        var lines = tooltipLines(widget.getTooltip(), mc);
        String closing = "。，；：！？、）】〕〉》」』．,.;:!?)]}";
        var isolated = lines.stream().map(String::strip).filter(line -> !line.isEmpty()
                && line.codePoints().allMatch(codePoint -> closing.indexOf(codePoint) >= 0)).toList();
        require(isolated.isEmpty(), "Actual 170px Chinese tooltip has isolated closing punctuation: " + key
                + "; isolated=" + isolated + "; visualLines=" + lines);
    }

    private static void verifyEditableFooter(Minecraft mc) throws Exception {
        var list = (OptionsList) read(mc.screen, "list");
        var cancel = button(mc.screen, CommonComponents.GUI_CANCEL.getString());
        var defaults = button(mc.screen, translated("defaults"));
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        require(cancel.getWidth() == 100 && defaults.getWidth() == 100 && done.getWidth() == 100
                && cancel.getY() == defaults.getY() && defaults.getY() == done.getY()
                && cancel.getX() + cancel.getWidth() + 5 == defaults.getX()
                && defaults.getX() + defaults.getWidth() + 5 == done.getX()
                && done.getY() >= list.getBottom()
                && !rowWidgets(list).contains(cancel) && !rowWidgets(list).contains(defaults) && !rowWidgets(list).contains(done),
                "Editable native footer must keep Cancel / Defaults / Done left-to-right with Done on the right");
        require(mc.screen.children().indexOf(cancel) >= 0
                && mc.screen.children().indexOf(cancel) < mc.screen.children().indexOf(defaults)
                && mc.screen.children().indexOf(defaults) < mc.screen.children().indexOf(done),
                "Editable footer listener registration does not follow Cancel / Defaults / Done");
    }

    private static void verifyProfessionalFooter(Minecraft mc) {
        // NeoForge's first button is Undo, not ordinary Cancel. Its native
        // undo/save lifecycle must remain unchanged when aligning Done.
        var undo = button(mc.screen, ConfigurationScreen.UNDO.getString());
        var reset = button(mc.screen, ConfigurationScreen.RESET.getString());
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        // ConfigurationSectionScreen creates all three using the actual
        // vanilla Button.SMALL_WIDTH (120 in 1.21.1), not our native editor's
        // deliberately narrower 100px controls, and arranges them with 8px.
        require(undo.getWidth() == Button.SMALL_WIDTH && reset.getWidth() == Button.SMALL_WIDTH
                && done.getWidth() == Button.SMALL_WIDTH
                && undo.getY() == reset.getY() && reset.getY() == done.getY()
                && undo.getX() + undo.getWidth() + 8 == reset.getX()
                && reset.getX() + reset.getWidth() + 8 == done.getX(),
                "Professional native footer must keep Undo / Reset / Done left-to-right with Done on the right"
                    + "; nativeSmallWidth=" + Button.SMALL_WIDTH + "; undo=" + bounds(undo)
                    + "; reset=" + bounds(reset) + "; done=" + bounds(done));
        require(mc.screen.children().indexOf(undo) >= 0
                && mc.screen.children().indexOf(undo) < mc.screen.children().indexOf(reset)
                && mc.screen.children().indexOf(reset) < mc.screen.children().indexOf(done),
                "Professional footer listener registration does not follow Undo / Reset / Done"
                    + "; indices=" + List.of(mc.screen.children().indexOf(undo), mc.screen.children().indexOf(reset),
                        mc.screen.children().indexOf(done))
                    + "; undo=" + bounds(undo) + "; reset=" + bounds(reset) + "; done=" + bounds(done));
    }

    private static Bounds bounds(AbstractWidget widget) {
        return new Bounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
    }

    private static void setSliderEndpoint(Minecraft mc, String name, boolean maximum) throws Exception {
        var binding = binding(mc.screen, name);
        var list = (OptionsList) read(mc.screen, "list");
        var widget = list.findOption(binding.option());
        require(widget != null && widget.active, "Native slider is unavailable: " + name);
        require(widget.getY() >= list.getY() && widget.getY() + widget.getHeight() <= list.getBottom(),
                "Native slider is not fully visible after rendering: " + name);
        int x = widget.getX() + (maximum ? widget.getWidth() - 4 : 4);
        mc.setLastInputType(InputType.MOUSE);
        require(mc.screen.mouseClicked(x, widget.getY() + 5, 0), "Native slider ignored an actual click: " + name);
        mc.screen.mouseReleased(x, widget.getY() + 5, 0);
        list.applyUnsavedChanges();
        var range = binding.configValue().getSpec().<Integer>getRange();
        require(binding.option().get().equals(maximum ? range.getMax() : range.getMin()),
                "Native slider did not retain its registered endpoint: " + name + "=" + binding.option().get());
    }

    private void reopenMain(Minecraft mc) {
        require(mc.screen instanceof AccessibilityOptionsScreen, "Personal settings did not return to accessibility");
        // Returning after changing GUI scale repositions the native list's
        // hitboxes immediately, but Entry.render assigns child coordinates.
        // Wait for actual render events before using those child coordinates.
        deferUntilRendered(mc.screen, () -> {
            try {
                verifyAccessibility(mc, true);
                click(mc.screen, modName());
                require(mc.screen instanceof CraftableSettingsScreen, "Accessibility entry did not reopen the flattened main page");
            } catch (Exception error) { throw new AssertionError("Deferred accessibility navigation failed", error); }
        });
    }

    private void openWorldAfterRender(Minecraft mc) {
        deferUntilRendered(mc.screen, () -> {
            try { click(mc.screen, (Button) read(mc.screen, "worldEntry")); }
            catch (Exception error) { throw new AssertionError("Deferred ordinary world navigation failed", error); }
        });
    }

    private void deferUntilRendered(Screen screen, Runnable action) {
        require(afterRender == null, "A native interaction is already waiting for layout");
        deferredScreen = screen; afterRender = action; deferredRenderedFrames = 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void openReadOnlyWorld(Minecraft mc) throws Exception {
        // Exercise the same read-only presentation used for remote/LAN clients
        // without pretending the integrated fixture proves those permissions.
        var rules = read(home, "rules");
        var pageType = read(home, "page").getClass();
        var page = Enum.valueOf((Class<? extends Enum>) pageType, "WORLD");
        var constructor = java.util.Arrays.stream(CraftableSettingsScreen.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 4 && c.getParameterTypes()[1] == pageType).findFirst().orElseThrow();
        constructor.setAccessible(true);
        mc.setScreen((Screen) constructor.newInstance(home, page, rules, null));
    }

    private static void verifySavedFile(CraftableClientConfig.Preferences expected) {
        var config = registered(ModConfig.Type.CLIENT, CraftableClientConfig.SPEC);
        try (var file = CommentedFileConfig.of(config.getFullPath())) {
            file.load();
            require((boolean) file.get("presentation.recipeBookEnhancements") == expected.recipeBookEnhancements()
                    && (boolean) file.get("presentation.detailedFailureFeedback") == expected.detailedFailureFeedback()
                    && (boolean) file.get("presentation.unlockedOnly") == expected.unlockedOnly()
                    && file.get("crafting.partialPolicy").equals(expected.partialPolicy().name())
                    && (boolean) file.get("crafting.allowSurplusDrops") == expected.allowSurplusDrops()
                    && ((Number) file.get("crafting.doublePressMillis")).intValue() == expected.doublePressMillis(),
                    "Native settings did not persist to the registered TOML");
        }
    }

    private static void verifyServerSavedFile(int steps) {
        var config = registered(ModConfig.Type.SERVER, CraftableServerConfig.SPEC);
        try (var file = CommentedFileConfig.of(config.getFullPath())) {
            file.load();
            require(((Number) file.get("crafting.maxPlanSteps")).intValue() == steps,
                    "Native world Done did not persist to the current world's registered TOML");
            require(file.get("crafting.directPreparationAllowedRecipes") instanceof List<?> ids
                    && ids.contains("craftable:native_persist_probe"),
                    "Native world Done did not persist the recipe override to the registered TOML");
        }
    }

    private static ModConfig registered(ModConfig.Type type, ModConfigSpec spec) {
        return ModConfigs.getConfigSet(type).stream().filter(c -> c.getSpec() == spec).findFirst().orElseThrow();
    }

    private void configure(Minecraft mc, int steps, int radius) {
        configured = false;
        mc.getSingleplayerServer().execute(() -> {
            try { serverSteps().set(steps); serverRadius().set(radius); configured = true; }
            catch (Throwable error) { serverFailure = error; }
        });
    }

    @SuppressWarnings("unchecked") private static ModConfigSpec.ConfigValue<Integer> serverSteps() {
        return (ModConfigSpec.ConfigValue<Integer>) CraftableServerConfig.SPEC.getValues().get(List.of("crafting", "maxPlanSteps"));
    }
    @SuppressWarnings("unchecked") private static ModConfigSpec.ConfigValue<Integer> serverRadius() {
        return (ModConfigSpec.ConfigValue<Integer>) CraftableServerConfig.SPEC.getValues().get(List.of("environment", "horizontalRadius"));
    }
    @SuppressWarnings("unchecked") private static ModConfigSpec.ConfigValue<Integer> serverValue(String section, String name) {
        return (ModConfigSpec.ConfigValue<Integer>) CraftableServerConfig.SPEC.getValues().get(List.of(section, name));
    }

    private void restore(Minecraft mc) {
        if (!initialized || restored) return;
        restored = true;
        CraftableClientConfig.savePreferences(before); serverSteps().set(oldSteps); serverRadius().set(oldRadius);
        oldPolicyValues.forEach(PreM5SettingsScenario::restoreValue);
        CraftableServerConfig.SPEC.save();
        NeoForge.EVENT_BUS.unregister(initListener); NeoForge.EVENT_BUS.unregister(captureListener);
        mc.options.guiScale().set(oldScale); mc.getWindow().setWindowed(oldWidth, oldHeight); mc.resizeDisplay();
        mc.getLanguageManager().setSelected(oldLanguage); mc.options.languageCode = oldLanguage;
        if (!oldLanguage.equals("en_us")) mc.reloadResourcePacks();
        mc.options.save();
        mc.setLastInputType(oldInputType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restoreValue(ModConfigSpec.ConfigValue<?> value, Object original) { ((ModConfigSpec.ConfigValue) value).set(original); }

    private static void setLanguage(Minecraft mc, String language) {
        mc.getLanguageManager().setSelected(language); mc.options.languageCode = language; mc.reloadResourcePacks();
    }

    private void capture(Minecraft mc, String name) { captured = false; captureScreen = mc.screen; captureName = name; }
    private void captureFrame(ScreenEvent.Render.Post event) {
        if (afterRender != null && event.getScreen() == deferredScreen) deferredRenderedFrames++;
        if (captureName == null || event.getScreen() != captureScreen) return;
        var name = captureName; captureName = null;
        event.getGuiGraphics().flush();
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message -> {
            if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                    && contents.getKey().equals("screenshot.failure")) captureFailure = new AssertionError(message.getString());
            else captured = true;
        });
    }

    private static List<AbstractWidget> widgets(GuiEventListener listener) {
        var result = new java.util.ArrayList<AbstractWidget>();
        if (listener instanceof AbstractWidget widget) result.add(widget);
        if (listener instanceof ContainerEventHandler container) container.children().forEach(child -> result.addAll(widgets(child)));
        return result;
    }
    private static List<AbstractWidget> rowWidgets(OptionsList list) {
        // OptionsList is itself an AbstractWidget. Content counts must start
        // at the actual native entries, not include the enclosing list.
        return ((ContainerEventHandler) list).children().stream().flatMap(row -> widgets(row).stream()).toList();
    }
    private static Button button(Screen screen, String label) {
        return widgets(screen).stream().filter(widget -> widget instanceof Button && widget.getMessage().getString().equals(label))
                .map(widget -> (Button) widget).findFirst().orElseThrow(() -> new AssertionError("Missing native button: " + label
                        + "; available=" + widgets(screen).stream().filter(w -> w instanceof Button).map(w -> w.getMessage().getString()).toList()));
    }
    private static void click(Screen screen, String label) {
        click(screen, button(screen, label));
    }
    private static void clickSection(Screen screen, String translationKey) {
        String section = Component.translatable("neoforge.configuration.uitext.section", Component.translatable(translationKey)).getString();
        var label = widgets(screen).stream().filter(widget -> !(widget instanceof Button)
                && widget.getMessage().getString().equals(section)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing native section label: " + section));
        var button = widgets(screen).stream().filter(widget -> widget instanceof Button && widget.getY() == label.getY())
                .map(widget -> (Button) widget).findFirst()
                .orElseThrow(() -> new AssertionError("Missing native editor on section row: " + section));
        click(screen, button);
    }
    private static void clickLabeledValue(Screen screen, String translationKey) {
        String caption = Component.translatable(translationKey).getString();
        var label = widgets(screen).stream().filter(widget -> !(widget instanceof Button)
                && widget.getMessage().getString().equals(caption)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing professional option label: " + caption));
        var control = widgets(screen).stream().filter(widget -> widget instanceof AbstractWidget && widget != label
                && widget.getY() == label.getY() && widget.getX() > label.getX()).findFirst()
                .orElseThrow(() -> new AssertionError("Missing professional control on row: " + caption));
        require(control.active, "Professional control is disabled: " + caption);
        Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        require(screen.mouseClicked(control.getX() + 5, control.getY() + 5, 0), "Professional option ignored an actual click: " + caption);
        screen.mouseReleased(control.getX() + 5, control.getY() + 5, 0);
    }
    private static void click(Screen screen, Button button) {
        String label = button.getMessage().getString();
        require(button.active, "Native settings button is disabled: " + label);
        Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        require(screen.mouseClicked(button.getX() + 5, button.getY() + 5, 0), "Native settings click was ignored: " + label);
        screen.mouseReleased(button.getX() + 5, button.getY() + 5, 0);
    }
    private static String modName() { return Component.translatable("mod.craftable.name").getString(); }
    private static String translated(String key) { return Component.translatable("screen.craftable.settings." + key).getString(); }
    private static Object read(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private void next() { phase++; age = 0; languageReadyTicks = 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Bounds(int x, int y, int width, int height) {}
}
