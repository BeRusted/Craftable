package org.berusted.craftable.environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.junit.jupiter.api.Test;

class EnvironmentSnapshotCacheKeyTest {
    private static final EnvironmentScanSettings SETTINGS = new EnvironmentScanSettings(8, 4, 5, true);

    @Test
    void reusesOnlyTheSameContextWithinItsAgeBudget() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
                Level.OVERWORLD, BlockPos.ZERO, SETTINGS, 100, 7, List.of(), List.of());
        EnvironmentSnapshotCacheKey same = new EnvironmentSnapshotCacheKey(
                Level.OVERWORLD.location(), BlockPos.ZERO, SETTINGS);
        EnvironmentSnapshotCacheKey moved = new EnvironmentSnapshotCacheKey(
                Level.OVERWORLD.location(), BlockPos.ZERO.above(), SETTINGS);

        assertTrue(same.canReuse(snapshot, 105));
        assertFalse(same.canReuse(snapshot, 106));
        assertFalse(same.canReuse(snapshot, 99));
        assertFalse(moved.canReuse(snapshot, 101));
    }
}
