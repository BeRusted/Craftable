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

    /**
     * 必须在客户端初始化阶段（{@code GameOptions} 构造之前）调用。
     * <p>
     * 调用本方法本身即触发类初始化，从而完成 {@link KeyBindingHelper#registerKeyBinding}。
     * 若延后到首次使用 {@link #CREATE_ONE} 时才惰性初始化，Fabric 会抛出
     * {@code IllegalStateException: GameOptions has already been initialised}。
     */
    public static void register() {
        if (CREATE_ONE == null || UNDO_LAST == null) {
            throw new IllegalStateException("Craftable 按键注册失败");
        }
    }

    private CraftableKeyMappings() {
    }
}