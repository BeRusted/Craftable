package org.berusted.craftable.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Bounded display geometry only: no recipe, quantity, or production identity is inferred here. */
final class PlanGraphRouting {
    private static final int MAX_CANDIDATES = 40;
    private static final int OVERLAP = 1, CONTACT = 2, CROSSING = 4;

    record Node(String id, int x, int y) {}
    record Edge(String source, String target) {}
    record Segment(int x1, int y1, int x2, int y2) {}
    record Link(String source, String target, List<Segment> segments, List<Segment> strokes) {
        Link { segments = List.copyOf(segments); strokes = List.copyOf(strokes); }
    }
    record Score(int hits, int overlaps, int contacts, int crossings, int outside, int bends, long length)
            implements Comparable<Score> {
        @Override public int compareTo(Score other) {
            int result = Integer.compare(hits, other.hits);
            if (result == 0) result = Integer.compare(overlaps, other.overlaps);
            if (result == 0) result = Integer.compare(contacts, other.contacts);
            // A visibly separated crossing is cheaper than a perimeter detour.
            // This avoids sending a short shared-material edge around the whole graph.
            if (result == 0) result = Long.compare(cost(), other.cost());
            if (result == 0) result = Integer.compare(outside, other.outside);
            if (result == 0) result = Integer.compare(crossings, other.crossings);
            if (result == 0) result = Integer.compare(bends, other.bends);
            if (result == 0) result = Long.compare(length, other.length);
            return result;
        }
        private long cost() { return length + bends * 12L + crossings * 8L + outside * 96L; }
    }

    static List<Link> route(List<Node> nodes, List<Edge> edges) {
        var byId = index(nodes);
        var order = new ArrayList<Integer>();
        var incoming = new HashMap<String, Integer>();
        var outgoing = new HashMap<String, Integer>();
        for (int i = 0; i < edges.size(); i++) {
            var edge = edges.get(i);
            if (!byId.containsKey(edge.source) || !byId.containsKey(edge.target))
                throw new IllegalArgumentException("Missing display endpoint: " + edge);
            incoming.merge(edge.target, 1, Integer::sum);
            outgoing.merge(edge.source, 1, Integer::sum);
            order.add(i);
        }
        // Establish short, local buses first. Return the caller's edge order unchanged.
        order.sort(Comparator.<Integer>comparingInt(i -> Math.abs(byId.get(edges.get(i).target).x
                - byId.get(edges.get(i).source).x)).thenComparingInt(i -> byId.get(edges.get(i).target).y)
                .thenComparingInt(i -> byId.get(edges.get(i).source).y)
                .thenComparing(i -> edges.get(i).source).thenComparing(i -> edges.get(i).target));
        var placed = new ArrayList<Link>();
        var result = new Link[edges.size()];
        int top = nodes.stream().mapToInt(Node::y).min().orElse(0);
        int bottom = nodes.stream().mapToInt(n -> n.y + 26).max().orElse(0);
        for (int i : order) {
            var edge = edges.get(i);
            var source = byId.get(edge.source); var target = byId.get(edge.target);
            Link best = null; Score bestScore = null;
            for (var segments : candidates(source, target, nodes, top, bottom,
                    outgoing.get(edge.source) > 1, incoming.get(edge.target) > 1)) {
                var link = new Link(edge.source, edge.target, segments, segments);
                var candidateScore = assess(nodes, byId, link, placed, top, bottom);
                if (bestScore == null || candidateScore.compareTo(bestScore) < 0) {
                    best = link; bestScore = candidateScore;
                }
            }
            result[i] = best; placed.add(best);
        }
        var logical = List.of(result);
        var rendered = new ArrayList<Link>();
        for (var link : logical) rendered.add(new Link(link.source, link.target, link.segments, strokes(link, logical)));
        return List.copyOf(rendered);
    }

