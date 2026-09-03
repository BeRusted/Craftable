package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EndpointKind;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.workstation.WorkstationCapability;

/**
 * Server-authoritative direct crafting for ordinary shaped/shapeless vanilla
 * recipes. Recursive recipes and crafting remainders deliberately remain for M2/M4.
 */
public final class DirectCraftingService {
    private DirectCraftingService() {}

    public static DirectCraftingEvaluation evaluate(ServerPlayer player, ResourceLocation recipeId) {
        return evaluate(player, recipeId, EnvironmentSnapshotService.preview(player));
    }

    public static DirectCraftingEvaluation evaluate(
            ServerPlayer player, ResourceLocation recipeId, EnvironmentSnapshot snapshot) {
        if (player.isSpectator() || player.isDeadOrDying() || player.containerMenu != player.inventoryMenu) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.INVALID_CONTEXT);
        }
        ServerLevel level = player.serverLevel();
        RecipeHolder<?> holder = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.RECIPE_NOT_FOUND);
        }
        if (!"minecraft".equals(recipeId.getNamespace()) || !(holder.value() instanceof CraftingRecipe recipe)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }
        if (recipe.getSerializer() != RecipeSerializer.SHAPED_RECIPE
                && recipe.getSerializer() != RecipeSerializer.SHAPELESS_RECIPE) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }
        if (!recipe.canCraftInDimensions(2, 2)
                && !snapshot.supports(WorkstationCapability.CRAFTING_3X3)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.MISSING_WORKSTATION);
        }

        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || recipe.isSpecial() || recipe.isIncomplete()) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }

        ItemStack output = recipe.getResultItem(level.registryAccess()).copy();
        if (output.isEmpty()) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
        }

        Map<SlotKey, Integer> remainingBySlot = new LinkedHashMap<>();
        Map<SlotKey, Integer> extractionCounts = new LinkedHashMap<>();
        List<ItemStack> consumedItems = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }

            SlotKey match = findMatch(snapshot, ingredient, remainingBySlot);
            if (match == null) {
                return DirectCraftingEvaluation.blocked(CraftingResultCode.MISSING_INGREDIENTS);
            }

            ItemStack source = match.endpoint().container().getItem(match.slot());
            if (source.hasCraftingRemainingItem()) {
                return DirectCraftingEvaluation.blocked(CraftingResultCode.UNSUPPORTED_RECIPE);
            }
            remainingBySlot.put(match, remainingBySlot.get(match) - 1);
            extractionCounts.merge(match, 1, Integer::sum);
            consumedItems.add(source.copyWithCount(1));
        }

        List<DirectCraftingPlan.Extraction> extractions = extractionCounts.entrySet().stream()
                .map(entry -> new DirectCraftingPlan.Extraction(
                        entry.getKey().endpoint(),
                        entry.getKey().slot(),
                        entry.getValue(),
                        entry.getKey().endpoint().container().getItem(entry.getKey().slot())))
                .toList();
        if (!canFitPlayerInventory(player.getInventory(), output, extractions)) {
            return DirectCraftingEvaluation.blocked(CraftingResultCode.NO_OUTPUT_SPACE);
        }

        return DirectCraftingEvaluation.craftable(new DirectCraftingPlan(output, extractions, consumedItems));
    }

    public static CraftingResultCode createOne(ServerPlayer player, ResourceLocation recipeId) {
        // Preview snapshots are deliberately forbidden here: the transaction must
        // start from a new world observation even if the same recipe was just hovered.
        EnvironmentSnapshot snapshot = EnvironmentSnapshotService.fresh(player);
        try {
            return createOne(player, recipeId, snapshot);
        } finally {
            // Any create attempt may have observed or changed resources. Force the
            // next preview to obtain a new generation instead of reusing this view.
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }

    private static CraftingResultCode createOne(
            ServerPlayer player, ResourceLocation recipeId, EnvironmentSnapshot snapshot) {
        DirectCraftingEvaluation evaluation = evaluate(player, recipeId, snapshot);
        DirectCraftingPlan plan = evaluation.plan().orElse(null);
        if (plan == null) {
            return evaluation.resultCode();
        }

        Inventory inventory = player.getInventory();
        List<ItemStack> playerBackup = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) {
            playerBackup.add(inventory.getItem(slot).copy());
        }
        List<SlotBackup> otherBackups = plan.extractions().stream()
                .filter(extraction -> extraction.endpoint().kind() != EndpointKind.PLAYER)
                .map(extraction -> new SlotBackup(
                        extraction.endpoint(), extraction.slot(), extraction.endpoint().container().getItem(extraction.slot()).copy()))
                .toList();

        try {
            for (DirectCraftingPlan.Extraction extraction : plan.extractions()) {
                ContainerEndpoint endpoint = extraction.endpoint();
                ItemStack current = endpoint.container().getItem(extraction.slot());
                if (!endpoint.isStillValid(player)
                        || current.getCount() < extraction.count()
                        || !ItemStack.isSameItemSameComponents(current, extraction.expectedStack())) {
                    restore(inventory, playerBackup, otherBackups);
                    return CraftingResultCode.ENVIRONMENT_CHANGED;
                }

                ItemStack removed = endpoint.container().removeItem(extraction.slot(), extraction.count());
                if (removed.getCount() != extraction.count()
                        || !ItemStack.isSameItemSameComponents(removed, extraction.expectedStack())) {
                    restore(inventory, playerBackup, otherBackups);
                    return CraftingResultCode.ENVIRONMENT_CHANGED;
                }
            }

            ItemStack inserted = plan.output().copy();
            inserted.onCraftedBySystem(player.serverLevel());
            ItemStack craftedForHooks = inserted.copy();
            if (!insertIntoMainInventory(inventory, inserted)) {
                restore(inventory, playerBackup, otherBackups);
                return CraftingResultCode.NO_OUTPUT_SPACE;
            }

            plan.extractions().stream().map(DirectCraftingPlan.Extraction::endpoint).distinct()
                    .forEach(endpoint -> endpoint.container().setChanged());
            inventory.setChanged();
            player.containerMenu.broadcastChanges();
            try {
                craftedForHooks.onCraftedBy(player.serverLevel(), player, craftedForHooks.getCount());
                CriteriaTriggers.RECIPE_CRAFTED.trigger(player, recipeId, plan.consumedItems());
            } catch (RuntimeException hookException) {
                Craftable.LOGGER.error("Post-crafting hook failed for {} and recipe {}", player.getGameProfile().getName(), recipeId, hookException);
            }
            return CraftingResultCode.CREATED;
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Direct crafting transaction failed for {} and recipe {}", player.getGameProfile().getName(), recipeId, exception);
            restore(inventory, playerBackup, otherBackups);
            return CraftingResultCode.INTERNAL_ERROR;
        }
    }

    private static SlotKey findMatch(
            EnvironmentSnapshot snapshot, Ingredient ingredient, Map<SlotKey, Integer> remainingBySlot) {
        for (ContainerEndpoint endpoint : snapshot.endpoints()) {
            Container container = endpoint.container();
            int lastSlot = endpoint.firstSlot() + endpoint.slotCount();
            for (int slot = endpoint.firstSlot(); slot < lastSlot; slot++) {
                SlotKey key = new SlotKey(endpoint, slot);
                ItemStack stack = container.getItem(slot);
                int remaining = remainingBySlot.computeIfAbsent(key, ignored -> stack.getCount());
                if (remaining > 0 && ingredient.test(stack)) {
                    return key;
                }
            }
        }
        return null;
    }

    private static boolean canFitPlayerInventory(
            Inventory inventory, ItemStack output, List<DirectCraftingPlan.Extraction> extractions) {
        List<ItemStack> simulated = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) {
            simulated.add(inventory.getItem(slot).copy());
        }
        for (DirectCraftingPlan.Extraction extraction : extractions) {
            if (extraction.endpoint().kind() == EndpointKind.PLAYER) {
                simulated.get(extraction.slot()).shrink(extraction.count());
            }
        }

        int remaining = output.getCount();
        for (ItemStack stack : simulated) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, output)) {
                remaining -= Math.max(0, Math.min(inventory.getMaxStackSize(stack), output.getMaxStackSize()) - stack.getCount());
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        for (ItemStack stack : simulated) {
            if (stack.isEmpty()) {
                remaining -= Math.min(inventory.getMaxStackSize(output), output.getMaxStackSize());
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean insertIntoMainInventory(Inventory inventory, ItemStack remaining) {
        for (int slot = 0; slot < 36 && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int room = Math.min(inventory.getMaxStackSize(existing), remaining.getMaxStackSize()) - existing.getCount();
            if (room > 0) {
                int moved = Math.min(room, remaining.getCount());
                existing.grow(moved);
                remaining.shrink(moved);
            }
        }
        for (int slot = 0; slot < 36 && !remaining.isEmpty(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(inventory.getMaxStackSize(remaining), remaining.getMaxStackSize());
            inventory.setItem(slot, remaining.split(moved));
        }
        return remaining.isEmpty();
    }

    private static void restore(Inventory inventory, List<ItemStack> playerBackup, List<SlotBackup> otherBackups) {
        for (int slot = 0; slot < playerBackup.size(); slot++) {
            inventory.setItem(slot, playerBackup.get(slot).copy());
        }
        for (SlotBackup backup : otherBackups) {
            backup.endpoint().container().setItem(backup.slot(), backup.stack().copy());
            backup.endpoint().container().setChanged();
        }
        inventory.setChanged();
    }

    private record SlotKey(ContainerEndpoint endpoint, int slot) {}

    private record SlotBackup(ContainerEndpoint endpoint, int slot, ItemStack stack) {}
}
