package org.berusted.craftable.planner;

import java.util.List;
import java.util.Optional;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;

/** Search uncertainty is not evidence that ingredients are absent. */
public record SearchResult(CraftingResultCode code, Optional<CraftPlan> plan,
        List<CraftPlan.Missing> missing, boolean completeSearch, int visitedStates) {
    public SearchResult {
        missing = List.copyOf(missing);
    }

    public CraftingStatus status() {
        return plan.map(p -> p.partial() ? CraftingStatus.PARTIAL : CraftingStatus.CRAFTABLE)
                .orElse(CraftingStatus.BLOCKED);
    }

    public static SearchResult blocked(CraftingResultCode code) {
        return new SearchResult(code, Optional.empty(), List.of(), true, 0);
    }
}
