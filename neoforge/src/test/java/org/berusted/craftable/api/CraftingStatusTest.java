package org.berusted.craftable.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CraftingStatusTest {
    @Test
    void exposesExactlyTheThreeFrontendStates() {
        assertEquals(
                EnumSet.of(CraftingStatus.CRAFTABLE, CraftingStatus.PARTIAL, CraftingStatus.BLOCKED),
                EnumSet.allOf(CraftingStatus.class));
    }
}
