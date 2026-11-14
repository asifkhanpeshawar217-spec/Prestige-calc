
package com.prestige.calculator;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrestigeInventoryScanner {

    private static final Map<Integer, String> PRICES = new TreeMap<>();
    private static final List<String> DEBUG_LINES = new ArrayList<>();

    private static final Pattern PRESTIGE_PATTERN =
            Pattern.compile("(?i)prestige\\s*#?\\s*(\\d+)");

    private static final Pattern PRICE_PATTERN =
            Pattern.compile("(?i)^\\s*price\\s*:\\s*(.+?)\\s*$");

    private static int scannedItems;
    private static String status = "Waiting for inventory...";

    private PrestigeInventoryScanner() {
    }

    public static void scan() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null || client.currentScreen == null) {
            return;
        }

        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            return;
        }

        scannedItems = 0;
        DEBUG_LINES.clear();
        status = "Inventory detected";

        for (var slot : client.player.currentScreenHandler.slots) {
            ItemStack stack = slot.getStack();

            if (stack == null || stack.isEmpty()) {
                continue;
            }

            scannedItems++;

            List<Text> tooltip = TooltipReader.getTooltipLines(stack);
            List<String> lines = new ArrayList<>();

            lines.add(stack.getName().getString());

            for (Text line : tooltip) {
                lines.add(line.getString());
            }

            if (DEBUG_LINES.size() < 8) {
                DEBUG_LINES.add("Item: " + stack.getName().getString());

                for (String line : lines) {
                    if (DEBUG_LINES.size() >= 8) break;
                    DEBUG_LINES.add("  " + line);
                }
            }

            readItem(lines);
        }

        status = "Inventory scanned";
    }

    private static void readItem(List<String> lines) {
        int prestigeLevel = -1;
        String price = null;
        boolean bulkItem = false;

        for (String line : lines) {
            if (line.toLowerCase().contains("bulk prestige")) {
                bulkItem = true;
            }

            Matcher prestigeMatcher = PRESTIGE_PATTERN.matcher(line);

            if (prestigeMatcher.find()) {
                prestigeLevel = Integer.parseInt(prestigeMatcher.group(1));
            }

            Matcher priceMatcher = PRICE_PATTERN.matcher(line);

            if (priceMatcher.find()) {
                price = priceMatcher.group(1).trim();
            }
        }

        if (bulkItem || prestigeLevel < 1 || price == null) {
            return;
        }

        PRICES.put(prestigeLevel, price);
    }

    public static int getPricesCollected() {
        return PRICES.size();
    }

    public static int getScannedItems() {
        return scannedItems;
    }

    public static String getStatus() {
        return status;
    }

    public static List<String> getDebugLines() {
        return Collections.unmodifiableList(DEBUG_LINES);
    }

    public static String getPrice(int prestigeLevel) {
        return PRICES.get(prestigeLevel);
    }

    public static int getMissingPrices(int start, int end) {
        if (start > end) return 0;

        int missing = 0;

        for (int level = start; level <= end; level++) {
            if (!PRICES.containsKey(level)) missing++;
        }

        return missing;
    }

    public static Map<Integer, String> getCollectedPrices() {
        return Collections.unmodifiableMap(PRICES);
    }

    public static void clearPrices() {
        PRICES.clear();
    }
}
