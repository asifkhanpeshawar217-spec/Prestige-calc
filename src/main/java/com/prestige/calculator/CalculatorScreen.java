
package com.prestige.calculator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class CalculatorScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 300;

    public CalculatorScreen() {
        super(Text.literal("Prestige Calculator"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Settings"), button ->
                this.client.setScreen(new SettingsScreen(this))
        ).dimensions(left + 12, top + PANEL_HEIGHT - 34, 90, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button ->
                this.close()
        ).dimensions(left + PANEL_WIDTH - 102, top + PANEL_HEIGHT - 34, 90, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {

        context.fill(0, 0, this.width, this.height, 0x90000000);

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        context.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xEE171B25);

        context.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF65718A);
        context.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF65718A);
        context.fill(left, top, left + 1, top + PANEL_HEIGHT, 0xFF65718A);
        context.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF65718A);

        context.drawCenteredTextWithShadow(
                this.textRenderer, this.title,
                this.width / 2, top + 12, 0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Scanner: " + PrestigeInventoryScanner.getStatus()),
                left + 16, top + 42, 0xFFD8DEE9
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Items detected: " + PrestigeInventoryScanner.getScannedItems()),
                left + 16, top + 60, 0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Prices collected: " + PrestigeInventoryScanner.getPricesCollected()),
                left + 16, top + 78, 0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Debug information:"),
                left + 16, top + 105, 0xFFFFD479
        );

        int y = top + 123;

        for (String line : PrestigeInventoryScanner.getDebugLines()) {
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(line),
                    left + 16, y, 0xFFD8DEE9
            );

            y += 15;
            if (y > top + PANEL_HEIGHT - 48) break;
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(null);
        }
    }
}
