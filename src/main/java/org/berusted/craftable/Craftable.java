package org.berusted.craftable;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Craftable.MOD_ID)
public final class Craftable {
    public static final String MOD_ID = "craftable";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Craftable(IEventBus modEventBus, ModContainer modContainer) {
        // Registration is added here as Craftable features are implemented.
    }
}
