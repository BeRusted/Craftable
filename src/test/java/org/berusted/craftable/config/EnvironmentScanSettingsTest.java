package org.berusted.craftable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EnvironmentScanSettingsTest {
    @Test
    void computesTheBoundedScanVolume() {
        EnvironmentScanSettings settings = new EnvironmentScanSettings(8, 4, 5, true);

        assertEquals(2601, settings.scanVolume());
    }

    @Test
    void rejectsAnUnboundedHorizontalRadius() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentScanSettings(17, 4, 5, true));
    }

    @Test
    void rejectsAnUnboundedCacheLifetime() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentScanSettings(8, 4, 21, true));
    }
}
