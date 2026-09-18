package org.berusted.craftable.planner;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/** Bounded player intent, never a list of client-supplied inventory operations. */
public record CraftRequest(ResourceLocation recipe, int batches, boolean partial, boolean allowDrops,
        PartialPolicy policy, Map<String, ResourceLocation> selections) {
    public static final int MAX_BATCHES = 64;
    public static final int MAX_SELECTIONS = 32;

    public CraftRequest {
        Objects.requireNonNull(recipe);
        Objects.requireNonNull(policy);
        if (batches < 1 || batches > MAX_BATCHES || selections.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("Craft request exceeds limits");
        }
        for (var entry : selections.entrySet()) {
            if (!entry.getKey().matches("0(?:\\.[0-8]){1,12}")) {
                throw new IllegalArgumentException("Invalid demand path");
            }
            Objects.requireNonNull(entry.getValue());
        }
        selections = selections.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(selections));
    }

    public static CraftRequest one(ResourceLocation recipe) {
        return new CraftRequest(recipe, 1, false, false, PartialPolicy.EXPLICIT_SAFE, Map.of());
    }

    public CraftRequest withBatches(int count) {
        if (count == batches) return this;
        return new CraftRequest(recipe, count, partial, allowDrops, policy, selections);
    }

    public CraftRequest withPartial(boolean value) {
        if (value == partial) return this;
        return new CraftRequest(recipe, batches, value, allowDrops, policy, selections);
    }

    public enum PartialPolicy {
        NEVER, CONFIRM, EXPLICIT_SAFE;

        public PartialPolicy restrict(PartialPolicy other) {
            return ordinal() < other.ordinal() ? this : other;
        }
    }
}
