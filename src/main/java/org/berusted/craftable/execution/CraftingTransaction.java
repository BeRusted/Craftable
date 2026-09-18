package org.berusted.craftable.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EndpointKind;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.planner.CraftPlan;

/** The sole chain commit: all inventory and optional surplus entities, or their preimages. */
final class CraftingTransaction {
    private CraftingTransaction() {}

    static CraftingResultCode execute(ServerPlayer player, CraftPlan plan, EnvironmentSnapshot snapshot,
            MainInventoryInsertion.Delivery delivery) {
        if (!CraftingService.validContext(player)) return CraftingResultCode.INVALID_CONTEXT;
        List<SlotBackup> otherSlots = new ArrayList<>();
        for (var extraction : plan.extractions()) {
            var endpoint = snapshot.endpoint(extraction.endpointId()).orElse(null);
            if (endpoint == null || !endpoint.containsSlot(extraction.slot()) || !endpoint.isStillValid(player)
                    || !ItemStack.matches(endpoint.container().getItem(extraction.slot()), extraction.expected())) {
                return CraftingResultCode.ENVIRONMENT_CHANGED;
            }
            if (endpoint.kind() != EndpointKind.PLAYER) {
                otherSlots.add(new SlotBackup(endpoint, extraction.slot(), extraction.expected()));
            }
        }
        if (delivery.failure() != null) return delivery.failure();
        List<ItemStack> playerBefore = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) playerBefore.add(player.getInventory().getItem(slot).copy());
        List<ItemEntity> created = new ArrayList<>();
        CraftingResultCode failed = CraftingResultCode.ENVIRONMENT_CHANGED;
        try {
            for (var extraction : plan.extractions()) {
                var endpoint = snapshot.endpoint(extraction.endpointId()).orElseThrow();
                ItemStack removed = endpoint.container().removeItem(extraction.slot(), extraction.count());
                if (removed.getCount() != extraction.count()
                        || !ItemStack.isSameItemSameComponents(removed, extraction.expected())) {
                    throw new Rejected();
                }
            }
            MainInventoryInsertion.publish(player.getInventory(), delivery);
            for (ItemStack stack : delivery.drops()) {
                ItemEntity entity = captureVanillaDrop(player, stack);
                if (entity == null) throw new Rejected();
                created.add(entity); // Track before hooks can cancel/throw.
                if (NeoForge.EVENT_BUS.post(new ItemTossEvent(entity, player)).isCanceled()
                        || !ItemStack.matches(entity.getItem(), stack)
                        || !player.serverLevel().addFreshEntity(entity)
                        || !ItemStack.matches(entity.getItem(), stack)
                        || player.serverLevel().getEntity(entity.getUUID()) != entity) {
                    throw new Rejected();
                }
            }
            // No normal entity tick/pickup can interleave this synchronous
            // section. Custom hooks mutating arbitrary worlds are not supported
            // transaction participants; do not claim database atomicity for them.
            var expectedDrops = delivery.drops();
            for (int i = 0; i < created.size(); i++) {
                ItemEntity entity = created.get(i);
                if (entity.isRemoved() || !ItemStack.matches(entity.getItem(), expectedDrops.get(i))
                        || player.serverLevel().getEntity(entity.getUUID()) != entity) throw new Rejected();
            }
            markChanged(player, snapshot, plan);
        } catch (RuntimeException exception) {
            if (!(exception instanceof Rejected)) {
                failed = CraftingResultCode.INTERNAL_ERROR;
                Craftable.LOGGER.error("Chain commit failed before completion for {}", plan.target(), exception);
            }
            // Remove exactly this transaction's entities BEFORE restoring
            // inputs. A positional sweep could delete another player's drops.
            for (ItemEntity entity : created) entity.discard();
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, playerBefore.get(slot).copy());
            for (var backup : otherSlots) {
                backup.endpoint.container().setItem(backup.slot, backup.stack.copy());
                backup.endpoint.container().setChanged();
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return failed;
        }

        notifyCrafted(player, plan, created);
        return plan.partial() ? CraftingResultCode.PARTIAL_CREATED : CraftingResultCode.CREATED;
    }

    private static ItemEntity captureVanillaDrop(ServerPlayer player, ItemStack stack) {
        // The server override ignores addFreshEntity's boolean result. Capture
        // construction only, preserve its motion/40-tick pickup delay, and check
        // insertion ourselves. No irreversible drop stats run before commit.
        var captured = new ArrayList<ItemEntity>();
        var previous = player.captureDrops(captured);
        try {
            ItemEntity entity = player.drop(stack.copy(), false, false);
            if (entity != null) entity.setThrower(player);
            return entity;
        } finally {
            player.captureDrops(previous);
        }
    }

    private static void markChanged(ServerPlayer player, EnvironmentSnapshot snapshot, CraftPlan plan) {
        plan.extractions().stream().map(e -> e.endpointId()).distinct().forEach(id ->
                snapshot.endpoint(id).orElseThrow().container().setChanged());
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void notifyCrafted(ServerPlayer player, CraftPlan plan, List<ItemEntity> drops) {
        for (var step : plan.steps()) {
            // Post-commit observers also see intermediate crafts. One failing
            // listener must neither refund the chain nor suppress later steps.
            try {
                ItemStack output = step.output();
                output.onCraftedBy(player.serverLevel(), player, output.getCount());
                EventHooks.firePlayerCraftingEvent(player, output,
                        new SimpleContainer(step.inputs().toArray(ItemStack[]::new)));
                player.triggerRecipeCrafted(step.recipe(), step.inputs());
                player.awardRecipes(Collections.singleton(step.recipe()));
            } catch (RuntimeException exception) {
                Craftable.LOGGER.error("Post-commit recipe observer failed for {}", step.recipe().id(), exception);
            }
        }
        for (ItemEntity entity : drops) {
            try {
                player.awardStat(Stats.ITEM_DROPPED.get(entity.getItem().getItem()), entity.getItem().getCount());
                player.awardStat(Stats.DROP);
            } catch (RuntimeException exception) {
                // Statistics are post-commit observers, never a reason to
                // report failure after the player already received the items.
                Craftable.LOGGER.error("Post-commit drop statistic failed", exception);
            }
        }
    }

    private record SlotBackup(ContainerEndpoint endpoint, int slot, ItemStack stack) {}
    private static final class Rejected extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
