package org.berusted.craftable.client.recipebook;

import java.util.HashMap;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.api.CraftingStatus;

/** Session-local presentation only. Pending previews retain their last result, never authorize writes. */
public final class ClientRecipeStatusStore {
    public enum Lifecycle { UNKNOWN, PENDING, KNOWN }
    private static final HashMap<ResourceLocation, StatusEntry> STATUSES = new HashMap<>();
    private static long generation = Long.MIN_VALUE;
    private static long responseFloor = Long.MIN_VALUE;
    private static long revision;

    private ClientRecipeStatusStore() {}

    public static CraftingStatus get(ResourceLocation id, boolean vanillaCraftable) {
        StatusEntry entry = STATUSES.get(id);
        return entry == null ? (vanillaCraftable ? CraftingStatus.CRAFTABLE : CraftingStatus.BLOCKED) : entry.status();
    }

    public static Lifecycle lifecycle(ResourceLocation id) {
        StatusEntry entry = STATUSES.get(id);
        return entry == null ? Lifecycle.UNKNOWN : entry.pending() ? Lifecycle.PENDING : Lifecycle.KNOWN;
    }

    public static CraftingResultCode reason(ResourceLocation id) {
        StatusEntry entry = STATUSES.get(id);
        return entry == null ? CraftingResultCode.MISSING_INGREDIENTS : entry.resultCode();
    }

    public static void put(ResourceLocation id, long requestId, CraftingStatus status,
            CraftingResultCode resultCode, long newGeneration, long receivedAtGameTime) {
        StatusEntry current = STATUSES.get(id);
        if (requestId <= responseFloor || newGeneration < generation
                || (current != null && requestId < current.requestId())) return;
        generation = newGeneration;
        STATUSES.put(id, new StatusEntry(status, resultCode, requestId, receivedAtGameTime, false));
        if (current == null || current.status() != status || current.resultCode() != resultCode || current.pending()) revision++;
    }

    public static boolean accepts(long requestId, long newGeneration) {
        return requestId > responseFloor && newGeneration >= generation;
    }

    public static boolean isFresh(ResourceLocation id, long gameTime, long maximumAge) {
        StatusEntry entry = STATUSES.get(id);
        long age = entry == null ? Long.MAX_VALUE : gameTime - entry.receivedAtGameTime();
        return entry != null && !entry.pending() && age >= 0 && age <= maximumAge;
    }

    public static void invalidate(long barrier) {
        // Output capacity and shared ingredients can affect every recipe. Retain
        // values but expire them; only the active view is actually re-evaluated.
        responseFloor = Math.max(responseFloor, barrier);
        STATUSES.replaceAll((id, value) -> new StatusEntry(value.status(), value.resultCode(),
                value.requestId(), value.receivedAtGameTime(), true));
        revision++;
    }

    public static void clear() {
        generation = Long.MIN_VALUE;
        responseFloor = Long.MIN_VALUE;
        STATUSES.clear();
        revision++;
    }

    public static long generation() { return generation; }
    public static long revision() { return revision; }

    private record StatusEntry(CraftingStatus status, CraftingResultCode resultCode,
            long requestId, long receivedAtGameTime, boolean pending) {}
}
