package com.prestige.calculator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrestigeInventoryScanner {
    public record PriceData(int rebirth, int prestige, String individualCost, String cumulativeSpent) {}

    private record CachedSlot(ItemStack stackCopy, String name, List<String> lines) {}
    private record SlotSnapshot(Slot slot, String name, List<String> lines) {}

    private enum ScanState {
        WAITING_FOR_MENU,
        WAITING_FOR_STABLE_PAGE,
        SCANNING_PAGE,
        WAITING_FOR_NEXT_PAGE,
        FINISHED
    }

    private static final NavigableMap<Integer, NavigableMap<Integer, PriceData>> PRICES = new TreeMap<>();
    private static final Map<Integer, CachedSlot> SLOT_CACHE = new TreeMap<>();
    private static final ExecutorService FILE_WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PrestigeCalculator-FileWorker");
        thread.setDaemon(true);
        return thread;
    });

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

    private static volatile String status = "Waiting for /prestige menu...";
    private static ScanState state = ScanState.WAITING_FOR_MENU;
    private static boolean dataLoaded;
    private static int scannedItems;
    private static int pagesScanned;
    private static int pricesAddedThisPage;
    private static int missingCostItemsThisPage;
    private static int currentRebirth;
    private static int stableChecks;
    private static int unchangedPageChecks;
    private static String previousPageFingerprint = "";
    private static String candidateFingerprint = "";
    private static long saveGeneration;

    // Hover scanning deliberately advances one visible slot at a time.
    private static final int MAX_SLOT_RETRIES = 4;
    private static List<SlotSnapshot> activePageSnapshot = List.of();
    private static List<SlotSnapshot> activePageAllSlots = List.of();
    private static final List<SlotSnapshot> hoveredPageEntries = new ArrayList<>();
    private static int activeSlotIndex;
    private static String activePageFingerprint = "";

    private PrestigeInventoryScanner() {}

    public static void scan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !(client.currentScreen instanceof HandledScreen<?>)) {
            if (state != ScanState.WAITING_FOR_MENU && state != ScanState.FINISHED) {
                status = "Menu closed. Prices are saved; scanner paused.";
                pauseNavigation();
            }
            return;
        }
        if (client.interactionManager == null) return;

        loadExistingPrices(client);

        var handler = client.player.currentScreenHandler;
        int containerCount = Math.max(0, handler.slots.size() - 36);
        if (containerCount <= 0) return;

        List<SlotSnapshot> snapshot = makeSnapshot(handler.slots.subList(0, containerCount));
        if (!isPrestigeMenu(snapshot)) {
            if (state != ScanState.WAITING_FOR_MENU && state != ScanState.FINISHED) {
                status = "Prestige menu not detected. Scanner paused.";
                pauseNavigation();
            }
            return;
        }

        if (state == ScanState.FINISHED) return;

        if (state == ScanState.SCANNING_PAGE) {
            advanceDirectScan(client, handler);
            return;
        }

        String fingerprint = fingerprint(snapshot);

        if (state == ScanState.WAITING_FOR_NEXT_PAGE) {
            if (fingerprint.equals(previousPageFingerprint)) {
                unchangedPageChecks++;
                if (unchangedPageChecks >= 30) {
                    status = "Next page did not change after 30 checks. Scan stopped; inspect the audit file.";
                    state = ScanState.FINISHED;
                    save(client);
                }
                return;
            }
            state = ScanState.WAITING_FOR_STABLE_PAGE;
            candidateFingerprint = fingerprint;
            stableChecks = 1;
            unchangedPageChecks = 0;
            status = "New page detected. Waiting for it to finish loading...";
            return;
        }

        if (state == ScanState.WAITING_FOR_MENU) {
            state = ScanState.WAITING_FOR_STABLE_PAGE;
            candidateFingerprint = fingerprint;
            stableChecks = 1;
            status = "Prestige menu detected. Waiting for page to settle...";
            return;
        }

        if (!fingerprint.equals(candidateFingerprint)) {
            candidateFingerprint = fingerprint;
            stableChecks = 1;
            status = "Page is still changing. Waiting...";
            return;
        }

        stableChecks++;
        if (stableChecks < 2) return;

        if (fingerprint.equals(previousPageFingerprint)) return;

        state = ScanState.SCANNING_PAGE;
        scanPage(client, handler, snapshot, fingerprint);
    }

    private static List<SlotSnapshot> makeSnapshot(List<Slot> slots) {
        List<SlotSnapshot> result = new ArrayList<>(slots.size());

        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) {
                SLOT_CACHE.remove(slot.id);
                result.add(new SlotSnapshot(slot, "", List.of()));
                continue;
            }

            CachedSlot cached = SLOT_CACHE.get(slot.id);
            if (cached == null || !ItemStack.areEqual(cached.stackCopy(), stack)) {
                String name = stack.getName().getString();
                List<String> lines = getLines(stack);
                cached = new CachedSlot(stack.copy(), name, List.copyOf(lines));
                SLOT_CACHE.put(slot.id, cached);
            }

            result.add(new SlotSnapshot(slot, cached.name(), cached.lines()));
        }

        return result;
    }

    private static void scanPage(MinecraftClient client, net.minecraft.screen.ScreenHandler handler,
                                 List<SlotSnapshot> snapshot, String fingerprint) {
        pagesScanned++;
        scannedItems = 0;
        pricesAddedThisPage = 0;
        missingCostItemsThisPage = 0;
        activePageAllSlots = new ArrayList<>(snapshot);
        activePageSnapshot = snapshot.stream()
                .filter(entry -> isGlassItem(entry.slot().getStack()))
                .toList();
        hoveredPageEntries.clear();
        activeSlotIndex = 0;
        activePageFingerprint = fingerprint;
        status = "Page " + pagesScanned + ": scanning " + activePageSnapshot.size() + " glass slots...";
        state = ScanState.SCANNING_PAGE;
    }

    /**
     * Reads tooltip/lore already stored on each glass ItemStack.
     * It never moves or simulates the mouse cursor.
     */
    private static void advanceDirectScan(MinecraftClient client, net.minecraft.screen.ScreenHandler handler) {
        hoveredPageEntries.clear();
        scannedItems = 0;
        for (SlotSnapshot original : activePageSnapshot) {
            Slot slot = original.slot();
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            String name = stack.getName().getString();
            List<String> lines = getLines(stack);
            hoveredPageEntries.add(new SlotSnapshot(slot, name, List.copyOf(lines)));
            scannedItems++;
            if (isIncompleteEntry(name, lines)) missingCostItemsThisPage++;
        }
        status = "Page " + pagesScanned + ": read " + scannedItems + " glass slots without moving the cursor.";
        finishDirectPage(client, handler);
    }

    private static boolean isIncompleteEntry(String name, List<String> lines) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String joined = String.join(" | ", lines);
        if (lowerName.contains("prestige")) {
            return !PRESTIGE_PATTERN.matcher(joined).find()
                    || !COST_PATTERN.matcher(joined).find();
        }
        if (lowerName.contains("rebirth")) {
            return !REBIRTH_PATTERN.matcher(joined).find();
        }
        return false;
    }

    private static void finishDirectPage(MinecraftClient client, net.minecraft.screen.ScreenHandler handler) {
        Slot nextSlot = null;
        List<PriceData> pagePrices = new ArrayList<>();
        StringBuilder audit = new StringBuilder();
        audit.append("\n========== PRICE PAGE ").append(pagesScanned)
                .append(" | RB CONTEXT ").append(currentRebirth).append(" | DIRECT ITEM DATA ==========").append('\n');

        for (SlotSnapshot entry : hoveredPageEntries) {
            if (entry.lines().isEmpty()) continue;

            if (hasNextTooltip(entry.lines())) {
                nextSlot = entry.slot();
                audit.append("[NEXT CONTROL] slot ").append(nextSlot.id).append('\n');
            }

            String joined = String.join(" | ", entry.lines());
            Integer rebirthInItem = find(REBIRTH_PATTERN, joined);
            Integer prestige = find(PRESTIGE_PATTERN, joined);

            if (rebirthInItem != null && prestige == null) {
                currentRebirth = rebirthInItem;
                audit.append("[REBIRTH] RB").append(currentRebirth).append('\n');
                continue;
            }

            if (prestige == null) continue;
            int rebirth = rebirthInItem != null ? rebirthInItem : currentRebirth;

            Matcher costMatcher = COST_PATTERN.matcher(joined);
            if (!costMatcher.find()) {
                missingCostItemsThisPage++;
                audit.append("[MISSING COST AFTER RETRIES] RB").append(rebirth).append(" P").append(prestige)
                        .append(" | ").append(entry.name()).append('\n');
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
            if (previous == null || !previous.equals(data)) pricesAddedThisPage++;
            pagePrices.add(data);

            audit.append("[PRICE] RB").append(rebirth).append(" P").append(prestige)
                    .append(" = ").append(individualCost);
            if (cumulative != null) audit.append(" | TOTAL ").append(cumulative);
            audit.append('\n');
        }

        // Navigation controls may be non-glass items; they are not price entries.
        if (nextSlot == null) {
            for (int i = activePageAllSlots.size() - 1; i >= 0; i--) {
                SlotSnapshot entry = activePageAllSlots.get(i);
                ItemStack stack = entry.slot().getStack();
                if (stack != null && !stack.isEmpty() && stack.isOf(Items.ARROW)) {
                    nextSlot = entry.slot();
                    audit.append("[NEXT CONTROL CANDIDATE] rightmost arrow slot ")
                            .append(nextSlot.id).append('\n');
                    break;
                }
            }
        }

        appendAudit(client, audit.toString());
        save(client);
        CompanionBridge.sendPage(pagesScanned, pagePrices, scannedItems, missingCostItemsThisPage, currentRebirth);

        if (nextSlot == null) {
            int missingExpected = 0;
            for (Integer rebirth : PRICES.keySet()) missingExpected += getMissingPriceCount(rebirth);
            state = ScanState.FINISHED;
            status = missingCostItemsThisPage > 0
                    ? "Last page scanned, but " + missingCostItemsThisPage + " costs remain unreadable. Check audit."
                    : "No Next button found. Scanned " + pagesScanned + " pages; verify the final catalogue.";
            if (missingExpected > 0) status += " Expected prestige gaps: " + missingExpected + ".";
            return;
        }

        // Use Minecraft's slot action directly; no cursor positioning is needed.
        previousPageFingerprint = activePageFingerprint;
        state = ScanState.WAITING_FOR_NEXT_PAGE;
        unchangedPageChecks = 0;
        status = "Page " + pagesScanned + " scanned. Opening the next page...";
        client.interactionManager.clickSlot(handler.syncId, nextSlot.id, 0,
                SlotActionType.PICKUP, client.player);
    }

    private static boolean isGlassItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // Includes normal/stained glass and glass panes.
        return Registries.ITEM.getId(stack.getItem()).getPath().contains("glass");
    }

    private static boolean isPrestigeMenu(List<SlotSnapshot> slots) {
        for (SlotSnapshot entry : slots) {
            for (String line : entry.lines()) {
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

    private static String fingerprint(List<SlotSnapshot> slots) {
        StringBuilder result = new StringBuilder(slots.size() * 48);
        for (SlotSnapshot entry : slots) {
            result.append(entry.slot().id).append(':');
            if (entry.lines().isEmpty()) result.append("empty");
            else result.append(entry.name()).append('|').append(String.join("|", entry.lines()));
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

    private static void loadExistingPrices(MinecraftClient client) {
        if (dataLoaded) return;
        dataLoaded = true;
        Path file = client.runDirectory.toPath().resolve(PRICE_FILE);
        if (!Files.exists(file)) {
            status = "Ready. Open /prestige to start scanning.";
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray prices = root.has("prices") && root.get("prices").isJsonArray()
                    ? root.getAsJsonArray("prices") : new JsonArray();

            int loaded = 0;
            for (JsonElement element : prices) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (!item.has("rebirth") || !item.has("prestige") || !item.has("individualCost")) continue;
                int rebirth = item.get("rebirth").getAsInt();
                int prestige = item.get("prestige").getAsInt();
                String cost = item.get("individualCost").getAsString();
                String cumulative = item.has("cumulativeSpent") && !item.get("cumulativeSpent").isJsonNull()
                        ? item.get("cumulativeSpent").getAsString() : null;
                if (rebirth < 0 || prestige < 0 || cost.isBlank()) continue;
                PRICES.computeIfAbsent(rebirth, ignored -> new TreeMap<>())
                        .put(prestige, new PriceData(rebirth, prestige, cost, cumulative));
                loaded++;
            }

            if (root.has("pagesScanned")) pagesScanned = Math.max(0, root.get("pagesScanned").getAsInt());
            status = "Loaded " + loaded + " saved prices. Ready to scan.";
        } catch (Exception exception) {
            status = "Could not load saved prices: " + exception.getMessage();
        }
    }

    private static void appendAudit(MinecraftClient client, String value) {
        Path file = client.runDirectory.toPath().resolve(AUDIT_FILE);
        FILE_WORKER.execute(() -> {
            try {
                Files.writeString(file, value, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                status = "Audit save failed: " + exception.getMessage();
            }
        });
    }

    private static void save(MinecraftClient client) {
        NavigableMap<Integer, NavigableMap<Integer, PriceData>> snapshot = new TreeMap<>();
        for (Map.Entry<Integer, NavigableMap<Integer, PriceData>> entry : PRICES.entrySet()) {
            snapshot.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }
        int savedPages = pagesScanned;
        long generation = saveGeneration;
        Path file = client.runDirectory.toPath().resolve(PRICE_FILE);

        FILE_WORKER.execute(() -> {
            if (generation != saveGeneration) return;
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.writeString(temporary, toJson(snapshot, savedPages), StandardCharsets.UTF_8);
                if (generation != saveGeneration) {
                    Files.deleteIfExists(temporary);
                    return;
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                status = "Price save failed: " + exception.getMessage();
            }
        });
    }

    private static String toJson(NavigableMap<Integer, NavigableMap<Integer, PriceData>> prices, int pageCount) {
        StringBuilder json = new StringBuilder("{\n  \"prices\": [\n");
        int index = 0;
        int total = 0;
        for (NavigableMap<Integer, PriceData> byPrestige : prices.values()) {
            for (PriceData data : byPrestige.values()) {
                if (index++ > 0) json.append(",\n");
                total++;
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
                .append(",\n  \"pagesScanned\": ").append(pageCount).append("\n}\n");
        return json.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void pauseNavigation() {
        state = ScanState.WAITING_FOR_MENU;
        previousPageFingerprint = "";
        candidateFingerprint = "";
        stableChecks = 0;
        unchangedPageChecks = 0;
        SLOT_CACHE.clear();
        activePageSnapshot = List.of();
        activePageAllSlots = List.of();
        hoveredPageEntries.clear();
        activeSlotIndex = 0;
        hoverWaitTicks = 0;
        slotRetryCount = 0;
        cursorMovedForSlot = false;
        nextHoverPhase = false;
        cursorMovedForNext = false;
        pendingNextSlot = null;
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

    public static PriceData getPrice(int rebirth, int prestige) {
        NavigableMap<Integer, PriceData> values = PRICES.get(rebirth);
        return values == null ? null : values.get(prestige);
    }

    public static String exportPrices() throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        Path file = client.runDirectory.toPath().resolve(PRICE_FILE);
        Files.writeString(file, toJson(PRICES, pagesScanned), StandardCharsets.UTF_8);
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
        SLOT_CACHE.clear();
        scannedItems = 0;
        pagesScanned = 0;
        pricesAddedThisPage = 0;
        missingCostItemsThisPage = 0;
        saveGeneration++;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                Files.deleteIfExists(client.runDirectory.toPath().resolve(PRICE_FILE));
                Files.deleteIfExists(client.runDirectory.toPath().resolve(AUDIT_FILE));
            }
        } catch (IOException ignored) {}
        dataLoaded = true;
        currentRebirth = 0;
        pauseNavigation();
        status = "Price data cleared";
    }
}
