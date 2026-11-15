package com.prestige.calculator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class CalculatorScreen extends Screen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 270;

    private TextFieldWidget rebirthField;
    private TextFieldWidget startField;
    private TextFieldWidget targetField;

    private String result = "Enter a rebirth and prestige range, then calculate.";
    private String detail = "";
    private boolean calculationDone;

    public CalculatorScreen() {
        super(Text.literal("Prestige Calculator"));
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;

        rebirthField = new TextFieldWidget(textRenderer, left + 16, top + 91, 120, 20,
                Text.literal("Rebirth"));
        startField = new TextFieldWidget(textRenderer, left + 170, top + 91, 120, 20,
                Text.literal("Starting prestige"));
        targetField = new TextFieldWidget(textRenderer, left + 324, top + 91, 120, 20,
                Text.literal("Target prestige"));

        rebirthField.setMaxLength(7);
        startField.setMaxLength(9);
        targetField.setMaxLength(9);

        addDrawableChild(rebirthField);
        addDrawableChild(startField);
        addDrawableChild(targetField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Calculate"), button -> calculate())
                .dimensions(left + 160, top + 132, 140, 24).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(left + PANEL_WIDTH - 96, top + PANEL_HEIGHT - 32, 80, 20).build());
    }

    private void calculate() {
        try {
            int rebirth = Integer.parseInt(rebirthField.getText().trim());
            int start = Integer.parseInt(startField.getText().trim());
            int target = Integer.parseInt(targetField.getText().trim());

            if (rebirth < 0 || start < 0 || target < 0) {
                showError("Values cannot be negative.");
                return;
            }
            if (target < start) {
                showError("Target prestige must be at least the starting prestige.");
                return;
            }
            if (target == start) {
                result = "Total cost: 0T";
                detail = "No upgrades required.";
                calculationDone = true;
                return;
            }
            if (PriceCatalogue.getPriceCount() == 0) {
                result = "Price catalogue is not loaded yet.";
                detail = PriceCatalogue.getStatus();
                calculationDone = true;
                return;
            }

            BigDecimal knownTotal = BigDecimal.ZERO;
            List<Integer> missingLevels = new ArrayList<>();
            int requestedPaidLevels = 0;

            for (int level = start + 1; level <= target; level++) {
                // Prestige 1 is always free and is intentionally not in the price file.
                if (level == 1) continue;
                requestedPaidLevels++;

                BigDecimal price = PriceCatalogue.getPrice(rebirth, level);
                if (price == null) {
                    missingLevels.add(level);
                } else {
                    knownTotal = knownTotal.add(price);
                }
            }

            if (missingLevels.isEmpty()) {
                result = "Total cost: " + formatAmount(knownTotal);
                detail = "All " + requestedPaidLevels + " paid prestige levels are covered.";
            } else {
                result = "Known subtotal: " + formatAmount(knownTotal);
                StringBuilder missing = new StringBuilder("Missing ");
                missing.append(missingLevels.size()).append(" price(s): ");
                int shown = Math.min(8, missingLevels.size());
                for (int i = 0; i < shown; i++) {
                    if (i > 0) missing.append(", ");
                    missing.append("P").append(missingLevels.get(i));
                }
                if (missingLevels.size() > shown) missing.append(", ...");
                detail = missing.toString();
            }

            calculationDone = true;
        } catch (NumberFormatException exception) {
            showError("Enter valid whole numbers in all three fields.");
        }
    }

    private String formatAmount(BigDecimal amount) {
        BigDecimal value;
        String unit;

        if (amount.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            value = amount.divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP);
            unit = "qt";
        } else if (amount.compareTo(BigDecimal.valueOf(1_000)) >= 0) {
            value = amount.divide(BigDecimal.valueOf(1_000), 6, java.math.RoundingMode.HALF_UP);
            unit = "q";
        } else {
            value = amount;
            unit = "T";
        }

        return value.stripTrailingZeros().toPlainString() + unit;
    }

    private void showError(String message) {
        result = message;
        detail = "";
        calculationDone = true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x90000000);

        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;

        context.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xEE171B25);
        context.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF65718A);
        context.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF65718A);
        context.fill(left, top, left + 1, top + PANEL_HEIGHT, 0xFF65718A);
        context.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF65718A);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top + 12, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Catalogue: " + PriceCatalogue.getStatus()),
                left + 16, top + 43, 0xFFB8C7E0);
        context.drawTextWithShadow(textRenderer,
                Text.literal("Available prices: " + PriceCatalogue.getPriceCount()
                        + " across " + PriceCatalogue.getRebirthCount() + " rebirths"),
                left + 16, top + 62, 0xFFB8C7E0);

        context.drawTextWithShadow(textRenderer, Text.literal("Rebirth"), left + 16, top + 76, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Starting prestige"), left + 170, top + 76, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Target prestige"), left + 324, top + 76, 0xFFFFFFFF);

        if (calculationDone) {
            context.drawTextWithShadow(textRenderer, Text.literal(result),
                    left + 16, top + 178, 0xFFFFD479);
            context.drawTextWithShadow(textRenderer, Text.literal(detail),
                    left + 16, top + 199, 0xFFD8DEE9);
        } else {
            context.drawTextWithShadow(textRenderer, Text.literal(result),
                    left + 16, top + 178, 0xFFD8DEE9);
        }

        context.drawTextWithShadow(textRenderer,
                Text.literal("P1 is free. Only recorded prices are used; missing prices are never estimated."),
                left + 16, top + PANEL_HEIGHT - 28, 0xFF9FB3D9);

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
        if (client != null) client.setScreen(null);
    }
}
