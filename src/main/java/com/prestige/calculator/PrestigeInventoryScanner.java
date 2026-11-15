package com.prestige.calculator;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrestigeInventoryScanner {
    public record PriceData(int rebirth, int prestige, String individualCost, String cumulativeSpent) {}

    private static final NavigableMap<Integer, NavigableMap<Integer, PriceData>> PRICES = new TreeMap<>();
    private static final List<String> DEBUG_LINES = new ArrayList<>();

    private static final Pattern REBIRTH_PATTERN =
            Pattern.compile("(?i)\\brebirth\\b\\s*(?:level\\s*)?[:#-]?\\s*(\\d+)\\b");
    private static final Pattern PRESTIGE_PATTERN =
            Pattern.compile("(?i)\\bprestige\\b\\s*(?:level\\s*)?[:#-]?\\s*(\\d+)\\b");
    private static final Pattern COST_PATTERN =
            Pattern.compile("(?i)\\bcost\\s*:\\s*\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([a-z]+)?");
    private static final Pattern TOTAL_PATTERN =
            Pattern.compile("(?i)\\[\\s*([\\d,.]+)\\s*(qt|q|t|b|m|k)\\s*\\]");
    private static final String NEXT_PAGE_TOOLTIP = "click to view next page";
    private static final String PRICE_FILE = "prestige_prices.json";
    private static final String AUDIT_FILE = "prestige_price_scan_audit.txt";

    private static int scannedItems;
    private static int pagesScanned;
    private static int pricesAddedThisPage;
    private static int missingCostItemsThisPage;
    private static int currentRebirth;
    private static int unchangedPageChecks;
    private static boolean waitingForPageChange;
    private static boolean scanFinished;
    private static String previousPageFingerprint = "";
    private static String status = "Waiting for /prestige menu...";

    private PrestigeInventoryScanner() {}

    public static void scan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !(client.currentScreen instanceof HandledScreen<?>)) {
            if (!previousPageFingerprint.isEmpty()) {
                status = "Menu closed. Prices saved; scanner paused.";
                resetNavigation();
            }
            return;
        }
        if (client.interactionManager == null) return;

        var handler = client.player.currentScreenHandler;
        int containerCount = Math.max(0, handler.slots.size() - 36);
        if (containerCount == 0) return;

        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < containerCount; i++) slots.add(handler.slots.get(i));

        if (!isPrestigeMenu(slots)) {
            if (!previousPageFingerprint.isEmpty()) {
                status = "Prestige menu not detected. Scanner paused.";
                resetNavigation();
            }
            return;
        }
        if (scanFinished) return;

        String fingerprint = fingerprint(slots);
        if (waitingForPageChange) {
            if (fingerprint.equals(previousPageFingerprint)) {
                unchangedPageChecks++;
                if (unchangedPageChecks >= 10) {
                    status = "Next page did not change. Check the audit file; scan stopped.";
                    waitingForPageChange = false;
                    scanFinished = true;
                    save(client);
                }
                return;
            }
            waitingForPageChange = false;
            unchangedPageChecks = 0;
        }
        if (fingerprint.equals(previousPageFingerprint)) return;

        pagesScanned++;
        scannedItems = 0;
        pricesAddedThisPage = 0;
        missingCostItemsThisPage = 0;
        Slot nextSlot = null;
        StringBuilder audit = new StringBuilder();
        audit.append("\n========== PRICE PAGE ").append(pagesScanned).append(" | RB CONTEXT ")
                .append(currentRebirth).append(" ==========\n");

        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            scannedItems++;

            List<String> lines = getLines(stack);
            audit.append("\nSlot ").append(slot.id).append(" | ")
                    .append(stack.getCount()).append("x ").append(stack.getName().getString()).append("\n");
            for (String line : lines) audit.append("  ").append(line).append("\n");

            if (hasNextTooltip(lines)) {
                nextSlot = slot;
                audit.append("  [NEXT PAGE CONTROL]\n");
            }

            String joined = String.join(" | ", lines);
            Integer rebirthInItem = find(REBIRTH_PATTERN, joined);
            Integer prestige = find(PRESTIGE_PATTERN, joined);

            if (rebirthInItem != null && prestige == null) {
                currentRebirth = rebirthInItem;
                audit.append("  [REBIRTH CONTEXT SET TO ").append(currentRebirth).append("]\n");
                continue;
            }

            if (prestige == null) continue;
            int rebirth = rebirthInItem != null ? rebirthInItem : currentRebirth;

            Matcher costMatcher = COST_PATTERN.matcher(joined);
            if (!costMatcher.find()) {
                missingCostItemsThisPage++;
                audit.append("  [PRICE MISSING: RECOGNISED PRESTIGE ENTRY]\n");
                continue;
            }

            String number = costMatcher.group(1).replace(",", "");
            String unit = costMatcher.group(2);
            if (unit == null || unit.isBlank()) unit = "T";
            String individualCost = "$" + number + unit.toUpperCase(Locale.ROOT);

            Matcher totalMatcher = TOTAL_PATTERN.matcher(joined);
            String cumulative = totalMatcher.find()
                    ? totalMatcher.group(1) + totalMatcher.group(2)
                    : null;

            PriceData data = new PriceData(rebirth, prestige, individualCost, cumulative);
            PriceData previous = PRICES.computeIfAbsent(rebirth, ignored -> new TreeMap<>())
                    .put(prestige, data);
            if (previous == null || !previous.individualCost().equals(individualCost)) {
                pricesAddedThisPage++;
            }
            audit.append("  [PRICE SAVED: RB").append(rebirth).append(" P").append(prestige)
                    .append(" = ").append(individualCost).append("]\n");
        }

        previousPageFingerprint = fingerprint;
        appendAudit(client, audit.toString());
        save(client);

        if (nextSlot == null) {
            status = "Finished page " + pagesScanned + ". No Next control found; review audit before assuming complete.";
            scanFinished = true;
            return;
        }

        status = "Page " + pagesScanned + ": saved " + pricesAddedThisPage
                + " prices; " + missingCostItemsThisPage + " prestige entries had no readable Cost. Turning page...";
        waitingForPageChange = true;
        unchangedPageChecks = 0;
        client.interactionManager.clickSlot(handler.syncId, nextSlot.id, 0, SlotActionType.PICKUP, client.player);
    }

    private static boolean isPrestigeMenu(List<Slot> slots) {
        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            for (String line : getLines(stack)) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("prestige") || lower.contains("rebirth")) return true;
            }
        }
        return false;
    }

    private static List<String> getLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        lines.add(stack.getName().getString());
        for (Text text : TooltipReader.getTooltipLines(stack)) {
            String line = text.getString();
            if (!line.isBlank() && !lines.contains(line)) lines.add(line);
        }
        return lines;
    }

    private static String fingerprint(List<Slot> slots) {
        StringBuilder result = new StringBuilder();
        for (Slot slot : slots) {
            result.append(slot.id).append(':');
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) result.append("empty");
            else result.append(stack.getCount()).append('|').append(String.join("|", getLines(stack)));
            result.append(';');
        }
        return result.toString();
    }

    private static boolean hasNextTooltip(List<String> lines) {
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).contains(NEXT_PAGE_TOOLTIP)) return true;
        }
        return false;
    }

    private static Integer find(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) return null;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static void appendAudit(MinecraftClient client, String value) {
        try {
            Path file = client.runDirectory.toPath().resolve(AUDIT_FILE);
            Files.writeString(file, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            status = "Audit save failed: " + exception.getMessage();
        }
    }

    private static void save(MinecraftClient client) {
        try {
            Files.writeString(client.runDirectory.toPath().resolve(PRICE_FILE),
                    toJson(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            status = "Price save failed: " + exception.getMessage();
        }
    }

    private static String toJson() {
        StringBuilder json = new StringBuilder("{\n  \"prices\": [\n");
        int index = 0;
        int total = getPricesCollected();
        for (NavigableMap<Integer, PriceData> byPrestige : PRICES.values()) {
            for (PriceData data : byPrestige.values()) {
                if (index++ > 0) json.append(",\n");
                json.append("    { \"rebirth\": ").append(data.rebirth())
                        .append(", \"prestige\": ").append(data.prestige())
                        .append(", \"individualCost\": \"").append(escape(data.individualCost())).append("\"");
                if (data.cumulativeSpent() != null) {
                    json.append(", \"cumulativeSpent\": \"").append(escape(data.cumulativeSpent())).append("\"");
                }
                json.append(" }");
            }
        }
        json.append("\n  ],\n  \"priceCount\": ").append(total)
                .append(",\n  \"pagesScanned\": ").append(pagesScanned).append("\n}\n");
        return json.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void resetNavigation() {
        previousPageFingerprint = "";
        waitingForPageChange = false;
        unchangedPageChecks = 0;
        scanFinished = false;
        currentRebirth = 0;
    }

    public static int getPricesCollected() {
        int count = 0;
        for (NavigableMap<Integer, PriceData> values : PRICES.values()) count += values.size();
        return count;
    }

    public static int getScannedItems() { return scannedItems; }
    public static int getPagesScanned() { return pagesScanned; }
    public static int getMissingCostItemsThisPage() { return missingCostItemsThisPage; }
    public static String getStatus() { return status; }
    public static List<String> getDebugLines() { return Collections.unmodifiableList(DEBUG_LINES); }

    public static PriceData getPrice(int rebirth, int prestige) {
        NavigableMap<Integer, PriceData> values = PRICES.get(rebirth);
        return values == null ? null : values.get(prestige);
    }

    public static String exportPrices() throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        Path file = client.runDirectory.toPath().resolve(PRICE_FILE);
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        return file.toAbsolutePath().toString();
    }

    public static int getExpectedMaximum(int rebirth) {
        if (rebirth < 0) return 0;
        return rebirth <= 6 ? 75 + 25 * rebirth : 300 + 50 * (rebirth - 7);
    }

    public static int getMissingPriceCount(int rebirth) {
        int missing = 0;
        int max = getExpectedMaximum(rebirth);
        for (int prestige = 2; prestige <= max; prestige++) {
            if (getPrice(rebirth, prestige) == null) missing++;
        }
        return missing;
    }

    public static void clearPrices() {
        PRICES.clear();
        DEBUG_LINES.clear();
        scannedItems = 0;
        pagesScanned = 0;
        pricesAddedThisPage = 0;
        missingCostItemsThisPage = 0;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                Files.deleteIfExists(client.runDirectory.toPath().resolve(PRICE_FILE));
                Files.deleteIfExists(client.runDirectory.toPath().resolve(AUDIT_FILE));
            }
        } catch (IOException ignored) {}
        resetNavigation();
        status = "Price data cleared";
    }
}
