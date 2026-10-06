package org.berusted.craftable.planner;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;

/** OR alternatives are a set of precise components, not an ordered list. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M411MissingIdentityGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void reversedOrAlternativesCannotHideAnExtraShortage(GameTestHelper helper) {
        var diamond = new ItemStack(Items.DIAMOND);
        var emerald = new ItemStack(Items.EMERALD);
        var baseline = List.of(new CraftPlan.Missing("0.0", List.of(diamond, emerald), 2));
        var reversed = List.of(new CraftPlan.Missing("0.7", List.of(emerald, diamond), 2));
        var additional = List.of(reversed.getFirst(), new CraftPlan.Missing("0.1", List.of(new ItemStack(Items.STICK)), 1));
        helper.assertTrue(!addsMissing(reversed, baseline) && !addsMissing(baseline, reversed),
                "the order or path of an OR set changed its material identity");
        helper.assertTrue(addsMissing(additional, baseline),
                "reversing the same OR set concealed an extra stick deficit");
        var split = List.of(new CraftPlan.Missing("0.0", List.of(emerald, diamond), 1),
                new CraftPlan.Missing("0.1", List.of(diamond, emerald), 2));
        helper.assertTrue(addsMissing(split, baseline), "equivalent OR deficits were not aggregated");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void differentComponentsRemainDifferentMissingAlternatives(GameTestHelper helper) {
        var diamond = new ItemStack(Items.DIAMOND);
        var named = diamond.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("different exact component"));
        var emerald = new ItemStack(Items.EMERALD);
        var baseline = List.of(new CraftPlan.Missing("0.0", List.of(diamond, emerald), 2));
        var distinct = List.of(new CraftPlan.Missing("0.0", List.of(emerald, named), 2),
                new CraftPlan.Missing("0.1", List.of(new ItemStack(Items.STICK)), 1));
        helper.assertTrue(!addsMissing(distinct, baseline),
                "registry ID comparison erased exact component differences");
        helper.succeed();
    }

    private static boolean addsMissing(List<CraftPlan.Missing> candidate, List<CraftPlan.Missing> baseline) {
        try {
            var comparison = CraftSearch.class.getDeclaredMethod("addsMissing", List.class, List.class);
            comparison.setAccessible(true);
            return (boolean) comparison.invoke(null, candidate, baseline);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("missing identity comparator could not be inspected", failure);
        }
    }
}
