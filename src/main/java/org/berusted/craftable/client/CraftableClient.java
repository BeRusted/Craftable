package org.berusted.craftable.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.berusted.craftable.Craftable;

/** Client-only integration points that must never be class-loaded by a dedicated server. */
@Mod(value = Craftable.MOD_ID, dist = Dist.CLIENT)
public final class CraftableClient {
    public CraftableClient(ModContainer container) {
        // Reuse NeoForge's vanilla-styled screen instead of maintaining a
        // second set of widgets, persistence rules, and validation logic.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
