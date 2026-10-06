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
import org.berusted.craftable.client.PlanGraphRouting.Link;

/** Small bounded layout/renderer. It consumes a projection and never computes craftability. */
final class PlanGraphWidget extends AbstractWidget {
    private final List<Cell> cells = new ArrayList<>();
    private final Map<String, Cell> byId = new HashMap<>();
    private final List<Link> connections = new ArrayList<>();
    private final Consumer<List<String>> select;
    private final java.util.Set<String> collapsed = new java.util.HashSet<>();
    private int focusedCell;
    private int panX, panY;
    private PlanView view;
    private Map<String, Object> alternativeGroups = Map.of();

    PlanGraphWidget(int x, int y, int width, int height, Consumer<List<String>> select) {
        super(x, y, width, height, Component.translatable("screen.craftable.plan.graph"));
        this.select = select;
    }

    void show(PlanView view) {
        show(view, this.view == view ? alternativeGroups : Map.of());
    }

    void show(PlanView view, Map<String, Object> alternativeGroups) {
        boolean updating = this.view != null;
        int previousX = panX, previousY = panY;
        String previousFocus = cells.isEmpty() ? "" : cells.get(focusedCell).id;
        this.view = view;
        this.alternativeGroups = Map.copyOf(alternativeGroups);
        layout();
        if (updating) {
            // Resource renewal updates the projection, not the player's view.
            panX = previousX; panY = previousY;
            focus(previousFocus);
        }
    }

    private void layout() {
        cells.clear();
        byId.clear();
        connections.clear();
        if (view == null) return;
        var raw = new LinkedHashMap<String, PlanView.Node>();
        view.nodes().stream().sorted(java.util.Comparator.comparing(PlanView.Node::path))
                .forEach(n -> raw.put(n.path(), n));
        var grouped = projectMaterials(raw);
        mergeSiblingDemands(grouped);
        collapsed.retainAll(grouped.keySet());
        layer(grouped);
        for (var cell : grouped.values()) for (String child : cell.children)
            if (grouped.containsKey(child)) grouped.get(child).parents.add(cell.id);
        arrange(grouped);
        if (!cells.isEmpty()) {
            int min = Math.min(cells.stream().mapToInt(c -> c.y).min().orElse(0), connections.stream()
                    .flatMap(l -> l.segments().stream()).mapToInt(s -> Math.min(s.y1(), s.y2())).min().orElse(0));
            int max = Math.max(cells.stream().mapToInt(c -> c.y + 26).max().orElse(0), connections.stream()
                    .flatMap(l -> l.segments().stream()).mapToInt(s -> Math.max(s.y1(), s.y2())).max().orElse(0));
            // Center the occupied bounds, including the icon height, rather
            // than the root's top edge (which clips lower leaves at GUI scale 2).
            panY = (height - (max - min)) / 2 - min;
        }
        focusedCell = Math.min(focusedCell, Math.max(0, cells.size() - 1));
    }

