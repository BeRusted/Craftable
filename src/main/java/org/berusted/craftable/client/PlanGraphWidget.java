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
        boolean updating = this.view != null;
        int previousX = panX, previousY = panY, previousFocus = focusedCell;
        this.view = view;
        layout();
        if (updating) {
            // Resource renewal updates the projection, not the player's view.
            panX = previousX; panY = previousY;
            focusedCell = Math.min(previousFocus, Math.max(0, cells.size() - 1));
        }
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
        mergeEquivalentBranches(raw, aliases);
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
        // Different real materials across batches are AND inputs, not an
        // animated OR icon. Split actual mixed leaves for display only; all
        // variants retain authoritative paths. Operations remain executable.
        for (var cell : List.copyOf(grouped.values())) {
            if (!cell.children.isEmpty() || cell.alternatives || cell.needs.size() <= 1) continue;
            if (grouped.size() + cell.needs.size() - 1 > PlanView.MAX_NODES) continue;
            var variants = new ArrayList<String>();
            variants.add(cell.id);
            for (int i = 1; i < cell.needs.size(); i++) {
                var variant = new Cell(cell.id + ":" + i);
                variant.paths.addAll(cell.paths);
                variant.needs.add(cell.needs.get(i).copy());
                variant.explanation = cell.explanation;
                variant.deviation = true;
                grouped.put(variant.id, variant); variants.add(variant.id);
            }
            var first = cell.needs.getFirst();
            cell.needs.clear(); cell.needs.add(first);
            for (var parent : grouped.values()) if (parent.children.contains(cell.id)) {
                int at = parent.children.indexOf(cell.id);
                parent.children.remove(at); parent.children.addAll(at, variants);
            }
        }
        collapsed.retainAll(grouped.keySet());
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
        int depth = cell.paths.getFirst().split("\\.").length - 1;
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
        // As in vanilla advancements, finish every outline before drawing any
        // white core. Per-edge interleaving cuts previously drawn junctions.
        drawConnections(g, true);
        drawConnections(g, false);
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
            if (c.deviation || c.recipes.size() > 1 || c.needs.size() > 1 && !c.alternatives)
                g.drawString(font, "!", x + 22, y - 3, 0xFFFFFF55, false);
            if (collapsed.contains(c.id)) g.drawString(font, "+", x - 7, y + 6, 0xFFFFFFFF, false);
            g.pose().popPose();
        }
        g.disableScissor();
        if (hovered != null) g.renderComponentTooltip(font, tooltip(hovered), mouseX, mouseY);
    }

    private void drawConnections(GuiGraphics g, boolean outline) {
        for (var cell : cells) if (!collapsed.contains(cell.id)) for (String childId : cell.children) {
            var child = byId.get(childId);
            if (child == null) continue;
            int x1 = x(child) + 13, y1 = y(child) + 13, x2 = x(cell) + 13, y2 = y(cell) + 13;
            int bend = (x1 + x2) / 2;
            if (outline) {
                g.fill(x1, y1 - 1, bend + 2, y1 + 2, 0xFF000000);
                g.fill(bend - 1, Math.min(y1, y2) - 1, bend + 2, Math.max(y1, y2) + 2, 0xFF000000);
                g.fill(bend - 1, y2 - 1, x2 + 1, y2 + 2, 0xFF000000);
            } else {
                g.hLine(x1, bend, y1, 0xFFFFFFFF);
                // vLine excludes its endpoints; both horizontal cores include them.
                g.vLine(bend, Math.min(y1, y2), Math.max(y1, y2), 0xFFFFFFFF);
                g.hLine(bend, x2, y2, 0xFFFFFFFF);
            }
        }
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
        if (cell.deviation) lines.add(Component.translatable("screen.craftable.plan.additional_material"));
        if (!cell.explanation && cell.made.isEmpty() && cell.recipes.isEmpty())
            lines.add(Component.translatable("screen.craftable.plan.existing"));
        if (cell.alternatives && cell.needs.size() > 1) lines.add(Component.translatable("screen.craftable.plan.or"));
        for (var stack : cell.needs) lines.add(Component.translatable("screen.craftable.plan.need", stack.getCount(), stack.getHoverName()));
        for (var stack : cell.made) lines.add(Component.translatable("screen.craftable.plan.made", stack.getCount(), stack.getHoverName()));
        // Summarize the selected real operations once, not once per batch.
        // Exact components remain distinct; input/output/remainder roles never
        // cancel each other. This presentation does not modify the reviewed plan.
        var inputs = new ArrayList<ItemStack>();
        var outputs = new ArrayList<ItemStack>();
        var remainders = new ArrayList<ItemStack>();
        for (var op : view.operations()) if (cell.paths.contains(op.path())) {
            merge(outputs, op.output());
            op.inputs().stream().filter(s -> !s.isEmpty()).forEach(s -> merge(inputs, s));
            op.remainders().stream().filter(s -> !s.isEmpty()).forEach(s -> merge(remainders, s));
        }
        addAmounts(lines, "recipe_inputs", inputs);
        addAmounts(lines, "recipe_outputs", outputs);
        addAmounts(lines, "remainders", remainders);
        if (cell.paths.size() > 1) lines.add(Component.translatable("screen.craftable.plan.shared", cell.paths.size()));
        if (lines.size() > 40) {
            lines.subList(40, lines.size()).clear();
            lines.add(Component.literal("…"));
        }
        return lines;
    }

    private static void addAmounts(List<Component> lines, String title, List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;
        lines.add(Component.translatable("screen.craftable.plan." + title));
        for (var stack : stacks) lines.add(Component.translatable("screen.craftable.plan.item_count",
                stack.getCount(), stack.getHoverName()));
    }

    private static void mergeEquivalentBranches(Map<String, PlanView.Node> raw, Map<String, String> aliases) {
        var children = new HashMap<String, List<String>>();
        raw.keySet().forEach(p -> children.computeIfAbsent(parent(p), ignored -> new ArrayList<>()).add(p));
        var shapes = new HashMap<String, Object>();
        // Same-batch references are a DAG, not independent production. Exclude
        // their source AND destination subtrees from structural display merging.
        var referenced = new java.util.HashSet<>(aliases.keySet());
        referenced.addAll(aliases.values());
        shape("0", raw, children, referenced, shapes);
        var first = new HashMap<Object, String>();
        for (String path : raw.keySet()) {
            Object shape = shapes.get(path);
            if (path.equals("0") || shape == null || aliases.containsKey(path)) continue;
            String previous = first.putIfAbsent(List.of(parent(path), shape), path);
            if (previous != null) aliasTree(path, previous, children, aliases);
        }
    }

    private static Object shape(String path, Map<String, PlanView.Node> raw, Map<String, List<String>> children,
            java.util.Set<String> referenced, Map<String, Object> shapes) {
        var node = raw.get(path);
        if (node == null) return null;
        var childPaths = children.getOrDefault(path, List.of());
        // Explanation marks even a single fixed missing item as alternatives.
        // Identical terminal option sets are additive independent demands, not
        // AND inputs. At the projection's 16-option cap the set might be cut,
        // so do not infer equivalence. References retain distinct semantics.
        // Selected explanation subtrees can merge only with identical shapes.
        // This is a display
        // alias, never a change to the demand graph or executable operations.
        boolean equivalentMissingLeaf = node.explanation() && node.alternatives()
                && !node.needs().isEmpty() && node.needs().size() < 16 && node.needs().stream().noneMatch(ItemStack::isEmpty)
                && node.made().isEmpty() && node.recipes().isEmpty() && childPaths.isEmpty();
        boolean safe = !referenced.contains(path) && node.reference().isEmpty()
                && (!node.alternatives() || equivalentMissingLeaf);
        var childShapes = new ArrayList<Object>();
        for (String child : childPaths) {
            Object childShape = shape(child, raw, children, referenced, shapes);
            safe &= childShape != null;
            if (childShape != null) childShapes.add(List.of(child.substring(path.length()), childShape));
        }
        if (!safe) return null;
        Object result = List.of(new java.util.HashSet<>(CraftPlan.stackKeys(node.needs().stream().map(s -> s.copyWithCount(1)).toList())),
                CraftPlan.stackKeys(node.made().stream().map(s -> s.copyWithCount(1)).toList()),
                node.recipes(), node.explanation(), node.alternatives(), childShapes);
        shapes.put(path, result);
        return result;
    }

    private static void aliasTree(String path, String target, Map<String, List<String>> children, Map<String, String> aliases) {
        // Only display aliases: keep every real demand path and sum its counts
        // below. Choosing a merged node still constrains all represented paths.
        aliases.put(path, target);
        for (String child : children.getOrDefault(path, List.of()))
            aliasTree(child, target + child.substring(path.length()), children, aliases);
    }

    private int x(Cell c) { return getX() + c.x + panX; }
    private int y(Cell c) { return getY() + c.y + panY; }
    private static ItemStack icon(Cell c) {
        var values = !c.needs.isEmpty() ? c.needs : c.made;
        if (values.isEmpty()) return ItemStack.EMPTY;
        // A mixed intermediate remains one aggregate production node. Its
        // badge/tooltip explains the variants; the count must include all of
        // them, not just the first wood type represented by the display icon.
        return !c.alternatives && values.size() > 1
                ? values.getFirst().copyWithCount(values.stream().mapToInt(ItemStack::getCount).sum()) : values.getFirst();
    }
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
        boolean explanation, alternatives, deviation;
        int x, y;
        Cell(String id) { this.id = id; }
    }
}
