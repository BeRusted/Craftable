package org.berusted.craftable.recipe;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;

/** Read-only production-index contracts; no alternate recipe registry or executor. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4KnowledgeGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void planningHandshakeIncludesIngredientsOutputAndSupportSemantics(GameTestHelper helper) {
        var id = ResourceLocation.withDefaultNamespace("handshake_fixture");
        var first = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK, 4),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS)));
        var changed = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK, 4),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.BAMBOO)));
        var output = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK, 3),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS)));
        var unknown = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK, 4),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS))) { };
        String baseline = new CraftingRecipes.Index(List.of(new RecipeHolder<>(id, first)), helper.getLevel().registryAccess()).planningFingerprint;
        for (var other : List.of(changed, output, unknown)) helper.assertFalse(baseline.equals(
                new CraftingRecipes.Index(List.of(new RecipeHolder<>(id, other)), helper.getLevel().registryAccess()).planningFingerprint),
                "same recipe ID concealed changed value semantics");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void slicedCatalogReusesGenerationAndMatchesSynchronousIndex(GameTestHelper helper) {
        var recipes = helper.getLevel().getRecipeManager().getRecipes();
        var catalog = new PlanningInput.Catalog();
        var connection = new Object();
        var generation = new Object();
        catalog.observe(connection, generation, recipes, helper.getLevel().registryAccess());
        var clock = new java.util.concurrent.atomic.AtomicLong();
        int slices = 0;
        while (catalog.state() == PlanningInput.Catalog.State.BUILDING && ++slices < 20_000) {
            int before = catalog.processed();
            catalog.advance(7, clock::getAndIncrement);
            if (catalog.state() == PlanningInput.Catalog.State.BUILDING)
                helper.assertTrue(catalog.processed() >= before, "slice restarted normalization");
            catalog.observe(connection, generation, recipes, helper.getLevel().registryAccess());
        }
        helper.assertTrue(slices > 10 && catalog.state() == PlanningInput.Catalog.State.READY,
                "sliced normalization failed to finish");
        var input = catalog.bind(true, false, java.util.Set.of());
        var baseline = new CraftingRecipes.Index(recipes, helper.getLevel().registryAccess());
        helper.assertValueEqual(input.fingerprint(), baseline.planningFingerprint, "shared value model fingerprint differs");
        helper.assertValueEqual(input.entries().stream().map(CraftingRecipes.Entry::id).toList(),
                baseline.valueOrdered.stream().map(CraftingRecipes.Entry::id).toList(), "catalog ordering differs");
        for (var entry : baseline.valueOrdered) {
            var actual = input.find(entry.id());
            helper.assertTrue(ItemStack.matches(actual.output(), entry.output()), "sliced output differs");
            for (var demand : entry.requirements()) {
                helper.assertValueEqual(input.producing(demand.ingredient()).stream().map(CraftingRecipes.Entry::id).toList(),
                        baseline.producing(demand.ingredient()).stream().filter(e -> baseline.valueRecipes.contains(e.id()))
                                .map(CraftingRecipes.Entry::id).toList(), "sliced production relation differs");
            }
        }
        for (int changes = 0; changes < 100; changes++) {
            catalog.observe(connection, generation, recipes, helper.getLevel().registryAccess());
            catalog.advance(1);
            helper.assertTrue(catalog.bind(changes % 2 == 0, false, java.util.Set.of()).generation() == input.generation(),
                    "workstation/menu refresh rebuilt static index");
        }
        helper.assertValueEqual(catalog.builds(), 1, "same generation built twice");
        Craftable.LOGGER.info("M48_CATALOG sliced={} recipes={} stableBindings=100 builds=1",
                slices, input.entries().size());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void catalogReloadDisconnectAndLimitNeverPublishIncompleteData(GameTestHelper helper) {
        var recipes = helper.getLevel().getRecipeManager().getRecipes();
        var catalog = new PlanningInput.Catalog();
        var connection = new Object();
        catalog.observe(connection, new Object(), recipes, helper.getLevel().registryAccess());
        var clock = new java.util.concurrent.atomic.AtomicLong();
        catalog.advance(7, clock::getAndIncrement);
        helper.assertTrue(catalog.state() == PlanningInput.Catalog.State.BUILDING, "fixture did not pause");
        boolean rejected = false;
        try { catalog.bind(true, false, java.util.Set.of()); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "incomplete graph was exposed");
        // Reload has the exact same content but a new runtime generation.
        catalog.observe(connection, new Object(), recipes, helper.getLevel().registryAccess());
        helper.assertValueEqual(catalog.processed(), 0, "reload reused previous cursor");
        helper.assertValueEqual(catalog.builds(), 2, "reload did not rebind generation");
        catalog.clear();
        catalog.advance(1_000_000);
        helper.assertTrue(catalog.state() == PlanningInput.Catalog.State.EMPTY, "disconnect republished cancelled work");
        catalog.observe(new Object(), new Object(), java.util.Collections.nCopies(65_537, recipes.iterator().next()),
                helper.getLevel().registryAccess());
        helper.assertTrue(catalog.state() == PlanningInput.Catalog.State.LIMITED, "oversized catalog accepted");
        helper.assertValueEqual(catalog.builds(), 2, "oversized input began normalization");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void repeatedRelationsBenchmark(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        try {
            var catalog = new CraftingRecipes(player, true);
            var ingredients = List.of(Ingredient.of(Items.STICK),
                    Ingredient.of(net.minecraft.tags.ItemTags.PLANKS), Ingredient.of(Items.DIAMOND),
                    Ingredient.of(Items.IRON_INGOT), Ingredient.of(net.minecraft.tags.ItemTags.LOGS));
            long[] samples = new long[100];
            int entries = 0;
            for (int i = 0; i < samples.length; i++) {
                long start = System.nanoTime();
                for (int n = 0; n < 20; n++) for (var ingredient : ingredients)
                    entries += catalog.producing(ingredient).size();
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            helper.assertTrue(entries > 0, "benchmark produced no candidates");
            Craftable.LOGGER.warn("M4_KNOWLEDGE relations100x100 p50={}ms p95={}ms p99={}ms max={}ms entries={}",
                    samples[49] / 1e6, samples[94] / 1e6, samples[98] / 1e6, samples[99] / 1e6, entries);
            long builds = catalog.knowledgeStats().builds();
            for (int i = 0; i < 100; i++) {
                var equivalent = Ingredient.of(Items.STICK, Items.STICK);
                helper.assertTrue(catalog.producing(equivalent).equals(catalog.producing(ingredients.getFirst())),
                        "equivalent ordinary requirements lost candidates");
                new CraftingRecipes(player, false).producing(equivalent);
            }
            helper.assertValueEqual(catalog.knowledgeStats().builds(), builds, "relations relearned on repeated queries");
            helper.assertTrue(catalog.producing(Ingredient.of(Items.STICK)).stream()
                    .anyMatch(e -> e.requirements().size() == 2 && e.requirements().stream()
                            .allMatch(r -> r.ingredient().test(new net.minecraft.world.item.ItemStack(Items.BAMBOO)))),
                    "bamboo alternative lost");
            Craftable.LOGGER.warn("M4_KNOWLEDGE counters={}", catalog.knowledgeStats());
        } finally { player.getServer().getPlayerList().remove(player); }
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void restoredRelationsAreCompleteAndRebindCurrentEntries(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var server = player.getServer();
        try {
            var catalog = new CraftingRecipes(player, true);
            var items = CraftingRecipes.key(Ingredient.of(Items.STICK));
            var candidates = catalog.producing(Ingredient.of(Items.STICK));
            var index = new CraftingRecipes.Index(server);
            index.fingerprint = index.fingerprint(server);
            helper.assertTrue(index.fingerprint != null, "vanilla fingerprint unavailable");
            var snapshot = new RecipeKnowledgeCache.Snapshot(index.fingerprint,
                    Map.of(items, candidates.stream().map(e -> e.id().toString()).toList()));
            helper.assertTrue(index.restore(snapshot), "valid relations rejected");
            helper.assertTrue(index.knowledge.get(items).getFirst() != candidates.getFirst(), "restored stale Entry object");
            helper.assertTrue(index.knowledge.get(items).getFirst() == index.byId.get(candidates.getFirst().id()),
                    "restored entry is not current index entry");
            var incomplete = new RecipeKnowledgeCache.Snapshot(index.fingerprint, Map.of(items, List.of()));
            helper.assertFalse(index.restore(incomplete), "false complete empty adjacency trusted");
            helper.assertValueEqual(index.knowledge.get(items).size(), candidates.size(), "failed load partially published");
            var invalid = new RecipeKnowledgeCache.Snapshot(index.fingerprint, Map.of(items, List.of("minecraft:missing")));
            helper.assertFalse(index.restore(invalid), "invalid reference trusted");
            var unknown = new RecipeKnowledgeCache.Snapshot(index.fingerprint,
                    Map.of(List.of("minecraft:nonexistent_item"), List.of()));
            helper.assertFalse(index.restore(unknown), "unknown item accepted as empty relation");
        } finally { server.getPlayerList().remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fingerprintDetectsSameIdInputYieldAndRemoval(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var manager = server.getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            var first = fixture("m4_knowledge_fixture", Items.OAK_PLANKS, 4);
            manager.replaceRecipes(List.of(first));
            String base = new CraftingRecipes.Index(server).fingerprint(server);
            helper.assertTrue(base != null, "fixture fingerprint unavailable");
            manager.replaceRecipes(List.of(fixture("m4_knowledge_fixture", Items.BAMBOO, 4)));
            helper.assertFalse(base.equals(new CraftingRecipes.Index(server).fingerprint(server)), "same ID input change ignored");
            manager.replaceRecipes(List.of(fixture("m4_knowledge_fixture", Items.OAK_PLANKS, 1)));
            helper.assertFalse(base.equals(new CraftingRecipes.Index(server).fingerprint(server)), "yield change ignored");
            manager.replaceRecipes(List.of());
            helper.assertFalse(base.equals(new CraftingRecipes.Index(server).fingerprint(server)), "removed recipe ignored");
        } finally { manager.replaceRecipes(original); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void resolvedTagsAndOrderingDefineFingerprint(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var manager = server.getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        var tags = new java.util.HashMap<net.minecraft.tags.TagKey<net.minecraft.world.item.Item>,
                List<net.minecraft.core.Holder<net.minecraft.world.item.Item>>>();
        BuiltInRegistries.ITEM.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        try {
            var tag = net.minecraft.tags.ItemTags.PLANKS;
            var recipe = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK, 4),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(tag), Ingredient.of(tag)));
            var holder = new RecipeHolder<>(ResourceLocation.withDefaultNamespace("m4_knowledge_tag"), recipe);
            manager.replaceRecipes(List.of(holder, fixture("m4_other", Items.BAMBOO, 1)));
            String base = new CraftingRecipes.Index(server).fingerprint(server);
            var reversed = new ArrayList<>(manager.getRecipes());
            java.util.Collections.reverse(reversed);
            manager.replaceRecipes(reversed);
            helper.assertValueEqual(new CraftingRecipes.Index(server).fingerprint(server), base, "iteration order changed fingerprint");
            var changed = new java.util.HashMap<>(tags);
            changed.put(tag, List.of(Items.BAMBOO.builtInRegistryHolder()));
            BuiltInRegistries.ITEM.bindTags(changed);
            // Reload creates new ingredients after rebinding tags; do not reuse
            // a previously resolved Ingredient's own vanilla itemStacks cache.
            recipe = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK, 4),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(tag), Ingredient.of(tag)));
            manager.replaceRecipes(List.of(new RecipeHolder<>(holder.id(), recipe), fixture("m4_other", Items.BAMBOO, 1)));
            helper.assertFalse(base.equals(new CraftingRecipes.Index(server).fingerprint(server)), "tag-only change ignored");
        } finally {
            BuiltInRegistries.ITEM.bindTags(tags);
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void cachedKnowledgeDoesNotCacheInventoryOrPermissions(GameTestHelper helper) {
        var a = helper.makeMockServerPlayerInLevel();
        var b = helper.makeMockServerPlayerInLevel();
        try {
            var first = new CraftingRecipes(a, true);
            var other = new CraftingRecipes(b, false);
            var sticks = Ingredient.of(Items.STICK);
            helper.assertTrue(first.producing(sticks) == other.producing(Ingredient.of(Items.STICK)), "players did not share relation");
            var pickaxe = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
            helper.assertTrue(first.unavailable(first.find(pickaxe)) == null, "table available");
            helper.assertTrue(other.unavailable(other.find(pickaxe)) != null, "table permission leaked between players");
            var request = org.berusted.craftable.planner.CraftRequest.one(pickaxe);
            var sources = List.of(new org.berusted.craftable.planner.ResourceLedger.Source("fixture", 0, new ItemStack(Items.STICK, 2)),
                    new org.berusted.craftable.planner.ResourceLedger.Source("fixture", 1, new ItemStack(Items.DIAMOND, 3)));
            var result = new org.berusted.craftable.planner.CraftSearch(first, request, sources,
                    new org.berusted.craftable.planner.SearchBudget(1_000_000_000L), plan -> null).run();
            helper.assertValueEqual(result.plan().orElseThrow().steps().size(), 1, "existing sticks expanded wood recipe");
            var empty = new org.berusted.craftable.planner.CraftSearch(first, request, List.of(),
                    new org.berusted.craftable.planner.SearchBudget(1_000_000_000L), plan -> null).run();
            helper.assertTrue(empty.plan().isEmpty(), "knowledge created resources from empty inventory");
            var generation = first.generation();
            CraftingRecipes.reloaded(new net.neoforged.neoforge.event.OnDatapackSyncEvent(a.getServer().getPlayerList(), null));
            helper.assertTrue(new CraftingRecipes(a, true).generation() != generation, "same-content reload retained runtime generation");
        } finally { a.getServer().getPlayerList().remove(a); b.getServer().getPlayerList().remove(b); }
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void customIngredientsNeverUseOrdinarySharedKeys(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        try {
            var custom = new net.neoforged.neoforge.common.crafting.ICustomIngredient() {
                @Override public boolean test(ItemStack stack) { return stack.is(Items.STICK) && stack.getCount() == 1; }
                @Override public java.util.stream.Stream<ItemStack> getItems() { return java.util.stream.Stream.of(new ItemStack(Items.STICK)); }
                @Override public boolean isSimple() { return false; }
                @Override public net.neoforged.neoforge.common.crafting.IngredientType<?> getType() { throw new UnsupportedOperationException(); }
            }.toVanilla();
            helper.assertTrue(CraftingRecipes.key(custom) == null, "custom ingredient got an ordinary key");
            var difference = net.neoforged.neoforge.common.crafting.DifferenceIngredient.of(
                    Ingredient.of(Items.STICK, Items.DIAMOND), Ingredient.of(Items.DIAMOND));
            helper.assertValueEqual(CraftingRecipes.key(difference), CraftingRecipes.key(Ingredient.of(Items.STICK)),
                    "platform item-set difference not normalized");
            var intersection = net.neoforged.neoforge.common.crafting.IntersectionIngredient.of(
                    Ingredient.of(Items.STICK, Items.DIAMOND), Ingredient.of(Items.STICK));
            helper.assertValueEqual(CraftingRecipes.key(intersection), CraftingRecipes.key(difference), "intersection key");
            var compound = net.neoforged.neoforge.common.crafting.CompoundIngredient.of(
                    Ingredient.of(Items.STICK), Ingredient.of(Items.DIAMOND));
            helper.assertValueEqual(CraftingRecipes.key(compound), CraftingRecipes.key(Ingredient.of(Items.STICK, Items.DIAMOND)), "union key");
            helper.assertTrue(CraftingRecipes.key(net.neoforged.neoforge.common.crafting.DifferenceIngredient.of(
                    Ingredient.of(Items.STICK), custom)) == null, "custom leaf hidden in set expression");
            var catalog = new CraftingRecipes(player, true);
            catalog.producing(Ingredient.of(Items.STICK));
            helper.assertTrue(catalog.producing(custom).stream().allMatch(e -> e.output().getCount() == 1), "ordinary cache bypassed component/count predicate");
        } finally { player.getServer().getPlayerList().remove(player); }
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 300)
    public static void diskRestoreCostIsMeasuredAgainstRebuildingRelations(GameTestHelper helper) throws Exception {
        var player = helper.makeMockServerPlayerInLevel();
        var server = player.getServer();
        var directory = java.nio.file.Files.createTempDirectory(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                "craftable-knowledge-test-");
        var path = directory.resolve("knowledge.json");
        try {
            var catalog = new CraftingRecipes(player, true);
            var index = new CraftingRecipes.Index(server);
            index.fingerprint = index.fingerprint(server);
            for (var entry : catalog.entries()) for (var r : entry.requirements()) {
                var key = CraftingRecipes.key(r.ingredient());
                if (key != null) index.knowledge.put(key, catalog.producing(r.ingredient()));
            }
            var saved = index.snapshot();
            try (var cache = new RecipeKnowledgeCache(path, ignored -> {})) {
                cache.submit(cache.advance(), saved);
                helper.assertTrue(cache.awaitIdle(), "test cache write did not finish");
            }
            long[] restored = new long[5], rebuilt = new long[5];
            for (int i = 0; i < 5; i++) {
                long start = System.nanoTime();
                var fresh = new CraftingRecipes.Index(server);
                fresh.fingerprint = fresh.fingerprint(server);
                try (var cache = new RecipeKnowledgeCache(path, ignored -> {})) {
                    helper.assertTrue(fresh.restore(cache.load(fresh.fingerprint)), "disk cache failed to rebind");
                }
                restored[i] = System.nanoTime() - start;
                start = System.nanoTime();
                var raw = new CraftingRecipes.Index(server);
                raw.fingerprint(server);
                // Rebuild each shared key only ONCE, not the old per-slot
                // algorithm: otherwise this would exaggerate disk-cache gain.
                var seen = new java.util.HashSet<List<String>>();
                for (var entry : raw.ordered) for (var r : entry.requirements()) {
                    var key = CraftingRecipes.key(r.ingredient());
                    if (key != null && seen.add(key)) catalog.producingUncached(r.ingredient());
                }
                rebuilt[i] = System.nanoTime() - start;
            }
            Arrays.sort(restored); Arrays.sort(rebuilt);
            Craftable.LOGGER.warn("M4_KNOWLEDGE reload5 keys={} bytes={} loadMedian={}ms loadMax={}ms rebuildMedian={}ms rebuildMax={}ms",
                    saved.relations().size(), java.nio.file.Files.size(path), restored[2]/1e6, restored[4]/1e6, rebuilt[2]/1e6, rebuilt[4]/1e6);
        } finally {
            server.getPlayerList().remove(player);
            java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.deleteIfExists(directory);
        }
        helper.succeed();
    }

    private static RecipeHolder<ShapelessRecipe> fixture(String id, net.minecraft.world.item.Item input, int count) {
        return new RecipeHolder<>(ResourceLocation.withDefaultNamespace(id), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(Items.STICK, count), NonNullList.of(Ingredient.EMPTY, Ingredient.of(input), Ingredient.of(input))));
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void effectCureSetsAreCanonicalButCustomArraysRemainOrdered(GameTestHelper helper) {
        String a = "{\"minecraft:food\":{\"effects\":[{\"effect\":{\"id\":\"minecraft:regeneration\","
                + "\"neoforge:cures\":[\"milk\",\"protected_by_totem\"]}}]}}";
        String b = a.replace("[\"milk\",\"protected_by_totem\"]", "[\"protected_by_totem\",\"milk\"]");
        var first = com.google.gson.JsonParser.parseString(a).getAsJsonObject();
        var second = com.google.gson.JsonParser.parseString(b).getAsJsonObject();
        CraftingRecipes.Index.normalizeComponentSets(first);
        CraftingRecipes.Index.normalizeComponentSets(second);
        helper.assertValueEqual(first, second, "unordered effect cure set changed identity");
        var custom = com.google.gson.JsonParser.parseString(
                "{\"minecraft:custom_data\":{\"neoforge:cures\":[\"z\",\"a\"]}}").getAsJsonObject();
        var before = custom.deepCopy();
        CraftingRecipes.Index.normalizeComponentSets(custom);
        helper.assertValueEqual(custom, before, "arbitrary custom array was reordered");
        helper.succeed();
    }
}
