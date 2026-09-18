package org.berusted.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;

public record CreateRecipeResultPayload(
        ResourceLocation recipeId,
        long requestId,
        CraftingResultCode resultCode,
        java.util.List<net.minecraft.world.item.ItemStack> primary,
        java.util.List<org.berusted.craftable.planner.CraftPlan.Missing> missing,
        java.util.List<net.minecraft.world.item.ItemStack> drops)
        implements CustomPacketPayload {
    public CreateRecipeResultPayload {
        primary = org.berusted.craftable.planner.CraftPlan.copies(primary);
        missing = java.util.List.copyOf(missing);
        drops = org.berusted.craftable.planner.CraftPlan.copies(drops);
    }
    public CreateRecipeResultPayload(ResourceLocation recipe, long request, CraftingResultCode result) {
        this(recipe, request, result, java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
    @Override public java.util.List<net.minecraft.world.item.ItemStack> primary() {
        return org.berusted.craftable.planner.CraftPlan.copies(primary);
    }
    @Override public java.util.List<net.minecraft.world.item.ItemStack> drops() {
        return org.berusted.craftable.planner.CraftPlan.copies(drops);
    }
    public static CreateRecipeResultPayload from(ResourceLocation recipe, long request,
            org.berusted.craftable.execution.CraftingService.Outcome outcome) {
        boolean committed = outcome.code() == CraftingResultCode.CREATED || outcome.code() == CraftingResultCode.PARTIAL_CREATED;
        return new CreateRecipeResultPayload(recipe, request, outcome.code(),
                committed && outcome.plan() != null ? outcome.plan().primary() : java.util.List.of(),
                outcome.missing(), outcome.drops());
    }
    public static final Type<CreateRecipeResultPayload> TYPE = new Type<>(Craftable.id("create_recipe_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateRecipeResultPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> {
                        buffer.writeResourceLocation(payload.recipeId());
                        buffer.writeVarLong(payload.requestId());
                        buffer.writeEnum(payload.resultCode());
                        CraftingWire.stacks(buffer, payload.primary());
                        CraftingWire.missing(buffer, payload.missing());
                        CraftingWire.stacks(buffer, payload.drops());
                    },
                    buffer -> new CreateRecipeResultPayload(
                            buffer.readResourceLocation(),
                            buffer.readVarLong(),
                            buffer.readEnum(CraftingResultCode.class), CraftingWire.stacks(buffer),
                            CraftingWire.missing(buffer), CraftingWire.stacks(buffer)));

    @Override
    public Type<CreateRecipeResultPayload> type() {
        return TYPE;
    }
}
