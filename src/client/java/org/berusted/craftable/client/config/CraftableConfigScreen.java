package org.berusted.craftable.client.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.berusted.craftable.planner.CraftRequest;

/** Vanilla settings surface available with Fabric API alone. */
public final class CraftableConfigScreen extends Screen {
    private final Screen parent;

    public CraftableConfigScreen(Screen parent) {
        super(Component.translatable("craftable.configuration.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int x = width / 2 - 155;
        int y = Math.max(32, height / 2 - 90);
        booleanOption(x, y, "recipe_book_enhancements", CraftableConfigHandler.Option.RECIPE_BOOK_ENHANCEMENTS);
        booleanOption(x, y + 24, "detailed_failure_feedback", CraftableConfigHandler.Option.DETAILED_FAILURE_FEEDBACK);
        booleanOption(x, y + 48, "unlocked_only", CraftableConfigHandler.Option.UNLOCKED_ONLY);
        booleanOption(x, y + 72, "allow_surplus_drops", CraftableConfigHandler.Option.ALLOW_SURPLUS_DROPS);
        addRenderableWidget(CycleButton.<CraftRequest.PartialPolicy>builder(value ->
                        Component.translatable("config.craftable.partial_policy." + value.name().toLowerCase(java.util.Locale.ROOT)))
                .withValues(CraftRequest.PartialPolicy.values()).withInitialValue(CraftableConfigHandler.partialPolicy())
                .withTooltip(value -> Tooltip.create(label("partial_policy.tooltip")))
                .create(x, y + 96, 310, 20, label("partial_policy"),
                        (button, value) -> CraftableConfigHandler.update(CraftableConfigHandler.Option.PARTIAL_POLICY, value)));
        addRenderableWidget(new AbstractSliderButton(x, y + 120, 310, 20, Component.empty(),
                (CraftableConfigHandler.doublePressMillis() - 150) / 650.0) {
            { updateMessage(); setTooltip(Tooltip.create(label("double_press_millis.tooltip"))); }
            @Override protected void updateMessage() {
                setMessage(label("double_press_millis").append(": " + Math.round(150 + value * 650)));
            }
            @Override protected void applyValue() {
                CraftableConfigHandler.update(CraftableConfigHandler.Option.DOUBLE_PRESS_MILLIS, (int) Math.round(150 + value * 650));
            }
        });
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, y + 152, 200, 20).build());
    }

    private void booleanOption(int x, int y, String key, CraftableConfigHandler.Option option) {
        addRenderableWidget(CycleButton.onOffBuilder(CraftableConfigHandler.get(option))
                .withTooltip(value -> Tooltip.create(label(key + ".tooltip")))
                .create(x, y, 310, 20, label(key), (button, value) -> CraftableConfigHandler.update(option, value)));
    }

    private static net.minecraft.network.chat.MutableComponent label(String key) {
        return Component.translatable("config.craftable.client." + key);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
        super.render(graphics, mouseX, mouseY, tickDelta);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(font, org.berusted.craftable.client.menu.AmbientInventoryEvents.ruleSummary(),
                width / 2, height - 18, 0xAAAAAA);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