    /** Paths are player choices, not production identities. Across batches a
     * single path can produce several exact wood variants. Split their display
     * values before resolving origins so one icon never receives another wood's
     * quantity or upstream inputs. The reviewed operations remain unchanged. */
    private Map<String, Cell> projectMaterials(Map<String, PlanView.Node> raw) {
        var grouped = new LinkedHashMap<String, Cell>();
        var variants = new LinkedHashMap<String, List<Cell>>();
        var branches = raw.keySet().stream().map(PlanGraphWidget::parent).collect(java.util.stream.Collectors.toSet());
        int extra = 0;
        for (var node : raw.values()) {
            var materials = new ArrayList<ItemStack>();
            // The first actual production is the route's baseline. Demand
            // insertion order alone can differ between consumers of that batch.
            for (var stack : node.made()) addMaterial(materials, stack);
            for (var stack : node.needs()) addMaterial(materials, stack);
            boolean split = !node.alternatives() && materials.size() > 1
                    && (!node.explanation() || !branches.contains(node.path()))
                    && raw.size() + extra + materials.size() - 1 <= PlanView.MAX_NODES;
            int count = split ? materials.size() : 1;
            extra += count - 1;
            var pathCells = new ArrayList<Cell>();
            for (int i = 0; i < count; i++) {
                var cell = new Cell(node.path() + (i == 0 ? "" : ":" + i));
                cell.owner = parent(node.path()); cell.paths.add(node.path());
                cell.material = materials.size() == 1 || split ? materials.get(i).copyWithCount(1) : ItemStack.EMPTY;
                node.needs().stream().filter(s -> !split || sameMaterial(cell, s)).forEach(s -> merge(cell.needs, s));
                node.made().stream().filter(s -> !split || sameMaterial(cell, s)).forEach(s -> merge(cell.made, s));
                cell.explanation = node.explanation(); cell.alternatives = node.alternatives();
                cell.alternativesKey = alternativeGroups.get(node.path());
                cell.deviation = split && i > 0;
                for (int op = 0; op < view.operations().size(); op++) {
                    var operation = view.operations().get(op);
                    if (operation.path().equals(node.path()) && (!split || sameMaterial(cell, operation.output())))
                        cell.operations.add(op);
                }
                cell.actualRoute = !cell.operations.isEmpty();
                if (split && !node.made().isEmpty()) {
                    for (var op : view.operations()) if (op.path().equals(node.path()) && sameMaterial(cell, op.output()))
                        if (!cell.recipes.contains(op.recipe().toString())) cell.recipes.add(op.recipe().toString());
                } else node.recipes().forEach(r -> cell.recipes.add(r.toString()));
                grouped.put(cell.id, cell); pathCells.add(cell);
            }
            variants.put(node.path(), pathCells);
        }
        // A mixed producer only owns the input variants of its matching real
        // operations. Attaching every raw child to every variant invents edges.
        for (var node : raw.values()) {
            var parents = variants.get(parent(node.path()));
            if (parents == null) continue;
            int slot = Integer.parseInt(node.path().substring(node.path().lastIndexOf('.') + 1));
            for (var consumer : parents) for (var input : variants.get(node.path())) {
                boolean owns = parents.size() == 1 || view.operations().stream()
                        .anyMatch(op -> op.path().equals(parent(node.path())) && sameMaterial(consumer, op.output())
                                && slot < op.inputs().size() && sameMaterial(input, op.inputs().get(slot)));
                if (owns && !consumer.children.contains(input.id)) {
                    consumer.children.add(input.id);
                    // Keep the material-specific producer owner, not the raw
                    // path's first wood variant, for the later sibling pass.
                    if (parents.size() > 1) input.owner = consumer.id;
                }
            }
        }
        var references = new LinkedHashMap<String, java.util.Set<String>>();
        for (var op : view.operations()) for (int slot = 0; slot < op.inputs().size(); slot++) {
            int origin = op.inputOrigins().get(slot);
            if (origin < 0 || op.inputs().get(slot).isEmpty()) continue;
            var producerOp = view.operations().get(origin);
            var demand = matching(variants.get(op.path() + "." + slot), op.inputs().get(slot));
            var producer = matching(variants.get(producerOp.path()), producerOp.output());
            addReference(references, demand, producer);
        }
        for (var node : raw.values()) if (!node.reference().isEmpty())
            for (var demand : variants.get(node.path()))
                addReference(references, demand, matching(variants.get(node.reference()), demand.material));
        var aliases = new HashMap<String, String>();
        references.forEach((id, producers) -> {
            var demand = grouped.get(id); demand.sharedBatch = true;
            if (producers.size() == 1 && demand.made.isEmpty()) aliases.put(id, producers.iterator().next());
            for (String producer : producers) grouped.get(producer).sharedBatch = true;
        });
        for (var cell : grouped.values())
            cell.supply = aliases.containsKey(cell.id) ? resolve(aliases.get(cell.id), aliases)
                    : references.values().stream().anyMatch(ids -> ids.contains(cell.id)) ? cell.id : "";
        for (var reference : references.entrySet()) {
            var demand = grouped.get(reference.getKey());
            for (String id : reference.getValue()) {
                var producer = grouped.get(resolve(id, aliases));
                if (producer == null || producer == demand) continue;
                // Consumers can encounter shared materials in opposite order
                // across batches. Their local insertion order is not a new
                // baseline; an exact single source owns that decision.
                if (demand.made.isEmpty() && reference.getValue().size() == 1) demand.deviation = producer.deviation;
                else demand.deviation |= producer.deviation;
                if (producer.children.isEmpty()) continue;
                producer.recipes.forEach(r -> { if (!demand.recipes.contains(r)) demand.recipes.add(r); });
                demand.actualRoute |= producer.actualRoute;
                // Reuse existing input cells, never their quantities. Each
                // extraction/producing batch is counted exactly once.
                for (String input : producer.children) if (!demand.children.contains(input)
                        && !reaches(input, demand.id, grouped, new java.util.HashSet<>())) demand.children.add(input);
            }
        }
        mergeSharedInventory(grouped, references);
        return grouped;
    }

