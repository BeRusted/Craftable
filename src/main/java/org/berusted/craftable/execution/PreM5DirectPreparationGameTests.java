package org.berusted.craftable.execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.DirectPreparationPolicy;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.network.CraftingDetailPayloads;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.CraftingRecipes;
import org.berusted.craftable.recipe.PlanningInput;

/** Consent changes use the existing search, fresh authority and transaction.
 * Genuine ordinary recipes and a frozen functional clock do not claim wall
 * clock performance, add support or weaken the production admission cap. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreM5DirectPreparationGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final ResourceLocation A = id("a"), B = id("b"), TARGET = id("target");

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void defaultsKeepStructuralEligibilityInsteadOfTrustingAVanillaId(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(), List.of());
            fixture.recipes(List.of(
                    recipe(ResourceLocation.withDefaultNamespace("oak_planks"), Items.OAK_PLANKS, 4, Items.OAK_LOG),
                    recipe(TARGET, Items.GOLD_INGOT, 1, Items.OAK_PLANKS, Items.EMERALD)));
            fixture.stock(Items.OAK_LOG);
            var baseline = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(baseline.code(), CraftingResultCode.PARTIAL_CREATED, "default ordinary wood processing");
            helper.assertTrue(baseline.plan() != null && baseline.plan().safePartial(), "structural default omitted consent marker");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.OAK_PLANKS), 4, "default preparation output");

            // A data pack retains the familiar ID but replaces its input.
            fixture.recipes(List.of(
                    recipe(ResourceLocation.withDefaultNamespace("oak_planks"), Items.OAK_PLANKS, 4, Items.DIAMOND),
                    recipe(TARGET, Items.GOLD_INGOT, 1, Items.OAK_PLANKS, Items.EMERALD)));
            fixture.stock(Items.DIAMOND);
            var before = inventoryIdentity(fixture.player);
            var changed = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(changed.code(), CraftingResultCode.CONFIRMATION_REQUIRED, "vanilla recipe ID bypassed structural default");
            helper.assertTrue(changed.plan() != null && !changed.plan().safePartial(), "expensive replacement was a default direct preparation");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unconfirmed replacement consumed stock");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void customOverridesApplyToEveryActualStepAndConfirmationWins(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            var before = inventoryIdentity(fixture.player);
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(A.toString()), List.of());
            var missingConsent = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(missingConsent.code(), CraftingResultCode.CONFIRMATION_REQUIRED, "allowing only the first operation allowed the whole chain");
            helper.assertTrue(missingConsent.plan() != null && missingConsent.plan().steps().size() == 2
                    && !missingConsent.plan().safePartial(), "fixture did not check two actual preparation operations");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unapproved chain consumed stock");
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(A.toString(), B.toString()), List.of(B.toString()));
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "confirmation override did not win over allow");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "confirmation override consumed stock");
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(A.toString(), B.toString()), List.of());
            var allowed = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(allowed.code(), CraftingResultCode.PARTIAL_CREATED, "explicit custom direct preparation");
            helper.assertTrue(allowed.plan().safePartial(), "fully allowed chain omitted consent marker");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.AMETHYST_SHARD), 0, "chain did not extract actual input");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.COPPER_INGOT), 1, "chain did not deliver actual frontier");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.IRON_INGOT), 0, "chain leaked an intermediate");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.GOLD_INGOT), 0, "partial preparation fabricated root output");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void shortcutModesPreserveCustomListsAndDoNotDisableConfirmedPreparation(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            var allowed = List.of(A.toString(), B.toString());
            var confirmation = List.of(B.toString());
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, allowed, confirmation);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.PARTIAL_CREATED, "all-supported mode did not permit an explicit supported preparation");
            fixture.stock(Items.AMETHYST_SHARD);
            fixture.mode.set(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
            helper.assertValueEqual(fixture.allowed.get(), allowed, "shortcut erased custom allow IDs");
            helper.assertValueEqual(fixture.confirmation.get(), confirmation, "shortcut erased custom confirmation IDs");
            var before = inventoryIdentity(fixture.player);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "require-confirmation mode allowed direct preparation");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "require-confirmation consumed stock");
            var draft = CraftingService.preview(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE), "", null, -1, logical(), null);
            helper.assertTrue(draft.view().complete() && !draft.token().equals(CraftingDetailPayloads.NO_TOKEN),
                    "require-confirmation mode prohibited a genuine review offer");
            helper.assertValueEqual(CraftingService.confirm(fixture.player, draft.token(), null, -1, logical(), null).code(),
                    CraftingResultCode.PARTIAL_CREATED, "require-confirmation mode prohibited confirmed preparation");
            fixture.mode.set(DirectPreparationPolicy.Mode.CUSTOM);
            helper.assertTrue(!CraftableServerConfig.directPreparationPolicy().allowsWithoutConfirmation(B, false),
                    "returning to custom ignored the retained confirmation override");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void allSupportedCannotBypassExplicitIntentOrStricterWorldAndPersonalPolicy(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of(), List.of());
            var before = inventoryIdentity(fixture.player);
            helper.assertValueEqual(create(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "single C implicitly authorized preparation");
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.NEVER)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "personal NEVER was relaxed by all-supported");
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.CONFIRM)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "personal CONFIRM was relaxed by all-supported");
            fixture.partial.set(CraftRequest.PartialPolicy.NEVER);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "world NEVER was relaxed by all-supported");
            fixture.partial.set(CraftRequest.PartialPolicy.CONFIRM);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "world CONFIRM was relaxed by all-supported");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "stricter policies consumed stock");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void policyChangeInvalidatesOfferAndDynamicEvidenceWithoutRebuildingStaticKnowledge(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            fixture.player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of(), List.of());
            var first = CraftingSessions.refreshBrowsing(fixture.player);
            var prepared = prepare(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            var token = CraftingSessions.offer(fixture.player, prepared);
            var staticGeneration = prepared.recipes().generation();
            String fingerprint = prepared.recipes().planningInput().fingerprint();
            var before = inventoryIdentity(fixture.player);
            fixture.mode.set(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
            helper.assertValueEqual(CraftingService.confirm(fixture.player, token, null, -1, logical(), null).code(),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "old offer survived policy change");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "stale offer consumed or delivered stock");
            var second = CraftingSessions.refreshBrowsing(fixture.player);
            var current = new CraftingRecipes(fixture.player, true);
            helper.assertTrue(second.resources() > first.resources() && second.recipes() == first.recipes(),
                    "consent rule did not isolate dynamic identity from recipe generation");
            helper.assertTrue(current.generation() == staticGeneration, "consent change rebuilt server recipe index");
            helper.assertValueEqual(current.planningInput().fingerprint(), fingerprint, "consent polluted static recipe fingerprint");

            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(A.toString(), B.toString()), List.of(B.toString()));
            var listed = CraftingSessions.refreshBrowsing(fixture.player);
            var encoded = org.berusted.craftable.network.CraftingWire.snapshot(listed, fixture.player.registryAccess());
            var decoded = org.berusted.craftable.network.CraftingWire.snapshot(encoded, fixture.player.registryAccess());
            helper.assertValueEqual(decoded.directPreparation(), CraftableServerConfig.directPreparationPolicy(),
                    "resource snapshot codec lost the authoritative recipe overrides");
            helper.assertValueEqual(decoded.contentIdentity(), listed.contentIdentity(),
                    "policy/source content identity changed through the actual resource codec");
            helper.assertTrue(listed.resources() > second.resources(), "list-only policy change did not invalidate dynamic evidence");
            helper.assertTrue(new CraftingRecipes(fixture.player, true).generation() == staticGeneration,
                    "list-only policy change rebuilt static recipe knowledge");

            var catalog = new PlanningInput.Catalog();
            var recipes = fixture.player.getServer().getRecipeManager().getRecipes();
            catalog.observe(fixture.player.getServer(), recipes, recipes, fixture.player.registryAccess());
            catalog.advance(Long.MAX_VALUE / 2);
            helper.assertValueEqual(catalog.state(), PlanningInput.Catalog.State.READY, "ordinary value catalog failed to build");
            var custom = catalog.bind(true, false, java.util.Set.of(), DirectPreparationPolicy.DEFAULT);
            var all = catalog.bind(true, false, java.util.Set.of(), CraftableServerConfig.directPreparationPolicy());
            helper.assertTrue(custom.generation() == all.generation() && catalog.builds() == 1,
                    "binding policy rebuilt client static catalog");
            helper.assertValueEqual(custom.fingerprint(), all.fingerprint(), "policy binding altered static client fingerprint");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void searchAndAuthoritativeWitnessUseTheSameFrozenStepPolicy(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            fixture.player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of(), List.of());
            var prepared = prepare(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            var plan = prepared.result().plan().orElseThrow();
            var snapshot = CraftingSessions.refreshBrowsing(fixture.player);
            var witness = CraftPlan.Witness.from(plan, snapshot);
            var replayed = CraftingService.prepareWitness(fixture.player, prepared.request(), EnvironmentSnapshotService.fresh(fixture.player), witness, logical());
            helper.assertTrue(plan.safePartial() && replayed.result().plan().orElseThrow().safePartial(),
                    "all-supported search and actual replay disagree on step consent");
            var frozen = prepared.recipes().planningInput();
            fixture.mode.set(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
            helper.assertTrue(frozen.allowDirectPreparation(frozen.find(A)), "running search adopted later mutable policy");
            var current = new CraftingRecipes(fixture.player, true);
            helper.assertTrue(!current.allowDirectPreparation(current.find(A)), "new authority ignored changed policy");
            prepared = prepare(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            plan = prepared.result().plan().orElseThrow();
            snapshot = CraftingSessions.refreshBrowsing(fixture.player);
            replayed = CraftingService.prepareWitness(fixture.player, prepared.request(), EnvironmentSnapshotService.fresh(fixture.player),
                    CraftPlan.Witness.from(plan, snapshot), logical());
            helper.assertTrue(!plan.safePartial() && !replayed.result().plan().orElseThrow().safePartial(),
                    "require-confirmation search and actual replay disagree on step consent");
            helper.assertValueEqual(prepared.result().code(), CraftingResultCode.CREATED, "consent setting prohibited complete crafting");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void allSupportedAndUnknownConfiguredIdsNeverGrantUnsupportedOrBudgetUnknownWork(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            var unsupported = ResourceLocation.fromNamespaceAndPath("pre_m5_unsupported", "recipe");
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of(unsupported.toString()), List.of());
            var before = inventoryIdentity(fixture.player);
            var unsupportedRequest = CraftRequest.one(unsupported).withPartial(true);
            helper.assertValueEqual(create(fixture.player, unsupportedRequest).code(), CraftingResultCode.UNSUPPORTED_RECIPE,
                    "configured ID added unsupported execution capability");
            var clock = new AtomicLong();
            var expired = new SearchBudget(clock::get, 8_000_000L, SearchBudget.MAX_STATES);
            clock.set(8_000_001L);
            helper.assertValueEqual(CraftingService.create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE), false, expired).code(),
                    CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "all-supported converted unknown into partial authority");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unsupported or unknown request changed stock");
            fixture.mode.set(DirectPreparationPolicy.Mode.CUSTOM);
            helper.assertValueEqual(CraftableServerConfig.directPreparationPolicy().allowedRecipes(), List.of(unsupported.toString()),
                    "well-formed unknown ID was not retained for future availability");
            helper.assertTrue(new CraftingRecipes(fixture.player, true).find(unsupported) == null,
                    "unknown configuration created a catalog entry");
        }
        helper.succeed();
    }

    private static CraftRequest request(boolean partial, CraftRequest.PartialPolicy policy) {
        return new CraftRequest(TARGET, 1, partial, false, policy, Map.of());
    }
    private static CraftingService.Prepared prepare(ServerPlayer player, CraftRequest request) {
        return CraftingService.prepare(player, request, EnvironmentSnapshotService.fresh(player), logical());
    }
    private static CraftingService.Outcome create(ServerPlayer player, CraftRequest request) {
        return CraftingService.create(player, request, false, logical());
    }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }
    private static Object inventoryIdentity(ServerPlayer player) {
        return CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory()));
    }
    private static ResourceLocation id(String name) { return ResourceLocation.withDefaultNamespace("pre_m5_consent_" + name); }
    private static RecipeHolder<CraftingRecipe> recipe(ResourceLocation id, Item output, int count, Item... inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        for (var item : inputs) ingredients.add(Ingredient.of(item));
        return new RecipeHolder<>(id, new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output, count), ingredients));
    }

    private static final class Environment implements AutoCloseable {
        final ServerPlayer player;
        final List<RecipeHolder<?>> originalRecipes;
        final BlockPos table;
        final BlockState originalTable;
        final ModConfigSpec.EnumValue<DirectPreparationPolicy.Mode> mode;
        final ModConfigSpec.ConfigValue<List<String>> allowed, confirmation;
        final ModConfigSpec.EnumValue<CraftRequest.PartialPolicy> partial;
        final DirectPreparationPolicy.Mode originalMode;
        final List<String> originalAllowed, originalConfirmation;
        final CraftRequest.PartialPolicy originalPartial;
        Environment(GameTestHelper helper) {
            player = M4PlanningGameTests.player(helper);
            originalRecipes = List.copyOf(player.getServer().getRecipeManager().getRecipes());
            table = player.blockPosition().north();
            originalTable = player.serverLevel().getBlockState(table);
            player.serverLevel().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
            mode = CraftableServerConfig.SPEC.getValues().get("crafting.directPreparationMode");
            allowed = CraftableServerConfig.SPEC.getValues().get("crafting.directPreparationAllowedRecipes");
            confirmation = CraftableServerConfig.SPEC.getValues().get("crafting.directPreparationConfirmationRecipes");
            partial = CraftableServerConfig.SPEC.getValues().get("crafting.partialExecution");
            originalMode = mode.get(); originalAllowed = List.copyOf(allowed.get());
            originalConfirmation = List.copyOf(confirmation.get()); originalPartial = partial.get();
            partial.set(CraftRequest.PartialPolicy.EXPLICIT_SAFE);
        }
        void recipes(List<RecipeHolder<?>> recipes) { player.getServer().getRecipeManager().replaceRecipes(recipes); }
        void chain() {
            recipes(List.of(recipe(A, Items.IRON_INGOT, 1, Items.AMETHYST_SHARD),
                    recipe(B, Items.COPPER_INGOT, 1, Items.IRON_INGOT),
                    recipe(TARGET, Items.GOLD_INGOT, 1, Items.COPPER_INGOT, Items.EMERALD)));
        }
        void stock(Item item) { player.getInventory().clearContent(); player.getInventory().setItem(0, new ItemStack(item)); }
        void policy(DirectPreparationPolicy.Mode value, List<String> allow, List<String> confirm) {
            mode.set(value); allowed.set(allow); confirmation.set(confirm);
        }
        @Override public void close() {
            try { player.getServer().getRecipeManager().replaceRecipes(originalRecipes); }
            finally {
                try {
                    mode.set(originalMode); allowed.set(originalAllowed); confirmation.set(originalConfirmation); partial.set(originalPartial);
                    player.serverLevel().setBlock(table, originalTable, 2);
                } finally { M4PlanningGameTests.remove(player); }
            }
        }
    }
}
