
package com.prestige.calculator;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrestigeInventoryScanner {

    public record PriceData(
            int rebirth,
            int prestige,
            String individualCost,
            String cumulativeSpent
    ) {}

    private static final NavigableMap<Integer, NavigableMap<Integer, PriceData>> PRICES =
            new TreeMap<>();

    private static final List<String> DEBUG_LINES = new ArrayList<>();

    private static final Pattern REBIRTH_PATTERN =
            Pattern.compile("(?i)rebirth\\s*#?\\s*(\\d+)");

    private static final Pattern PRESTIGE_PATTERN =
            Pattern.compile("(?i)prestige\\s*#?\\s*(\\d+)");

    private static final Pattern COST_PATTERN =
            Pattern.compile("(?i)cost\\s*:\\s*\\$\\s*([\\d,.]+)\\s*([a-z]+)?");

    private static final Pattern TOTAL_PATTERN =
            Pattern.compile("(?i)\\[\\s*([\\d,.]+)\\s*(qt|q|t|b|m|k)\\s*\\]");

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

            readItem(lines);

            for (String line : lines) {
                String lower = line.toLowerCase();

                if (lower.contains("rebirth")
                        || lower.contains("prestige")
                        || lower.contains("cost:")
                        || lower.contains("qt")
                        || lower.contains("already completed")
                        || lower.contains("complete previous")) {

                    if (DEBUG_LINES.size() < 10 && !DEBUG_LINES.contains(line)) {
                        DEBUG_LINES.add(line);
                    }
                }
            }
        }

        status = "Inventory scanned";
    }

    private static void readItem(List<String> lines) {
        StringBuilder combined = new StringBuilder();

        for (String line : lines) {
            combined.append(line).append(" ");
        }

        String text = combined.toString();

        if (text.toLowerCase().contains("bulk prestige")) {
            return;
        }

        Matcher rebirthMatcher = REBIRTH_PATTERN.matcher(text);
        Matcher prestigeMatcher = PRESTIGE_PATTERN.matcher(text);
        Matcher costMatcher = COST_PATTERN.matcher(text);
        Matcher totalMatcher = TOTAL_PATTERN.matcher(text);

        if (!rebirthMatcher.find()
                || !prestigeMatcher.find()
                || !costMatcher.find()) {
            return;
        }

        int rebirth = Integer.parseInt(rebirthMatcher.group(1));
        int prestige = Integer.parseInt(prestigeMatcher.group(1));

        String costNumber = costMatcher.group(1);
        String costUnit = costMatcher.group(2);

        if (costUnit == null || costUnit.isBlank()) {
            costUnit = "T";
        }

        String individualCost = "$" + costNumber + costUnit;

        String cumulativeSpent = null;

        if (totalMatcher.find()) {
            cumulativeSpent = totalMatcher.group(1) + totalMatcher.group(2);
        }

        PriceData data = new PriceData(
                rebirth,
                prestige,
                individualCost,
                cumulativeSpent
        );

        PRICES.computeIfAbsent(rebirth, ignored -> new TreeMap<>())
                .put(prestige, data);
    }

    public static int getPricesCollected() {
        int count = 0;

        for (NavigableMap<Integer, PriceData> prices : PRICES.values()) {
            count += prices.size();
        }

        return count;
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

    public static PriceData getPrice(int rebirth, int prestige) {
        NavigableMap<Integer, PriceData> prices = PRICES.get(rebirth);
        return prices == null ? null : prices.get(prestige);
    }

    public static String getPrice(int prestige) {
        for (NavigableMap<Integer, PriceData> prices : PRICES.descendingMap().values()) {
            PriceData data = prices.get(prestige);

            if (data != null) {
                return data.individualCost();
            }
        }

        return null;
    }

    public static int getMissingPrices(int rebirth, int start, int end) {
        if (start > end) {
            return 0;
        }

        int missing = 0;
        NavigableMap<Integer, PriceData> prices = PRICES.get(rebirth);

        for (int level = start; level <= end; level++) {
            if (prices == null || !prices.containsKey(level)) {
                missing++;
            }
        }

        return missing;
    }

    public static int getMissingPrices(int start, int end) {
        if (PRICES.isEmpty()) {
            return Math.max(0, end - start + 1);
        }

        return getMissingPrices(PRICES.lastKey(), start, end);
    }

    public static Map<Integer, NavigableMap<Integer, PriceData>> getAllPrices() {
        return Collections.unmodifiableMap(PRICES);
    }

    public static void clearPrices() {
        PRICES.clear();
        DEBUG_LINES.clear();
        scannedItems = 0;
    }
}
