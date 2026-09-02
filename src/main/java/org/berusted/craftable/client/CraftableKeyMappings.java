package org.berusted.craftable.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.berusted.craftable.Craftable;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class CraftableKeyMappings {
    public static final String CATEGORY = "key.categories.craftable";

    public static final KeyMapping CREATE_ONE = new KeyMapping(
            "key.craftable.create_one",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY);

    public static final KeyMapping UNDO_LAST = new KeyMapping(
            "key.craftable.undo_last",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY);

    private CraftableKeyMappings() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(CREATE_ONE);
        event.register(UNDO_LAST);
    }
}
