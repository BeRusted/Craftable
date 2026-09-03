package org.berusted.craftable.client.recipebook;

import java.util.HashMap;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;

/**
 * Holds server-derived recipe statuses for the current environment generation.
 */
public final class ClientRecipeStatusStore {
    private static final HashMap<ResourceLocation, StatusEntry> STATUSES = new HashMap<>();
    private static long generation = Long.MIN_VALUE;

    private ClientRecipeStatusStore() {}

    public static CraftingStatus get(ResourceLocation recipeId, boolean vanillaCraftable) {
        StatusEntry entry = STATUSES.get(recipeId);
        return entry == null
                ? vanillaCraftable ? CraftingStatus.CRAFTABLE : CraftingStatus.BLOCKED
                : entry.status();
    }

    public static void put(
            ResourceLocation recipeId,
            long requestId,
            CraftingStatus status,
            CraftingResultCode resultCode,
            long newGeneration,
            long receivedAtGameTime) {
        StatusEntry current = STATUSES.get(recipeId);
        if (current != null && requestId < current.requestId()) {
            return;
        }
        generation = Math.max(generation, newGeneration);
        STATUSES.put(recipeId, new StatusEntry(
                status, resultCode, newGeneration, requestId, receivedAtGameTime));
    }

    public static boolean isFresh(ResourceLocation recipeId, long gameTime, long maximumAge) {
        StatusEntry entry = STATUSES.get(recipeId);
        long age = entry == null ? Long.MAX_VALUE : gameTime - entry.receivedAtGameTime();
        return age >= 0 && age <= maximumAge;
    }

    public static void clear() {
        generation = Long.MIN_VALUE;
        STATUSES.clear();
    }

    public static long generation() {
        return generation;
    }

    private record StatusEntry(
            CraftingStatus status,
            CraftingResultCode resultCode,
            long environmentGeneration,
            long requestId,
            long receivedAtGameTime) {}
}
