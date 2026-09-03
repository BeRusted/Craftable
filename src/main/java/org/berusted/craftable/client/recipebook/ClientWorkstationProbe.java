package org.berusted.craftable.client.recipebook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.EnvironmentScanSettings;

/**
 * Lightweight client prediction used only to shape the recipe book. The server
 * performs an independent scan before any crafting request is accepted.
 */
public final class ClientWorkstationProbe {
    private static ClientLevel cachedLevel;
    private static BlockPos cachedOrigin = BlockPos.ZERO;
    private static long cachedAt = Long.MIN_VALUE;
    private static boolean cachedResult;
    private static EnvironmentScanSettings cachedSettings;

    private ClientWorkstationProbe() {}

    public static boolean hasNearbyCraftingTable() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return false;
        }

        EnvironmentScanSettings settings = CraftableServerConfig.scanSettings();
        BlockPos origin = minecraft.player.blockPosition();
        long gameTime = level.getGameTime();
        if (level == cachedLevel
                && origin.equals(cachedOrigin)
                && settings.equals(cachedSettings)
                && gameTime - cachedAt >= 0
                && gameTime - cachedAt <= settings.previewCacheTicks()) {
            return cachedResult;
        }

        cachedLevel = level;
        cachedOrigin = origin.immutable();
        cachedAt = gameTime;
        cachedSettings = settings;
        cachedResult = scan(level, origin, settings);
        return cachedResult;
    }

    public static void clear() {
        cachedLevel = null;
        cachedOrigin = BlockPos.ZERO;
        cachedAt = Long.MIN_VALUE;
        cachedResult = false;
        cachedSettings = null;
    }

    private static boolean scan(
            ClientLevel level, BlockPos origin, EnvironmentScanSettings settings) {
        int horizontalRadius = settings.horizontalRadius();
        int verticalRadius = settings.verticalRadius();
        BlockPos min = origin.offset(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = origin.offset(horizontalRadius, verticalRadius, horizontalRadius);
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
