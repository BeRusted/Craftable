package org.berusted.craftable.client.recipebook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Lightweight client prediction used only to shape the recipe book. The server
 * performs an independent scan before any crafting request is accepted.
 */
public final class ClientWorkstationProbe {
    private static final int HORIZONTAL_RANGE = 8;
    private static final int VERTICAL_RANGE = 4;
    private static final int CACHE_TICKS = 10;

    private static ClientLevel cachedLevel;
    private static BlockPos cachedOrigin = BlockPos.ZERO;
    private static long cachedAt = Long.MIN_VALUE;
    private static boolean cachedResult;

    private ClientWorkstationProbe() {}

    public static boolean hasNearbyCraftingTable() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return false;
        }

        BlockPos origin = minecraft.player.blockPosition();
        long gameTime = level.getGameTime();
        if (level == cachedLevel
                && origin.equals(cachedOrigin)
                && gameTime - cachedAt >= 0
                && gameTime - cachedAt < CACHE_TICKS) {
            return cachedResult;
        }

        cachedLevel = level;
        cachedOrigin = origin.immutable();
        cachedAt = gameTime;
        cachedResult = scan(level, origin);
        return cachedResult;
    }

    private static boolean scan(ClientLevel level, BlockPos origin) {
        BlockPos min = origin.offset(-HORIZONTAL_RANGE, -VERTICAL_RANGE, -HORIZONTAL_RANGE);
        BlockPos max = origin.offset(HORIZONTAL_RANGE, VERTICAL_RANGE, HORIZONTAL_RANGE);
        for (BlockPos candidate : BlockPos.betweenClosed(min, max)) {
            if (level.hasChunk(
                            SectionPos.blockToSectionCoord(candidate.getX()),
                            SectionPos.blockToSectionCoord(candidate.getZ()))
                    && level.getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }
}
