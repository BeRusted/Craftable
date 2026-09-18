package org.berusted.craftable.execution;

import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.PlanView;
import org.berusted.craftable.recipe.CraftingRecipes;

@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class M4DetailsGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void oversizedDetailsReplaceBothGraphAndConfirmationToken(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        try {
            var large = new ItemStack(Items.DIAMOND);
            large.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("x".repeat(30000)));
            var view = new PlanView(org.berusted.craftable.api.CraftingResultCode.CREATED, true, true, 1,
                    List.of(), List.of(), List.of(large, large, large), List.of(new ItemStack(Items.STICK)),
                    List.of(), List.of(), List.of());
            var payload = new org.berusted.craftable.network.CraftingDetailPayloads.PreviewResponse(0, 7,
                    new CraftingService.Draft(java.util.UUID.randomUUID(), view, new PlanView.Choices(List.of(), false)));
            org.berusted.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() < 65536, "oversized detail escaped byte cap");
            var decoded = org.berusted.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.decode(buffer);
            helper.assertValueEqual(decoded.draft().token(), org.berusted.craftable.network.CraftingDetailPayloads.NO_TOKEN,
                    "hidden plan retained executable token");
            helper.assertValueEqual(decoded.draft().view().code(),
                    org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "oversize not explicit");
            helper.assertFalse(decoded.draft().view().complete(), "truncated view called complete");
            var local = org.berusted.craftable.network.CraftingDetailPayloads.boundedLocal(payload.draft(), player.registryAccess());
            helper.assertValueEqual(local.view().code(), org.berusted.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                    "local display bypassed the wire size bound");
        } finally { buffer.release(); M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void graphKeepsActualSharedOriginsAndNetCosts(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var result = M4PlanningGameTests.search(player, "diamond_pickaxe", 1, false, true,
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
            var view = PlanView.from(new CraftingRecipes(player, true), request("diamond_pickaxe"), result, List.of());
            helper.assertTrue(view.complete(), "incomplete ordinary display");
            helper.assertValueEqual(view.operations().size(), 3, "operation count");
            helper.assertValueEqual(M4PlanningGameTests.count(view.consumed(), Items.OAK_LOG), 1, "net log cost");
            helper.assertValueEqual(M4PlanningGameTests.count(view.consumed(), Items.OAK_PLANKS), 0, "intermediate counted as stock");
            helper.assertValueEqual(view.operations().get(2).inputOrigins().stream().filter(i -> i == 1).count(),
                    2L, "both stick inputs must reference the same producing batch");
            view.consumed().getFirst().setCount(60);
            helper.assertValueEqual(M4PlanningGameTests.count(view.consumed(), Items.OAK_LOG), 1, "mutable display cost escaped");
            var stickPath = result.plan().orElseThrow().steps().get(1).path();
            var choices = view.choices(new CraftingRecipes(player, true), request("diamond_pickaxe"), stickPath);
            helper.assertTrue(choices.candidates().stream().anyMatch(c -> c.recipe().getPath().equals("stick"))
                    && choices.candidates().stream().anyMatch(c -> c.recipe().getPath().equals("stick_from_bamboo_item")),
                    "missing alternative stick recipes");
            helper.assertTrue(view.choices(new CraftingRecipes(player, true), request("diamond_pickaxe"), "0.8.8.8")
                    .candidates().isEmpty(), "undisplayed demand exposed candidates");
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
            try {
                var payload = new org.berusted.craftable.network.CraftingDetailPayloads.PreviewResponse(0, 1,
                        new CraftingService.Draft(java.util.UUID.randomUUID(), view, choices));
                org.berusted.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.encode(buffer, payload);
                helper.assertTrue(buffer.readableBytes() < 65536, "ordinary graph exceeds byte cap");
                var decoded = org.berusted.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.decode(buffer);
                helper.assertValueEqual(decoded.draft().view().operations().get(2).inputOrigins(),
                        view.operations().get(2).inputOrigins(), "wire lost shared origins");
                helper.assertValueEqual(org.berusted.craftable.planner.CraftPlan.stackKeys(decoded.draft().view().consumed()),
                        org.berusted.craftable.planner.CraftPlan.stackKeys(view.consumed()), "wire changed costs");
                helper.assertValueEqual(decoded.draft().view().reviewIdentity(), view.reviewIdentity(),
                        "new stack objects or wire token changed the reviewed values");
                var changed = new PlanView(view.code(), view.workbench(), view.complete(), view.completedBatches(),
                        view.nodes(), view.operations(), view.consumed(), view.primary(), view.surplus(),
                        List.of(new ItemStack(Items.OAK_PLANKS)), view.missing());
                helper.assertFalse(changed.reviewIdentity().equals(view.reviewIdentity()),
                        "new overflow must require another explicit review");
            } finally { buffer.release(); }
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void blockedExplanationNeverFabricatesCostsOrCompletedRoot(GameTestHelper helper) {
        var player = M4PlanningGameTests.player(helper);
        try {
            var result = M4PlanningGameTests.search(player, "diamond_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_LOG));
            var view = PlanView.from(new CraftingRecipes(player, true), request("diamond_pickaxe").withPartial(true), result, List.of());
            helper.assertTrue(view.complete(), "bounded partial display");
            helper.assertValueEqual(view.completedBatches(), 0, "invented completed root");
            helper.assertValueEqual(M4PlanningGameTests.count(view.primary(), Items.STICK), 4, "partial frontier");
            helper.assertValueEqual(M4PlanningGameTests.count(view.consumed(), Items.DIAMOND), 0, "explanation consumed diamonds");
            helper.assertTrue(view.nodes().stream().anyMatch(n -> n.path().equals("0") && n.explanation()),
                    "blocked root shown as actual operation");
            helper.assertValueEqual(view.operations().size(), 2, "explanation appended fake operations");
            helper.assertTrue(view.nodes().stream().anyMatch(n -> !n.reference().isEmpty()),
                    "partial explanation did not share actual stick frontier");
            helper.assertTrue(view.nodes().stream().noneMatch(n -> n.recipes().stream().anyMatch(id -> id.getPath().equals("diamond_block"))),
                    "missing diamonds explained as a redundant conversion cycle");
        } finally { M4PlanningGameTests.remove(player); }
        helper.succeed();
    }

    private static CraftRequest request(String id) {
        return CraftRequest.one(ResourceLocation.withDefaultNamespace(id));
    }
}
