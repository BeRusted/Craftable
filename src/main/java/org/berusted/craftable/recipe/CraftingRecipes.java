package org.berusted.craftable.recipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.level.storage.LevelResource;
import org.berusted.craftable.Craftable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.CommonHooks;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.planner.CraftPlan;

/** Ordinary vanilla recipe boundary. Each index belongs to its owning game thread;
 * execution and crafting hooks remain exclusively on the server thread. */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class CraftingRecipes {
    private static final Map<MinecraftServer, Index> INDEXES = new IdentityHashMap<>();
    private final ServerPlayer player;
    private final boolean workbench;
    private final Set<ResourceLocation> unlocked;
    private final boolean limited;
    private final Index index;

    public CraftingRecipes(ServerPlayer player, boolean workbench) {
        this.player = player;
        this.workbench = workbench;
        this.limited = player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING);
        var server = player.getServer();
        var manager = server.getRecipeManager();
        // RecipeManager replaces its immutable byName map on reload. Its values
        // view is stable between reloads, so checking identity is O(1), not an
        // O(all recipes) fingerprint for every visible button.
        Object identity = manager.getRecipes();
        Index cached = INDEXES.get(server);
        if (cached == null || cached.identity != identity) {
            cached = refresh(server, false);
        }
        this.index = cached;
        this.unlocked = new java.util.HashSet<>();
        if (limited) {
            for (var entry : index.byId.values()) {
                if (player.getRecipeBook().contains(entry.holder())) unlocked.add(entry.id());
            }
        }
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        refresh(event.getServer(), true);
    }

    @SubscribeEvent
    public static void reloaded(OnDatapackSyncEvent event) {
        // TagsUpdatedEvent can run on the integrated client's loading thread.
        // This server event runs after recipe AND tag binding; player joins do
        // not replace the shared index, even on an empty server.
        if (event.getPlayer() == null) {
            var server = event.getPlayerList().getServer();
            refresh(server, true);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        var index = INDEXES.remove(event.getServer());
        if (index != null && index.cache != null) {
            index.flush();
            index.cache.close();
            Craftable.LOGGER.info("Craftable knowledge: stopped lookups={} hits={} builds={} keys={} estimatedBytes={}",
                    index.lookups, index.hits, index.builds, index.knowledge.size(), index.estimatedBytes);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        var index = INDEXES.get(event.getServer());
        if (index != null && System.nanoTime() - index.lastFlush >= 30_000_000_000L) index.flush();
    }

    private static Index refresh(MinecraftServer server, boolean lifecycle) {
        var old = INDEXES.get(server);
        var next = new Index(server);
        next.cache = old == null ? null : old.cache;
        if (lifecycle && next.cache == null) next.cache = new RecipeKnowledgeCache(
                server.getWorldPath(LevelResource.ROOT).resolve("craftable/cache/recipe-knowledge-v1.json"),
                message -> Craftable.LOGGER.info("Craftable knowledge: {}", message));
        if (next.cache != null) next.epoch = next.cache.advance();
        // Only lifecycle calls fingerprint/read disk. An unexpected runtime
        // recipe replacement falls back to memory; a C request never does I/O.
        if (lifecycle) {
            long start = System.nanoTime();
            next.fingerprint = next.fingerprint(server);
            if (next.fingerprint != null) {
                var snapshot = old != null && next.fingerprint.equals(old.fingerprint)
                        ? old.snapshot() : next.cache.load(next.fingerprint);
                boolean restored = next.restore(snapshot);
                next.dirty = restored && old != null && next.fingerprint.equals(old.fingerprint);
                if (snapshot != null && !restored)
                    Craftable.LOGGER.info("Craftable knowledge: rejected incomplete or incompatible relations");
            }
            Craftable.LOGGER.info("Craftable knowledge: generation recipes={} restored={} setupMs={}",
                    next.ordered.size(), next.knowledge.size(), (System.nanoTime() - start) / 1e6);
        }
        INDEXES.put(server, next);
        return next;
    }

    public Object generation() { return index; }
    public Object accessIdentity() { return List.of(index, workbench, limited, Set.copyOf(unlocked)); }
    public boolean workbench() { return workbench; }
    public boolean limitedCrafting() { return limited; }
    public Set<ResourceLocation> unlockedRecipes() { return Set.copyOf(unlocked); }
    public Entry find(ResourceLocation id) { return index.byId.get(id); }
    public List<Entry> entries() { return index.ordered; }
    public PlanningInput planningInput() { return new PlanningInput(index, workbench, limited, unlocked); }

    /** Validate the chosen route against live authoritative behavior, never
     * search an alternative here. The pure model cannot authorize execution. */
    public boolean validate(CraftPlan plan) {
        for (var step : plan.steps()) {
            var entry = find(step.recipe().id());
            if (unavailable(entry) != null) return false;
            var actual = assemble(entry, step.path(), step.inputs());
            if (actual == null || !ItemStack.matches(actual.output(), step.output())
                    || !CraftPlan.stackKeys(actual.remainders()).equals(CraftPlan.stackKeys(step.remainders()))) return false;
        }
        return true;
    }

    public CraftingResultCode unavailable(Entry entry) {
        if (entry == null) return CraftingResultCode.UNSUPPORTED_RECIPE;
        if (limited && !unlocked.contains(entry.id())) return CraftingResultCode.RECIPE_LOCKED;
        if (entry.gridSize() == 3 && !workbench) return CraftingResultCode.MISSING_WORKSTATION;
        return null;
    }

    public List<Entry> producing(Ingredient ingredient) {
        return index.producing(ingredient);
    }

    // Vanilla Ingredient.test ignores stack components and count. Only this
    // exact ordinary semantics (and the platform's proven set expressions)
    // may share an item-set key. "Simple" alone is NOT such a proof.
    static List<String> key(Ingredient ingredient) {
        if (!itemSetSemantics(ingredient, 0, new int[]{256})
                || ingredient.getItems().length > RecipeKnowledgeCache.MAX_REFERENCES) return null;
        var key = java.util.Arrays.stream(ingredient.getItems())
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).distinct().sorted().toList();
        return key.stream().anyMatch(s -> s.length() > 512) ? null : key;
    }

    private static boolean itemSetSemantics(Ingredient ingredient, int depth, int[] remaining) {
        if (depth > 16 || --remaining[0] < 0) return false;
        if (!ingredient.isCustom()) return true;
        var custom = ingredient.getCustomIngredient();
        if (custom instanceof net.neoforged.neoforge.common.crafting.DifferenceIngredient difference)
            return itemSetSemantics(difference.base(), depth + 1, remaining)
                    && itemSetSemantics(difference.subtracted(), depth + 1, remaining);
        List<Ingredient> children;
        if (custom instanceof net.neoforged.neoforge.common.crafting.CompoundIngredient compound) children = compound.children();
        else if (custom instanceof net.neoforged.neoforge.common.crafting.IntersectionIngredient intersection) children = intersection.children();
        else return false;
        for (var child : children) if (!itemSetSemantics(child, depth + 1, remaining)) return false;
        return true;
    }

    List<Entry> producingUncached(Ingredient ingredient) {
        return index.producingUncached(ingredient);
    }

    record KnowledgeStats(long lookups, long hits, long builds, int relations, int options, int producers, long estimatedBytes) {}
    KnowledgeStats knowledgeStats() {
        return new KnowledgeStats(index.lookups, index.hits, index.builds, index.knowledge.size(),
                index.optionReferences, index.producerReferences, index.estimatedBytes);
    }

    public CraftPlan.Step assemble(Entry entry, String path, List<ItemStack> grid) {
        CraftingInput input = CraftingInput.of(entry.gridSize(), entry.gridSize(), CraftPlan.copies(grid));
        var recipe = entry.holder().value();
        if (!recipe.matches(input, player.serverLevel())) return null;
        ItemStack output = recipe.assemble(input, player.registryAccess()).copy();
        // Static output is an index hint, never permission to invent a dynamic
        // result. Unsupported behavior is rejected, including component drift.
        if (!ItemStack.matches(output, entry.output())) return null;
        List<ItemStack> remainders;
        CommonHooks.setCraftingPlayer(player);
        try {
            remainders = recipe.getRemainingItems(input);
        } finally {
            CommonHooks.setCraftingPlayer(null);
        }
        if (remainders.size() != input.size()) return null;
        return new CraftPlan.Step(entry.holder(), path, entry.gridSize(), grid, output, remainders);
    }

    public static boolean protectedStack(ItemStack stack) {
        return !stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()));
    }

    public record Requirement(int slot, Ingredient ingredient) {}

    public record Entry(RecipeHolder<CraftingRecipe> holder, int gridSize,
            List<Requirement> requirements, ItemStack output, boolean safePreparation) {
        public Entry {
            requirements = List.copyOf(requirements);
            output = output.copy();
        }
        public ResourceLocation id() { return holder.id(); }
        @Override public ItemStack output() { return output.copy(); }
    }

    private static boolean safePreparation(String path, ItemStack output, List<Requirement> inputs) {
        // A datapack can replace a vanilla ID. Names alone cannot make a
        // newly expensive diamond-consuming recipe safe for automatic partials.
        if (protectedStack(output)) return false;
        if (path.endsWith("_planks") && output.is(net.minecraft.tags.ItemTags.PLANKS)
                && output.getCount() == 4 && inputs.size() == 1
                && java.util.Arrays.stream(inputs.getFirst().ingredient().getItems())
                    .allMatch(s -> s.is(net.minecraft.tags.ItemTags.LOGS))) return true;
        if (output.is(net.minecraft.world.item.Items.STICK) && inputs.size() == 2) {
            if (output.getCount() == 4 && inputs.stream().allMatch(r ->
                    java.util.Arrays.stream(r.ingredient().getItems()).allMatch(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)))) return true;
            if (output.getCount() == 1 && inputs.stream().allMatch(r ->
                    java.util.Arrays.stream(r.ingredient().getItems()).allMatch(s -> s.is(net.minecraft.world.item.Items.BAMBOO)))) return true;
        }
        var pairs = List.of(
                List.of(net.minecraft.world.item.Items.IRON_NUGGET, net.minecraft.world.item.Items.IRON_INGOT),
                List.of(net.minecraft.world.item.Items.GOLD_NUGGET, net.minecraft.world.item.Items.GOLD_INGOT),
                List.of(net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.IRON_BLOCK),
                List.of(net.minecraft.world.item.Items.GOLD_INGOT, net.minecraft.world.item.Items.GOLD_BLOCK),
                List.of(net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.DIAMOND_BLOCK),
                List.of(net.minecraft.world.item.Items.EMERALD, net.minecraft.world.item.Items.EMERALD_BLOCK),
                List.of(net.minecraft.world.item.Items.REDSTONE, net.minecraft.world.item.Items.REDSTONE_BLOCK),
                List.of(net.minecraft.world.item.Items.LAPIS_LAZULI, net.minecraft.world.item.Items.LAPIS_BLOCK),
                List.of(net.minecraft.world.item.Items.COAL, net.minecraft.world.item.Items.COAL_BLOCK),
                List.of(net.minecraft.world.item.Items.COPPER_INGOT, net.minecraft.world.item.Items.COPPER_BLOCK));
        for (var pair : pairs) {
            Item source = null;
            if (output.is(pair.get(0)) && output.getCount() == 9 && inputs.size() == 1) source = pair.get(1);
            if (output.is(pair.get(1)) && output.getCount() == 1 && inputs.size() == 9) source = pair.get(0);
            if (source == null) continue;
            Item expected = source;
            if (inputs.stream().allMatch(r -> java.util.Arrays.stream(r.ingredient().getItems())
                    .allMatch(s -> s.is(expected)))) return true;
        }
        return false;
    }

    static final class Index {
        static final long MAX_ESTIMATED_BYTES = 64L * 1024 * 1024;
        final Object identity;
        final Map<ResourceLocation, Entry> byId = new HashMap<>();
        final Map<Item, List<Entry>> byOutput = new HashMap<>();
        // Published only when Builder finishes. An incomplete Index never
        // escapes through PlanningInput or the server's generation map.
        List<Entry> ordered = List.of();
        List<Entry> valueOrdered = List.of();
        final Map<List<String>, List<Entry>> knowledge = new HashMap<>();
        final Map<Ingredient, List<Entry>> aliases = new IdentityHashMap<>();
        final Set<ResourceLocation> valueRecipes = new java.util.HashSet<>();
        final Map<Item, ItemStack> remainders = new HashMap<>();
        int optionReferences, producerReferences;
        long lookups, hits, builds;
        long estimatedBytes;
        RecipeKnowledgeCache cache;
        String fingerprint;
        String planningFingerprint;
        long epoch, lastFlush = System.nanoTime();
        boolean dirty;

        Index(MinecraftServer server) {
            this(server.getRecipeManager().getRecipes(), server.registryAccess());
        }

        // One normalization algorithm for both sides. No player, disk cache or
        // execution hook is required to build static knowledge from synced data.
        // This is not a worker-thread API: recipes/tags belong to the game thread.
        Index(Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries) {
            this.identity = recipes;
            var builder = new Builder(this, recipes, registries, false);
            while (!builder.advance(Long.MAX_VALUE / 2, System::nanoTime)) { }
        }

        private Index(Object identity) { this.identity = identity; }

        @SuppressWarnings("unchecked")
        private void add(RecipeHolder<?> holder, HolderLookup.Provider registries) {
            if (!holder.id().getNamespace().equals("minecraft") || !(holder.value() instanceof CraftingRecipe recipe)
                    || (recipe.getSerializer() != RecipeSerializer.SHAPED_RECIPE
                    && recipe.getSerializer() != RecipeSerializer.SHAPELESS_RECIPE)
                    || recipe.isSpecial() || recipe.isIncomplete() || recipe.getIngredients().isEmpty()) return;
            var output = recipe.getResultItem(registries);
            if (output.isEmpty()) return;
            int size = recipe.canCraftInDimensions(2, 2) ? 2 : 3;
            if (!recipe.canCraftInDimensions(size, size)) return;
            var requirements = new ArrayList<Requirement>();
            boolean valueRecipe = recipe.getClass() == ShapedRecipe.class
                    || recipe.getClass() == net.minecraft.world.item.crafting.ShapelessRecipe.class;
            for (int i = 0; i < recipe.getIngredients().size(); i++) {
                Ingredient ingredient = recipe.getIngredients().get(i);
                if (ingredient.isEmpty()) continue;
                int slot = recipe instanceof ShapedRecipe shaped
                        ? i / shaped.getWidth() * size + i % shaped.getWidth() : requirements.size();
                if (slot >= size * size) { requirements.clear(); break; }
                boolean ordinary = key(ingredient) != null;
                valueRecipe &= ordinary;
                if (ordinary) {
                    // Freeze resolved tag membership once per generation.
                    // Solver steps never call data-pack or custom ingredient code.
                    var options = java.util.Arrays.stream(ingredient.getItems()).map(ItemStack::copy).toArray(ItemStack[]::new);
                    for (var option : options) {
                        if (!BuiltInRegistries.ITEM.getKey(option.getItem()).getNamespace().equals("minecraft")) valueRecipe = false;
                        else remainders.computeIfAbsent(option.getItem(), item -> new ItemStack(item).getCraftingRemainingItem().copy());
                    }
                    ingredient = Ingredient.of(options);
                }
                requirements.add(new Requirement(slot, ingredient));
            }
            if (requirements.isEmpty()) return;
            String path = holder.id().getPath();
            // Explicit low-risk preparation set, not a made-up item value
            // score. Bucket/container or unusual recipes require consent.
            boolean safe = safePreparation(path, output, requirements);
            var entry = new Entry((RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder,
                    size, requirements, output, safe);
            byId.put(holder.id(), entry);
            if (valueRecipe) valueRecipes.add(holder.id());
            byOutput.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
        }

        /** The synchronous server constructor and sliced client initialization
         * execute this same normalizer. Each unit finishes atomically on the
         * owning game thread; overruns are measured, not hidden as hard deadlines. */
        static final class Builder {
            private final java.security.MessageDigest modelDigest = RecipeKnowledgeCache.digest();
            private final Map<Object, com.google.gson.JsonObject> componentValues = new HashMap<>();
            private final Index index;
            private final HolderLookup.Provider registries;
            private final java.util.Iterator<RecipeHolder<?>> recipes;
            private final java.util.TreeMap<String, Entry> sorted = new java.util.TreeMap<>();
            private final ArrayList<Entry> ordered = new ArrayList<>(), values = new ArrayList<>();
            private java.util.Iterator<Entry> publishing;
            private final boolean warm;
            private int entryCursor, requirementCursor, processed;
            private long normalizedOptions;
            private boolean done, cancelled;

            Builder(Object identity, Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries) {
                this(new Index(identity), recipes, registries, true);
            }

            private Builder(Index index, Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries, boolean warm) {
                this.index = index;
                this.recipes = recipes.iterator();
                this.registries = registries;
                this.warm = warm;
                RecipeKnowledgeCache.field(modelDigest, "craftable-value-model-v1");
            }

            boolean advance(long nanos, java.util.function.LongSupplier clock) {
                if (nanos <= 0) throw new IllegalArgumentException("Positive build slice required");
                if (done || cancelled) return true;
                long start = clock.getAsLong();
                while (clock.getAsLong() - start < nanos) {
                    if (publishing == null) {
                        if (recipes.hasNext()) {
                            var holder = recipes.next();
                            index.add(holder, registries);
                            var entry = index.byId.get(holder.id());
                            if (entry != null) {
                                sorted.put(holder.id().toString(), entry);
                                for (var requirement : entry.requirements())
                                    normalizedOptions += requirement.ingredient().getItems().length;
                                if (warm && (sorted.size() > RecipeKnowledgeCache.MAX_KEYS
                                        || normalizedOptions > RecipeKnowledgeCache.MAX_REFERENCES))
                                    throw new IllegalStateException("Local catalog exceeds bounds");
                            }
                            processed++;
                            continue;
                        }
                        publishing = sorted.values().iterator();
                    }
                    if (publishing.hasNext()) {
                        var entry = publishing.next();
                        ordered.add(entry);
                        if (index.valueRecipes.contains(entry.id())) values.add(entry);
                        fingerprintEntry(entry);
                        continue;
                    }
                    if (warm && entryCursor < ordered.size()) {
                        var demands = ordered.get(entryCursor).requirements();
                        if (requirementCursor < demands.size()) {
                            index.producing(demands.get(requirementCursor++).ingredient());
                        } else {
                            entryCursor++;
                            requirementCursor = 0;
                        }
                        continue;
                    }
                    // Builder exclusively owns these lists and never mutates
                    // them again. No all-catalog copy/sort at the publish tick.
                    index.ordered = java.util.Collections.unmodifiableList(ordered);
                    index.valueOrdered = java.util.Collections.unmodifiableList(values);
                    index.planningFingerprint = RecipeKnowledgeCache.hex(modelDigest);
                    sorted.clear();
                    done = true;
                    return true;
                }
                return false;
            }

            Index result() {
                if (!done || cancelled) throw new IllegalStateException("Catalog is not complete");
                return index;
            }
            int processed() { return processed; }
            void cancel() { cancelled = true; }

            // Both ends attest to the SAME normalized model, not merely recipe
            // IDs. Fold one entry per builder unit; no second graph or final
            // all-catalog hashing pass. Unknown semantics remain explicit.
            private void fingerprintEntry(Entry entry) {
                RecipeKnowledgeCache.field(modelDigest, entry.id().toString());
                RecipeKnowledgeCache.field(modelDigest, entry.gridSize() + ":" + entry.safePreparation()
                        + ":" + index.valueRecipes.contains(entry.id()));
                fingerprintStack(entry.output());
                for (var demand : entry.requirements()) {
                    RecipeKnowledgeCache.field(modelDigest, "slot:" + demand.slot());
                    var options = key(demand.ingredient());
                    RecipeKnowledgeCache.field(modelDigest, options == null ? "unknown" : options.toString());
                    if (options != null) for (var option : options) {
                        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(option));
                        var remainder = index.remainders.get(item);
                        RecipeKnowledgeCache.field(modelDigest, remainder == null ? "unknown" : "known");
                        if (remainder != null) fingerprintStack(remainder);
                    }
                }
            }

            private void fingerprintStack(ItemStack stack) {
                if (stack.isEmpty()) { RecipeKnowledgeCache.field(modelDigest, "empty"); return; }
                RecipeKnowledgeCache.field(modelDigest, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                RecipeKnowledgeCache.field(modelDigest, Integer.toString(stack.getCount()));
                var key = List.of(stack.getItem(), stack.getComponentsPatch());
                var components = componentValues.get(key);
                if (components == null) {
                    var ops = registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                    components = net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(ops,
                            stack.getComponents()).getOrThrow().getAsJsonObject();
                    normalizeComponentSets(components);
                    if (componentValues.size() < 4096) componentValues.put(key, components);
                }
                RecipeKnowledgeCache.json(modelDigest, components);
            }
        }

        List<Entry> producing(Ingredient ingredient) {
            lookups++;
            var alias = aliases.get(ingredient);
            if (alias != null) { hits++; return alias; }
            var key = key(ingredient);
            var result = key == null ? null : knowledge.get(key);
            if (result != null) hits++;
            else {
                result = producingUncached(ingredient);
                // Publish only a complete candidate set. A missing key is not a
                // proof of no recipe. Reaching this memory bound never drops recipes.
                if (key != null && knowledge.size() < RecipeKnowledgeCache.MAX_KEYS
                        && optionReferences + key.size() <= RecipeKnowledgeCache.MAX_REFERENCES
                        && producerReferences + result.size() <= RecipeKnowledgeCache.MAX_REFERENCES
                        && estimatedBytes + relationBytes(key, result.size()) <= MAX_ESTIMATED_BYTES) {
                    knowledge.put(key, result);
                    estimatedBytes += relationBytes(key, result.size());
                    optionReferences += key.size();
                    producerReferences += result.size();
                    dirty = true;
                } else return result;
            }
            long aliasBytes = 64L + ingredient.getItems().length * 64L;
            if (aliases.size() < 4096 && estimatedBytes + aliasBytes <= MAX_ESTIMATED_BYTES) {
                aliases.put(ingredient, result);
                estimatedBytes += aliasBytes;
            }
            return result;
        }

        List<Entry> producingUncached(Ingredient ingredient) {
            builds++;
            var result = new LinkedHashSet<Entry>();
            for (ItemStack option : ingredient.getItems()) {
                for (Entry entry : byOutput.getOrDefault(option.getItem(), List.of())) {
                    if (ingredient.test(entry.output())) result.add(entry);
                }
            }
            return result.stream().sorted(Comparator.comparing(e -> e.id().toString())).toList();
        }

        String fingerprint(MinecraftServer server) {
            if (ordered.size() > RecipeKnowledgeCache.MAX_KEYS) return null;
            try {
                var digest = RecipeKnowledgeCache.digest();
                RecipeKnowledgeCache.field(digest, "craftable-ordinary-knowledge-v2");
                RecipeKnowledgeCache.field(digest, server.getServerVersion());
                RecipeKnowledgeCache.field(digest,
                        net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion.getVersion());
                var ops = server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                long serializedChars = 0;
                for (var entry : ordered) {
                    RecipeKnowledgeCache.field(digest, entry.id().toString());
                    // Include full recipe data, not just IDs or declared result.
                    // Resolved ordinary item sets below additionally cover tag
                    // changes, including nested tag membership and empty tags.
                    var data = Recipe.CODEC.encodeStart(ops, entry.holder().value()).getOrThrow();
                    if (data.getAsJsonObject().get("result") instanceof com.google.gson.JsonObject result
                            && result.get("components") instanceof com.google.gson.JsonObject components)
                        normalizeComponentSets(components);
                    serializedChars += data.toString().length();
                    if (serializedChars > RecipeKnowledgeCache.MAX_BYTES) return null;
                    RecipeKnowledgeCache.json(digest, data);
                    var components = net.minecraft.core.component.DataComponentMap.CODEC
                            .encodeStart(ops, entry.output.getComponents()).getOrThrow().getAsJsonObject();
                    normalizeComponentSets(components);
                    RecipeKnowledgeCache.json(digest, components);
                    RecipeKnowledgeCache.field(digest, entry.gridSize() + ":" + entry.safePreparation());
                    for (var requirement : entry.requirements()) {
                        var items = key(requirement.ingredient());
                        if (items == null) {
                            Craftable.LOGGER.info("Craftable knowledge: noncanonical ingredient in {} ({})", entry.id(),
                                    requirement.ingredient().getCustomIngredient() == null ? "size limit"
                                            : requirement.ingredient().getCustomIngredient().getClass().getSimpleName());
                            return null; // No guessed custom-ingredient persistence.
                        }
                        RecipeKnowledgeCache.field(digest, "slot:" + requirement.slot() + ":" + items.size());
                        items.forEach(item -> RecipeKnowledgeCache.field(digest, item));
                    }
                }
                return RecipeKnowledgeCache.hex(digest);
            } catch (RuntimeException exception) {
                Craftable.LOGGER.info("Craftable knowledge: fingerprint unavailable; memory index only ({})",
                        exception.getClass().getSimpleName());
                return null;
            }
        }

        RecipeKnowledgeCache.Snapshot snapshot() {
            if (fingerprint == null) return null;
            var relations = new HashMap<List<String>, List<String>>();
            var ids = new HashMap<ResourceLocation, String>();
            knowledge.forEach((key, entries) -> relations.put(key, entries.stream()
                    .map(e -> ids.computeIfAbsent(e.id(), ResourceLocation::toString)).toList()));
            return new RecipeKnowledgeCache.Snapshot(fingerprint, relations);
        }

        boolean restore(RecipeKnowledgeCache.Snapshot snapshot) {
            if (snapshot == null || !snapshot.fingerprint().equals(fingerprint)) return false;
            var restored = new HashMap<List<String>, List<Entry>>();
            int options = 0, producers = 0;
            long bytes = 0;
            for (var relation : snapshot.relations().entrySet()) {
                bytes += relationBytes(relation.getKey(), relation.getValue().size());
                if (bytes > MAX_ESTIMATED_BYTES) return false;
                int expected = 0;
                for (String id : relation.getKey()) {
                    var itemId = ResourceLocation.tryParse(id);
                    if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) return false;
                    expected += byOutput.getOrDefault(BuiltInRegistries.ITEM.get(itemId), List.of()).size();
                }
                // Each recipe has exactly one indexed output item; disjoint
                // item sets + unique IDs + this cardinality prove completeness
                // without rebuilding, deduplicating and sorting the union.
                if (expected != relation.getValue().size()) return false;
                var entries = new ArrayList<Entry>();
                for (String id : relation.getValue()) {
                    var entry = byId.get(ResourceLocation.tryParse(id));
                    if (entry == null || java.util.Collections.binarySearch(relation.getKey(),
                            BuiltInRegistries.ITEM.getKey(entry.output.getItem()).toString()) < 0) return false;
                    entries.add(entry);
                }
                restored.put(relation.getKey(), List.copyOf(entries));
                options += relation.getKey().size();
                producers += entries.size();
            }
            // No partial publication: even a checksummed file is untrusted
            // until every adjacency is proven complete against today's index.
            knowledge.clear();
            aliases.clear();
            knowledge.putAll(restored);
            optionReferences = options;
            producerReferences = producers;
            estimatedBytes = bytes;
            return true;
        }

        private static long relationBytes(List<String> key, int producers) {
            return 128L + producers * 16L + key.stream().mapToLong(id -> 64L + id.length() * 2L).sum();
        }

        static void normalizeComponentSets(com.google.gson.JsonObject components) {
            // MobEffectInstance.Details uses setOf(EffectCure.CODEC). Its
            // iteration order changes between JVMs (notably golden apples).
            // Normalize only those typed paths, NEVER arbitrary custom_data
            // arrays or the order of recipe inputs/effects themselves.
            if (components.get("minecraft:food") instanceof com.google.gson.JsonObject food
                    && food.get("effects") instanceof com.google.gson.JsonArray effects) {
                for (var entry : effects) if (entry.getAsJsonObject().get("effect") instanceof com.google.gson.JsonObject effect)
                    normalizeCures(effect, 0);
            }
            if (components.get("minecraft:potion_contents") instanceof com.google.gson.JsonObject potion
                    && potion.get("custom_effects") instanceof com.google.gson.JsonArray effects) {
                for (var entry : effects) normalizeCures(entry.getAsJsonObject(), 0);
            }
        }

        private static void normalizeCures(com.google.gson.JsonObject effect, int depth) {
            if (depth > 32) throw new IllegalArgumentException("effect nesting");
            if (effect.get("neoforge:cures") instanceof com.google.gson.JsonArray cures) {
                var sorted = new com.google.gson.JsonArray();
                java.util.stream.StreamSupport.stream(cures.spliterator(), false)
                        .map(com.google.gson.JsonElement::getAsString).distinct().sorted().forEach(sorted::add);
                effect.add("neoforge:cures", sorted);
            }
            if (effect.get("hidden_effect") instanceof com.google.gson.JsonObject hidden) normalizeCures(hidden, depth + 1);
        }

        void flush() {
            lastFlush = System.nanoTime();
            if (cache == null || fingerprint == null || !dirty) return;
            try {
                cache.submit(epoch, snapshot());
                dirty = false;
            } catch (RuntimeException exception) {
                Craftable.LOGGER.info("Craftable knowledge: snapshot skipped ({})", exception.getClass().getSimpleName());
            }
        }
    }
}
