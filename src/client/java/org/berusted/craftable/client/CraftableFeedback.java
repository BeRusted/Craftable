package org.berusted.craftable.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import org.berusted.craftable.api.CraftingResultCode;

public final class CraftableFeedback {
    private static final SystemToast.SystemToastId CREATE_FAILURE =
            new SystemToast.SystemToastId();

    private CraftableFeedback() {}

    public static void showCreateResult(
            CraftingResultCode resultCode,
            boolean detailed
    ) {
        if (resultCode == CraftingResultCode.CREATED) {
            return;
        }

        String messageKey = detailed
                ? "message.create."
                + resultCode.name().toLowerCase(Locale.ROOT)
                : "message.create.failed";

        Minecraft minecraft = Minecraft.getInstance();

        SystemToast.addOrUpdate(
                minecraft.getToasts(),
                CREATE_FAILURE,
                Component.translatable(
                        "toast.create.failed.title"
                ),
                Component.translatable(messageKey)
        );
    }
}