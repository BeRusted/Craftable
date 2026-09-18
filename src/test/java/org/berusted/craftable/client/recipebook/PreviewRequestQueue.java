package org.berusted.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** Fair, bounded selection. Hidden candidates cannot starve behind a continually changing visible page. */
// Protocol 6 baseline fixture; the production scheduler uses continuations.
final class PreviewRequestQueue {
    private int cursor;
    private int foregroundCursor;
    private List<ResourceLocation> unanswered = List.of();

    void received(List<ResourceLocation> sent, List<ResourceLocation> answered) {
        unanswered = sent.stream().filter(id -> !answered.contains(id)).toList();
    }

    void clear() { unanswered = List.of(); cursor = foregroundCursor = 0; }

    List<ResourceLocation> select(List<ResourceLocation> visible, List<ResourceLocation> scope,
            Predicate<ResourceLocation> needsRefresh, int limit) {
        LinkedHashSet<ResourceLocation> selected = new LinkedHashSet<>();
        // The server's time budget may return only a prefix. Finish its omitted
        // tail before cycling again, but discard targets outside the new view.
        for (var id : unanswered) {
            if ((visible.contains(id) || scope.contains(id)) && needsRefresh.test(id)) selected.add(id);
            if (selected.size() >= limit) return new ArrayList<>(selected);
        }
        int foregroundBudget = scope.isEmpty() ? limit : Math.max(1, limit * 3 / 4);
        var uniqueVisible = new ArrayList<>(new LinkedHashSet<>(visible));
        for (int checked = 0; checked < uniqueVisible.size() && selected.size() < foregroundBudget; checked++) {
            if (foregroundCursor >= uniqueVisible.size()) foregroundCursor = 0;
            var id = uniqueVisible.get(foregroundCursor++);
            if (needsRefresh.test(id)) selected.add(id);
            if (selected.size() >= foregroundBudget) break;
        }
        for (int checked = 0; checked < scope.size() && selected.size() < limit; checked++) {
            if (cursor >= scope.size()) cursor = 0;
            var id = scope.get(cursor++);
            if (needsRefresh.test(id)) selected.add(id);
        }
        return new ArrayList<>(selected);
    }
}
