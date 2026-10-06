package org.berusted.craftable.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class CraftableKeyMappings {
    public static final String CATEGORY = "config.key.title";

    public static final KeyMapping CREATE_ONE = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    // Vanilla persists bindings by this name; keep it stable across upgrades.
                    "key.craftable.create_one",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_C,
                    CATEGORY
            )
    );

    public static void register() {
        if (CREATE_ONE == null) {
            throw new IllegalStateException("Craftable 按键注册失败");
        }
    }

    private CraftableKeyMappings() {
    }
}
