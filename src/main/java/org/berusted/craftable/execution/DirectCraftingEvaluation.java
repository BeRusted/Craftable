package org.berusted.craftable.execution;

import java.util.Optional;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;

public record DirectCraftingEvaluation(
        CraftingStatus status,
        CraftingResultCode resultCode,
        Optional<DirectCraftingPlan> plan) {

    public static DirectCraftingEvaluation craftable(DirectCraftingPlan plan) {
        return new DirectCraftingEvaluation(
                CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, Optional.of(plan));
    }

    public static DirectCraftingEvaluation blocked(CraftingResultCode resultCode) {
        return new DirectCraftingEvaluation(CraftingStatus.BLOCKED, resultCode, Optional.empty());
    }
}
