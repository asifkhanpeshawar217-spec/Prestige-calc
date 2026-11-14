
package com.prestige.calculator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;

public final class DiscoveryScreen extends Screen {

    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 290;

    private String exportMessage = "Visit each rebirth tab to discover its prestige entries.";

    public DiscoveryScreen() {
        super(Text.literal("Prestige Discovery"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Export Catalogue"),
                button -> exportCatalogue()
        ).dimensions(left + 16, top + PANEL_HEIGHT - 34, 130, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Back"),
                button -> this.client.setScreen(new CalculatorScreen())
        ).dimensions(left + PANEL_WIDTH - 106, top + PANEL_HEIGHT - 34, 90, 20).build());
    }

    private void exportCatalogue() {
        try {
            String path = PrestigeDiscoveryScanner.exportCatalogue();
            exportMessage = "Exported: " + path;
        } catch (IOException exception) {
            exportMessage = "Export failed: " + exception.getMessage();
        }
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
                this.textRenderer,
                this.title,
                this.width / 2,
                top + 12,
                0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Status: " + PrestigeDiscoveryScanner.getStatus()),
                left + 16,
                top + 42,
                0xFFD8DEE9
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Items scanned in current menu: " + PrestigeDiscoveryScanner.getScannedItems()),
                left + 16,
                top + 62,
                0xFFD8DEE9
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Rebirths discovered: " + PrestigeDiscoveryScanner.getDiscoveredRebirthCount()),
                left + 16,
                top + 82,
                0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Prestige entries discovered: " + PrestigeDiscoveryScanner.getDiscoveredPrestigeCount()),
                left + 16,
                top + 102,
                0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Recently discovered:"),
                left + 16,
                top + 130,
                0xFFFFD479
        );

        int y = top + 150;

        for (String entry : PrestigeDiscoveryScanner.getRecentDiscoveries()) {
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(entry),
                    left + 16,
                    y,
                    0xFFD8DEE9
            );

            y += 14;

            if (y > top + PANEL_HEIGHT - 58) {
                break;
            }
        }

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal(exportMessage),
                left + 16,
                top + PANEL_HEIGHT - 52,
                0xFF9FB3D9
        );

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
}
