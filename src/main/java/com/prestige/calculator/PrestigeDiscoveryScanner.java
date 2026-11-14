package com.prestige.calculator;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrestigeDiscoveryScanner {

    private static final NavigableMap<Integer, NavigableSet<Integer>> DISCOVERED =
            new TreeMap<>();
    private static final List<String> RECENT_DISCOVERIES = new ArrayList<>();

    private static final Pattern REBIRTH_PATTERN =
            Pattern.compile("(?i)rebirth\\s*#?\\s*(\\d+)");
    private static final Pattern PRESTIGE_PATTERN =
            Pattern.compile("(?i)prestige\\s*#?\\s*(\\d+)");

    private static final String NEXT_PAGE_TOOLTIP = "click to view next page";

    private static int scannedItems;
    // The prestige menu begins at Rebirth 0. A visible Rebirth-only entry
    // changes this context for all following prestige-only entries.
    private static int currentRebirth = 0;
    private static int unchangedPageChecks;
    private static boolean waitingForPageChange;
    private static boolean scanFinished;
    private static String previousPageFingerprint = "";
    private static String status = "Waiting for /prestige menu...";

    private PrestigeDiscoveryScanner() {
    }

    public static void scan() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null || client.currentScreen == null
                || !(client.currentScreen instanceof HandledScreen<?>)) {
            if (!previousPageFingerprint.isEmpty()) {
                status = "Menu closed. Scan paused.";
                resetPageNavigation();
            }
            return;
        }

        if (client.interactionManager == null) {
            return;
        }

        var handler = client.player.currentScreenHandler;

        // A normal player inventory contributes the final 36 slots to a
        // container handler. Only inspect the menu's own slots.
        int containerSlotCount = Math.max(0, handler.slots.size() - 36);
        if (containerSlotCount == 0) {
            return;
        }

        List<Slot> containerSlots = new ArrayList<>();
        for (int i = 0; i < containerSlotCount; i++) {
            containerSlots.add(handler.slots.get(i));
        }

        if (!containsPrestigeText(containerSlots)) {
            if (!previousPageFingerprint.isEmpty()) {
                status = "Prestige menu not detected. Scan paused.";
                resetPageNavigation();
            }
            return;
        }

        if (scanFinished) {
            return;
        }

        String fingerprint = createPageFingerprint(containerSlots);

        if (waitingForPageChange) {
            if (fingerprint.equals(previousPageFingerprint)) {
                unchangedPageChecks++;
                if (unchangedPageChecks >= 3) {
                    status = "Page did not change after clicking Next. Scan stopped safely.";
                    scanFinished = true;
                    waitingForPageChange = false;
                }
                return;
            }
            waitingForPageChange = false;
            unchangedPageChecks = 0;
        }

        if (fingerprint.equals(previousPageFingerprint)) {
            return;
        }

        previousPageFingerprint = fingerprint;
        scannedItems = 0;
        status = "Scanning page...";

        Slot nextPageSlot = null;

        for (Slot slot : containerSlots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            scannedItems++;
            List<String> lines = getItemLines(stack);

            if (containsNextPageTooltip(lines)) {
                nextPageSlot = slot;
            }

            discoverItem(lines);
        }

        if (nextPageSlot == null) {
            status = "Discovery scan complete. No Next page button found.";
            scanFinished = true;
            return;
        }

        status = "Page scanned. Opening next page...";
        waitingForPageChange = true;
        unchangedPageChecks = 0;

        // The server menu remains open while its contents are paged.
        client.interactionManager.clickSlot(
                handler.syncId,
                nextPageSlot.id,
                0,
                SlotActionType.PICKUP,
                client.player
        );
    }

    private static List<String> getItemLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        lines.add(stack.getName().getString());

        for (Text line : TooltipReader.getTooltipLines(stack)) {
            lines.add(line.getString());
        }

        return lines;
    }

    private static boolean containsPrestigeText(List<Slot> slots) {
        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            for (String line : getItemLines(stack)) {
                if (REBIRTH_PATTERN.matcher(line).find()
                        || PRESTIGE_PATTERN.matcher(line).find()) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String createPageFingerprint(List<Slot> slots) {
        StringBuilder fingerprint = new StringBuilder();

        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            fingerprint.append(slot.id).append(':');

            if (stack == null || stack.isEmpty()) {
                fingerprint.append("empty");
            } else {
                fingerprint.append(stack.getCount()).append('|');
                for (String line : getItemLines(stack)) {
                    fingerprint.append(line).append('|');
                }
            }

            fingerprint.append(';');
        }

        return fingerprint.toString();
    }

    private static boolean containsNextPageTooltip(List<String> lines) {
        for (String line : lines) {
            if (line.toLowerCase(java.util.Locale.ROOT).contains(NEXT_PAGE_TOOLTIP)) {
                return true;
            }
        }

        return false;
    }

    private static void discoverItem(List<String> lines) {
        StringBuilder combined = new StringBuilder();
        for (String line : lines) {
            combined.append(line).append(' ');
        }

        String itemText = combined.toString();
        Integer itemRebirth = findNumber(REBIRTH_PATTERN.matcher(itemText));
        Integer prestige = findNumber(PRESTIGE_PATTERN.matcher(itemText));

        // A Rebirth-only item is a section marker. It updates the context
        // without creating a prestige entry of its own.
        if (itemRebirth != null && prestige == null) {
            currentRebirth = itemRebirth;
            addRecent("Section marker: Rebirth " + currentRebirth);
            return;
        }

        if (prestige == null) {
            return;
        }

        // Some items may include both values. Prefer their explicit rebirth;
        // otherwise assign the prestige to the most recent rebirth marker.
        int rebirth = itemRebirth != null ? itemRebirth : currentRebirth;
        if (rebirth < 0) {
            return;
        }

        NavigableSet<Integer> levels =
                DISCOVERED.computeIfAbsent(rebirth, ignored -> new TreeSet<>());

        if (levels.add(prestige)) {
            addRecent("Rebirth " + rebirth + " - Prestige " + prestige);
        }
    }

    private static Integer findNumber(Matcher matcher) {
        if (!matcher.find()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void addRecent(String message) {
        RECENT_DISCOVERIES.add(message);
        if (RECENT_DISCOVERIES.size() > 12) {
            RECENT_DISCOVERIES.remove(0);
        }
    }

    private static void resetPageNavigation() {
        previousPageFingerprint = "";
        waitingForPageChange = false;
        unchangedPageChecks = 0;
        scanFinished = false;
        currentRebirth = 0;
    }

    public static int getDiscoveredRebirthCount() {
        return DISCOVERED.size();
    }

    public static int getDiscoveredPrestigeCount() {
        int count = 0;
        for (NavigableSet<Integer> levels : DISCOVERED.values()) {
            count += levels.size();
        }
        return count;
    }

    public static int getScannedItems() {
        return scannedItems;
    }

    public static String getStatus() {
        return status;
    }

    public static List<String> getRecentDiscoveries() {
        return Collections.unmodifiableList(RECENT_DISCOVERIES);
    }

    public static NavigableMap<Integer, NavigableSet<Integer>> getCatalogue() {
        NavigableMap<Integer, NavigableSet<Integer>> copy = new TreeMap<>();

        for (Map.Entry<Integer, NavigableSet<Integer>> entry : DISCOVERED.entrySet()) {
            copy.put(
                    entry.getKey(),
                    Collections.unmodifiableNavigableSet(new TreeSet<>(entry.getValue()))
            );
        }

        return Collections.unmodifiableNavigableMap(copy);
    }

    public static String exportCatalogue() throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        StringBuilder json = new StringBuilder();

        json.append("{\n");
        json.append("  \"rebirths\": {\n");

        int rebirthIndex = 0;
        for (Map.Entry<Integer, NavigableSet<Integer>> entry : DISCOVERED.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": [");

            int prestigeIndex = 0;
            for (Integer prestige : entry.getValue()) {
                if (prestigeIndex++ > 0) {
                    json.append(", ");
                }
                json.append(prestige);
            }

            json.append("]");
            if (++rebirthIndex < DISCOVERED.size()) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("  }\n");
        json.append("}\n");

        Path output = client.runDirectory.toPath().resolve("prestige_catalogue.json");
        Files.writeString(output, json.toString(), StandardCharsets.UTF_8);

        return output.toAbsolutePath().toString();
    }

    public static void clearCatalogue() {
        DISCOVERED.clear();
        RECENT_DISCOVERIES.clear();
        scannedItems = 0;
        resetPageNavigation();
        status = "Catalogue cleared";
    }
}
