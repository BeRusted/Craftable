package org.berusted.craftable.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.config.DirectPreparationPolicy;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.SearchBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsPayloadTest {
    @Test void preparationPolicyRoundTripCanonicalAndBoundedInRulesAndLease() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                java.util.List.of("future:recipe", "minecraft:stick"), java.util.List.of("minecraft:stick"));
        var rules = new CraftableServerConfig.CraftingRules(CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, 128, policy);
        try {
            var response = new SettingsPayloads.RulesResponse(20, new EnvironmentScanSettings(8, 4, 5, true), rules);
            SettingsPayloads.RulesResponse.CODEC.encode(buffer, response);
            assertEquals(response, SettingsPayloads.RulesResponse.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
            buffer.clear();
            var lease = new CraftingDetailPayloads.BrowseLease(1, 20, java.util.UUID.randomUUID(), 2, 3, 100,
                    "a".repeat(64), null, response.scan(), true, rules);
            CraftingDetailPayloads.BrowseLease.CODEC.encode(buffer, lease);
            assertEquals(lease, CraftingDetailPayloads.BrowseLease.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
            var ids = java.util.stream.IntStream.range(0, DirectPreparationPolicy.MAX_RECIPES)
                    .mapToObj(i -> "test:" + "x".repeat(118) + i).toList();
            var largest = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.ALL_SUPPORTED, ids, ids);
            buffer.clear(); SettingsPayloads.writeDirectPreparation(buffer, largest);
            assertTrue(buffer.readableBytes() < CraftingWire.MAX_DETAIL_BYTES);
            assertEquals(largest, SettingsPayloads.readDirectPreparation(buffer));
        } finally { buffer.release(); }
    }

    @Test void malformedPreparationCountsIdsModesAndTruncationFailBeforeUse() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(129);
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(-1);
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(1); buffer.writeUtf("stick");
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(1);
            buffer.writeUtf("test:" + "x".repeat(124)); // 129 characters, before any second-list parsing.
            assertThrows(RuntimeException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeVarInt(99);
            assertThrows(RuntimeException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(1);
            assertThrows(RuntimeException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
        } finally { buffer.release(); }
    }

    @Test void effectiveRulesRoundTripWithoutAResourceGrant() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var request = new SettingsPayloads.RulesRequest(19);
            SettingsPayloads.RulesRequest.CODEC.encode(buffer, request);
            assertEquals(request, SettingsPayloads.RulesRequest.CODEC.decode(buffer));
            buffer.clear();
            var response = new SettingsPayloads.RulesResponse(19, new EnvironmentScanSettings(3, 2, 0, false),
                    new CraftableServerConfig.CraftingRules(CraftRequest.PartialPolicy.NEVER,
                            CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW, 17, 256));
            SettingsPayloads.RulesResponse.CODEC.encode(buffer, response);
            assertTrue(buffer.readableBytes() < 64);
            assertEquals(response, SettingsPayloads.RulesResponse.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void ruleBoundsAndMalformedMessagesFailClosed() {
        for (int steps : new int[]{0, 257, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new CraftableServerConfig.CraftingRules(
                    CraftRequest.PartialPolicy.NEVER, CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, steps));
        assertThrows(IllegalArgumentException.class, () -> new SettingsPayloads.RulesRequest(-1));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarLong(1); SettingsPayloads.writeScan(buffer, new EnvironmentScanSettings(8, 4, 5, true));
            buffer.writeEnum(CraftRequest.PartialPolicy.NEVER);
            buffer.writeEnum(CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE);
            buffer.writeVarInt(64); buffer.writeVarInt(257);
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.RulesResponse.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void hardWitnessCeilingIsIndependentFromTheDefaultWorldLimit() {
        assertEquals(128, SearchBudget.DEFAULT_MAX_STEPS);
        assertEquals(256, SearchBudget.HARD_MAX_STEPS);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var body = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            body.writeUUID(java.util.UUID.randomUUID()); body.writeVarLong(0); body.writeVarLong(0);
            body.writeVarInt(257); // Rejected before any grid/step allocation or ingredient decoding.
            buffer.writeBoolean(true); buffer.writeVarInt(body.readableBytes()); buffer.writeBytes(body);
            assertThrows(IllegalArgumentException.class, () -> CraftingWire.witness(buffer));
        } finally { body.release(); buffer.release(); }
    }
}
