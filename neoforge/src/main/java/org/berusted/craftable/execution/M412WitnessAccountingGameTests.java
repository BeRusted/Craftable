package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Untrusted multi-batch routes exercise the same bounded final accounting as
 * bulk witness replay. Component-only cases isolate that private final check;
 * they cannot bypass the normal source/recipe/provenance entry or authorize. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M412WitnessAccountingGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void differentRecipesAtOnePathCannotHideAncestorOrInverse(GameTestHelper helper) {
        try (var environment = new Environment(helper)) {
            // P, then Q, share an intermediate demand path. A map retaining
            // only Q would erase the earlier P ancestor of the descendant P.
            var route = environment.route(List.of(recipe("root", Items.DIAMOND, 1, Items.STICK),
                    recipe("p", Items.STICK, 1, Items.STICK), recipe("q", Items.STICK, 1, Items.COBBLESTONE)),
                    new ItemStack(Items.STICK), new ItemStack(Items.COBBLESTONE));
            route.add("p", "0.0.0", new Item[]{Items.STICK}, -1);
            route.add("p", "0.0", new Item[]{Items.STICK}, 0);
            route.add("q", "0.0", new Item[]{Items.COBBLESTONE}, -1);
            route.add("root", "0", new Item[]{Items.STICK}, 1);
            route.add("root", "0", new Item[]{Items.STICK}, 2);
            rejected(() -> route.validate("root", 2), "later same-path recipe concealed repeated ancestor");

            var inverse = environment.route(List.of(recipe("root", Items.DIAMOND, 1, Items.STICK),
                    recipe("p", Items.STICK, 2, Items.COBBLESTONE), recipe("q", Items.STICK, 1, Items.OAK_LOG),
                    recipe("child", Items.COBBLESTONE, 1, Items.STICK, Items.STICK)),
                    new ItemStack(Items.STICK, 2), new ItemStack(Items.OAK_LOG));
            inverse.add("child", "0.0.0", new Item[]{Items.STICK, Items.STICK}, -1, -1);
            inverse.add("p", "0.0", new Item[]{Items.COBBLESTONE}, 0);
            inverse.add("q", "0.0", new Item[]{Items.OAK_LOG}, -1);
            inverse.add("root", "0", new Item[]{Items.STICK}, 1);
            inverse.add("root", "0", new Item[]{Items.STICK}, 1);
            inverse.add("root", "0", new Item[]{Items.STICK}, 2);
            rejected(() -> inverse.validate("root", 3), "later same-path recipe concealed direct inverse");
            helper.assertValueEqual(environment.player.getInventory().countItem(Items.STICK), 2,
                    "rejected untrusted routes changed actual stock");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void wholeBatchRootOutputsAndReturnedContainersKeepOriginalRules(GameTestHelper helper) {
        try (var environment = new Environment(helper)) {
            var extra = environment.route(List.of(recipe("root", Items.DIAMOND, 1, Items.STICK),
                    recipe("producer", Items.STICK, 4, Items.COBBLESTONE)), new ItemStack(Items.COBBLESTONE, 2));
            extra.add("producer", "0.0", new Item[]{Items.COBBLESTONE}, -1);
            extra.add("producer", "0.0", new Item[]{Items.COBBLESTONE}, -1);
            extra.add("root", "0", new Item[]{Items.STICK}, 0);
            extra.add("root", "0", new Item[]{Items.STICK}, 1);
            rejected(() -> extra.validate("root", 2), "whole removable intermediate batch was accepted");

            var roots = environment.route(List.of(recipe("root", Items.STICK, 2, Items.STICK),
                    recipe("producer", Items.STICK, 4, Items.COBBLESTONE)), new ItemStack(Items.COBBLESTONE));
            roots.add("producer", "0.0", new Item[]{Items.COBBLESTONE}, -1);
            for (int i = 0; i < 3; i++) roots.add("root", "0", new Item[]{Items.STICK}, 0);
            var plan = roots.validate("root", 3);
            helper.assertValueEqual(M4PlanningGameTests.count(plan.primary(), Items.STICK), 6,
                    "root output incorrectly participated in intermediate spare grouping");
            helper.assertValueEqual(M4PlanningGameTests.count(plan.surplus(), Items.STICK), 1, "batch rounding changed");

            var returned = environment.route(List.of(recipe("root", Items.DIAMOND, 1, Items.STICK, Items.BUCKET),
                    recipe("producer", Items.STICK, 2, Items.MILK_BUCKET)), new ItemStack(Items.MILK_BUCKET));
            returned.add("producer", "0.0", new Item[]{Items.MILK_BUCKET}, -1);
            returned.add("root", "0", new Item[]{Items.STICK, Items.BUCKET}, 0, 0);
            plan = returned.validate("root", 1);
            helper.assertValueEqual(M4PlanningGameTests.count(plan.primary(), Items.DIAMOND), 1, "returned bucket lost provenance");
            helper.assertValueEqual(M4PlanningGameTests.count(plan.surplus(), Items.STICK), 1, "remainder inflated used main outputs");
            helper.assertValueEqual(M4PlanningGameTests.count(plan.surplus(), Items.BUCKET), 0, "used returned bucket was duplicated");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void exactOutputGroupsKeepComponentsOverflowAndBudgetBounds(GameTestHelper helper) {
        var plain = new ItemStack(Items.STICK, 4);
        var named = plain.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("different exact component"));
        accounting(List.of(accountingStep(plain), accountingStep(named)), new int[]{1, 1}, logical());
        accounting(List.of(accountingStep(plain), accountingStep(new ItemStack(Items.DIAMOND, 4))),
                new int[]{1, 1}, logical()); // Same empty component patch is not the same item.
        rejected(() -> accounting(List.of(accountingStep(plain), accountingStep(plain)), new int[]{1, 1}, logical()),
                "identical output groups concealed a whole removable batch");
        boolean overflow = false;
        try {
            var huge = new ItemStack(Items.STICK, Integer.MAX_VALUE);
            accounting(List.of(accountingStep(huge), accountingStep(huge)), new int[]{1, 1}, logical());
        } catch (ArithmeticException expected) { overflow = true; }
        helper.assertTrue(overflow, "intermediate spare overflow wrapped into an accepted count");
        var clock = new java.util.concurrent.atomic.AtomicLong();
        rejected(() -> accounting(List.of(accountingStep(plain)), new int[]{3},
                new SearchBudget(clock::getAndIncrement, 1, SearchBudget.MAX_STATES)), "final accounting ignored expired CPU allowance");
        helper.succeed();
    }

    private static CraftPlan.Step accountingStep(ItemStack output) {
        var holder = recipe("account", Items.STICK, 1, Items.COBBLESTONE);
        return new CraftPlan.Step(holder, "0.0", 2,
                List.of(new ItemStack(Items.COBBLESTONE), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), output, List.of());
    }

    private static void accounting(List<CraftPlan.Step> steps, int[] used, SearchBudget budget) {
        try {
            var method = CraftWitnessValidator.class.getDeclaredMethod("validateIntermediateOutputs", List.class, int[].class, SearchBudget.class);
            method.setAccessible(true);
            method.invoke(null, steps, used, budget);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static RecipeHolder<CraftingRecipe> recipe(String id, Item output, int count, Item... inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        for (var item : inputs) ingredients.add(Ingredient.of(item));
        return new RecipeHolder<>(id(id), new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output, count), ingredients));
    }

    private static ResourceLocation id(String name) { return ResourceLocation.withDefaultNamespace("m412_witness_" + name); }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }
    private static void rejected(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static final class Route {
        final ServerPlayer player;
        final CraftingRecipes recipes;
        final ArrayList<CraftPlan.Step> steps = new ArrayList<>();
        Route(ServerPlayer player) { this.player = player; recipes = new CraftingRecipes(player, true); }
        void add(String recipe, String path, Item[] inputItems, int... origins) {
            var grid = new ArrayList<ItemStack>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
            var originList = new ArrayList<Integer>(List.of(-1, -1, -1, -1));
            for (int i = 0; i < inputItems.length; i++) { grid.set(i, new ItemStack(inputItems[i])); originList.set(i, origins[i]); }
            var assembled = recipes.assemble(recipes.find(id(recipe)), path, grid);
            if (assembled == null) throw new AssertionError("ordinary fixture recipe did not assemble");
            steps.add(new CraftPlan.Step(assembled.recipe(), path, 2, grid, assembled.output(), assembled.remainders(), originList));
        }
        CraftPlan validate(String root, int batches) {
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var fabricated = new CraftPlan(id(root), batches, batches, steps, List.of(),
                    steps.stream().filter(step -> step.path().equals("0")).map(CraftPlan.Step::output).toList(), List.of(), List.of(), false);
            var witness = CraftPlan.Witness.from(fabricated, snapshot);
            return CraftWitnessValidator.validate(recipes, CraftRequest.one(id(root)).withBatches(batches), witness, snapshot.sources(), logical());
        }
    }

    private static final class Environment implements AutoCloseable {
        final ServerPlayer player;
        final List<RecipeHolder<?>> original;
        Environment(GameTestHelper helper) { player = M4PlanningGameTests.player(helper); original = List.copyOf(player.getServer().getRecipeManager().getRecipes()); }
        Route route(List<RecipeHolder<CraftingRecipe>> recipes, ItemStack... stock) {
            player.getServer().getRecipeManager().replaceRecipes(List.<RecipeHolder<?>>copyOf(recipes));
            player.getInventory().clearContent();
            for (int i = 0; i < stock.length; i++) player.getInventory().setItem(i, stock[i]);
            return new Route(player);
        }
        @Override public void close() { player.getServer().getRecipeManager().replaceRecipes(original); M4PlanningGameTests.remove(player); }
    }
}
