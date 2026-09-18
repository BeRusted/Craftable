package org.berusted.craftable.execution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;

/**
 * One small, connection-local store. It retains intent and immutable value
 * fingerprints and browsing values only, never executable source-container
 * handles, reservations or an executable plan. Browsing identity checks retain
 * weak container identities, never a means to bypass a fresh authorized scan.
 * The menu identity is retained solely to invalidate offers on menu replacement.
 */
public final class CraftingSessions {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private CraftingSessions() {}

    public static void clear(UUID player) { STATES.remove(player); }
    static void discardOffer(ServerPlayer player) { state(player).confirmation = null; }

    /** Read-only projection for the future snapshot protocol. Its caller must
     * hold the normal server admission lease; no capture is scheduled here. */
    public static org.berusted.craftable.environment.BrowsingSnapshot refreshBrowsing(ServerPlayer player) {
        if (!CraftingService.validContext(player)) {
            if (player.getServer().isSameThread()) closeBrowsing(player);
            throw new IllegalStateException("Invalid browsing context");
        }
        State owner = state(player);
        var world = org.berusted.craftable.environment.EnvironmentSnapshotService.fresh(player);
        boolean workbench = world.supports(org.berusted.craftable.workstation.WorkstationCapability.CRAFTING_3X3)
                || player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu;
        var recipes = new org.berusted.craftable.recipe.CraftingRecipes(player, workbench);
        if (owner.recipeIdentity.get() != recipes.generation()) {
            owner.recipeIdentity = new java.lang.ref.WeakReference<>(recipes.generation());
            owner.recipeVersion = Math.incrementExact(owner.recipeVersion);
            owner.browsing = null;
        }
        if (owner.browsing == null || owner.browsing.dimension != player.level().dimension())
            owner.browsing = new BrowsingState(player.level().dimension());
        try {
            return owner.browsing.capture(player, world, recipes, owner.recipeVersion);
        } catch (RuntimeException failure) {
            // An interrupted/oversized capture cannot renew the old grant.
            owner.browsing = null;
            throw failure;
        }
    }

    public static void closeBrowsing(ServerPlayer player) { state(player).browsing = null; }

    static long browsingLease(ServerPlayer player) {
        var browsing = state(player).browsing;
        return browsing == null ? Long.MIN_VALUE : browsing.validUntil;
    }

    /** Only weak identity references survive a scan. They cannot be used for
     * extraction and do not keep chunks/containers alive. Fresh scanning is
     * still mandatory for every grant renewal and for later execution. */
    private static final class BrowsingState {
        final UUID session = UUID.randomUUID();
        final Object dimension;
        Map<String, EndpointBinding> endpoints = Map.of();
        org.berusted.craftable.environment.BrowsingSnapshot snapshot;
        Object authorityIdentity;
        long version, validUntil;

        BrowsingState(Object dimension) { this.dimension = dimension; }

        org.berusted.craftable.environment.BrowsingSnapshot capture(ServerPlayer player,
                org.berusted.craftable.environment.EnvironmentSnapshot world,
                org.berusted.craftable.recipe.CraftingRecipes recipes, long recipeVersion) {
            var next = new java.util.LinkedHashMap<String, EndpointBinding>();
            var inputs = new java.util.ArrayList<org.berusted.craftable.environment.BrowsingSnapshot.Input>();
            int slots = 0;
            for (var endpoint : world.endpoints()) {
                slots = Math.addExact(slots, endpoint.slotCount());
                if (slots > 16_384)
                    throw new IllegalStateException("Browsing source range exceeds bounds");
                var parts = parts(player, endpoint);
                var binding = endpoints.get(endpoint.id());
                if (binding == null || !binding.matches(endpoint, parts)) binding = new EndpointBinding(endpoint, parts);
                next.put(endpoint.id(), binding);
                for (int slot = endpoint.firstSlot(); slot < endpoint.firstSlot() + endpoint.slotCount(); slot++) {
                    var stack = endpoint.container().getItem(slot);
                    if (stack.isEmpty() || org.berusted.craftable.recipe.CraftingRecipes.protectedStack(stack)) continue;
                    var reference = binding.references.computeIfAbsent(slot, ignored -> UUID.randomUUID());
                    inputs.add(new org.berusted.craftable.environment.BrowsingSnapshot.Input(reference,
                            endpoint.kind() == org.berusted.craftable.environment.EndpointKind.PLAYER ? slot : -1, stack));
                }
            }
            var rules = CraftableServerConfig.craftingRules();
            var candidate = new org.berusted.craftable.environment.BrowsingSnapshot(session, recipeVersion, Math.incrementExact(version),
                    recipes.workbench(), recipes.limitedCrafting(), recipes.unlockedRecipes(),
                    rules.surplusDelivery() == CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW, rules.partialPolicy(),
                    rules.maxBatches(), player.getInventory().getMaxStackSize(),
                    MainInventoryInsertion.copyMainInventory(player.getInventory()), inputs);
            // Include empty endpoints/permission boundaries in the private
            // identity too. Time, scan number and current player position are
            // deliberately excluded when authorized content stays identical.
            Object authority = List.of(world.dimension(), world.scanSettings(), recipes.accessIdentity(), rules,
                    next.values().stream().map(binding -> binding.identity).toList());
            if (snapshot == null || !authority.equals(authorityIdentity)
                    || !candidate.contentIdentity().equals(snapshot.contentIdentity())) {
                version = candidate.resources();
                snapshot = candidate;
                authorityIdentity = authority;
            }
            endpoints = next; // Lost/revoked endpoints cannot regain old refs.
            validUntil = Math.addExact(player.level().getGameTime(), 10);
            return snapshot;
        }

