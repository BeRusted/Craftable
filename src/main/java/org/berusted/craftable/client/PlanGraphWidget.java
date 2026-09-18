package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.PlanView;

/** Small bounded layout/renderer. It consumes a projection and never computes craftability. */
final class PlanGraphWidget extends AbstractWidget {
    private final List<Cell> cells = new ArrayList<>();
    private final Map<String, Cell> byId = new HashMap<>();
    private final Consumer<List<String>> select;
    private final java.util.Set<String> collapsed = new java.util.HashSet<>();
    private int focusedCell;
    private int panX, panY;
    private PlanView view;

    PlanGraphWidget(int x, int y, int width, int height, Consumer<List<String>> select) {
        super(x, y, width, height, Component.translatable("screen.craftable.plan.graph"));
        this.select = select;
    }

    void show(PlanView view) {
        this.view = view;
        collapsed.clear();
        panX = panY = focusedCell = 0;
        layout();
    }

    private void layout() {
        cells.clear();
        byId.clear();
        if (view == null) return;
        var aliases = new HashMap<String, String>();
        var raw = new LinkedHashMap<String, PlanView.Node>();
        view.nodes().stream().sorted(java.util.Comparator.comparing(PlanView.Node::path))
                .forEach(n -> raw.put(n.path(), n));
        var references = new HashMap<String, java.util.Set<String>>();
        for (var op : view.operations()) for (int i = 0; i < op.inputs().size(); i++) {
            int origin = op.inputOrigins().get(i);
            if (origin >= 0) references.computeIfAbsent(op.path() + "." + i, ignored -> new java.util.HashSet<>())
                    .add(view.operations().get(origin).path());
        }
        // Merge only proven same-batch references, not merely matching icons.
        // Keep mixed/ambiguous references separate and expose the exact steps.
        references.forEach((path, producers) -> {
            if (producers.size() != 1 || !raw.containsKey(path) || !raw.get(path).made().isEmpty()) return;
            String producer = producers.iterator().next();
            if (producer.compareTo(path) >= 0 || !raw.containsKey(producer) || path.startsWith(producer + ".")
                    || producer.startsWith(path + ".")) return;
            if (raw.get(path).needs().stream().allMatch(s -> raw.get(producer).made().stream()
                    .anyMatch(p -> ItemStack.isSameItemSameComponents(s, p)))) aliases.put(path, producer);
        });
        for (var node : raw.values()) if (!node.reference().isEmpty() && raw.containsKey(node.reference())
                && !node.reference().startsWith(node.path() + ".") && !node.path().startsWith(node.reference() + "."))
            aliases.put(node.path(), node.reference());
        var identicalLeaves = new HashMap<Object, String>();
        for (var n : raw.values()) {
            boolean leaf = raw.keySet().stream().noneMatch(p -> parent(p).equals(n.path()));
            if (!leaf || n.path().equals("0") || !n.made().isEmpty() || !n.recipes().isEmpty() || aliases.containsKey(n.path())) continue;
            Object key = List.of(parent(n.path()), CraftPlan.stackKeys(n.needs().stream().map(s -> s.copyWithCount(1)).toList()),
                    n.explanation(), n.alternatives());
            String previous = identicalLeaves.putIfAbsent(key, n.path());
            if (previous != null) aliases.put(n.path(), previous);
        }
        var grouped = new LinkedHashMap<String, Cell>();
        for (var n : raw.values()) {
            String id = resolve(n.path(), aliases);
            var cell = grouped.computeIfAbsent(id, ignored -> new Cell(id));
            cell.paths.add(n.path());
            n.needs().forEach(s -> merge(cell.needs, s));
            n.made().forEach(s -> merge(cell.made, s));
            for (var recipe : n.recipes()) if (!cell.recipes.contains(recipe.toString())) cell.recipes.add(recipe.toString());
            cell.explanation |= n.explanation();
            cell.alternatives |= n.alternatives();
        }
        for (var n : raw.values()) {
            String id = resolve(n.path(), aliases), parent = resolve(parent(n.path()), aliases);
            if (!id.equals(parent) && grouped.containsKey(parent) && !grouped.get(parent).children.contains(id))
                grouped.get(parent).children.add(id);
        }
        int[] row = {0};
        place("0", grouped, new java.util.HashSet<>(), row);
        if (!cells.isEmpty()) {
            int min = cells.stream().mapToInt(c -> c.y).min().orElse(0);
            int max = cells.stream().mapToInt(c -> c.y).max().orElse(0);
            // Center the occupied bounds, including the icon height, rather
            // than the root's top edge (which clips lower leaves at GUI scale 2).
            panY = (height - (max - min + 26)) / 2 - min;
        }
        cells.forEach(c -> byId.put(c.id, c));
        focusedCell = Math.min(focusedCell, Math.max(0, cells.size() - 1));
    }

