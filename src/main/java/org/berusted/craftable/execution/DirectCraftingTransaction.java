package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.EventHooks;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EndpointKind;

/** Main-thread compensated commit for one already planned craft. */
final class DirectCraftingTransaction {
    private DirectCraftingTransaction() {}

    static CraftingResultCode execute(
            ServerPlayer player, ResourceLocation requestedRecipeId, DirectCraftingPlan plan) {
        if (!DirectCraftingPlanner.hasValidContext(player)
                || !plan.recipe().id().equals(requestedRecipeId)) {
            return CraftingResultCode.INVALID_CONTEXT;
        }
        if (!revalidate(player, plan)) {
            return CraftingResultCode.ENVIRONMENT_CHANGED;
        }
        if (!MainInventoryInsertion.canFitAfterExtractions(player.getInventory(), plan)) {
            return CraftingResultCode.NO_OUTPUT_SPACE;
        }

        TransactionBackup backup = TransactionBackup.capture(player.getInventory(), plan.extractions());
        List<EscrowEntry> escrow = new ArrayList<>();
        try {
            for (DirectCraftingPlan.Extraction extraction : plan.extractions()) {
                ItemStack removed = extraction.endpoint().container().removeItem(
                        extraction.slot(), extraction.count());
                if (removed.getCount() != extraction.count()
                        || !ItemStack.isSameItemSameComponents(removed, extraction.expectedStack())) {
                    restore(player, backup);
                    return CraftingResultCode.ENVIRONMENT_CHANGED;
                }
                escrow.add(new EscrowEntry(extraction.endpoint(), extraction.slot(), removed.copy()));
            }

            if (!MainInventoryInsertion.insertAll(player.getInventory(), plan.producedItems())) {
                restore(player, backup);
                return CraftingResultCode.NO_OUTPUT_SPACE;
            }

            markCommitted(player, plan.extractions());
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error(
                    "Crafting transaction failed before commit for {} and recipe {}; restoring {} escrow entries",
                    player.getGameProfile().getName(),
                    requestedRecipeId,
                    escrow.size(),
                    exception);
            try {
                restore(player, backup);
            } catch (RuntimeException restoreException) {
                exception.addSuppressed(restoreException);
                Craftable.LOGGER.error(
                        "Crafting compensation failed for {} and recipe {}",
                        player.getGameProfile().getName(),
                        requestedRecipeId,
                        restoreException);
            }
            return CraftingResultCode.INTERNAL_ERROR;
        }

        notifyCrafted(player, plan);
        return CraftingResultCode.CREATED;
    }

    private static boolean revalidate(ServerPlayer player, DirectCraftingPlan plan) {
        for (DirectCraftingPlan.Extraction extraction : plan.extractions()) {
            ContainerEndpoint endpoint = extraction.endpoint();
            if (!endpoint.isStillValid(player)
                    || !ItemStack.matches(
                            endpoint.container().getItem(extraction.slot()), extraction.expectedStack())) {
                return false;
            }
        }
        return true;
    }

    private static void markCommitted(
            ServerPlayer player, List<DirectCraftingPlan.Extraction> extractions) {
        extractions.stream()
                .map(DirectCraftingPlan.Extraction::endpoint)
                .filter(endpoint -> endpoint.kind() != EndpointKind.PLAYER)
                .distinct()
                .forEach(endpoint -> endpoint.container().setChanged());
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void notifyCrafted(ServerPlayer player, DirectCraftingPlan plan) {
        try {
            ItemStack craftedForHooks = plan.output();
            craftedForHooks.onCraftedBy(player.serverLevel(), player, craftedForHooks.getCount());
            SimpleContainer eventGrid = new SimpleContainer(plan.gridItems().toArray(ItemStack[]::new));
            EventHooks.firePlayerCraftingEvent(player, craftedForHooks, eventGrid);
            player.triggerRecipeCrafted(plan.recipe(), plan.gridItems());
            player.awardRecipes(Collections.singleton(plan.recipe()));
        } catch (RuntimeException hookException) {
            // Item changes are already committed. Rolling them back after a hook
            // may have changed advancements or other world state would duplicate
            // or tear the transaction, so notification failures are isolated.
            Craftable.LOGGER.error(
                    "Post-commit crafting hook failed for {} and recipe {}",
                    player.getGameProfile().getName(),
                    plan.recipe().id(),
                    hookException);
        }
    }

    private static void restore(ServerPlayer player, TransactionBackup backup) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < backup.playerItems().size(); slot++) {
            inventory.setItem(slot, backup.playerItems().get(slot).copy());
        }
        for (SlotBackup slotBackup : backup.otherSlots()) {
            slotBackup.endpoint().container().setItem(slotBackup.slot(), slotBackup.stack().copy());
            slotBackup.endpoint().container().setChanged();
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }

    private record TransactionBackup(List<ItemStack> playerItems, List<SlotBackup> otherSlots) {
        static TransactionBackup capture(
                Inventory inventory, Collection<DirectCraftingPlan.Extraction> extractions) {
            List<ItemStack> playerItems = new ArrayList<>(36);
            for (int slot = 0; slot < 36; slot++) {
                playerItems.add(inventory.getItem(slot).copy());
            }
            List<SlotBackup> otherSlots = extractions.stream()
                    .filter(extraction -> extraction.endpoint().kind() != EndpointKind.PLAYER)
                    .map(extraction -> new SlotBackup(
                            extraction.endpoint(),
                            extraction.slot(),
                            extraction.endpoint().container().getItem(extraction.slot()).copy()))
                    .toList();
            return new TransactionBackup(List.copyOf(playerItems), otherSlots);
        }
    }

    private record SlotBackup(ContainerEndpoint endpoint, int slot, ItemStack stack) {}

    private record EscrowEntry(ContainerEndpoint endpoint, int slot, ItemStack stack) {}
}
