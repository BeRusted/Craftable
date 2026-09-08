package org.berusted.craftable.api;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftableModePolicyTest {
    @Test void onlySurvivalAndAdventureHaveCraftableAbilities() {
        assertTrue(CraftableModePolicy.allows(GameType.SURVIVAL));
        assertTrue(CraftableModePolicy.allows(GameType.ADVENTURE));
        assertFalse(CraftableModePolicy.allows(GameType.CREATIVE));
        assertFalse(CraftableModePolicy.allows(GameType.SPECTATOR));
        assertFalse(CraftableModePolicy.allows(null));
    }
}
