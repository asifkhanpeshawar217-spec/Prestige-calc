
package com.prestige.calculator;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
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

    private static int scannedItems;
    private static String status = "Waiting for /prestige menu...";

    private PrestigeDiscoveryScanner() {
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
        status = "Scanning prestige entries...";

        for (var slot : client.player.currentScreenHandler.slots) {
            ItemStack stack = slot.getStack();

            if (stack == null || stack.isEmpty()) {
                continue;
            }

            scannedItems++;

            List<String> lines = new ArrayList<>();
            lines.add(stack.getName().getString());

            for (Text line : TooltipReader.getTooltipLines(stack)) {
                lines.add(line.getString());
            }

            discoverItem(lines);
        }

        status = "Discovery scan complete";
    }

    private static void discoverItem(List<String> lines) {
        StringBuilder combined = new StringBuilder();

        for (String line : lines) {
            combined.append(line).append(" ");
        }

        String text = combined.toString();

        Matcher rebirthMatcher = REBIRTH_PATTERN.matcher(text);
        Matcher prestigeMatcher = PRESTIGE_PATTERN.matcher(text);

        if (!rebirthMatcher.find() || !prestigeMatcher.find()) {
            return;
        }

        int rebirth;
        int prestige;

        try {
            rebirth = Integer.parseInt(rebirthMatcher.group(1));
            prestige = Integer.parseInt(prestigeMatcher.group(1));
        } catch (NumberFormatException exception) {
            return;
        }

        if (rebirth < 0 || prestige < 0) {
            return;
        }

        NavigableSet<Integer> levels =
                DISCOVERED.computeIfAbsent(rebirth, ignored -> new TreeSet<>());

        boolean isNew = levels.add(prestige);

        if (isNew) {
            RECENT_DISCOVERIES.add(
                    "Rebirth " + rebirth + " - Prestige " + prestige
            );

            if (RECENT_DISCOVERIES.size() > 12) {
                RECENT_DISCOVERIES.remove(0);
            }
        }
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
                    Collections.unmodifiableNavigableSet(
                            new TreeSet<>(entry.getValue())
                    )
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
            json.append("    \"")
                    .append(entry.getKey())
                    .append("\": [");

            int prestigeIndex = 0;

            for (Integer prestige : entry.getValue()) {
                if (prestigeIndex > 0) {
                    json.append(", ");
                }

                json.append(prestige);
                prestigeIndex++;
            }

            json.append("]");

            rebirthIndex++;

            if (rebirthIndex < DISCOVERED.size()) {
                json.append(",");
            }

            json.append("\n");
        }

        json.append("  }\n");
        json.append("}\n");

        Path output = client.runDirectory.toPath()
                .resolve("prestige_catalogue.json");

        Files.writeString(output, json.toString(), StandardCharsets.UTF_8);

        return output.toAbsolutePath().toString();
    }

    public static void clearCatalogue() {
        DISCOVERED.clear();
        RECENT_DISCOVERIES.clear();
        scannedItems = 0;
        status = "Catalogue cleared";
    }
}