    static Score score(List<Node> nodes, List<Link> links) {
        var byId = index(nodes);
        int top = nodes.stream().mapToInt(Node::y).min().orElse(0);
        int bottom = nodes.stream().mapToInt(n -> n.y + 26).max().orElse(0);
        int hits = 0, overlaps = 0, contacts = 0, crossings = 0, outside = 0, bends = 0;
        long length = 0;
        var previous = new ArrayList<Link>();
        for (var link : links) {
            var part = assess(nodes, byId, link, previous, top, bottom);
            hits += part.hits; overlaps += part.overlaps; contacts += part.contacts;
            crossings += part.crossings; outside += part.outside; bends += part.bends; length += part.length;
            previous.add(link);
        }
        return new Score(hits, overlaps, contacts, crossings, outside, bends, length);
    }

    private static Map<String, Node> index(List<Node> nodes) {
        var byId = new HashMap<String, Node>();
        for (var node : nodes) if (byId.put(node.id, node) != null)
            throw new IllegalArgumentException("Duplicate display node: " + node.id);
        return byId;
    }

    private static List<List<Segment>> candidates(Node source, Node target, List<Node> nodes, int top, int bottom,
            boolean sharedOutput, boolean sharedInput) {
        int x1 = source.x + 13, y1 = source.y + 13, x2 = target.x + 13, y2 = target.y + 13;
        var result = new ArrayList<List<Segment>>();
        if (y1 == y2) add(result, List.of(new Segment(x1, y1, x2, y2)));
        var bends = new LinkedHashSet<Integer>();
        bends.add(target.x - 12); bends.add((x1 + x2) / 2); bends.add(source.x + 38);
        bends.add(target.x - 8); bends.add(source.x + 34);
        for (int x : bends) if (x > source.x + 27 && x < target.x - 2)
            add(result, List.of(new Segment(x1, y1, x, y1), new Segment(x, y1, x, y2), new Segment(x, y2, x2, y2)));
        int left = source.x + 38, right = target.x - 12;
        var rows = new LinkedHashSet<Integer>();
        rows.add((y1 + y2) / 2); rows.add(y1 - 20); rows.add(y1 + 20); rows.add(y2 - 20); rows.add(y2 + 20);
        for (var node : nodes) { rows.add(node.y - 8); rows.add(node.y + 34); }
        var local = rows.stream().filter(y -> y >= top - 8 && y <= bottom + 8)
                .sorted(Comparator.<Integer>comparingInt(y -> Math.abs(y - y1) + Math.abs(y - y2))
                        .thenComparingInt(y -> y)).limit(14).toList();
        for (int y : local) add(result, dogleg(x1, y1, x2, y2, left, right, y));
        // A shared bus has one visible port, never a separate +/-6 entry per
        // edge (which made a same-row demand enter its consumer twice).
        // Keep hidden offsets for independent one-to-one edges: they separate
        // opposing buses without merging unrelated same-row horizontal lines.
        int[] starts = sharedOutput ? new int[]{0} : new int[]{-6, 6};
        int[] ends = sharedInput ? new int[]{0} : new int[]{-6, 6};
        for (int start : starts) for (int end : ends)
            for (int x : new int[]{target.x - 12, source.x + 38})
                if (x > source.x + 27 && x < target.x - 2)
                    add(result, List.of(new Segment(x1, y1, x1, y1 + start), new Segment(x1, y1 + start, x, y1 + start),
                            new Segment(x, y1 + start, x, y2 + end), new Segment(x, y2 + end, x2, y2 + end),
                            new Segment(x2, y2 + end, x2, y2)));
        // Perimeter lanes are the final candidates, not the first response to
        // one skipped column. Distinct lanes can avoid unrelated collinear wires.
        for (int lane = 0; lane < 4; lane++) {
            add(result, dogleg(x1, y1, x2, y2, left, right, top - 16 - lane * 8));
            add(result, dogleg(x1, y1, x2, y2, left, right, bottom + 16 + lane * 8));
        }
        if (result.isEmpty()) add(result, List.of(new Segment(x1, y1, x2, y1), new Segment(x2, y1, x2, y2)));
        return result;
    }

