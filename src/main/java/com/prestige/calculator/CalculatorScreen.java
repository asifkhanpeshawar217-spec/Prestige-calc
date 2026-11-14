
package com.prestige.calculator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CalculatorScreen extends Screen {

    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 280;

    private TextFieldWidget rebirthField;
    private TextFieldWidget startField;
    private TextFieldWidget targetField;

    private String result = "Enter your prestige range and calculate.";
    private String missingResult = "";
    private boolean calculationDone = false;

    private static final Pattern COST_PATTERN =
            Pattern.compile("(?i)\\$?([\\d,.]+)\\s*(qt|q|t)");

    public CalculatorScreen() {
        super(Text.literal("Prestige Calculator"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        rebirthField = new TextFieldWidget(
                this.textRenderer, left + 16, top + 82, 120, 20,
                Text.literal("Rebirth")
        );

        startField = new TextFieldWidget(
                this.textRenderer, left + 160, top + 82, 120, 20,
                Text.literal("Starting prestige")
        );

        targetField = new TextFieldWidget(
                this.textRenderer, left + 304, top + 82, 120, 20,
                Text.literal("Target prestige")
        );

        rebirthField.setMaxLength(7);
        startField.setMaxLength(9);
        targetField.setMaxLength(9);

        this.addDrawableChild(rebirthField);
        this.addDrawableChild(startField);
        this.addDrawableChild(targetField);

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Calculate"),
                button -> calculate()
        ).dimensions(left + 150, top + 120, 140, 24).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Settings"),
                button -> this.client.setScreen(new SettingsScreen(this))
        ).dimensions(left + 16, top + PANEL_HEIGHT - 34, 90, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Close"),
                button -> this.close()
        ).dimensions(left + PANEL_WIDTH - 106, top + PANEL_HEIGHT - 34, 90, 20).build());
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
                showError("Target must be higher than starting prestige.");
                return;
            }

            if (target == start) {
                result = "Total cost: $0";
                missingResult = "No upgrades required.";
                calculationDone = true;
                return;
            }

            BigDecimal total = BigDecimal.ZERO;
            int missing = 0;

            // Upgrading from prestige 57 to 60 means buying 58, 59 and 60.
            for (int level = start + 1; level <= target; level++) {
                PrestigeInventoryScanner.PriceData data =
                        PrestigeInventoryScanner.getPrice(rebirth, level);

                if (data == null) {
                    missing++;
                    continue;
                }

                BigDecimal price = parseCost(data.individualCost());

                if (price == null) {
                    missing++;
                    continue;
                }

                total = total.add(price);
            }

            if (missing > 0) {
                result = "Calculation incomplete";
                missingResult = missing + " missing price(s). Scan the relevant rebirth tab.";
                calculationDone = true;
                return;
            }

            result = "Total cost: $" + formatAmount(total);
            missingResult = "All " + (target - start) + " upgrade prices found.";
            calculationDone = true;

        } catch (NumberFormatException exception) {
            showError("Enter valid numbers in all three fields.");
        }
    }

    private BigDecimal parseCost(String value) {
        if (value == null) {
            return null;
        }

        Matcher matcher = COST_PATTERN.matcher(value.trim());

        if (!matcher.find()) {
            return null;
        }

        try {
            BigDecimal amount = new BigDecimal(
                    matcher.group(1).replace(",", "")
            );

            String unit = matcher.group(2).toLowerCase();

            // All values are converted to T before adding.
            return switch (unit) {
                case "t" -> amount;
                case "q" -> amount.multiply(BigDecimal.valueOf(1_000));
                case "qt" -> amount.multiply(BigDecimal.valueOf(1_000_000));
                default -> null;
            };

        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String formatAmount(BigDecimal amount) {
        BigDecimal value;
        String unit;

        if (amount.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            value = amount.divide(BigDecimal.valueOf(1_000_000));
            unit = "qt";
        } else if (amount.compareTo(BigDecimal.valueOf(1_000)) >= 0) {
            value = amount.divide(BigDecimal.valueOf(1_000));
            unit = "q";
        } else {
            value = amount;
            unit = "T";
        }

        return value.stripTrailingZeros().toPlainString() + unit;
    }

    private void showError(String message) {
        result = message;
        missingResult = "";
        calculationDone = true;
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
                Text.literal("Prices collected: " + PrestigeInventoryScanner.getPricesCollected()),
                left + 16, top + 42, 0xFFB8C7E0
        );

        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("Scanner: " + PrestigeInventoryScanner.getStatus()),
                left + 16, top + 57, 0xFFB8C7E0
        );

        context.drawTextWithShadow(
                this.textRenderer, Text.literal("Rebirth"),
                left + 16, top + 67, 0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer, Text.literal("Starting prestige"),
                left + 160, top + 67, 0xFFFFFFFF
        );

        context.drawTextWithShadow(
                this.textRenderer, Text.literal("Target prestige"),
                left + 304, top + 67, 0xFFFFFFFF
        );

        if (calculationDone) {
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(result),
                    left + 16, top + 165, 0xFFFFD479
            );

            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(missingResult),
                    left + 16, top + 185, 0xFFD8DEE9
            );
        } else {
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(result),
                    left + 16, top + 165, 0xFFD8DEE9
            );
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
