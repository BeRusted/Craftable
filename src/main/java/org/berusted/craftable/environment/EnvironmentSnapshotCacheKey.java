package org.berusted.craftable.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.config.CraftableServerConfigHandler.EnvironmentScanSettings;

record EnvironmentSnapshotCacheKey(
        ResourceLocation dimension,
        BlockPos origin,
        EnvironmentScanSettings settings) {
    EnvironmentSnapshotCacheKey {
        origin = origin.immutable();
    }

    boolean canReuse(EnvironmentSnapshot snapshot, long currentGameTime) {
        long age = currentGameTime - snapshot.capturedGameTime();
        return dimension.equals(snapshot.dimension().location())
                && origin.equals(snapshot.origin())
                && settings.equals(snapshot.scanSettings())
                && age >= 0
                && age <= settings.previewCacheTicks();
    }
}
