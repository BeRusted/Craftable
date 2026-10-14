package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.berusted.craftable.config.ConfigSettings;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.DirectPreparationPolicy;
import org.berusted.craftable.recipe.CraftingRecipes;
import org.berusted.craftable.recipe.PlanningInput;

/** Bounded presentation of two registered recipe override lists. Done updates
 * only the parent's world draft; it never saves a spec or sends a rule edit. */
final class DirectPreparationSettingsScreen extends OptionsSubScreen {
    private static final int MAX_DISPLAYED_RECIPES = 256;
    private enum Choice { INHERIT, ALLOW, CONFIRM }
    private final CraftableSettingsScreen parent;
    private final ConfigSettings.Draft draft;
    private final BooleanSupplier canEdit;
    private final Supplier<DirectPreparationPolicy> remotePolicy;
    private final ModConfigSpec.ConfigValue<List<String>> allowedValue, confirmationValue;
    private final List<String> allowed = new ArrayList<>(), confirmation = new ArrayList<>();
    private final List<Button> choices = new ArrayList<>();
    private final Map<ResourceLocation, CraftingRecipes.Entry> recipes = new LinkedHashMap<>();
    private final PlanningInput.Catalog catalog = new PlanningInput.Catalog();
    private PlanningInput.Catalog.State observedCatalogState = PlanningInput.Catalog.State.EMPTY;
    private List<CraftingRecipes.Entry> observedRecipes = List.of();
    private DirectPreparationPolicy observedPolicy;
    private EditBox search, recipeId;
    private Button add, done, defaults;
    private StringWidget status;
    private String searchText = "", inputText = "";
    private int matchingRecipes;