    /** Reused production can cross demand paths between batches. Its direct
     * inventory inputs are one resource pool, not independent producers whose
     * wires can be interpreted as each supplying the other's full quantity.
     * Only a proved same-output/same-recipe reference group may pool those
     * leaves. Generated inputs, OR/explanation leaves and unrelated routes
     * retain their own identities and quantities. */
    private void mergeSharedInventory(Map<String, Cell> grouped, Map<String, java.util.Set<String>> references) {
        var peers = new LinkedHashMap<String, java.util.Set<String>>();
        references.forEach((id, producers) -> {
            var demand = grouped.get(id);
            for (String producerId : producers) {
                var producer = grouped.get(producerId);
                if (demand == null || producer == null || demand.material.isEmpty() || producer.material.isEmpty()
                        || demand.explanation || producer.explanation || demand.alternatives || producer.alternatives
                        || !demand.actualRoute || !producer.actualRoute || demand.recipes.size() != 1
                        || !demand.recipes.equals(producer.recipes) || !sameMaterial(demand, producer.material)) continue;
                peers.computeIfAbsent(id, ignored -> new java.util.LinkedHashSet<>()).add(producerId);
                peers.computeIfAbsent(producerId, ignored -> new java.util.LinkedHashSet<>()).add(id);
            }
        });
        var seen = new java.util.HashSet<String>();
        var aliases = new HashMap<String, String>();
        var inventory = new HashMap<String, Boolean>();
        for (String start : peers.keySet()) {
            if (!seen.add(start)) continue;
            var pending = new java.util.ArrayDeque<String>(); pending.add(start);
            var sources = new java.util.LinkedHashSet<String>();
            while (!pending.isEmpty()) {
                String id = pending.removeFirst();
                var cell = grouped.get(id);
                if (cell != null) for (String child : cell.children) {
                    var source = grouped.get(resolve(child, aliases));
                    if (source != null && inventory.computeIfAbsent(source.id, ignored -> directInventory(source))) sources.add(source.id);
                }
                for (String peer : peers.getOrDefault(id, java.util.Set.of())) if (seen.add(peer)) pending.add(peer);
            }
            var first = new HashMap<Object, Cell>();
            for (String sourceId : sources) {
                var source = grouped.get(sourceId);
                Object key = CraftPlan.stackKeys(List.of(source.needs.getFirst().copyWithCount(1)));
                var previous = first.putIfAbsent(key, source);
                if (previous == null) continue;
                source.paths.forEach(path -> { if (!previous.paths.contains(path)) previous.paths.add(path); });
                source.needs.forEach(stack -> merge(previous.needs, stack));
                aliases.put(source.id, previous.id); grouped.remove(source.id);
            }
        }
        if (aliases.isEmpty()) return;
        for (var cell : grouped.values()) {
            cell.owner = resolve(cell.owner, aliases);
            var children = cell.children.stream().map(id -> resolve(id, aliases)).distinct().toList();
            cell.children.clear(); cell.children.addAll(children);
        }
    }

    private boolean directInventory(Cell cell) {
        if (cell.explanation || cell.alternatives || cell.material.isEmpty() || cell.needs.size() != 1
                || !cell.made.isEmpty() || !cell.recipes.isEmpty() || !cell.children.isEmpty() || !cell.operations.isEmpty()) return false;
        boolean found = false;
        for (String path : cell.paths) {
            String owner = parent(path);
            if (owner.isEmpty()) return false;
            int slot = Integer.parseInt(path.substring(path.lastIndexOf('.') + 1));
            for (var operation : view.operations()) if (operation.path().equals(owner)
                    && slot < operation.inputs().size() && sameMaterial(cell, operation.inputs().get(slot))) {
                found = true;
                if (operation.inputOrigins().get(slot) >= 0) return false;
            }
        }
        return found;
    }

