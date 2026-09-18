package org.berusted.craftable.client;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.berusted.craftable.api.CraftingResultCode;

/** Routes player-facing results onto the vanilla UI surface suited to their meaning. */
@OnlyIn(Dist.CLIENT)
public final class CraftableFeedback {
    private static final SystemToast.SystemToastId CREATE_FAILURE = new SystemToast.SystemToastId();

    private CraftableFeedback() {}

    public static void showCreateResult(org.berusted.craftable.network.CreateRecipeResultPayload payload, boolean detailed) {
        var code = payload.resultCode();
        if (code == CraftingResultCode.PARTIAL_CREATED || !payload.drops().isEmpty()
                || detailed && code == CraftingResultCode.MISSING_INGREDIENTS && !payload.missing().isEmpty()) {
            var message = Component.empty();
            if (code == CraftingResultCode.PARTIAL_CREATED) {
                message.append(Component.translatable("toast.craftable.prepared", itemList(payload.primary())));
            }
            if (!payload.missing().isEmpty()) {
                if (!message.getSiblings().isEmpty()) message.append("; ");
                var deficits = Component.empty();
                int limit = Math.min(3, payload.missing().size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) deficits.append(", ");
                    var missing = payload.missing().get(i);
                    Component item = missing.alternatives().isEmpty() ? Component.literal("?")
                            : missing.alternatives().getFirst().getHoverName();
                    deficits.append(Component.translatable("toast.craftable.item_count", missing.count(), item));
                    if (missing.alternatives().size() > 1) deficits.append(Component.translatable("toast.craftable.or_equivalent"));
                }
                if (payload.missing().size() > limit) deficits.append("…");
                message.append(Component.translatable("toast.craftable.missing", deficits));
            }
            if (!payload.drops().isEmpty()) {
                if (!message.getSiblings().isEmpty()) message.append("; ");
                message.append(Component.translatable("toast.craftable.dropped", itemList(payload.drops())));
            }
            SystemToast.addOrUpdate(Minecraft.getInstance().getToasts(), CREATE_FAILURE,
                    Component.translatable(code == CraftingResultCode.CREATED || code == CraftingResultCode.PARTIAL_CREATED
                            ? "toast.craftable.result.title" : "toast.craftable.create.failed.title"), message);
            return;
        }
        showCreateResult(code, detailed);
    }

    private static Component itemList(java.util.List<net.minecraft.world.item.ItemStack> stacks) {
        var text = Component.empty();
        for (int i = 0; i < Math.min(3, stacks.size()); i++) {
            if (i > 0) text.append(", ");
            var stack = stacks.get(i);
            text.append(Component.translatable("toast.craftable.item_count", stack.getCount(), stack.getHoverName()));
        }
        if (stacks.size() > 3) text.append("…");
        return text;
    }

    public static void showCreateResult(CraftingResultCode resultCode, boolean detailed) {
        // Ordinary vanilla crafting does not announce every successful item.
        // Seeing the output enter the inventory remains the success feedback.
        if (resultCode == CraftingResultCode.CREATED) {
            return;
        }

        String messageKey = detailed
                ? "message.craftable.create." + resultCode.name().toLowerCase(Locale.ROOT)
                : "message.craftable.create.failed";
        Minecraft minecraft = Minecraft.getInstance();
        // One stable token updates an existing toast when a player retries,
        // matching vanilla placement while preventing a queue of duplicates.
        SystemToast.addOrUpdate(
                minecraft.getToasts(),
                CREATE_FAILURE,
                Component.translatable("toast.craftable.create.failed.title"),
                Component.translatable(messageKey));
    }
}
