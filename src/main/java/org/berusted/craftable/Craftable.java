package org.berusted.craftable;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.network.CraftablePayloads;
import org.slf4j.Logger;

@Mod(Craftable.MOD_ID)
public final class Craftable {
    public static final String MOD_ID = "craftable";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Craftable(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, CraftableServerConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, CraftableClientConfig.SPEC);
        modEventBus.addListener(CraftablePayloads::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