    private static List<Segment> dogleg(int x1, int y1, int x2, int y2, int left, int right, int y) {
        return List.of(new Segment(x1, y1, left, y1), new Segment(left, y1, left, y),
                new Segment(left, y, right, y), new Segment(right, y, right, y2), new Segment(right, y2, x2, y2));
    }

    private static void add(List<List<Segment>> candidates, List<Segment> raw) {
        if (candidates.size() >= MAX_CANDIDATES) return;
        var segments = new ArrayList<Segment>();
        for (var segment : raw) {
            if (segment.x1 == segment.x2 && segment.y1 == segment.y2) continue;
            if (!segments.isEmpty()) {
                var last = segments.getLast();
                if (last.x2 == segment.x1 && last.y2 == segment.y1
                        && (last.x1 == last.x2 && segment.x1 == segment.x2
                        || last.y1 == last.y2 && segment.y1 == segment.y2)) {
                    segments.set(segments.size() - 1, new Segment(last.x1, last.y1, segment.x2, segment.y2));
                    continue;
                }
            }
            segments.add(segment);
        }
        if (!candidates.contains(segments)) candidates.add(List.copyOf(segments));
    }

    private static Score assess(List<Node> nodes, Map<String, Node> byId, Link link, List<Link> previous, int top, int bottom) {
        int hits = 0, overlaps = 0, contacts = 0, crossings = 0, outside = 0;
        var source = byId.get(link.source); var target = byId.get(link.target);
        if (source == null || target == null || source.x >= target.x) hits++;
        for (var node : nodes) if (!node.id.equals(link.source) && !node.id.equals(link.target)) {
            for (var segment : link.segments) if (intersects(segment, node.x - 2, node.y - 2, node.x + 27, node.y + 27)) {
                hits++; break;
            }
        }
        for (var other : previous) if (!shared(link, other)) {
            int relation = relation(link, other);
            if ((relation & OVERLAP) != 0) overlaps++;
            if ((relation & CONTACT) != 0) contacts++;
            if ((relation & CROSSING) != 0) crossings++;
        }
        long length = 0;
        for (var segment : link.segments) {
            if (segment.x1 != segment.x2 && segment.y1 != segment.y2) hits++;
            length += Math.abs((long) segment.x2 - segment.x1) + Math.abs((long) segment.y2 - segment.y1);
            if (Math.min(segment.y1, segment.y2) < top - 8 || Math.max(segment.y1, segment.y2) > bottom + 8) outside = 1;
        }
        return new Score(hits, overlaps, contacts, crossings, outside, Math.max(0, link.segments.size() - 1), length);
    }

    private static boolean shared(Link a, Link b) {
        // Consecutive edges meet at the same production icon as well. With
        // left-to-right routing their common endpoint is a true junction.
        return a.source.equals(b.source) || a.target.equals(b.target)
                || a.source.equals(b.target) || a.target.equals(b.source);
    }

