package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.CraftSearch;
import org.berusted.craftable.planner.ResourceLedger;
import org.berusted.craftable.planner.SearchBudget;
import org.berusted.craftable.recipe.CraftingRecipes;

/** Reproducible tiny recipe graphs: OR inputs, cycles, batch surplus and stock competition. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4RandomGraphGameTests {
    private static final Item[] ITEMS = {Items.STONE, Items.DIRT, Items.ANDESITE, Items.GRANITE,
            Items.COBBLESTONE, Items.COPPER_INGOT, Items.GOLD_NUGGET, Items.IRON_NUGGET, Items.AMETHYST_SHARD, Items.QUARTZ};

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 1000)
    public static void tenThousandSeededGraphsConserveActualRecipeDeltas(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var manager = helper.getLevel().getRecipeManager();
        var random = new Random(0xC4AF7ABL);
        int[] produced = {0}, limited = {0};
        // Separate small synchronous batches avoid one long watchdog-blocking
        // tick. Recipe replacement is restored BEFORE yielding back to Minecraft.
        for (int batch = 0; batch < 100; batch++) {
            int currentBatch = batch;
            helper.runAtTickTime(batch + 1L, () -> {
                var original = List.copyOf(manager.getRecipes());
                try {
                    for (int sample = 0; sample < 100; sample++) {
                        int index = currentBatch * 100 + sample;
                        var recipes = new ArrayList<RecipeHolder<?>>();
                        for (int r = 0; r < 5; r++) {
                            var ingredients = NonNullList.<Ingredient>create();
                            for (int j = 0, size = 1 + random.nextInt(3); j < size; j++) {
                                Item a = ITEMS[random.nextInt(ITEMS.length)];
                                ingredients.add(random.nextBoolean() ? Ingredient.of(a)
                                        : Ingredient.of(a, ITEMS[random.nextInt(ITEMS.length)]));
                            }
                            var recipe = new ShapelessRecipe("", CraftingBookCategory.MISC,
                                    new ItemStack(ITEMS[r], 1 + random.nextInt(4)), ingredients);
                            recipes.add(new RecipeHolder<>(ResourceLocation.withDefaultNamespace("m4_random_" + r), recipe));
                        }
                        manager.replaceRecipes(recipes);
                        var sources = new ArrayList<ResourceLedger.Source>();
                        for (int i = 0; i < ITEMS.length; i++) {
                            int count = random.nextInt(5);
                            if (count > 0) sources.add(new ResourceLedger.Source("random", i, new ItemStack(ITEMS[i], count)));
                        }
                        var request = new CraftRequest(recipes.getFirst().id(), 1 + random.nextInt(3),
                                random.nextBoolean(), false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, Map.of());
                        var result = new CraftSearch(new CraftingRecipes(player, true), request, sources,
                                new SearchBudget(2_000_000L), plan -> null).run();
                        if (!result.completeSearch()) limited[0]++;
                        if (result.plan().isEmpty()) continue;
                        produced[0]++;
                        var plan = result.plan().get();
                        helper.assertFalse(plan.partial() && !result.completeSearch(), "Unproven partial at seed " + index);
                        var balance = new HashMap<Item, Integer>();
                        for (var extraction : plan.extractions()) {
                            var source = sources.stream().filter(s -> s.slot() == extraction.slot()).findFirst().orElseThrow();
                            helper.assertTrue(extraction.count() <= source.stack().getCount(), "Overspent stock at seed " + index);
                            add(balance, source.stack().copyWithCount(extraction.count()), 1);
                        }
                        var producedLots = new ArrayList<java.util.List<ItemStack>>();
                        for (var step : plan.steps()) {
                            for (int slot = 0; slot < step.inputs().size(); slot++) {
                                int origin = step.inputOrigins().get(slot);
                                if (origin < 0) continue;
                                var stack = step.inputs().get(slot);
                                var lot = producedLots.get(origin).stream().filter(s -> s.getCount() > 0
                                        && ItemStack.isSameItemSameComponents(s, stack)).findFirst().orElseThrow();
                                helper.assertTrue(lot.getCount() >= stack.getCount(), "Duplicate generated reference at seed " + index);
                                lot.shrink(stack.getCount());
                            }
                            var input = CraftingInput.of(step.gridSize(), step.gridSize(), step.inputs());
                            helper.assertTrue(step.recipe().value().matches(input, player.serverLevel()), "Invalid grid at seed " + index);
                            helper.assertTrue(ItemStack.matches(step.output(),
                                    step.recipe().value().assemble(input, player.registryAccess())), "Invented output at seed " + index);
                            step.inputs().forEach(s -> add(balance, s, -1));
                            add(balance, step.output(), 1);
                            step.remainders().forEach(s -> add(balance, s, 1));
                            var lots = new ArrayList<ItemStack>();
                            lots.add(step.output());
                            lots.addAll(step.remainders());
                            producedLots.add(lots);
                        }
                        plan.primary().forEach(s -> add(balance, s, -1));
                        plan.surplus().forEach(s -> add(balance, s, -1));
                        helper.assertTrue(balance.values().stream().allMatch(n -> n == 0),
                                "Nonconserving recipe delta at seed " + index + ": " + balance);
                    }
                } catch (Throwable failure) {
                    M4PlanningGameTests.remove(player);
                    throw failure;
                } finally {
                    manager.replaceRecipes(original);
                }
                if (currentBatch == 99) {
                    Craftable.LOGGER.warn("M4_RANDOM seed=0xC4AF7AB samples=10000 plans={} limited={}", produced[0], limited[0]);
                    M4PlanningGameTests.remove(player);
                    helper.succeed();
                }
            });
        }
    }

    private static void add(Map<Item, Integer> balance, ItemStack stack, int sign) {
        if (!stack.isEmpty()) balance.merge(stack.getItem(), sign * stack.getCount(), Integer::sum);
    }
}
