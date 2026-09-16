package org.berusted.craftable.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class CraftableKeyMappings {
    public static final String CATEGORY = "key.categories.craftable";

    public static final KeyMapping CREATE_ONE = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.craftable.create_one",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_C,
                    CATEGORY
            )
    );

    public static final KeyMapping UNDO_LAST = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.craftable.undo_last",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_Z,
                    CATEGORY
            )
    );

    public static void register() {
        if (CREATE_ONE == null || UNDO_LAST == null) {
            throw new IllegalStateException("Craftable 按键注册失败");
        }
    }

    private CraftableKeyMappings() {
    }
}