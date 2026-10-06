package org.berusted.craftable.client;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanGraphRoutingTest {
    @Test void sameRowUsesOneStraightSegment() {
        var nodes = List.of(node("a", 0, 0), node("b", 64, 0));
        var links = PlanGraphRouting.route(nodes, List.of(edge("a", "b")));
        assertEquals(List.of(segment(13, 13, 77, 13)), links.getFirst().segments());
        assertEquals(links.getFirst().segments(), links.getFirst().strokes());
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.bends()); assertEquals(64, score.length());
    }

    @Test void sameTargetSharesABusWithoutFalseCrossings() {
        var nodes = List.of(node("a", 0, 0), node("b", 0, 40), node("c", 64, 20));
        var links = PlanGraphRouting.route(nodes, List.of(edge("a", "c"), edge("b", "c")));
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.overlaps()); assertEquals(0, score.contacts());
        assertEquals(0, score.crossings()); assertEquals(0, score.outside());
        links.forEach(link -> assertEquals(link.segments(), link.strokes(), "A true bus was cut"));
    }

    @Test void capturedRepeaterInputsHaveOneVisibleConsumerEntry() {
        // Existing m4-sibling-repeater-real capture, translated so the block is
        // column zero and the stick is row zero. The old per-edge +/-6 target
        // ports left both y=87 and y=93 horizontal strokes entering the root.
        var nodes = List.of(node("block", 0, 60), node("stick", 64, 0), node("two_dust", 64, 40),
                node("one_dust", 64, 80), node("torches", 128, 20), node("stone", 128, 93), node("repeater", 192, 80));
        var edges = List.of(edge("block", "two_dust"), edge("block", "one_dust"), edge("stick", "torches"),
                edge("two_dust", "torches"), edge("torches", "repeater"), edge("stone", "repeater"), edge("one_dust", "repeater"));
        var links = PlanGraphRouting.route(nodes, edges);
        var entries = new java.util.HashSet<Integer>();
        for (var link : links) if (link.target().equals("repeater")) {
            for (var s : link.segments())
                if (s.y1() == s.y2() && Math.min(s.x1(), s.x2()) <= 191 && Math.max(s.x1(), s.x2()) >= 191)
                    entries.add(s.y1());
            var last = link.segments().getLast();
            assertEquals(93, last.y1()); assertEquals(93, last.y2());
            assertTrue(last.x1() <= 191 && last.x2() == 205, "Root inputs do not share their final horizontal stroke");
        }
        assertEquals(java.util.Set.of(93), entries, "Same consumer has independently offset/double input lines");
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.overlaps()); assertEquals(0, score.contacts());
        assertEquals(edges, links.stream().map(link -> edge(link.source(), link.target())).toList());
        assertEndpoints(nodes, links);
    }

    @Test void sharedMaterialHasOneVisibleOutputPortAsWell() {
        var nodes = List.of(node("block", 0, 80), node("dust2", 64, 40), node("dust1", 64, 80),
                node("stick", 64, 0), node("torch", 128, 20), node("stone", 128, 100), node("root", 192, 80));
        var edges = List.of(edge("block", "dust2"), edge("block", "dust1"), edge("dust2", "torch"),
                edge("stick", "torch"), edge("torch", "root"), edge("stone", "root"), edge("dust1", "root"));
        var links = PlanGraphRouting.route(nodes, edges);
        var exits = new java.util.HashSet<Integer>();
        for (var link : links) if (link.source().equals("block")) for (var s : link.segments())
            if (s.y1() == s.y2() && Math.min(s.x1(), s.x2()) <= 27 && Math.max(s.x1(), s.x2()) >= 27)
                exits.add(s.y1());
        assertEquals(java.util.Set.of(93), exits, "Shared material has independently offset output ports");
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.overlaps()); assertEquals(0, score.contacts());
        assertEndpoints(nodes, links);
    }

    @Test void skippedColumnUsesALocalEmptyRowInsteadOfThePerimeter() {
        var nodes = List.of(node("a", 0, 40), node("obstacle", 64, 40), node("b", 192, 40));
        var links = PlanGraphRouting.route(nodes, List.of(edge("a", "b")));
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.outside());
        assertTrue(links.getFirst().segments().size() <= 5);
        assertTrue(score.length() <= 240, "One icon caused a whole-scene detour");
        assertOrthogonal(links);
    }

    @Test void consecutiveProductionEdgesMeetNormallyAtTheirSharedIcon() {
        var nodes = List.of(node("a", 0, 0), node("b", 64, 0), node("c", 128, 0));
        var links = PlanGraphRouting.route(nodes, List.of(edge("a", "b"), edge("b", "c")));
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.contacts()); assertEquals(0, score.crossings());
        assertEquals(0, score.bends()); assertEquals(0, score.outside());
        links.forEach(link -> assertEquals(link.segments(), link.strokes(), "A production-chain endpoint was cut"));
    }

    @Test void independentOpposingBusesDoNotBecomeOneWhiteConnection() {
        var nodes = List.of(node("a", 0, 0), node("b", 64, 80), node("c", 0, 80), node("d", 64, 0));
        var links = PlanGraphRouting.route(nodes, List.of(edge("a", "b"), edge("c", "d")));
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.overlaps()); assertEquals(0, score.contacts());
        assertEquals(0, score.outside());
        assertOrthogonal(links); assertEndpoints(nodes, links);
    }

    @Test void unavoidableCrossingHasAThreePixelOutlineSafeBreak() {
        var nodes = List.of(node("a", 0, 0), node("b", 192, 0), node("c", 64, -40), node("d", 128, 40));
        var links = PlanGraphRouting.route(nodes, List.of(edge("a", "b"), edge("c", "d")));
        var score = PlanGraphRouting.score(nodes, links);
        assertEquals(0, score.hits()); assertEquals(0, score.overlaps()); assertEquals(0, score.contacts());
        assertEquals(1, score.crossings()); assertEquals(0, score.outside());
        var crossing = links.stream().filter(link -> link.source().equals("c")).findFirst().orElseThrow();
        assertTrue(crossing.strokes().size() > crossing.segments().size());
        var vertical = crossing.segments().stream().filter(s -> s.x1() == s.x2() && Math.min(s.y1(), s.y2()) < 13
                && Math.max(s.y1(), s.y2()) > 13).findFirst().orElseThrow();
        assertTrue(crossing.strokes().stream().filter(s -> s.x1() == s.x2() && s.x1() == vertical.x1())
                .noneMatch(s -> Math.min(s.y1(), s.y2()) - 1 <= 13 && Math.max(s.y1(), s.y2()) + 1 >= 13),
                "The black outline can fill the crossing gap");
        assertEquals(crossing.segments().getFirst().x1(), 77);
        assertEndpoints(nodes, links);
    }

    @Test void scoringDistinguishesOverlapCornerContactAndProperCrossing() {
        var nodes = List.of(node("a", 0, 0), node("b", 192, 0), node("c", 64, -40), node("d", 128, 40));
        var horizontal = link("a", "b", segment(13, 13, 205, 13));
        var crossing = link("c", "d", segment(100, -27, 100, 53));
        var touching = link("c", "d", segment(100, -27, 100, 13));
        var overlapping = link("c", "d", segment(77, 13, 141, 13));
        assertEquals(1, PlanGraphRouting.score(nodes, List.of(horizontal, crossing)).crossings());
        assertEquals(1, PlanGraphRouting.score(nodes, List.of(horizontal, touching)).contacts());
        assertEquals(1, PlanGraphRouting.score(nodes, List.of(horizontal, overlapping)).overlaps());
        var shortCross = new PlanGraphRouting.Score(0, 0, 0, 1, 0, 2, 120);
        var outside = new PlanGraphRouting.Score(0, 0, 0, 0, 1, 4, 220);
        assertTrue(shortCross.compareTo(outside) < 0, "A distinguishable crossing should not force a perimeter route");
    }

    @Test void nearParallelCoresAndNearCornerCrossingsAreAmbiguousContacts() {
        var nodes = List.of(node("a", 0, 0), node("b", 192, 0), node("c", 64, 1), node("d", 256, 1));
        var a = new PlanGraphRouting.Link("a", "b", List.of(segment(13, 13, 38, 13), segment(38, 13, 38, -7),
                segment(38, -7, 180, -7), segment(180, -7, 180, 13), segment(180, 13, 205, 13)), List.of());
        var b = new PlanGraphRouting.Link("c", "d", List.of(segment(77, 14, 102, 14), segment(102, 14, 102, -8),
                segment(102, -8, 244, -8), segment(244, -8, 244, 14), segment(244, 14, 269, 14)), List.of());
        assertEquals(0, PlanGraphRouting.score(nodes, List.of(a, b)).hits());
        assertEquals(1, PlanGraphRouting.score(nodes, List.of(a, b)).contacts(), "Adjacent white pixels become a false bus");
        var horizontal = link("a", "b", segment(13, 13, 205, 13));
        var corner = link("c", "d", segment(100, -27, 100, 14));
        assertEquals(1, PlanGraphRouting.score(nodes, List.of(horizontal, corner)).contacts(), "A gap cannot safely end at a corner");
    }

    @Test void orderTopologyAndCallerListsRemainStable() {
        var nodes = new ArrayList<>(List.of(node("a", 0, 0), node("b", 64, 40), node("c", 128, 80)));
        var edges = new ArrayList<>(List.of(edge("a", "c"), edge("b", "c"), edge("a", "b")));
        var originalNodes = List.copyOf(nodes); var originalEdges = List.copyOf(edges);
        var first = PlanGraphRouting.route(nodes, edges);
        assertEquals(first, PlanGraphRouting.route(nodes, edges));
        assertEquals(edges, first.stream().map(link -> edge(link.source(), link.target())).toList());
        assertEquals(originalNodes, nodes); assertEquals(originalEdges, edges);
        var reversed = new ArrayList<>(edges); java.util.Collections.reverse(reversed);
        var reordered = PlanGraphRouting.route(nodes, reversed);
        for (var link : first) assertEquals(link, reordered.stream().filter(other -> other.source().equals(link.source())
                && other.target().equals(link.target())).findFirst().orElseThrow(), "Edge traversal changed routing geometry");
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        assertThrows(UnsupportedOperationException.class, () -> first.getFirst().segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.getFirst().strokes().clear());
        assertEndpoints(nodes, first);
        assertEquals(1, PlanGraphRouting.score(List.of(node("a", 64, 0), node("b", 0, 0)),
                List.of(link("a", "b", segment(77, 13, 13, 13)))).hits());
        assertThrows(IllegalArgumentException.class, () -> PlanGraphRouting.route(nodes, List.of(edge("missing", "a"))));
    }

    @Test void boundedFullSizeSceneDoesNotExplode() {
        var nodes = new ArrayList<PlanGraphRouting.Node>();
        var edges = new ArrayList<PlanGraphRouting.Edge>();
        for (int row = 0; row < 32; row++) for (int column = 0; column < 8; column++) {
            String id = row + ":" + column;
            nodes.add(node(id, column * 64, row * 40));
            if (column > 0) edges.add(edge(row + ":" + (column - 1), id));
        }
        assertTimeout(Duration.ofSeconds(3), () -> {
            var links = PlanGraphRouting.route(nodes, edges);
            assertEquals(224, links.size()); assertOrthogonal(links); assertEndpoints(nodes, links);
            var score = PlanGraphRouting.score(nodes, links);
            assertEquals(0, score.hits()); assertEquals(0, score.overlaps()); assertEquals(0, score.contacts());
            assertEquals(0, score.crossings()); assertEquals(0, score.outside()); assertEquals(0, score.bends());
        });
    }

    private static PlanGraphRouting.Node node(String id, int x, int y) { return new PlanGraphRouting.Node(id, x, y); }
    private static PlanGraphRouting.Edge edge(String source, String target) { return new PlanGraphRouting.Edge(source, target); }
    private static PlanGraphRouting.Segment segment(int x1, int y1, int x2, int y2) { return new PlanGraphRouting.Segment(x1, y1, x2, y2); }
    private static PlanGraphRouting.Link link(String source, String target, PlanGraphRouting.Segment segment) {
        return new PlanGraphRouting.Link(source, target, List.of(segment), List.of(segment));
    }
    private static void assertOrthogonal(List<PlanGraphRouting.Link> links) {
        for (var link : links) for (var s : link.segments()) assertTrue(s.x1() == s.x2() || s.y1() == s.y2());
    }
    private static void assertEndpoints(List<PlanGraphRouting.Node> nodes, List<PlanGraphRouting.Link> links) {
        for (var link : links) {
            var source = nodes.stream().filter(n -> n.id().equals(link.source())).findFirst().orElseThrow();
            var target = nodes.stream().filter(n -> n.id().equals(link.target())).findFirst().orElseThrow();
            assertEquals(source.x() + 13, link.segments().getFirst().x1());
            assertEquals(source.y() + 13, link.segments().getFirst().y1());
            assertEquals(target.x() + 13, link.segments().getLast().x2());
            assertEquals(target.y() + 13, link.segments().getLast().y2());
        }
    }
}
