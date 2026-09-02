package org.berusted.craftable.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.berusted.craftable.client.recipebook.ClientRecipeStatusStore;

@OnlyIn(Dist.CLIENT)
final class ClientPayloadHandler {
    private ClientPayloadHandler() {}

    static void handle(RecipeStatusResponsePayload payload) {
        ClientRecipeStatusStore.put(
                payload.recipeId(), payload.status(), payload.resultCode(), payload.generation());
    }

    static void handle(CreateRecipeResultPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.displayClientMessage(
                Component.translatable("message.craftable.create." + payload.resultCode().name().toLowerCase()),
                true);
    }
}
