package org.berusted.craftable.config;

import org.berusted.craftable.client.config.CraftableConfigHandler;
import org.berusted.craftable.planner.CraftRequest;

/** Client settings backed by the existing Fabric JSON configuration. */
public final class CraftableClientConfig {
    private CraftableClientConfig() {}
    public static boolean recipeBookEnhancementsEnabled() { return CraftableConfigHandler.recipeBookEnhancementsEnabled(); }
    public static boolean detailedFailureFeedbackEnabled() { return CraftableConfigHandler.detailedFailureFeedbackEnabled(); }
    public static boolean unlockedOnly() { return CraftableConfigHandler.unlockedOnly(); }
    public static CraftRequest.PartialPolicy partialPolicy() { return CraftableConfigHandler.partialPolicy(); }
    public static boolean allowSurplusDrops() { return CraftableConfigHandler.allowSurplusDrops(); }
    public static int doublePressMillis() { return CraftableConfigHandler.doublePressMillis(); }
}
