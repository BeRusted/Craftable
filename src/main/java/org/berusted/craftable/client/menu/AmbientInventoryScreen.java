package org.berusted.craftable.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;

/** Vanilla inventory artwork/entity rendering over the unchanged crafting interaction implementation. */
public final class AmbientInventoryScreen extends CraftingScreen {
    private static final ResourceLocation INVENTORY = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");
    private static final ResourceLocation CRAFTING = ResourceLocation.withDefaultNamespace("textures/gui/container/crafting_table.png");
    private ImageButton bookButton;

    public AmbientInventoryScreen(CraftingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override protected void init() {
        super.init();
        bookButton = children().stream().filter(ImageButton.class::isInstance).map(ImageButton.class::cast)
                .findFirst().orElse(null);
        positionBookButton();
    }

    private void positionBookButton() {
        if (bookButton != null) bookButton.setPosition(leftPos + 76, topPos + 38);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        boolean handled = super.mouseClicked(x, y, button);
        // Vanilla repositions this same button after toggling the recipe book.
        positionBookButton();
        return handled;
    }

    @Override protected void renderLabels(GuiGraphics gui, int x, int y) {
        // Nine inputs plus the output use the former title area. No workbench
        // inventory heading is drawn over the vanilla character/armor layout.
    }

    @Override protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        gui.blit(INVENTORY, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        // Clear only the old 2x2/arrow/result area; retain vanilla equipment,
        // character backdrop, inventory/hotbar and outer border pixel-for-pixel.
        gui.fill(leftPos + 96, topPos + 5, leftPos + 173, topPos + 80, 0xFFC6C6C6);
        for (int i = 0; i < 10; i++) {
            var slot = menu.getSlot(i);
            gui.blit(INVENTORY, leftPos + slot.x - 1, topPos + slot.y - 1, 7, 7, 18, 18);
        }
        gui.blit(CRAFTING, leftPos + 105, topPos + 63, 90, 35, 22, 15);
        InventoryScreen.renderEntityInInventoryFollowsMouse(gui, leftPos + 26, topPos + 8,
                leftPos + 75, topPos + 78, 30, 0.0625F, mouseX, mouseY, minecraft.player);
    }
}
