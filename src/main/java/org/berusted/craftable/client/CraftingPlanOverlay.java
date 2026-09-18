package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.client.recipebook.RecipeBookProjection;
import org.berusted.craftable.client.recipebook.ClientBrowsePlanner;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.execution.CraftingService;
import org.berusted.craftable.network.CraftingDetailPayloads;
import org.berusted.craftable.network.CreateRecipeResultPayload;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.PlanView;

/**
 * A Screen used as an embedded input/render surface, never Minecraft.setScreen.
 * The parent menu and its carried/grid stacks keep their original lifecycle.
 * There is one in-flight detail/MAX/candidate request; revisions discard stale
 * responses, and no timeout automatically resends a resource-changing request.
 */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class CraftingPlanOverlay extends Screen {
    private static CraftingPlanOverlay current;
    private enum Pane { GRAPH, COSTS, CHOICES }
    private enum Work { PREVIEW, MAXIMUM, CANDIDATE, FALLBACK, AUTHORIZE, CONFIRM }
    private final Screen parent;
    private final Object menu, level, recipeGeneration;
    private CraftRequest intent;
    private CraftingService.Draft draft;
    private CraftingService.Maximum maximum;
    private Pane pane = Pane.GRAPH;
    private Work pending;
    private long inFlight = -1, sentAt, changedAt;
    private int generation, sentGeneration, scroll, choicePage;
    private boolean dirty = true;
    private boolean localDraft;
    private Object reviewedIdentity;
    private long resourceScope = -1;
    private long unavailableSince;
    private boolean fallbackUsed;
    private List<String> selectedPaths = List.of();
    private String choicePath = "";
    private final Map<ResourceLocation, CraftingResultCode> candidateStates = new java.util.HashMap<>();
    private Button applyChoice, previousChoice, nextChoice, automaticChoice;
    private Boolean queuedAction;
    private ResourceLocation testingCandidate;
    private List<Object> singleRoute = List.of();
    private boolean routeChanged;
    private PlanGraphWidget graph;
    private CountSlider slider;
    private Button create, partial;
    private final List<Component> rows = new ArrayList<>();
    private final List<net.minecraft.util.FormattedCharSequence> wrappedRows = new ArrayList<>();
    private Component notice = Component.empty();

    private CraftingPlanOverlay(Screen parent, ResourceLocation recipe, boolean partial) {
        super(text("title"));
        var mc = Minecraft.getInstance();
        this.parent = parent;
        menu = mc.player.containerMenu;
        level = mc.level;
        recipeGeneration = mc.level.getRecipeManager().getRecipes();
        intent = new CraftRequest(recipe, 1, partial, CraftableClientConfig.allowSurplusDrops(),
                CraftableClientConfig.partialPolicy(), Map.of());
    }

    public static boolean active() { return current != null && current.valid(); }

    public static void open(Screen parent, ResourceLocation recipe, boolean partial) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || parent != mc.screen || !RecipeBookProjection.active()
                || RecipeBookProjection.component(parent) == null) return;
        current = new CraftingPlanOverlay(parent, recipe, partial);
        current.init(mc, parent.width, parent.height);
    }

    private boolean valid() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.level == level && mc.screen == parent && mc.player.containerMenu == menu
                && RecipeBookProjection.active() && mc.level.getRecipeManager().getRecipes() == recipeGeneration;
    }

    private static CraftingPlanOverlay live(Screen screen) {
        if (current != null && !current.valid()) current = null;
        return current != null && current.parent == screen ? current : null;
    }

    @Override protected void init() {
        clearWidgets();
        int available = Math.max(180, width - 28);
        int sliderWidth = available * 35 / 100;
        int third = (available - sliderWidth - 12) / 3;
        graph = addRenderableWidget(new PlanGraphWidget(14, 14, available, Math.max(30, height - 76), this::choose));
        if (draft != null) graph.show(draft.view());
        graph.visible = pane == Pane.GRAPH;
        slider = addRenderableWidget(new CountSlider(14, height - 34, sliderWidth));
        create = button(18 + sliderWidth, height - 34, third, text("create"), () -> act(false));
        partial = button(22 + sliderWidth + third, height - 34, third, text("partial"), () -> act(true));
        button(26 + sliderWidth + third * 2, height - 34, third, text("refresh"), () -> {
            fallbackUsed = false; change(intent, true);
        });
        // The chooser is one visual recipe card. Candidate replies update it
        // in place, never replace focused widgets or flash a list of buttons.
        int cx = width / 2, cy = Math.max(24, (height - 76) / 2 - 38);
        previousChoice = button(cx - 108, cy + 34, 20, Component.literal("<"), () -> cycleChoice(-1));
        nextChoice = button(cx + 88, cy + 34, 20, Component.literal(">"), () -> cycleChoice(1));
        applyChoice = button(cx - 84, cy + 82, 82, text("use_recipe"), () -> {
            var candidate = selectedCandidate();
            if (candidate != null) applyCandidate(candidate);
        });
        automaticChoice = button(cx + 2, cy + 82, 82, text("automatic"), () -> {
                var pins = new TreeMap<>(intent.selections());
                pins.keySet().removeIf(this::affected);
                choicePath = ""; pane = Pane.GRAPH;
                change(new CraftRequest(intent.recipe(), intent.batches(), intent.partial(), intent.allowDrops(), intent.policy(), pins), true);
        });
        rebuildRows();
        controls();
    }

    private PlanView.Candidate selectedCandidate() {
        if (draft == null || draft.choices().candidates().isEmpty()) return null;
        choicePage = Math.floorMod(choicePage, draft.choices().candidates().size());
        return draft.choices().candidates().get(choicePage);
    }

    private void cycleChoice(int direction) {
        if (draft == null || draft.choices().candidates().isEmpty()) return;
        choicePage = Math.floorMod(choicePage + direction, draft.choices().candidates().size());
        controls();
    }

    private Button button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, w, 20).build());
    }

    private Component candidateLabel(PlanView.Candidate candidate) {
        var state = candidateStates.get(candidate.recipe());
        String marker = state == null || state == CraftingResultCode.SEARCH_BUDGET_EXCEEDED ? "? "
                : state == CraftingResultCode.CREATED ? "+ " : state == CraftingResultCode.PARTIAL_CREATED ? "! " : "× ";
        return Component.literal(marker).append(candidate.output().getHoverName()).append(" ×" + candidate.output().getCount())
                .append(" [" + candidate.recipe().getPath() + "]");
    }

    private Component candidateDescription(PlanView.Candidate candidate) {
        var description = Component.literal(candidate.recipe().toString()).append("\n")
                .append(text("candidate_grid", candidate.gridSize(), candidate.gridSize()));
        minecraft.level.getRecipeManager().byKey(candidate.recipe()).ifPresent(holder -> {
            for (var ingredient : holder.value().getIngredients()) if (!ingredient.isEmpty()) {
                description.append("\n").append(text("inputs")).append(" ");
                var alternatives = java.util.Arrays.stream(ingredient.getItems()).limit(3).toList();
                description.append(items(alternatives));
                if (ingredient.getItems().length > 1) description.append(text("or"));
                if (ingredient.getItems().length > 3) description.append("…");
            }
        });
        if (candidate.rejection() != null) description.append("\n").append(Component.translatable("reason.craftable."
                + candidate.rejection().name().toLowerCase(java.util.Locale.ROOT)));
        return description;
    }

    private void choose(List<String> paths) {
        if (paths.isEmpty()) return;
        selectedPaths = List.copyOf(paths);
        choicePath = paths.getFirst();
        pane = Pane.CHOICES;
        candidateStates.clear();
        choicePage = 0;
        change(intent, false);
    }

    private boolean affected(String path) {
        return selectedPaths.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "."));
    }

    private CraftRequest candidateIntent(ResourceLocation recipe) {
        if (selectedPaths.contains("0"))
            return new CraftRequest(recipe, intent.batches(), intent.partial(), intent.allowDrops(), intent.policy(), Map.of());
        var pins = new TreeMap<>(intent.selections());
        pins.keySet().removeIf(this::affected);
        selectedPaths.forEach(path -> pins.put(path, recipe));
        return new CraftRequest(intent.recipe(), intent.batches(), intent.partial(), intent.allowDrops(), intent.policy(), pins);
    }

    private void applyCandidate(PlanView.Candidate candidate) {
        try {
            var next = candidateIntent(candidate.recipe());
            pane = Pane.GRAPH; choicePath = "";
            notice = text("choices_reset");
            singleRoute = List.of();
            change(next, true);
        } catch (IllegalArgumentException limit) {
            notice = text("selection_limit");
        }
    }

    private void switchPane(Pane next) {
        if (pane == Pane.CHOICES) {
            choicePath = "";
            // Candidate probes replace the retained local plan. Restore the
            // actual intent before enabling its authoritative review.
            change(intent, false);
        }
        pane = next;
        scroll = 0;
        init();
    }

    private void change(CraftRequest next, boolean resetMaximum) {
        intent = next;
        generation++;
        dirty = true;
        changedAt = System.nanoTime();
        if (resetMaximum) maximum = null;
        draft = null;
        queuedAction = null;
        if (pending != Work.CONFIRM && pending != Work.AUTHORIZE) {
            pending = null; inFlight = -1;
            // Finish the bounded running search into the shared result store.
            // Changing UI intent must not reset its cumulative search budget.
        }
        controls();
    }

    private void act(boolean allowPartial) {
        if (dirty || draft == null || pane == Pane.CHOICES) return;
        // MAX is read-only and keeps the current token. Accept one explicit
        // click while its bounded slice finishes instead of blinking buttons.
        // A changed intent cancels this slot; confirmation is never retried.
        if (pending == Work.MAXIMUM) { queuedAction = allowPartial; controls(); return; }
        if (pending != null) return;
        if (intent.partial() != allowPartial) {
            notice = Component.empty();
            change(intent.withPartial(allowPartial), false);
            return;
        }
        if (!localDraft && draft.token().equals(CraftingDetailPayloads.NO_TOKEN)) return;
        if (routeChanged && pane != Pane.COSTS) {
            switchPane(Pane.COSTS);
            notice = text("mixed_review");
            return;
        }
        if (localDraft) {
            reviewedIdentity = draft.view().reviewIdentity();
            // This is the single explicit server planning request, not passive
            // browsing. M4.9 will replace it with witness validation.
            PacketDistributor.sendToServer(new CraftingDetailPayloads.PreviewRequest(
                    minecraft.player.containerMenu.containerId, begin(Work.AUTHORIZE), intent, ""));
            controls();
            return;
        }
        confirm();
    }

    private void confirm() {
        long sequence = begin(Work.CONFIRM);
        PacketDistributor.sendToServer(new CraftingDetailPayloads.ConfirmRequest(
                minecraft.player.containerMenu.containerId, sequence, intent.recipe(), draft.token()));
        controls();
    }

    private long begin(Work work) {
        pending = work;
        sentGeneration = generation;
        sentAt = System.nanoTime();
        return inFlight = ClientRequestSequence.next();
    }

    @Override public void tick() {
        if (!valid()) { current = null; return; }
        if (resourceScope != ClientBrowsePlanner.scopeVersion()) {
            resourceScope = ClientBrowsePlanner.scopeVersion();
            maximum = null; candidateStates.clear();
            if (pending != Work.CONFIRM && pending != Work.AUTHORIZE) {
                pending = null; inFlight = -1;
                change(intent, true);
            }
        }
        if (pending != null) {
            if (pending != Work.CONFIRM && pending != Work.AUTHORIZE && pending != Work.FALLBACK) return;
            if (System.nanoTime() - sentAt < 5_000_000_000L) return;
            boolean wasConfirm = pending == Work.CONFIRM || pending == Work.AUTHORIZE || pending == Work.FALLBACK;
            pending = null; inFlight = -1;
            queuedAction = null;
            if (wasConfirm) {
                notice = text("unknown_result");
                dirty = false;
                draft = null;
                controls();
                return; // Never auto-resend confirmation.
            }
            dirty = true;
        }
        if (!ClientBrowsePlanner.ready()) {
            if (unavailableSince == 0) unavailableSince = System.nanoTime();
            // One explicit Shift+C/Refresh may use the old authoritative
            // single-target path when local knowledge is unavailable. Never
            // turn this into candidate/MAX or whole-catalog fallback polling.
            if (dirty && !fallbackUsed && System.nanoTime() - unavailableSince >= 5_000_000_000L) {
                fallbackUsed = true; dirty = false;
                PacketDistributor.sendToServer(new CraftingDetailPayloads.PreviewRequest(
                        minecraft.player.containerMenu.containerId, begin(Work.FALLBACK), intent, choicePath));
            }
            controls(); return;
        }
        unavailableSince = 0;
        if (System.nanoTime() - sentAt < 200_000_000L) return;
        int menuId = minecraft.player.containerMenu.containerId;
        if (dirty && System.nanoTime() - changedAt >= 150_000_000L) {
            dirty = false;
            ClientBrowsePlanner.preview(begin(Work.PREVIEW), intent, choicePath);
        } else if (!dirty && pane == Pane.CHOICES && draft != null) {
            var candidate = draft.choices().candidates().stream().filter(c -> !candidateStates.containsKey(c.recipe())).findFirst().orElse(null);
            if (candidate != null) {
                try {
                    var probe = candidateIntent(candidate.recipe());
                    testingCandidate = candidate.recipe();
                    ClientBrowsePlanner.preview(begin(Work.CANDIDATE), probe, "");
                } catch (IllegalArgumentException limit) {
                    candidateStates.put(candidate.recipe(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
                }
            }
        } else if (!dirty && draft != null && (maximum == null || maximum.pending())) {
            ClientBrowsePlanner.maximum(begin(Work.MAXIMUM), intent.withPartial(false));
        }
        controls();
    }

    public static void receive(CraftingDetailPayloads.PreviewResponse payload) {
        receive(payload, false);
    }

    public static void receiveLocal(CraftingDetailPayloads.PreviewResponse payload) {
        receive(payload, true);
    }

    private static void receive(CraftingDetailPayloads.PreviewResponse payload, boolean local) {
        var self = current;
        if (self == null || !self.accepts(payload.menuId(), payload.revision())) return;
        Work work = self.pending;
        self.pending = null;
        if (self.sentGeneration != self.generation) return;
        var code = payload.draft().view().code();
        if (work == Work.CANDIDATE) {
            if (code != CraftingResultCode.REQUEST_THROTTLED) self.candidateStates.put(self.testingCandidate, code);
            // A status reply must not rebuild the focused recipe card every 200 ms.
            self.controls();
            return;
        }
        if (code == CraftingResultCode.REQUEST_THROTTLED) { self.dirty = true; return; }
        boolean sameReview = work == Work.AUTHORIZE && self.reviewedIdentity != null
                && self.reviewedIdentity.equals(payload.draft().view().reviewIdentity());
        self.draft = payload.draft();
        self.localDraft = local;
        var route = payload.draft().view().operations().stream().map(o -> (Object) List.of(o.path(), o.recipe())).distinct().toList();
        if (self.intent.batches() == 1 && self.singleRoute.isEmpty()) self.singleRoute = route;
        self.routeChanged = !self.singleRoute.isEmpty() && !self.singleRoute.equals(route)
                || payload.draft().view().nodes().stream().anyMatch(n -> n.recipes().size() > 1);
        self.graph.show(self.draft.view());
        self.slider.syncValue();
        self.rebuildRows();
        self.controls();
        self.triggerImmediateNarration(true);
        if (work == Work.AUTHORIZE) {
            self.reviewedIdentity = null;
            if (sameReview && !self.draft.token().equals(CraftingDetailPayloads.NO_TOKEN)) self.confirm();
            else {
                self.notice = Component.translatable("message.craftable.create.environment_changed");
                self.controls(); // Changed authoritative values require another explicit click.
            }
        }
    }

    public static void receive(CraftingDetailPayloads.MaximumResponse payload) {
        var self = current;
        if (self == null || !self.accepts(payload.menuId(), payload.revision())) return;
        if (!payload.maximum().pending()) self.pending = null;
        if (self.sentGeneration != self.generation) return;
        self.maximum = payload.maximum();
        self.slider.syncValue();
        if (self.queuedAction != null) {
            boolean action = self.queuedAction;
            self.queuedAction = null;
            ClientBrowsePlanner.cancelDetails(); self.pending = null;
            self.act(action);
        }
        self.controls();
    }

    public static void created(CreateRecipeResultPayload payload) {
        var self = current;
        if (self == null) {
            if (payload.resultCode() == CraftingResultCode.CONFIRMATION_REQUIRED)
                open(Minecraft.getInstance().screen, payload.recipeId(), true);
            return;
        }
        if (!self.valid() || self.pending != Work.CONFIRM || self.inFlight != payload.requestId()) return;
        self.pending = null;
        self.notice = payload.resultCode() == CraftingResultCode.CREATED || payload.resultCode() == CraftingResultCode.PARTIAL_CREATED
                ? text("completed") : Component.translatable("message.craftable.create." + payload.resultCode().name().toLowerCase(java.util.Locale.ROOT));
        self.change(self.intent, true);
    }

    private boolean accepts(int menuId, long sequence) {
        return valid() && minecraft.player.containerMenu.containerId == menuId && pending != null && inFlight == sequence;
    }

    private void controls() {
        if (create == null) return;
        boolean ready = draft != null && !dirty && queuedAction == null
                && (pending == null || pending == Work.MAXIMUM) && pane != Pane.CHOICES;
        boolean token = ready && (localDraft && ClientBrowsePlanner.ready()
                || !draft.token().equals(CraftingDetailPayloads.NO_TOKEN));
        create.active = ready && draft.view().code() == CraftingResultCode.CREATED
                && (intent.partial() || token);
        partial.active = ready && intent.policy() != CraftRequest.PartialPolicy.NEVER
                && (!intent.partial() || token)
                && (draft.view().code() == CraftingResultCode.CREATED || draft.view().code() == CraftingResultCode.PARTIAL_CREATED);
        create.setMessage(text(intent.partial() ? "review_full" : "confirm_full"));
        partial.setMessage(text(intent.partial() ? "confirm_partial" : "review_partial"));
        slider.active = pane != Pane.CHOICES && maximum != null && maximum.lowerBound() > 1 && pending != Work.CONFIRM;
        graph.visible = pane == Pane.GRAPH;
        for (var button : List.of(previousChoice, nextChoice, applyChoice, automaticChoice)) button.visible = pane == Pane.CHOICES;
        var candidate = selectedCandidate();
        previousChoice.active = nextChoice.active = candidate != null && draft.choices().candidates().size() > 1;
        applyChoice.active = candidate != null && !dirty;
        applyChoice.setTooltip(candidate == null ? null : net.minecraft.client.gui.components.Tooltip.create(candidateDescription(candidate)));
        slider.setTooltip(net.minecraft.client.gui.components.Tooltip.create(maximum == null || maximum.pending() ? text("max_waiting")
                : maximum.proven() ? text("max", maximum.lowerBound()) : text("max_lower", maximum.lowerBound())));
    }

    private void rebuildRows() {
        rows.clear();
        wrappedRows.clear();
        if (draft == null) return;
        var view = draft.view();
            addRows("inputs", view.consumed());
            addRows("outputs", view.primary());
            addRows("surplus", view.surplus());
            addRows("drops", view.drops());
            if (!view.drops().isEmpty()) rows.add(text("drop_warning"));
            for (var missing : view.missing())
                rows.add(text("missing", missing.count(), items(missing.alternatives())).append(missing.alternatives().size() > 1 ? text("or") : Component.empty()));
        // Wrap only when the view/size changes, not for every offscreen row on
        // every frame. Large ordered plans stay cheap to scroll.
        rows.forEach(row -> wrappedRows.addAll(font.split(row, width - 36)));
    }

    private void addRows(String title, List<ItemStack> stacks) {
        rows.add(text(title));
        if (stacks.isEmpty()) rows.add(text("none"));
        else for (var stack : stacks) rows.add(text("item_count", stack.getCount(), stack.getHoverName()));
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xF0202020);
        g.fill(10, 8, width - 10, height - 8, 0xFFC6C6C6);
        g.hLine(10, width - 11, 8, 0xFFFFFFFF);
        g.vLine(10, 8, height - 9, 0xFFFFFFFF);
        g.hLine(10, width - 11, height - 9, 0xFF373737);
        g.vLine(width - 11, 8, height - 9, 0xFF373737);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        if (draft != null && draft.view().workbench()) {
            g.fill(18, 18, 42, 42, 0xD0202020);
            g.renderItem(new ItemStack(Items.CRAFTING_TABLE), 22, 22);
            if (mx >= 18 && mx < 42 && my >= 18 && my < 42)
                g.renderTooltip(font, new ItemStack(Items.CRAFTING_TABLE), mx, my);
        }
        if (pane == Pane.COSTS) {
            g.fill(14, 14, width - 14, height - 62, 0xFF353535);
            g.enableScissor(18, 18, width - 18, height - 64);
            int first = Math.max(0, scroll / 12);
            int count = Math.max(1, (height - 82) / 12 + 2);
            for (int i = first; i < Math.min(wrappedRows.size(), first + count); i++)
                g.drawString(font, wrappedRows.get(i), 22, 20 + i * 12 - scroll, 0xFFFFFFFF, false);
            g.disableScissor();
        }
        if (pane == Pane.CHOICES) renderChoice(g, mx, my);
        if (draft != null && pane != Pane.CHOICES) {
            var cost = text("cost_summary", items(draft.view().consumed()));
            g.drawString(font, font.substrByWidth(cost, width - 52).getString(), 16, height - 47, 0xFF404040, false);
            if (mx >= 14 && mx < width - 34 && my >= height - 49 && my < height - 36)
                g.renderComponentTooltip(font, rows, mx, my);
        }
        if (routeChanged) {
            g.drawString(font, "!", width - 26, height - 47, 0xFFFFAA00);
            if (mx >= width - 32 && mx < width - 12 && my >= height - 49 && my < height - 36)
                g.renderComponentTooltip(font, List.of(text("mixed_warning")), mx, my);
        }
        // Normal success/review instructions do not occupy the canvas. Keep
        // actual failures visible; absence of a token never looks actionable.
        Component status = notice;
        if (draft == null) status = pending == Work.CONFIRM ? text("waiting") : Component.literal("…");
        else if (draft.view().code() != CraftingResultCode.CREATED && draft.view().code() != CraftingResultCode.PARTIAL_CREATED)
            status = Component.translatable("reason.craftable." + draft.view().code().name().toLowerCase(java.util.Locale.ROOT));
        if (!status.getString().isEmpty()) {
            g.fill(14, height - 62, width - 14, height - 51, 0xDD202020);
            g.drawString(font, font.substrByWidth(status, width - 36).getString(), 18, height - 61, 0xFFFFFFFF, false);
        }
    }

    private void renderChoice(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = Math.max(24, (height - 76) / 2 - 38);
        var candidate = selectedCandidate();
        if (candidate == null) {
            g.drawCenteredString(font, draft == null ? text("waiting") : text("none"), cx, cy + 20, 0xFF404040);
            return;
        }
        g.drawCenteredString(font, candidate.output().getHoverName(), cx, cy - 4, 0xFF404040);
        var holder = minecraft.level.getRecipeManager().byKey(candidate.recipe()).orElse(null);
        // Client-synchronized recipes are display data only. Choosing a card
        // still sends a recipe ID for an authoritative full-chain preview.
        if (holder != null) {
            var ingredients = holder.value().getIngredients();
            int gridWidth = holder.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped ? shaped.getWidth() : 3;
            long frame = minecraft.level.getGameTime() / 30;
            for (int slot = 0; slot < 9; slot++) {
                int x = cx - 70 + slot % 3 * 18, y = cy + 17 + slot / 3 * 18;
                g.blit(ResourceLocation.withDefaultNamespace("textures/gui/container/crafting_table.png"), x, y, 29, 16, 18, 18);
                int index = slot / 3 * gridWidth + slot % 3;
                if (slot % 3 >= gridWidth || index >= ingredients.size()) continue;
                var options = ingredients.get(index).getItems();
                if (options.length == 0) continue;
                var stack = options[(int) (frame % options.length)];
                g.renderItem(stack, x + 1, y + 1);
                if (mx >= x && mx < x + 18 && my >= y && my < y + 18) g.renderTooltip(font, stack, mx, my);
            }
        }
        g.blit(ResourceLocation.withDefaultNamespace("textures/gui/container/crafting_table.png"), cx - 6, cy + 33, 89, 35, 24, 17);
        g.blit(ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png"), cx + 32, cy + 34, 153, 27, 18, 18);
        g.renderItem(candidate.output(), cx + 33, cy + 35);
        g.renderItemDecorations(font, candidate.output(), cx + 33, cy + 35);
        g.drawCenteredString(font, Component.literal((choicePage + 1) + " / " + draft.choices().candidates().size()
                + (draft.choices().truncated() ? " +" : "")), cx, cy + 70, 0xFF404040);
        var state = candidateStates.get(candidate.recipe());
        String marker = state == null || state == CraftingResultCode.SEARCH_BUDGET_EXCEEDED ? "?"
                : state == CraftingResultCode.CREATED ? "+" : state == CraftingResultCode.PARTIAL_CREATED ? "!" : "×";
        g.drawString(font, marker, cx + 57, cy + 39, state == CraftingResultCode.CREATED ? 0xFF208020 : 0xFF803030, false);
        if (mx >= cx + 30 && mx < cx + 76 && my >= cy + 30 && my < cy + 60)
            g.renderComponentTooltip(font, List.of(candidateLabel(candidate), candidateDescription(candidate)), mx, my);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (pane == Pane.COSTS) {
            scroll = Math.max(0, Math.min(Math.max(0, wrappedRows.size() * 12 - (height - 82)), scroll - (int) (dy * 24))); return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) { onClose(); return true; }
        boolean used = super.keyPressed(key, scan, modifiers);
        triggerImmediateNarration(true);
        return used;
    }

    @Override public void onClose() {
        if (pane != Pane.GRAPH) { switchPane(Pane.GRAPH); return; }
        current = null;
        ClientBrowsePlanner.cancelDetails();
    }

    private final class CountSlider extends AbstractSliderButton {
        CountSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), 0);
            int max = maximum == null ? 1 : Math.max(1, maximum.lowerBound());
            value = max <= 1 ? 0 : (double) (intent.batches() - 1) / (max - 1);
            updateMessage();
        }
        @Override protected void updateMessage() {
            int output = minecraft.level.getRecipeManager().byKey(intent.recipe())
                    .map(r -> r.value().getResultItem(minecraft.level.registryAccess()).getCount()).orElse(1);
            setMessage(text("quantity_compact", intent.batches(), intent.batches() * output));
        }
        void syncValue() {
            int max = maximum == null ? 1 : Math.max(1, maximum.lowerBound());
            value = max <= 1 ? 0 : Math.max(0, Math.min(1, (double) (intent.batches() - 1) / (max - 1)));
            updateMessage();
        }
        @Override protected void applyValue() {
            int max = maximum == null ? 1 : Math.max(1, maximum.lowerBound());
            int count = 1 + (int) Math.round(value * (max - 1));
            if (count == intent.batches()) return;
            // Do not rebuild the dragged widget: that loses vanilla pointer
            // capture. Only replace the intent; debounce the server request.
            intent = intent.withBatches(count);
            generation++; dirty = true; draft = null; queuedAction = null; changedAt = System.nanoTime();
            controls();
        }
        @Override public void onRelease(double x, double y) {
            super.onRelease(x, y);
            changedAt = 0;
        }
    }

    private static net.minecraft.network.chat.MutableComponent text(String key, Object... args) {
        return Component.translatable("screen.craftable.plan." + key, args);
    }
    private static Component items(List<ItemStack> stacks) {
        var result = Component.empty();
        for (var stack : stacks) if (!stack.isEmpty()) {
            if (!result.getSiblings().isEmpty()) result.append(", ");
            result.append(text("item_count", stack.getCount(), stack.getHoverName()));
        }
        return result;
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (current == null) return;
        if (!current.valid()) { current = null; return; }
        current.tick();
    }
    @SubscribeEvent public static void closing(ScreenEvent.Closing event) {
        if (current != null && current.parent == event.getScreen()) current = null;
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        var self = live(event.getScreen());
        if (self == null) return;
        if (self.width != event.getScreen().width || self.height != event.getScreen().height)
            self.resize(Minecraft.getInstance(), event.getScreen().width, event.getScreen().height);
        var g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 800);
        self.renderWithTooltip(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        g.pose().popPose();
        self.handleDelayedNarration();
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void key(ScreenEvent.KeyPressed.Pre e) {
        var self = live(e.getScreen()); if (self == null) return;
        self.keyPressed(e.getKeyCode(), e.getScanCode(), e.getModifiers()); e.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void keyUp(ScreenEvent.KeyReleased.Pre e) {
        var self = live(e.getScreen()); if (self == null) return;
        self.keyReleased(e.getKeyCode(), e.getScanCode(), e.getModifiers()); e.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void character(ScreenEvent.CharacterTyped.Pre e) {
        var self = live(e.getScreen()); if (self == null) return;
        self.charTyped(e.getCodePoint(), e.getModifiers()); e.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void mouse(ScreenEvent.MouseButtonPressed.Pre e) {
        var self = live(e.getScreen()); if (self == null) return;
        self.mouseClicked(e.getMouseX(), e.getMouseY(), e.getButton()); e.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void mouseUp(ScreenEvent.MouseButtonReleased.Pre e) {
        var self = live(e.getScreen()); if (self == null) return;
        self.mouseReleased(e.getMouseX(), e.getMouseY(), e.getButton()); e.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void drag(ScreenEvent.MouseDragged.Pre e) {
        var self = live(e.getScreen()); if (self == null) return;
        self.mouseDragged(e.getMouseX(), e.getMouseY(), e.getMouseButton(), e.getDragX(), e.getDragY()); e.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void wheel(ScreenEvent.MouseScrolled.Pre e) {
        var self = live(e.getScreen()); if (self == null) return;
        self.mouseScrolled(e.getMouseX(), e.getMouseY(), e.getScrollDeltaX(), e.getScrollDeltaY()); e.setCanceled(true);
    }
}