    @SuppressWarnings("unchecked")
    DirectPreparationSettingsScreen(CraftableSettingsScreen parent, ConfigSettings.Draft draft,
            BooleanSupplier canEdit, Supplier<DirectPreparationPolicy> remotePolicy) {
        super(parent, Minecraft.getInstance().options, text("title"));
        this.parent = parent; this.draft = draft; this.canEdit = canEdit; this.remotePolicy = remotePolicy;
        var values = CraftableServerConfig.directPreparationValues();
        allowedValue = (ModConfigSpec.ConfigValue<List<String>>) values.stream()
                .filter(v -> v.getPath().getLast().equals("directPreparationAllowedRecipes")).findFirst().orElseThrow();
        confirmationValue = (ModConfigSpec.ConfigValue<List<String>>) values.stream()
                .filter(v -> v.getPath().getLast().equals("directPreparationConfirmationRecipes")).findFirst().orElseThrow();
        if (draft != null) {
            var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                    draft.get(allowedValue), draft.get(confirmationValue));
            allowed.addAll(policy.allowedRecipes()); confirmation.addAll(policy.confirmationRecipes());
        }
        else readRemote();
    }

    CraftableSettingsScreen settingsParent() { return parent; }

    @Override protected void addTitle() {
        layout.setHeaderHeight(58);
        var header = layout.addToHeader(LinearLayout.vertical().spacing(5));
        header.addChild(new StringWidget(310, 9, title, font));
        search = header.addChild(new EditBox(font, 310, 20, text("search")));
        search.setMaxLength(128); search.setHint(text("search")); search.setValue(searchText);
        search.setResponder(value -> { searchText = value; rebuildRows(); });
    }

    @Override protected void addOptions() {
        recipeId = new EditBox(font, 150, 20, text("recipe_id"));
        recipeId.setMaxLength(128); recipeId.setHint(Component.literal("namespace:recipe")); recipeId.setValue(inputText);
        recipeId.setTooltip(Tooltip.create(text("recipe_id.tooltip")));
        recipeId.setResponder(value -> { inputText = value; updatePermissions(); });
        add = Button.builder(text("add"), button -> addRecipe()).width(150)
                .tooltip(Tooltip.create(text("add.tooltip"))).build();
        status = new StringWidget(310, 20, Component.empty(), font).alignLeft();
        refreshRecipes(); rebuildRows();
    }

    @Override protected void addFooter() {
        var footer = layout.addToFooter(LinearLayout.horizontal().spacing(5));
        if (draft != null) {
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(100).build());
            defaults = footer.addChild(Button.builder(Component.translatable("screen.craftable.settings.defaults"), button -> {
                if (!editable()) return;
                allowed.clear(); confirmation.clear(); rebuildRows();
            }).width(100).tooltip(Tooltip.create(text("defaults.tooltip"))).build());
            done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> accept()).width(100).build());
        } else done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
        updatePermissions();
    }

    private boolean editable() { return draft != null && canEdit.getAsBoolean(); }

    @Override public void tick() {
        parent.tick();
        boolean changed = refreshRecipes();
        if (draft == null) changed |= readRemote();
        if (changed) rebuildRows();
        updatePermissions();
    }

    private boolean refreshRecipes() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            catalog.clear();
            return clearUnavailableRecipes(updateCatalogState());
        }
        var synchronizedRecipes = mc.level.getRecipeManager().getRecipes();
        catalog.observe(mc.getConnection(), synchronizedRecipes, synchronizedRecipes, mc.level.registryAccess());
        catalog.advance(1_000_000L);
        boolean changed = updateCatalogState();
        if (catalog.state() != PlanningInput.Catalog.State.READY) return clearUnavailableRecipes(changed);
        var current = catalog.bind(false, false, java.util.Set.of()).entries();
        if (observedRecipes == current || observedRecipes.equals(current)) return changed;
        observedRecipes = current; recipes.clear();
        for (var entry : current) recipes.put(entry.id(), entry);
        return true;
    }

    private boolean updateCatalogState() {
        var current = catalog.state();
        boolean changed = current != observedCatalogState;
        observedCatalogState = current;
        return changed;
    }

    private boolean clearUnavailableRecipes(boolean changed) {
        changed |= !recipes.isEmpty() || !observedRecipes.isEmpty();
        recipes.clear(); observedRecipes = List.of();
        // Configured IDs are not recipe absence proofs. They remain available
        // for inspection/editing while static knowledge is loading or limited.
        return changed;
    }

    private boolean readRemote() {
        var policy = remotePolicy.get();
        if (java.util.Objects.equals(observedPolicy, policy)) return false;
        observedPolicy = policy; allowed.clear(); confirmation.clear();
        if (policy != null) { allowed.addAll(policy.allowedRecipes()); confirmation.addAll(policy.confirmationRecipes()); }
        return true;
    }

    private void rebuildRows() {
        if (list == null || recipeId == null) return;
        list.setFocused(null); list.setSelected(null); list.children().clear(); choices.clear();
        if (draft != null) list.addSmall(recipeId, add);
        list.addSmall(status, null);
        var ids = new java.util.LinkedHashSet<ResourceLocation>(recipes.keySet());
        for (var id : allowed) ids.add(ResourceLocation.parse(id));
        for (var id : confirmation) ids.add(ResourceLocation.parse(id));
        String query = searchText.toLowerCase(Locale.ROOT).strip();
        var sorted = ids.stream().filter(id -> matches(id, query))
                .sorted(Comparator.comparing(this::itemSortKey).thenComparing(ResourceLocation::toString)).toList();
        matchingRecipes = sorted.size();
        for (var id : sorted.subList(0, Math.min(MAX_DISPLAYED_RECIPES, sorted.size()))) {
            var entry = recipes.get(id);
            boolean ready = catalog.state() == PlanningInput.Catalog.State.READY;
            Component item = entry != null ? entry.output().getHoverName()
                    : ready ? text("unsupported") : Component.literal(id.toString());
            Component full = Component.empty().append(item).append("\n").append(id.toString()).append("\n")
                    .append(entry != null ? text("method", entry.gridSize(), entry.gridSize())
                            : ready ? text("unsupported.tooltip") : catalogStatus())
                    .append("\n").append(text("baseline", !ready ? catalogStatus()
                            : entry != null && entry.safePreparation() ? text("allow") : text("confirm")))
                    .append("\n").append(text("scope"));
            if (entry != null) full = full.copy().append("\n").append(text("result", entry.output().getCount()))
                    .append("\n").append(text("inputs", inputSummary(entry)));
            var label = new StringWidget(150, 20, clipped(item), font).alignLeft();
            label.setTooltip(Tooltip.create(full));
            var button = Button.builder(choiceText(id), control -> cycle(id, control)).width(150)
                    .tooltip(Tooltip.create(full.copy().append("\n").append(text("choices.tooltip")))).build();
            choices.add(button); list.addSmall(label, button);
        }
        updateStatus();
        status.setTooltip(Tooltip.create(text("help")));
        list.setScrollAmount(0); updatePermissions();
    }

    private Component catalogStatus() {
        return text(catalog.state() == PlanningInput.Catalog.State.BUILDING ? "catalog_loading" : "catalog_unavailable");
    }

    private void updateStatus() {
        status.setMessage(catalog.state() != PlanningInput.Catalog.State.READY ? catalogStatus()
                : matchingRecipes > MAX_DISPLAYED_RECIPES ? text("filtered_count", MAX_DISPLAYED_RECIPES, matchingRecipes)
                : text("count", matchingRecipes, allowed.size(), confirmation.size()));
    }

    private String itemSortKey(ResourceLocation id) {
        var entry = recipes.get(id);
        return entry == null ? "~" + id : BuiltInRegistries.ITEM.getKey(entry.output().getItem()).toString();
    }

    private String inputSummary(CraftingRecipes.Entry entry) {
        var grouped = new java.util.IdentityHashMap<net.minecraft.world.item.crafting.Ingredient, Integer>();
        var order = new ArrayList<net.minecraft.world.item.crafting.Ingredient>();
        for (var requirement : entry.requirements()) {
            var ingredient = requirement.ingredient();
            if (!grouped.containsKey(ingredient)) order.add(ingredient);
            grouped.merge(ingredient, 1, Integer::sum);
        }
        var descriptions = new ArrayList<String>();
        for (var ingredient : order) {
            var options = ingredient.getItems();
            var names = new ArrayList<String>(Math.min(4, options.length));
            for (int i = 0; i < Math.min(4, options.length); i++) names.add(options[i].getHoverName().getString());
            String display = String.join(" / ", names);
            if (options.length > 4) display += " / …";
            descriptions.add(grouped.get(ingredient) + " × (" + display + ")");
        }
        return String.join("; ", descriptions);
    }

    private boolean matches(ResourceLocation id, String query) {
        var entry = recipes.get(id);
        return query.isEmpty() || id.toString().contains(query) || entry != null
                && (BuiltInRegistries.ITEM.getKey(entry.output().getItem()).toString().contains(query)
                || entry.output().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query));
    }

    private Component clipped(Component value) {
        if (font.width(value) <= 146) return value;
        return Component.literal(font.plainSubstrByWidth(value.getString(), 136) + "…");
    }

    private Choice choice(ResourceLocation id) {
        if (confirmation.contains(id.toString())) return Choice.CONFIRM;
        return allowed.contains(id.toString()) ? Choice.ALLOW : Choice.INHERIT;
    }

    private Component choiceText(ResourceLocation id) { return text(choice(id).name().toLowerCase(Locale.ROOT)); }

    private void cycle(ResourceLocation id, Button control) {
        if (!editable()) return;
        Choice next = Choice.values()[(choice(id).ordinal() + 1) % Choice.values().length];
        var nextAllowed = new ArrayList<>(allowed); var nextConfirmation = new ArrayList<>(confirmation);
        nextAllowed.remove(id.toString()); nextConfirmation.remove(id.toString());
        if (next == Choice.ALLOW) nextAllowed.add(id.toString());
        else if (next == Choice.CONFIRM) nextConfirmation.add(id.toString());
        if (!validLists(nextAllowed, nextConfirmation)) return;
        allowed.clear(); allowed.addAll(nextAllowed); confirmation.clear(); confirmation.addAll(nextConfirmation);
        control.setMessage(choiceText(id));
        updateStatus();
    }

    private boolean validLists(List<String> nextAllowed, List<String> nextConfirmation) {
        if (allowedValue.getSpec().test(nextAllowed) && confirmationValue.getSpec().test(nextConfirmation)) return true;
        status.setMessage(text("limit")); return false;
    }

    private ResourceLocation enteredId() {
        return DirectPreparationPolicy.validRecipeId(inputText) ? ResourceLocation.parse(inputText.trim()) : null;
    }

    private void addRecipe() {
        if (!editable()) return;
        var id = enteredId();
        if (id == null) return;
        if (!allowed.contains(id.toString()) && !confirmation.contains(id.toString())) {
            var proposed = new ArrayList<>(allowed); proposed.add(id.toString());
            if (!validLists(proposed, confirmation)) return;
            allowed.add(id.toString());
        }
        rebuildRows();
        if (catalog.state() == PlanningInput.Catalog.State.READY && !recipes.containsKey(id))
            status.setMessage(text("added_unsupported"));
    }

    private void accept() {
        if (!editable()) { updatePermissions(); return; }
        if (!validLists(allowed, confirmation)) return;
        // Validate both before updating either registered identity in the draft.
        if (!editable()) { updatePermissions(); return; }
        draft.set(allowedValue, List.copyOf(allowed)); draft.set(confirmationValue, List.copyOf(confirmation));
        minecraft.setScreen(parent);
    }

    private void updatePermissions() {
        boolean allowedNow = editable();
        for (var button : choices) button.active = allowedNow;
        if (recipeId != null) recipeId.setEditable(allowedNow);
        if (add != null) add.active = allowedNow && enteredId() != null;
        if (defaults != null) defaults.active = allowedNow;
        if (done != null && draft != null) {
            done.active = allowedNow;
            done.setTooltip(allowedNow ? null : Tooltip.create(Component.translatable("screen.craftable.settings.world_changed")));
        }
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void removed() { catalog.clear(); super.removed(); }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.craftable.settings.direct." + key, args);
    }
}
