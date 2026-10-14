package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.DirectPreparationPolicy;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.planner.CraftRequest;

/** Read-only configuration discovery. These messages carry no inventory,
 * browsing lease, execution token or remotely writable setting. */
public final class SettingsPayloads {
    private SettingsPayloads() {}

    public record RulesRequest(long revision) implements CustomPacketPayload {
        public RulesRequest { requireRevision(revision); }
        public static final Type<RulesRequest> TYPE = new Type<>(Craftable.id("settings_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RulesRequest> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeVarLong(p.revision), b -> new RulesRequest(b.readVarLong()));
        @Override public Type<RulesRequest> type() { return TYPE; }
    }

    public record RulesResponse(long revision, EnvironmentScanSettings scan, CraftableServerConfig.CraftingRules crafting)
            implements CustomPacketPayload {
        public RulesResponse {
            requireRevision(revision);
            java.util.Objects.requireNonNull(scan);
            java.util.Objects.requireNonNull(crafting);
        }
        public static final Type<RulesResponse> TYPE = new Type<>(Craftable.id("settings_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RulesResponse> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeVarLong(p.revision); writeScan(b, p.scan); writeCrafting(b, p.crafting); },
                b -> new RulesResponse(b.readVarLong(), readScan(b), readCrafting(b)));
        @Override public Type<RulesResponse> type() { return TYPE; }
    }

    static void writeScan(RegistryFriendlyByteBuf buffer, EnvironmentScanSettings scan) {
        buffer.writeVarInt(scan.horizontalRadius()); buffer.writeVarInt(scan.verticalRadius());
        buffer.writeVarInt(scan.previewCacheTicks()); buffer.writeBoolean(scan.includeEnderChest());
    }

    static EnvironmentScanSettings readScan(RegistryFriendlyByteBuf buffer) {
        return new EnvironmentScanSettings(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    static void writeCrafting(RegistryFriendlyByteBuf buffer, CraftableServerConfig.CraftingRules rules) {
        buffer.writeEnum(rules.partialPolicy()); buffer.writeEnum(rules.surplusDelivery());
        buffer.writeVarInt(rules.maxBatches()); buffer.writeVarInt(rules.maxPlanSteps());
        writeDirectPreparation(buffer, rules.directPreparation());
    }

    static CraftableServerConfig.CraftingRules readCrafting(RegistryFriendlyByteBuf buffer) {
        var partial = buffer.readEnum(CraftRequest.PartialPolicy.class);
        var delivery = buffer.readEnum(CraftableServerConfig.SurplusDelivery.class);
        int batches = CraftingWire.count(buffer, CraftRequest.MAX_BATCHES);
        if (batches == 0) throw new IllegalArgumentException("Zero batch limit");
        int steps = org.berusted.craftable.planner.SearchBudget.requireStepLimit(buffer.readVarInt());
        return new CraftableServerConfig.CraftingRules(partial, delivery, batches, steps, readDirectPreparation(buffer));
    }

    /** Bounded consent values, never new recipe knowledge or authority. */
    static void writeDirectPreparation(RegistryFriendlyByteBuf buffer, DirectPreparationPolicy policy) {
        buffer.writeEnum(policy.mode());
        writeRecipeIds(buffer, policy.allowedRecipes());
        writeRecipeIds(buffer, policy.confirmationRecipes());
    }

    static DirectPreparationPolicy readDirectPreparation(RegistryFriendlyByteBuf buffer) {
        var mode = buffer.readEnum(DirectPreparationPolicy.Mode.class);
        return DirectPreparationPolicy.of(mode, readRecipeIds(buffer), readRecipeIds(buffer));
    }

    private static void writeRecipeIds(RegistryFriendlyByteBuf buffer, java.util.List<String> recipes) {
        buffer.writeVarInt(recipes.size());
        for (var recipe : recipes) buffer.writeUtf(recipe, DirectPreparationPolicy.MAX_RECIPE_ID_LENGTH);
    }

    private static java.util.List<String> readRecipeIds(RegistryFriendlyByteBuf buffer) {
        int count = CraftingWire.count(buffer, DirectPreparationPolicy.MAX_RECIPES);
        var recipes = new java.util.ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            String recipe = buffer.readUtf(DirectPreparationPolicy.MAX_RECIPE_ID_LENGTH);
            if (!DirectPreparationPolicy.validRecipeId(recipe)) throw new IllegalArgumentException("Invalid preparation recipe ID");
            recipes.add(recipe);
        }
        return recipes;
    }

    private static void requireRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("Negative settings revision");
    }
}
