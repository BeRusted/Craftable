package org.berusted.craftable.client.config;

import org.berusted.craftable.planner.CraftRequest;

/** World-owned rules; storage is handled by the Fabric configuration owner. */
public final class CraftableServerConfig {
    private CraftableServerConfig() {}
    public static CraftingRules craftingRules() { return CraftableServerConfigHandler.craftingRules(); }
    public static EnvironmentScanSettings scanSettings() { return CraftableServerConfigHandler.scanSettings(); }
    public enum SurplusDelivery { REQUIRE_SPACE, DROP_OVERFLOW }
    public record CraftingRules(CraftRequest.PartialPolicy partialPolicy, SurplusDelivery surplusDelivery, int maxBatches) {}
}
