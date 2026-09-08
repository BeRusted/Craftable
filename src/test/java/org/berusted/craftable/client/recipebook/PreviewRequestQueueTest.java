package org.berusted.craftable.client.recipebook;

import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRequestQueueTest {
    private static ResourceLocation id(int i) { return ResourceLocation.withDefaultNamespace("recipe_" + i); }

    @Test void deduplicatesVisibleTargetsAndRespectsBatchLimit() {
        var queue = new PreviewRequestQueue();
        var result = queue.select(List.of(id(1), id(1), id(2), id(3)), List.of(), ignored -> true, 2);
        assertEquals(List.of(id(1), id(2)), result);
    }
    @Test void discoversHiddenTargetsEvenWhenForegroundIsAlwaysStale() {
        var queue = new PreviewRequestQueue();
        var visible = IntStream.range(0, 32).mapToObj(PreviewRequestQueueTest::id).toList();
        var hidden = IntStream.range(32, 96).mapToObj(PreviewRequestQueueTest::id).toList();
        var seen = new java.util.HashSet<ResourceLocation>();
        for (int round = 0; round < 8; round++) seen.addAll(queue.select(visible, hidden, ignored -> true, 32));
        assertTrue(seen.containsAll(hidden));
    }
    @Test void skipsFreshTargetsAndSurvivesAShrinkingSearchScope() {
        var queue = new PreviewRequestQueue();
        queue.select(List.of(), List.of(id(1), id(2), id(3)), ignored -> true, 2);
        assertEquals(List.of(id(9)), queue.select(List.of(id(0)), List.of(id(9)), id -> !id.equals(id(0)), 8));
    }

    @Test void truncatedRepliesFinishTheirTailWithoutStarvingHiddenRecipes() {
        var queue = new PreviewRequestQueue();
        var visible = IntStream.range(0, 32).mapToObj(PreviewRequestQueueTest::id).toList();
        var hidden = IntStream.range(32, 40).mapToObj(PreviewRequestQueueTest::id).toList();
        var seen = new java.util.HashSet<ResourceLocation>();
        for (int round = 0; round < 64; round++) {
            var batch = queue.select(visible, hidden, ignored -> true, 32);
            seen.add(batch.getFirst());
            queue.received(batch, List.of(batch.getFirst()));
        }
        assertTrue(seen.containsAll(hidden));
        assertEquals(List.of(id(90)), queue.select(List.of(id(90)), List.of(), ignored -> true, 32));
    }
}