    private static void addMaterial(List<ItemStack> materials, ItemStack stack) {
        if (materials.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s, stack))) materials.add(stack.copyWithCount(1));
    }
    private static boolean sameMaterial(Cell cell, ItemStack stack) {
        return cell.material.isEmpty() || ItemStack.isSameItemSameComponents(cell.material, stack);
    }
    private static Cell matching(List<Cell> cells, ItemStack stack) {
        return cells == null ? null : cells.stream().filter(c -> sameMaterial(c, stack)).findFirst().orElse(null);
    }
    private static void addReference(Map<String, java.util.Set<String>> references, Cell demand, Cell producer) {
        if (demand == null || producer == null || demand == producer) return;
        String source = producer.paths.getFirst(), target = demand.paths.getFirst();
        if (source.startsWith(target + ".") || target.startsWith(source + ".")) return;
        references.computeIfAbsent(demand.id, ignored -> new java.util.LinkedHashSet<>()).add(producer.id);
    }

    private void separateRows() {
        // Mixed supplies can have identical consumers and therefore identical
        // desired centers. Keep every material visible/clickable, while keeping
        // the occupied column centered near those desired positions.
        var columns = new java.util.TreeMap<Integer, List<Cell>>();
        for (var cell : cells) columns.computeIfAbsent(cell.x, ignored -> new ArrayList<>()).add(cell);
        for (var column : columns.values()) {
            column.sort(java.util.Comparator.<Cell>comparingInt(c -> c.y).thenComparing(c -> c.id));
            int desired = column.stream().mapToInt(c -> c.y).sum();
            int next = column.getFirst().y;
            for (var cell : column) { cell.y = Math.max(cell.y, next); next = cell.y + 40; }
            int shift = (desired - column.stream().mapToInt(c -> c.y).sum()) / column.size();
            column.forEach(c -> c.y += shift);
        }
    }

    private List<List<Cell>> sharedDemands() {
        var groups = new LinkedHashMap<Object, List<Cell>>();
        for (var cell : cells) if (!cell.supply.isEmpty() && cell.needs.size() == 1 && !cell.alternatives
                && !collapsed.contains(cell.id) && cell.recipes.size() == 1 && !cell.children.isEmpty()
                && cell.children.stream().allMatch(byId::containsKey)) {
            var key = List.of(cell.supply, cell.recipes, CraftPlan.stackKeys(List.of(cell.needs.getFirst().copyWithCount(1))));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cell);
        }
        return groups.values().stream().filter(g -> g.size() >= 2 && g.size() <= 8
                && g.stream().allMatch(c -> c.children.equals(g.getFirst().children))).limit(8).toList();
    }

    private void arrange(Map<String, Cell> grouped) {
        grouped.values().forEach(c -> c.column = c.depth);
        position(grouped, List.of());
        var groups = sharedDemands();
        var best = geometryScore(groups);
        var saved = positions();
        for (var group : groups) {
            int column = group.stream().mapToInt(c -> c.depth).max().orElseThrow();
            group.forEach(c -> c.column = column);
        }
        position(grouped, groups);
        var aligned = geometryScore(groups);
        if (aligned.compareTo(best) <= 0) best = aligned;
        else restore(saved);
        // Bounded sibling swaps (adjacent first), not a global graph
        // optimizer. Each trial uses exactly the same positioning and router.
        int trials = 0, limit = cells.size() <= 64 ? 16 : 4;
        for (var parent : List.copyOf(cells)) {
            if (collapsed.contains(parent.id) || parent.children.size() > 8) continue;
            for (int span = 1; span < parent.children.size() && trials < limit; span++)
            for (int i = 0; i + span < parent.children.size() && trials < limit; i++, trials++) {
                saved = positions();
                java.util.Collections.swap(parent.children, i, i + span);
                position(grouped, groups);
                var score = geometryScore(groups);
                if (score.compareTo(best) < 0) best = score;
                else {
                    java.util.Collections.swap(parent.children, i, i + span);
                    restore(saved);
                }
            }
            if (trials >= limit) break;
        }
    }

    private void position(Map<String, Cell> grouped, List<List<Cell>> groups) {
        cells.clear(); byId.clear();
        place("0", grouped, new java.util.HashSet<>(), new int[]{0});
        cells.forEach(c -> byId.put(c.id, c));
        // Pack each column only once, then settle unique one-input chains.
        // Shared inputs remain between consumers; they never consume a row
        // merely because they have been visited before.
        separateRows();
        for (var group : groups) {
            if (group.stream().map(c -> c.x).distinct().count() != 1) continue;
            int x = group.getFirst().x;
            var column = cells.stream().filter(c -> c.x == x)
                    .sorted(java.util.Comparator.<Cell>comparingInt(c -> c.y).thenComparing(c -> c.id)).toList();
            var members = group.stream().sorted(java.util.Comparator.<Cell>comparingInt(c -> c.y).thenComparing(c -> c.id)).toList();
            int end = members.stream().mapToInt(c -> c.y).max().orElseThrow();
            var ordered = new ArrayList<>(column.stream().filter(c -> !group.contains(c)).toList());
            int at = (int) ordered.stream().filter(c -> c.y <= end).count();
            ordered.addAll(at, members);
            int y = column.getFirst().y;
            for (var c : ordered) { c.y = y; y += 40; }
        }
        for (int pass = 0; pass < 2; pass++) for (var c : cells) {
            if (c.parents.size() > 1) moveRow(c, (int) c.parents.stream().map(byId::get)
                    .filter(java.util.Objects::nonNull).mapToInt(p -> p.y).average().orElse(c.y));
            if (!collapsed.contains(c.id) && c.children.size() > 1) {
                var ys = c.children.stream().map(byId::get).filter(java.util.Objects::nonNull)
                        .mapToInt(child -> child.y).sorted().toArray();
                if (ys.length > 1) moveRow(c, (ys[(ys.length - 1) / 2] + ys[ys.length / 2]) / 2);
            }
            if (!collapsed.contains(c.id) && c.children.size() == 1) {
                var child = byId.get(c.children.getFirst());
                if (child != null && child.parents.size() == 1) {
                    if (!moveRow(child, c.y)) moveRow(c, child.y);
                }
            }
        }
        routeConnections();
    }

    private boolean moveRow(Cell cell, int y) {
        if (cells.stream().anyMatch(c -> c != cell && c.x == cell.x && Math.abs(c.y - y) < 40)) return false;
        cell.y = y; return true;
    }

    private record LayoutSnapshot(List<Cell> order, List<int[]> positions, List<Link> connections) {}

    private LayoutSnapshot positions() {
        // Save keyboard traversal too: a rejected trial cannot reorder focus.
        return new LayoutSnapshot(List.copyOf(cells), cells.stream().sorted(java.util.Comparator.comparing(c -> c.id))
                .map(c -> new int[]{c.x, c.y, c.column}).toList(), List.copyOf(connections));
    }

    private void restore(LayoutSnapshot saved) {
        cells.clear(); cells.addAll(saved.order());
        var ordered = cells.stream().sorted(java.util.Comparator.comparing(c -> c.id)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            var c = ordered.get(i); var p = saved.positions().get(i);
            c.x = p[0]; c.y = p[1]; c.column = p[2];
        }
        connections.clear(); connections.addAll(saved.connections());
    }

    private SceneScore geometryScore(List<List<Cell>> groups) {
        var score = PlanGraphRouting.score(routingNodes(), connections);
        int overlaps = 0;
        for (int i = 0; i < cells.size(); i++) for (int j = i + 1; j < cells.size(); j++) {
            var a = cells.get(i); var b = cells.get(j);
            if (Math.abs(a.x - b.x) < 28 && Math.abs(a.y - b.y) < 28) overlaps++;
        }
        long separation = 0;
        for (var group : groups) {
            int left = group.stream().mapToInt(c -> c.x).min().orElseThrow();
            int low = group.stream().mapToInt(c -> c.y).min().orElseThrow();
            int high = group.stream().mapToInt(c -> c.y).max().orElseThrow();
            separation += group.stream().mapToInt(c -> c.x - left).sum() * 4L
                    + Math.max(0, high - low - (group.size() - 1) * 40) * 2L;
        }
        long chain = 0;
        for (var c : cells) if (c.children.size() == 1 && !collapsed.contains(c.id)) {
            var child = byId.get(c.children.getFirst());
            if (child != null && child.parents.size() == 1) chain += Math.abs(child.y - c.y) * 2L;
        }
        return new SceneScore(overlaps + score.hits(), score.overlaps(), score.contacts(),
                score.outside() * 160L + score.length() + score.bends() * 12L + score.crossings() * 8L + separation + chain);
    }

    private record SceneScore(int hits, int overlaps, int contacts, long cost) implements Comparable<SceneScore> {
        @Override public int compareTo(SceneScore other) {
            int value = Integer.compare(hits, other.hits);
            if (value == 0) value = Integer.compare(overlaps, other.overlaps);
            if (value == 0) value = Integer.compare(contacts, other.contacts);
            return value == 0 ? Long.compare(cost, other.cost) : value;
        }
    }

    private int place(String id, Map<String, Cell> grouped, java.util.Set<String> seen, int[] row) {
        var cell = grouped.get(id);
        if (cell == null) return row[0] * 40;
        if (!seen.add(id)) return cell.y;
        cell.x = width - 42 - cell.column * 64;
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

    private static boolean reaches(String from, String target, Map<String, Cell> grouped, java.util.Set<String> seen) {
        if (from.equals(target)) return true;
        var cell = grouped.get(from);
        if (cell == null || !seen.add(from)) return false;
        return cell.children.stream().anyMatch(c -> reaches(c, target, grouped, seen));
    }

    private static void layer(Map<String, Cell> grouped) {
        // Longest dependency depth makes every source lie to the left of every
        // consumer, including references crossing the original tree depths.
        // Display alignment cannot change graph depth or dependencies.
        for (int pass = 0; pass < grouped.size(); pass++) {
            boolean changed = false;
            for (var cell : grouped.values()) for (String id : cell.children) {
                var child = grouped.get(id);
                if (child != null && child.depth <= cell.depth) { child.depth = cell.depth + 1; changed = true; }
            }
            if (!changed) return;
        }
    }

    private static void mergeSiblingDemands(Map<String, Cell> grouped) {
        // Merge demand quantities only, after resolving shared supply edges.
        // Never recursively sum a shared source or derive new crafting costs.
        var aliases = new HashMap<String, String>();
        for (int pass = 0; pass < PlanView.MAX_NODES; pass++) {
            var shapes = new HashMap<String, Object>();
            var first = new HashMap<Object, Cell>();
            var removed = new java.util.HashSet<String>();
            for (var cell : grouped.values()) {
                Object shape = demandShape(cell.id, grouped, shapes, new java.util.HashSet<>());
                if (cell.id.equals("0") || shape == null) continue;
                var previous = first.putIfAbsent(List.of(resolve(cell.owner, aliases), shape), cell);
                if (previous == null) continue;
                if (reaches(cell.id, previous.id, grouped, new java.util.HashSet<>())
                        || reaches(previous.id, cell.id, grouped, new java.util.HashSet<>())) continue;
                aliases.put(cell.id, previous.id); removed.add(cell.id);
                previous.paths.addAll(cell.paths);
                cell.operations.forEach(op -> { if (!previous.operations.contains(op)) previous.operations.add(op); });
                cell.needs.forEach(s -> merge(previous.needs, s));
                cell.made.forEach(s -> merge(previous.made, s));
                previous.sharedBatch |= cell.sharedBatch;
                previous.deviation |= cell.deviation;
                previous.actualRoute |= cell.actualRoute;
                if (!previous.supply.equals(cell.supply)) previous.supply = "";
                for (String child : cell.children) if (!previous.children.contains(child)) previous.children.add(child);
            }
            if (removed.isEmpty()) return;
            removed.forEach(grouped::remove);
            for (var cell : grouped.values()) {
                cell.owner = resolve(cell.owner, aliases);
                cell.supply = resolve(cell.supply, aliases);
                var children = cell.children.stream().map(c -> resolve(c, aliases)).distinct().toList();
                cell.children.clear(); cell.children.addAll(children);
            }
        }
    }

    private static Object demandShape(String id, Map<String, Cell> grouped, Map<String, Object> shapes,
            java.util.Set<String> active) {
        if (shapes.containsKey(id)) return shapes.get(id);
        var cell = grouped.get(id);
        if (cell == null || !active.add(id) || cell.needs.isEmpty()
                || !cell.alternatives && cell.needs.size() > 1
                || cell.alternatives && (!cell.children.isEmpty() || !cell.recipes.isEmpty()
                        || !cell.made.isEmpty() || cell.needs.size() >= 16 && cell.alternativesKey == null)) return null;
        var children = new ArrayList<Object>();
        for (String child : cell.children) {
            Object shape = demandShape(child, grouped, shapes, active);
            if (shape == null) { active.remove(id); return null; }
            children.add(shape);
        }
        active.remove(id);
        Object materials = cell.alternatives && cell.alternativesKey != null ? cell.alternativesKey
                : new java.util.HashSet<>(CraftPlan.stackKeys(cell.needs.stream().map(s -> s.copyWithCount(1)).toList()));
        // A proved single production route can reach the same input through
        // several batches/consumer origins. Normalize those duplicate shapes,
        // not quantities. Explanations and mixed recipes keep multiplicity.
        Object inputs = cell.actualRoute && !cell.explanation && !cell.alternatives && cell.recipes.size() == 1
                ? new java.util.HashSet<>(children) : children;
        Object result = List.of(materials, cell.recipes, cell.explanation, cell.alternatives, inputs);
        shapes.put(id, result);
        return result;
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
            if (additionalMaterial(c))
                g.drawString(font, "!", x + 22, y - 3, 0xFFFFFF55, false);
            if (collapsed.contains(c.id)) g.drawString(font, "+", x - 7, y + 6, 0xFFFFFFFF, false);
            g.pose().popPose();
        }
        g.disableScissor();
        if (hovered != null) g.renderComponentTooltip(font, tooltip(hovered), mouseX, mouseY);
    }

    private void drawConnections(GuiGraphics g, boolean outline) {
        int pad = outline ? 1 : 0;
        for (var link : connections) for (var s : link.strokes()) {
            int x = getX() + panX, y = getY() + panY;
            g.fill(x + Math.min(s.x1(), s.x2()) - pad, y + Math.min(s.y1(), s.y2()) - pad,
                    x + Math.max(s.x1(), s.x2()) + pad + 1, y + Math.max(s.y1(), s.y2()) + pad + 1,
                    outline ? 0xFF000000 : 0xFFFFFFFF);
        }
    }

    private void routeConnections() {
        var edges = new ArrayList<PlanGraphRouting.Edge>();
        for (var cell : cells) if (!collapsed.contains(cell.id)) for (String id : cell.children) {
            var child = byId.get(id);
            if (child == null) continue;
            edges.add(new PlanGraphRouting.Edge(child.id, cell.id));
        }
        connections.clear(); connections.addAll(PlanGraphRouting.route(routingNodes(), edges));
    }

    private List<PlanGraphRouting.Node> routingNodes() {
        return cells.stream().map(c -> new PlanGraphRouting.Node(c.id, c.x, c.y)).toList();
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
            focus(id);
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
        if (cell.sharedBatch) lines.add(Component.translatable("screen.craftable.plan.shared_batch"));
        if (additionalMaterial(cell)) lines.add(Component.translatable("screen.craftable.plan.additional_material"));
        if (!cell.explanation && !cell.sharedBatch && cell.made.isEmpty() && cell.recipes.isEmpty())
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
        for (int index : cell.operations) {
            var op = view.operations().get(index);
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

    private int x(Cell c) { return getX() + c.x + panX; }
    private int y(Cell c) { return getY() + c.y + panY; }
    private void focus(String id) {
        for (int i = 0; i < cells.size(); i++) if (cells.get(i).id.equals(id)) { focusedCell = i; return; }
        focusedCell = Math.min(focusedCell, Math.max(0, cells.size() - 1));
    }
    private static ItemStack icon(Cell c) {
        var values = !c.needs.isEmpty() ? c.needs : c.made;
        if (values.isEmpty()) return ItemStack.EMPTY;
        // If the display cap prevents splitting, the first material retains
        // its own quantity. Never label a mixed total as one wood variant.
        return values.getFirst();
    }
    private static boolean additionalMaterial(Cell cell) {
        return cell.deviation || !cell.id.equals("0") && cell.recipes.size() > 1
                || cell.needs.size() > 1 && !cell.alternatives;
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
        final List<Integer> operations = new ArrayList<>();
        final java.util.Set<String> parents = new java.util.HashSet<>();
        final List<ItemStack> needs = new ArrayList<>(), made = new ArrayList<>();
        ItemStack material = ItemStack.EMPTY;
        boolean explanation, alternatives, deviation, sharedBatch, actualRoute;
        String owner = "", supply = "";
        Object alternativesKey;
        int x, y, depth, column;
        Cell(String id) { this.id = id; }
    }
}
