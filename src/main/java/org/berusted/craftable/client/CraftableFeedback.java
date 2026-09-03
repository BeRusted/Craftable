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