    private int place(String id, Map<String, Cell> grouped, java.util.Set<String> seen, int[] row) {
        var cell = grouped.get(id);
        if (cell == null) return row[0] * 40;
        if (!seen.add(id)) return cell.y;
        int depth = id.split("\\.").length - 1;
        cell.x = width - 42 - depth * 64;
        var visibleChildren = collapsed.contains(id) ? List.<String>of() : cell.children;
        if (visibleChildren.isEmpty()) cell.y = row[0]++ * 40;
        else {
            int sum = 0;
            for (String child : visibleChildren) sum += place(child, grouped, seen, row);
            cell.y = sum / visibleChildren.size();
        }
        cells.add(cell);
        return cell.y;
    }

    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF555555);
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        var stone = net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/block/stone.png");
        for (int x = getX(); x < getX() + width; x += 16) for (int y = getY(); y < getY() + height; y += 16)
            g.blit(stone, x, y, 0, 0, 16, 16, 16, 16);
        for (var cell : cells) if (!collapsed.contains(cell.id)) for (String childId : cell.children) {
            var child = byId.get(childId);
            if (child == null) continue;
            int x1 = x(child) + 13, y1 = y(child) + 13, x2 = x(cell) + 13, y2 = y(cell) + 13;
            int bend = (x1 + x2) / 2;
            // Vanilla advancement connectivity: black outline, white core.
            g.fill(x1, y1 - 1, bend + 2, y1 + 2, 0xFF000000);
            g.fill(bend - 1, Math.min(y1, y2) - 1, bend + 2, Math.max(y1, y2) + 2, 0xFF000000);
            g.fill(bend - 1, y2 - 1, x2, y2 + 2, 0xFF000000);
            g.hLine(x1, bend, y1, 0xFFFFFFFF);
            g.vLine(bend, Math.min(y1, y2), Math.max(y1, y2), 0xFFFFFFFF);
            g.hLine(bend, x2, y2, 0xFFFFFFFF);
        }
        Cell hovered = null;
        for (int i = 0; i < cells.size(); i++) {
            var c = cells.get(i);
            int x = x(c), y = y(c);
            if (x + 26 < getX() || x > getX() + width || y + 26 < getY() || y > getY() + height) continue;
            boolean hover = mouseX >= x && mouseX < x + 26 && mouseY >= y && mouseY < y + 26 && isMouseOver(mouseX, mouseY);
            if (hover) hovered = c;
            var icon = icon(c);
            boolean root = c.id.equals("0");
            var style = !root && !c.explanation
                    ? net.minecraft.client.gui.screens.advancements.AdvancementWidgetType.OBTAINED
                    : net.minecraft.client.gui.screens.advancements.AdvancementWidgetType.UNOBTAINED;
            g.blitSprite(style.frameSprite(root ? net.minecraft.advancements.AdvancementType.CHALLENGE
                    : net.minecraft.advancements.AdvancementType.TASK), x, y, 26, 26);
            if (hover || isFocused() && i == focusedCell) g.renderOutline(x - 1, y - 1, 28, 28, 0xFFFFFFFF);
            g.renderItem(icon, x + 5, y + 5);
            g.renderItemDecorations(font, icon, x + 5, y + 5);
            g.pose().pushPose();
            g.pose().translate(0, 0, 250);
            if (c.recipes.size() > 1 || c.needs.size() > 1 && !c.alternatives)
                g.drawString(font, "!", x + 22, y - 3, 0xFFFFFF55);
            if (collapsed.contains(c.id)) g.drawString(font, "+", x - 7, y + 6, 0xFFFFFFFF);
            g.pose().popPose();
        }
        g.disableScissor();
        if (hovered != null) g.renderComponentTooltip(font, tooltip(hovered), mouseX, mouseY);
    }

    @Override public void onClick(double mouseX, double mouseY) {
        for (int i = 0; i < cells.size(); i++) {
            var c = cells.get(i);
            if (mouseX >= x(c) && mouseX < x(c) + 26 && mouseY >= y(c) && mouseY < y(c) + 26) {
                focusedCell = i;
                select.accept(List.copyOf(c.paths));
                return;
            }
        }
    }

    @Override protected void onDrag(double mouseX, double mouseY, double dx, double dy) {
        panX = Math.max(-2000, Math.min(2000, panX + (int) dx));
        panY = Math.max(-10000, Math.min(10000, panY + (int) dy));
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!isMouseOver(x, y)) return false;
        if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) panX += (int) (vertical * 24);
        else panY += (int) (vertical * 24);
        panX = Math.max(-2000, Math.min(2000, panX));
        panY = Math.max(-10000, Math.min(10000, panY));
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (cells.isEmpty()) return false;
        if (key == 264 || key == 265) {
            focusedCell = Math.floorMod(focusedCell + (key == 264 ? 1 : -1), cells.size());
            var cell = cells.get(focusedCell);
            panX = width / 2 - cell.x;
            panY = height / 2 - cell.y;
            return true;
        }
        if (key == 262 || key == 263) { panX += key == 262 ? -24 : 24; return true; }
        if (key == 257 || key == 335) { select.accept(List.copyOf(cells.get(focusedCell).paths)); return true; }
        if (key == 32) {
            String id = cells.get(focusedCell).id;
            if (!collapsed.remove(id)) collapsed.add(id);
            layout();
            return true;
        }
        return false;
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput narration) {
        narration.add(NarratedElementType.TITLE, cells.isEmpty() ? getMessage() : icon(cells.get(focusedCell)).getHoverName());
        narration.add(NarratedElementType.USAGE, Component.translatable("screen.craftable.plan.graph_help"));
    }

    private List<Component> tooltip(Cell cell) {
        var lines = new ArrayList<Component>();
        lines.add(icon(cell).getHoverName());
        if (cell.explanation) lines.add(Component.translatable("screen.craftable.plan.explanation_node"));
        else if (cell.made.isEmpty() && cell.recipes.isEmpty())
            lines.add(Component.translatable("screen.craftable.plan.existing"));
        if (cell.alternatives) lines.add(Component.translatable("screen.craftable.plan.or"));
        for (var stack : cell.needs) lines.add(Component.translatable("screen.craftable.plan.need", stack.getCount(), stack.getHoverName()));
        for (var stack : cell.made) lines.add(Component.translatable("screen.craftable.plan.made", stack.getCount(), stack.getHoverName()));
        for (String recipe : cell.recipes) lines.add(Component.literal(recipe));
        // Exact per-step material/remaining-item details remain accessible
        // without spending a permanent toolbar row on an extra steps page.
        for (var op : view.operations()) if (cell.paths.contains(op.path())) {
            lines.add(Component.translatable("screen.craftable.plan.outputs"));
            lines.add(Component.translatable("screen.craftable.plan.item_count", op.output().getCount(), op.output().getHoverName()));
            for (var input : op.inputs()) if (!input.isEmpty())
                lines.add(Component.translatable("screen.craftable.plan.need", input.getCount(), input.getHoverName()));
            for (var remainder : op.remainders()) if (!remainder.isEmpty())
                lines.add(Component.translatable("screen.craftable.plan.remainders").append(" ").append(remainder.getHoverName()));
            if (lines.size() > 40) { lines.add(Component.literal("…")); break; }
        }
        if (cell.paths.size() > 1) lines.add(Component.translatable("screen.craftable.plan.shared", cell.paths.size()));
        return lines;
    }

    private int x(Cell c) { return getX() + c.x + panX; }
    private int y(Cell c) { return getY() + c.y + panY; }
    private static ItemStack icon(Cell c) { return !c.needs.isEmpty() ? c.needs.getFirst() : c.made.isEmpty() ? ItemStack.EMPTY : c.made.getFirst(); }
    private static String parent(String path) { int dot = path.lastIndexOf('.'); return dot < 0 ? "" : path.substring(0, dot); }
    private static String resolve(String path, Map<String, String> aliases) {
        for (int i = 0; i < PlanView.MAX_NODES && aliases.containsKey(path); i++) path = aliases.get(path);
        return path;
    }
    private static void merge(List<ItemStack> values, ItemStack stack) {
        for (var value : values) if (ItemStack.isSameItemSameComponents(value, stack)) {
            value.grow(stack.getCount()); return;
        }
        values.add(stack.copy());
    }
    private static final class Cell {
        final String id;
        final List<String> paths = new ArrayList<>(), recipes = new ArrayList<>(), children = new ArrayList<>();
        final List<ItemStack> needs = new ArrayList<>(), made = new ArrayList<>();
        boolean explanation, alternatives;
        int x, y;
        Cell(String id) { this.id = id; }
    }
}