        private static List<net.minecraft.world.Container> parts(ServerPlayer player,
                org.berusted.craftable.environment.ContainerEndpoint endpoint) {
            if (!(endpoint.container() instanceof net.minecraft.world.CompoundContainer compound))
                return List.of(endpoint.container());
            var position = endpoint.position();
            if (position == null) throw new IllegalStateException("Missing double chest binding");
            var level = player.serverLevel();
            var state = level.getBlockState(position);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock))
                throw new IllegalStateException("Invalid double chest binding");
            var first = level.getBlockEntity(position);
            var second = level.getBlockEntity(position.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)));
            if (!(first instanceof net.minecraft.world.Container a) || !(second instanceof net.minecraft.world.Container b)
                    || !compound.contains(a) || !compound.contains(b) || a == b)
                throw new IllegalStateException("Changed double chest binding");
            return List.of(a, b);
        }
    }

    private static final class EndpointBinding {
        final UUID identity = UUID.randomUUID();
        final int first, count;
        final List<java.lang.ref.WeakReference<net.minecraft.world.Container>> parts;
        final Map<Integer, UUID> references = new HashMap<>();

        EndpointBinding(org.berusted.craftable.environment.ContainerEndpoint endpoint,
                List<net.minecraft.world.Container> parts) {
            first = endpoint.firstSlot(); count = endpoint.slotCount();
            this.parts = parts.stream().map(java.lang.ref.WeakReference::new).toList();
        }

        boolean matches(org.berusted.craftable.environment.ContainerEndpoint endpoint, List<net.minecraft.world.Container> parts) {
            if (first != endpoint.firstSlot() || count != endpoint.slotCount() || parts.size() != this.parts.size()) return false;
            for (int i = 0; i < parts.size(); i++) if (this.parts.get(i).get() != parts.get(i)) return false;
            return true;
        }
    }

    public static void expire(net.minecraft.server.MinecraftServer server) {
        STATES.entrySet().removeIf(entry -> {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            State state = entry.getValue();
            long now = player.level().getGameTime();
            if (state.confirmation != null && (state.confirmation.menu != player.containerMenu
                    || state.confirmation.expires < now || !CraftingService.validContext(player))) state.confirmation = null;
            if (state.last != null && (state.last.menu != player.containerMenu || now - state.last.tick > 40)) state.last = null;
            if (!CraftingService.validContext(player)) {
                state.maximum = null;
                state.preview = null;
                state.browsing = null;
            }
            if (state.browsing != null && (state.menu != player.containerMenu
                    || state.browsing.dimension != player.level().dimension()
                    || now > state.browsing.validUntil + 100)) state.browsing = null;
            // A folded/closed book has no explicit close packet in protocol 6.
            // Release the migration cache after bounded inactivity, not forever.
            if (state.preview != null && (now < state.preview.lastUsed || now - state.preview.lastUsed > 200)) state.preview = null;
            return false;
        });
    }

    static boolean acceptSequence(ServerPlayer player, long sequence) {
        State state = state(player);
        if (sequence < 0 || sequence <= state.highestCreate) return false;
        state.highestCreate = sequence;
        return true;
    }

    static boolean partialGesture(ServerPlayer player, ResourceLocation recipe, long previous) {
        Last last = state(player).last;
        // Link the first *server result*, not a client guess about craftability.
        // Ordered packets allow the second press to arrive before the first
        // reply. A successful first press can never arm partial preparation.
        return last != null && previous >= 0 && last.sequence == previous
                && last.recipe.equals(recipe) && last.menu == player.containerMenu
                && last.code == CraftingResultCode.MISSING_INGREDIENTS
                && player.level().getGameTime() - last.tick <= 40
                && player.level().getGameTime() >= last.tick;
    }

    static void recordAttempt(ServerPlayer player, ResourceLocation recipe, long sequence,
            CraftingResultCode code, boolean partialIntent) {
        State state = state(player);
        state.last = partialIntent ? null : new Last(recipe, sequence, code,
                player.containerMenu, player.level().getGameTime());
        state.confirmation = null;
    }

    static UUID offer(ServerPlayer player, CraftingService.Prepared prepared) {
        State state = state(player);
        state.confirmation = null;
        var plan = prepared.result().plan().orElse(null);
        if (plan == null || !prepared.result().completeSearch() && plan.partial()) return new UUID(0, 0);
        UUID token = UUID.randomUUID();
        state.confirmation = new Confirmation(token, player.containerMenu, player.level().dimension(),
                player.level().getGameTime() + 200, prepared.request(), prepared.rules(),
                prepared.recipes().generation(), fingerprint(plan, prepared.delivery().drops()));
        return token;
    }

    static Confirmation take(ServerPlayer player, UUID token) {
        State state = state(player);
        Confirmation confirmation = state.confirmation;
        state.confirmation = null; // Consume before validation or re-planning.
        if (confirmation == null || !confirmation.token.equals(token)
                || confirmation.menu != player.containerMenu
                || confirmation.dimension != player.level().dimension()
                || player.level().getGameTime() > confirmation.expires
                || !CraftingService.validContext(player)) return null;
        return confirmation;
    }

    static Object fingerprint(CraftPlan plan, List<net.minecraft.world.item.ItemStack> drops) {
        return List.of(plan.fingerprint(), CraftPlan.stackKeys(drops));
    }

    static PreviewState previews(ServerPlayer player, Object identity) {
        State state = state(player);
        if (state.preview == null || !identity.equals(state.preview.identity)) state.preview = new PreviewState(identity);
        state.preview.lastUsed = player.level().getGameTime();
        return state.preview;
    }

    /** Same resource identity, across batches. No world handles or plans. */
    static final class PreviewState {
        static final int MAX_RESULTS = 16_384;
        final Object identity;
        final org.berusted.craftable.planner.CraftSearch.Reachability reachability =
                new org.berusted.craftable.planner.CraftSearch.Reachability();
        final Map<ResourceLocation, CraftingService.CachedVerdict> results = new java.util.LinkedHashMap<>();
        long lastUsed;
        long fullSearches, partialSearches, hits;
        PreviewState(Object identity) { this.identity = identity; }
        void put(ResourceLocation id, CraftingService.CachedVerdict verdict) {
            // Do not evict completed proofs to admit arbitrary IDs: a full
            // filtered catalog otherwise turns an LRU into endless re-search.
            if (results.containsKey(id) || results.size() < MAX_RESULTS) results.put(id, verdict);
        }
    }

    static MaximumProgress maximum(ServerPlayer player, Object identity, int cap) {
        State state = state(player);
        if (state.maximum == null || !state.maximum.identity.equals(identity) || state.maximum.cap != cap) {
            state.maximum = new MaximumProgress(identity, cap);
        }
        return state.maximum;
    }

    static final class MaximumProgress {
        final Object identity;
        final int cap;
        int next = 1;
        int lowerBound;
        int highestUnknown;
        int retries;
        int slices;
        MaximumProgress(Object identity, int cap) { this.identity = identity; this.cap = cap; }
        boolean proven() { return next > cap && highestUnknown <= lowerBound; }
    }

    private static State state(ServerPlayer player) {
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        if (state.menu != player.containerMenu) {
            state.menu = player.containerMenu;
            state.last = null;
            state.confirmation = null;
            state.maximum = null;
            state.preview = null;
            state.browsing = null;
        }
        return state;
    }

    record Confirmation(UUID token, AbstractContainerMenu menu, Object dimension, long expires,
            CraftRequest request, CraftableServerConfig.CraftingRules rules, Object recipeGeneration, Object fingerprint) {
        boolean matches(CraftingService.Prepared fresh) {
            return request.equals(fresh.request()) && rules.equals(fresh.rules())
                    && recipeGeneration == fresh.recipes().generation()
                    && fresh.result().plan().map(p -> fingerprint.equals(
                            CraftingSessions.fingerprint(p, fresh.delivery().drops()))).orElse(false);
        }
    }
    private record Last(ResourceLocation recipe, long sequence, CraftingResultCode code,
            AbstractContainerMenu menu, long tick) {}
    private static final class State {
        long highestCreate = -1;
        AbstractContainerMenu menu;
        Last last;
        Confirmation confirmation;
        MaximumProgress maximum;
        PreviewState preview;
        BrowsingState browsing;
        // An idle player must not pin a discarded recipe generation after reload.
        java.lang.ref.WeakReference<Object> recipeIdentity = new java.lang.ref.WeakReference<>(null);
        long recipeVersion;
    }
}
