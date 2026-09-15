package org.berusted.craftable.api;

import net.minecraft.world.level.GameType;

public final class CraftableModePolicy {
    private CraftableModePolicy() {
    }

    public static boolean allows(GameType mode) {
        return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
    }
}
