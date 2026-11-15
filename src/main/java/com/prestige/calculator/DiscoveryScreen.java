package com.prestige.calculator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;

public final class DiscoveryScreen extends Screen {
    private static final int WIDTH = 440;
    private static final int HEIGHT = 260;
    private String message = "Open /prestige. Price scanning starts automatically.";

    public DiscoveryScreen() {
        super(Text.literal("Prestige Price Scanner"));
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("Export Prices"), button -> {
            try {
                message = "Saved: " + PrestigeInventoryScanner.exportPrices();
            } catch (IOException exception) {
                message = "Export failed: " + exception.getMessage();
            }
        }).dimensions(left + 16, top + HEIGHT - 36, 110, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"),
                button -> client.setScreen(new CalculatorScreen()))
                .dimensions(left + WIDTH - 96, top + HEIGHT - 36, 80, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x90000000);
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        context.fill(left, top, left + WIDTH, top + HEIGHT, 0xEE171B25);
        context.fill(left, top, left + WIDTH, top + 1, 0xFF65718A);
        context.fill(left, top + HEIGHT - 1, left + WIDTH, top + HEIGHT, 0xFF65718A);
        context.fill(left, top, left + 1, top + HEIGHT, 0xFF65718A);
        context.fill(left + WIDTH - 1, top, left + WIDTH, top + HEIGHT, 0xFF65718A);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top + 12, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Status: " + PrestigeInventoryScanner.getStatus()),
                left + 16, top + 48, 0xFFD8DEE9);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Prices collected: " + PrestigeInventoryScanner.getPricesCollected()),
                left + 16, top + 72, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Pages scanned: " + PrestigeInventoryScanner.getPagesScanned()),
                left + 16, top + 94, 0xFFD8DEE9);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Items scanned on page: " + PrestigeInventoryScanner.getScannedItems()),
                left + 16, top + 116, 0xFFD8DEE9);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Prestige entries without readable Cost on page: "
                        + PrestigeInventoryScanner.getMissingCostItemsThisPage()),
                left + 16, top + 138, 0xFFFFD479);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Prices are saved automatically to prestige_prices.json"),
                left + 16, top + 168, 0xFF9FB3D9);
        context.drawTextWithShadow(textRenderer, Text.literal(message),
                left + 16, top + 190, 0xFF9FB3D9);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public boolean shouldPause() { return false; }
}
