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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrestigeDiscoveryScanner {

    private static final NavigableMap<Integer, NavigableSet<Integer>> DISCOVERED = new TreeMap<>();
    private static final List<String> RECENT_DISCOVERIES = new ArrayList<>();

    // Accept common formatting differences such as "Prestige: 75",
    // "Prestige Level #75", and "Rebirth - 1".
    private static final Pattern REBIRTH_PATTERN =
            Pattern.compile("(?i)\\brebirth\\b\\s*(?:level\\s*)?[:#-]?\\s*(\\d+)\\b");
    private static final Pattern PRESTIGE_PATTERN =
            Pattern.compile("(?i)\\bprestige\\b\\s*(?:level\\s*)?[:#-]?\\s*(\\d+)\\b");

    private static final String NEXT_PAGE_TOOLTIP = "click to view next page";
    private static final String CATALOGUE_FILE = "prestige_catalogue.json";
    private static final String AUDIT_FILE = "prestige_scan_audit.txt";

    private static int scannedItems;
    private static int pageNumber;
    private static int unrecognisedItems;
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
                status = "Menu closed. Progress saved; scan paused.";
                resetPageNavigation();
            }
            return;
        }

        if (client.interactionManager == null) {
            return;
        }

        var handler = client.player.currentScreenHandler;
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
                if (unchangedPageChecks >= 6) {
                    status = "Next click did not change the page. Audit saved; scan stopped.";
                    scanFinished = true;
                    waitingForPageChange = false;
                    saveProgress(client);
                }
                return;
            }
            waitingForPageChange = false;
            unchangedPageChecks = 0;
        }

        if (fingerprint.equals(previousPageFingerprint)) {
            return;
        }

        scannedItems = 0;
        unrecognisedItems = 0;
        pageNumber++;
        status = "Auditing page " + pageNumber + "...";

        Slot nextPageSlot = null;
        StringBuilder pageAudit = new StringBuilder();
        pageAudit.append("\n========== PAGE ").append(pageNumber).append(" ==========\n");

        // Read every non-empty slot in the server container, in menu order.
        for (Slot slot : containerSlots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            scannedItems++;
            List<String> lines = getItemLines(stack);
            pageAudit.append("\nSlot ").append(slot.id)
                    .append(" | ").append(stack.getCount()).append("x ")
                    .append(stack.getName().getString()).append("\n");

            for (String line : lines) {
                pageAudit.append("  ").append(line).append("\n");
            }

            if (containsNextPageTooltip(lines)) {
                nextPageSlot = slot;
                pageAudit.append("  [DETECTED: NEXT PAGE CONTROL]\n");
            }

            boolean recognised = discoverItem(lines);
            if (!recognised && looksLikePrestigeEntry(lines)) {
                unrecognisedItems++;
                pageAudit.append("  [WARNING: PRESTIGE-LIKE ITEM NOT PARSED]\n");
            }
        }

        previousPageFingerprint = fingerprint;
        appendAudit(client, pageAudit.toString());
        saveProgress(client);

        if (nextPageSlot == null) {
            status = "Page " + pageNumber + " audited. No Next button detected; review " + AUDIT_FILE + ".";
            scanFinished = true;
            return;
        }

        status = "Page " + pageNumber + " checked (" + scannedItems
                + " items, " + unrecognisedItems + " unrecognised). Opening next page...";
        waitingForPageChange = true;
        unchangedPageChecks = 0;

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
            String value = line.getString();
            if (!value.isBlank() && !lines.contains(value)) {
                lines.add(value);
            }
        }
        return lines;
    }

    private static boolean containsPrestigeText(List<Slot> slots) {
        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
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
            if (line.toLowerCase(Locale.ROOT).contains(NEXT_PAGE_TOOLTIP)) {
                return true;
            }
        }
        return false;
    }

    // Returns true when this item was understood as a rebirth marker or prestige.
    private static boolean discoverItem(List<String> lines) {
        StringBuilder combined = new StringBuilder();
        for (String line : lines) combined.append(line).append(' ');

        String itemText = combined.toString();
        Integer itemRebirth = findNumber(REBIRTH_PATTERN.matcher(itemText));
        Integer prestige = findNumber(PRESTIGE_PATTERN.matcher(itemText));

        if (itemRebirth != null && prestige == null) {
            currentRebirth = itemRebirth;
            addRecent("Section marker: Rebirth " + currentRebirth);
            return true;
        }

        if (prestige == null) return false;

        int rebirth = itemRebirth != null ? itemRebirth : currentRebirth;
        if (rebirth < 0) return false;

        NavigableSet<Integer> levels =
                DISCOVERED.computeIfAbsent(rebirth, ignored -> new TreeSet<>());

        if (levels.add(prestige)) {
            addRecent("Rebirth " + rebirth + " - Prestige " + prestige);
        }
        return true;
    }

    private static boolean looksLikePrestigeEntry(List<String> lines) {
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("prestige") || lower.contains("rebirth")) {
                return true;
            }
        }
        return false;
    }

    private static Integer findNumber(Matcher matcher) {
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void addRecent(String message) {
        RECENT_DISCOVERIES.add(message);
        if (RECENT_DISCOVERIES.size() > 12) RECENT_DISCOVERIES.remove(0);
    }

    private static void appendAudit(MinecraftClient client, String content) {
        try {
            Path output = client.runDirectory.toPath().resolve(AUDIT_FILE);
            Files.writeString(output, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            status = "Could not save scan audit: " + exception.getMessage();
        }
    }

    private static void saveProgress(MinecraftClient client) {
        try {
            Files.writeString(
                    client.runDirectory.toPath().resolve(CATALOGUE_FILE),
                    buildCatalogueJson(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            status = "Could not save catalogue: " + exception.getMessage();
        }
    }

    private static String buildCatalogueJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"rebirths\": {\n");
        int rebirthIndex = 0;
        for (Map.Entry<Integer, NavigableSet<Integer>> entry : DISCOVERED.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": [");
            int prestigeIndex = 0;
            for (Integer prestige : entry.getValue()) {
                if (prestigeIndex++ > 0) json.append(", ");
                json.append(prestige);
            }
            json.append("]");
            if (++rebirthIndex < DISCOVERED.size()) json.append(",");
            json.append("\n");
        }
        json.append("  },\n");
        json.append("  \"pagesAudited\": ").append(pageNumber).append(",\n");
        json.append("  \"unrecognisedPrestigeLikeItems\": ").append(unrecognisedItems).append("\n");
        json.append("}\n");
        return json.toString();
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
        for (NavigableSet<Integer> levels : DISCOVERED.values()) count += levels.size();
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
            copy.put(entry.getKey(),
                    Collections.unmodifiableNavigableSet(new TreeSet<>(entry.getValue())));
        }
        return Collections.unmodifiableNavigableMap(copy);
    }

    public static String exportCatalogue() throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        Path output = client.runDirectory.toPath().resolve(CATALOGUE_FILE);
        Files.writeString(output, buildCatalogueJson(), StandardCharsets.UTF_8);
        return output.toAbsolutePath().toString();
    }

    public static void clearCatalogue() {
        DISCOVERED.clear();
        RECENT_DISCOVERIES.clear();
        scannedItems = 0;
        pageNumber = 0;
        unrecognisedItems = 0;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                Files.deleteIfExists(client.runDirectory.toPath().resolve(AUDIT_FILE));
            }
        } catch (IOException ignored) {
        }
        resetPageNavigation();
        status = "Catalogue cleared";
    }
}