    private static int relation(Link a, Link b) {
        int relation = 0;
        for (var x : a.segments) for (var y : b.segments) {
            if (vertical(x) == vertical(y)) {
                int distance = Math.abs(vertical(x) ? x.x1 - y.x1 : x.y1 - y.y1);
                int start = Math.max(vertical(x) ? Math.min(x.y1, x.y2) : Math.min(x.x1, x.x2),
                        vertical(y) ? Math.min(y.y1, y.y2) : Math.min(y.x1, y.x2));
                int end = Math.min(vertical(x) ? Math.max(x.y1, x.y2) : Math.max(x.x1, x.x2),
                        vertical(y) ? Math.max(y.y1, y.y2) : Math.max(y.x1, y.x2));
                // Distinct center lines can still touch after the one-pixel
                // outline. Do not accept two adjacent cores as a separated bus.
                if (distance > 0 && distance <= 2 && start < end) relation |= CONTACT;
            }
            if (!intersects(x, Math.min(y.x1, y.x2), Math.min(y.y1, y.y2), Math.max(y.x1, y.x2), Math.max(y.y1, y.y2))) continue;
            if (vertical(x) == vertical(y)) {
                int start = Math.max(vertical(x) ? Math.min(x.y1, x.y2) : Math.min(x.x1, x.x2),
                        vertical(y) ? Math.min(y.y1, y.y2) : Math.min(y.x1, y.x2));
                int end = Math.min(vertical(x) ? Math.max(x.y1, x.y2) : Math.max(x.x1, x.x2),
                        vertical(y) ? Math.max(y.y1, y.y2) : Math.max(y.x1, y.x2));
                relation |= start < end ? OVERLAP : CONTACT;
            } else {
                var v = vertical(x) ? x : y; var h = vertical(x) ? y : x;
                boolean interior = v.x1 > Math.min(h.x1, h.x2) + 2 && v.x1 < Math.max(h.x1, h.x2) - 2
                        && h.y1 > Math.min(v.y1, v.y2) + 2 && h.y1 < Math.max(v.y1, v.y2) - 2;
                relation |= interior ? CROSSING : CONTACT;
            }
        }
        return relation;
    }

    private static boolean vertical(Segment segment) { return segment.x1 == segment.x2; }
    private static boolean intersects(Segment s, int x1, int y1, int x2, int y2) {
        return Math.max(s.x1, s.x2) >= x1 && Math.min(s.x1, s.x2) <= x2
                && Math.max(s.y1, s.y2) >= y1 && Math.min(s.y1, s.y2) <= y2;
    }

    private static List<Segment> strokes(Link link, List<Link> links) {
        var strokes = new ArrayList<Segment>();
        for (var segment : link.segments) {
            var gaps = new java.util.TreeSet<Integer>();
            for (var other : links) if (other != link && !shared(link, other)) for (var crossed : other.segments) {
                if (!intersects(segment, Math.min(crossed.x1, crossed.x2), Math.min(crossed.y1, crossed.y2),
                        Math.max(crossed.x1, crossed.x2), Math.max(crossed.y1, crossed.y2))) continue;
                if (vertical(segment) && !vertical(crossed)) gaps.add(crossed.y1);
                // Degenerate corner/endpoint contacts also need a visible break;
                // they must not turn back into a T when drawing black outlines.
                else if (vertical(segment) == vertical(crossed)) {
                    int at = vertical(segment) ? crossed.y1 : crossed.x1;
                    int end = vertical(segment) ? crossed.y2 : crossed.x2;
                    int low = vertical(segment) ? Math.min(segment.y1, segment.y2) : Math.min(segment.x1, segment.x2);
                    int high = vertical(segment) ? Math.max(segment.y1, segment.y2) : Math.max(segment.x1, segment.x2);
                    if (Math.max(low, Math.min(at, end)) == Math.min(high, Math.max(at, end)))
                        gaps.add(Math.max(low, Math.min(at, end)));
                }
            }
            if (gaps.isEmpty()) { strokes.add(segment); continue; }
            int low = vertical(segment) ? Math.min(segment.y1, segment.y2) : Math.min(segment.x1, segment.x2);
            int high = vertical(segment) ? Math.max(segment.y1, segment.y2) : Math.max(segment.x1, segment.x2);
            int next = low;
            for (int gap : gaps) {
                // Five omitted core pixels leave three pixels after the one-pixel
                // outline on each end. Rendering must use these for BOTH passes.
                if (next <= gap - 3) strokes.add(piece(segment, next, gap - 3));
                next = Math.max(next, gap + 3);
            }
            if (next <= high) strokes.add(piece(segment, next, high));
        }
        return List.copyOf(strokes);
    }

    private static Segment piece(Segment original, int low, int high) {
        return vertical(original) ? new Segment(original.x1, low, original.x1, high)
                : new Segment(low, original.y1, high, original.y1);
    }
}
