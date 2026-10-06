package org.berusted.craftable.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.junit.jupiter.api.Test;

class CraftableCommandsTest {
    @Test
    void environmentSummaryUsesOnlySupportedTranslationArguments() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
                Level.OVERWORLD,
                BlockPos.ZERO,
                new EnvironmentScanSettings(8, 4, 5, true),
                42,
                1,
                List.of(),
                List.of());

        assertDoesNotThrow(() -> CraftableCommands.environmentSummary(snapshot));
    }
}
