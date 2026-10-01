package org.berusted.craftable.environment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.workstation.WorkstationCapability;
import org.berusted.craftable.workstation.WorkstationEndpoint;

/** Performs one bounded, vanilla-only world scan for {@link EnvironmentSnapshotService}. */
final class EnvironmentScanner {
    private static final String VANILLA_NAMESPACE = "minecraft";

    private EnvironmentScanner() {}

    static EnvironmentSnapshot scan(
            ServerPlayer player, EnvironmentScanSettings settings, long generation) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        List<ContainerEndpoint> endpoints = new ArrayList<>();
        List<WorkstationEndpoint> workstations = new ArrayList<>();
        endpoints.add(new ContainerEndpoint(
                "player:" + player.getUUID() + ":inventory",
                EndpointKind.PLAYER,
                null,
                player.getInventory(),
                0,
                36));
        workstations.add(new WorkstationEndpoint(
                "player:" + player.getUUID() + ":crafting_2x2",
                WorkstationCapability.CRAFTING_2X2,
                null));

        boolean enderChestAvailable = false;
        BlockPos enderChestPosition = null;
        Set<BlockPos> scannedChests = new HashSet<>();

        int horizontalRadius = settings.horizontalRadius();
        int verticalRadius = settings.verticalRadius();
        BlockPos min = origin.offset(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = origin.offset(horizontalRadius, verticalRadius, horizontalRadius);
        for (BlockPos mutablePos : BlockPos.betweenClosed(min, max)) {
            int sectionX = SectionPos.blockToSectionCoord(mutablePos.getX());
            int sectionZ = SectionPos.blockToSectionCoord(mutablePos.getZ());
            if (!level.hasChunk(sectionX, sectionZ)) {
                continue;
            }

            BlockPos pos = mutablePos.immutable();
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (block == Blocks.CRAFTING_TABLE) {
                workstations.add(new WorkstationEndpoint(
                        workstationEndpointId(level, pos, WorkstationCapability.CRAFTING_3X3),
                        WorkstationCapability.CRAFTING_3X3,
                        pos));
            }

            if (settings.includeEnderChest() && block instanceof EnderChestBlock) {
                BlockPos above = pos.above();
                if (!enderChestAvailable && !level.getBlockState(above).isRedstoneConductor(level, above)) {
                    enderChestAvailable = true;
                    enderChestPosition = pos;
                }
                continue;
            }

            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (!VANILLA_NAMESPACE.equals(blockId.getNamespace())) {
                continue;
            }

            if (block instanceof ChestBlock chestBlock) {
                BlockPos canonicalPos = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT
                        ? pos.relative(ChestBlock.getConnectedDirection(state))
                        : pos;
                if (scannedChests.add(canonicalPos)) {
                    addChest(player, level, endpoints, canonicalPos, chestBlock);
                }
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)
                    || hasUnresolvedLoot(blockEntity)
                    || !canOpenWithoutSideEffects(player, blockEntity)
                    || !hasPhysicalAccess(level, pos, state, blockEntity)
                    || !container.stillValid(player)) {
                continue;
            }

            endpoints.add(new ContainerEndpoint(
                    blockEndpointId(level, pos),
                    EndpointKind.BLOCK,
                    pos,
                    container,
                    0,
                    container.getContainerSize()));
        }

        if (enderChestAvailable) {
            Container enderInventory = player.getEnderChestInventory();
            endpoints.add(new ContainerEndpoint(
                    "player:" + player.getUUID() + ":ender_chest",
                    EndpointKind.ENDER_CHEST,
                    enderChestPosition,
                    enderInventory,
                    0,
                    enderInventory.getContainerSize()));
        }

        return new EnvironmentSnapshot(
                level.dimension(),
                origin,
                settings,
                level.getGameTime(),
                generation,
                endpoints,
                workstations);
    }

    private static void addChest(
            ServerPlayer player,
            ServerLevel level,
            List<ContainerEndpoint> endpoints,
            BlockPos pos,
            ChestBlock chestBlock) {
        int sectionX = SectionPos.blockToSectionCoord(pos.getX());
        int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
        if (!level.hasChunk(sectionX, sectionZ)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return;
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.RIGHT) {
            return;
        }

        BlockEntity first = level.getBlockEntity(pos);
        if (hasUnresolvedLoot(first) || !canOpenWithoutSideEffects(player, first)) {
            return;
        }

        if (type != ChestType.SINGLE) {
            BlockPos partnerPos = pos.relative(ChestBlock.getConnectedDirection(state));
            int partnerSectionX = SectionPos.blockToSectionCoord(partnerPos.getX());
            int partnerSectionZ = SectionPos.blockToSectionCoord(partnerPos.getZ());
            if (!level.hasChunk(partnerSectionX, partnerSectionZ)) {
                return;
            }
            BlockEntity partner = level.getBlockEntity(partnerPos);
            if (hasUnresolvedLoot(partner) || !canOpenWithoutSideEffects(player, partner)) {
                return;
            }
        }

        Container container = ChestBlock.getContainer(chestBlock, state, level, pos, false);
        if (container == null || !container.stillValid(player)) {
            return;
        }

        endpoints.add(new ContainerEndpoint(
                blockEndpointId(level, pos),
                EndpointKind.BLOCK,
                pos,
                container,
                0,
                container.getContainerSize()));
    }

    private static boolean hasUnresolvedLoot(BlockEntity blockEntity) {
        return blockEntity instanceof RandomizableContainerBlockEntity randomizable
                && randomizable.getLootTable() != null;
    }

    private static boolean canOpenWithoutSideEffects(ServerPlayer player, BlockEntity blockEntity) {
        if (!(blockEntity instanceof BaseContainerBlockEntity baseContainer)) {
            return true;
        }

        // BaseContainerBlockEntity.canOpen sends a message and plays a sound when locked.
        // A background preview scan must be observational, so read the public component
        // snapshot and perform the same key comparison without invoking those effects.
        LockCode lock = baseContainer.collectComponents().getOrDefault(DataComponents.LOCK, LockCode.NO_LOCK);
        return player.isSpectator() || lock.unlocksWith(player.getMainHandItem());
    }

    private static boolean hasPhysicalAccess(
            ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(state.getBlock() instanceof ShulkerBoxBlock)
                || !(blockEntity instanceof ShulkerBoxBlockEntity shulkerBox)
                || shulkerBox.getAnimationStatus() != ShulkerBoxBlockEntity.AnimationStatus.CLOSED) {
            return true;
        }
        return level.noCollision(Shulker.getProgressDeltaAabb(
                        1.0F, state.getValue(ShulkerBoxBlock.FACING), 0.0F, 0.5F)
                .move(pos)
                .deflate(1.0E-6));
    }

    private static String blockEndpointId(ServerLevel level, BlockPos pos) {
        return "block:" + level.dimension().location() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String workstationEndpointId(
            ServerLevel level, BlockPos pos, WorkstationCapability capability) {
        return "workstation:" + level.dimension().location() + ":"
                + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":"
                + capability.name().toLowerCase(java.util.Locale.ROOT);
    }
}
