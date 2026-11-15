package com.prestige.calculator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only price catalogue for the calculator.
 *
 * The catalogue is downloaded from the repository's main branch and cached in
 * the Minecraft game directory. No inventory/menu scanning is performed.
 */
public final class PriceCatalogue {
    private static final URI CATALOGUE_URI = URI.create(
            "https://raw.githubusercontent.com/asifkhanpeshawar217-spec/Prestige-calc/main/prestige_prices.json"
    );
    private static final String CACHE_FILE = "prestige_prices_cache.json";
    private static final Pattern COST_PATTERN =
            Pattern.compile("(?i)([\\d,]+(?:\\.\\d+)?)\\s*(qt|q|t)");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PrestigeCalculator-Catalogue");
        thread.setDaemon(true);
        return thread;
    });

    private static final NavigableMap<Integer, NavigableMap<Integer, BigDecimal>> PRICES =
            new TreeMap<>();

    private static volatile String status = "Loading price catalogue...";
    private static volatile int priceCount;

    private PriceCatalogue() {}

    public static void initialize() {
        Path cache = net.minecraft.client.MinecraftClient.getInstance()
                .runDirectory.toPath().resolve(CACHE_FILE);

        if (Files.isRegularFile(cache)) {
            try {
                int loaded = loadJson(Files.readString(cache, StandardCharsets.UTF_8));
                if (loaded > 0) {
                    status = "Loaded " + loaded + " prices from local cache. Checking for updates...";
                }
            } catch (Exception exception) {
                status = "Local price cache could not be read. Downloading catalogue...";
            }
        }

        WORKER.execute(() -> downloadLatest(cache));
    }

    private static void downloadLatest(Path cache) {
        try {
            HttpRequest request = HttpRequest.newBuilder(CATALOGUE_URI)
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "PrestigeCalculator-Mod")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() != 200) {
                status = priceCount > 0
                        ? "Using cached prices; online update returned HTTP " + response.statusCode()
                        : "Could not download catalogue (HTTP " + response.statusCode() + ").";
                return;
            }

            int loaded = loadJson(response.body());
            if (loaded <= 0) {
                status = priceCount > 0
                        ? "Using cached prices; downloaded catalogue contained no readable entries."
                        : "Downloaded catalogue contained no readable prices.";
                return;
            }

            Files.writeString(cache, response.body(), StandardCharsets.UTF_8);
            status = "Catalogue updated: " + loaded + " prices loaded.";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = priceCount > 0 ? "Using cached prices; update was interrupted."
                    : "Catalogue download was interrupted.";
        } catch (Exception exception) {
            status = priceCount > 0
                    ? "Using " + priceCount + " cached prices; online update unavailable."
                    : "Catalogue unavailable. Connect to the internet and restart Minecraft.";
        }
    }

    private static synchronized int loadJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray rows = root.has("prices") && root.get("prices").isJsonArray()
                    ? root.getAsJsonArray("prices")
                    : null;

            if (rows == null) return 0;

            NavigableMap<Integer, NavigableMap<Integer, BigDecimal>> parsed = new TreeMap<>();
            int count = 0;

            for (JsonElement element : rows) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();

                if (!row.has("rebirth") || !row.has("prestige") || !row.has("individualCost")) {
                    continue;
                }

                int rebirth = row.get("rebirth").getAsInt();
                int prestige = row.get("prestige").getAsInt();
                BigDecimal cost = parseCost(row.get("individualCost").getAsString());

                if (rebirth < 0 || prestige < 2 || cost == null || cost.signum() < 0) {
                    continue;
                }

                parsed.computeIfAbsent(rebirth, ignored -> new TreeMap<>())
                        .put(prestige, cost);
            }

            for (Map.Entry<Integer, NavigableMap<Integer, BigDecimal>> entry : parsed.entrySet()) {
                count += entry.getValue().size();
            }

            if (count == 0) return 0;

            PRICES.clear();
            PRICES.putAll(parsed);
            priceCount = count;
            return count;
        } catch (Exception exception) {
            return 0;
        }
    }

    private static BigDecimal parseCost(String text) {
        if (text == null) return null;
        Matcher matcher = COST_PATTERN.matcher(text.trim());
        if (!matcher.find()) return null;

        try {
            BigDecimal amount = new BigDecimal(matcher.group(1).replace(",", ""));
            return switch (matcher.group(2).toLowerCase()) {
                case "t" -> amount;
                case "q" -> amount.multiply(BigDecimal.valueOf(1_000));
                case "qt" -> amount.multiply(BigDecimal.valueOf(1_000_000));
                default -> null;
            };
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static synchronized BigDecimal getPrice(int rebirth, int prestige) {
        NavigableMap<Integer, BigDecimal> levels = PRICES.get(rebirth);
        return levels == null ? null : levels.get(prestige);
    }

    public static int getPriceCount() {
        return priceCount;
    }

    public static synchronized int getRebirthCount() {
        return PRICES.size();
    }

    public static String getStatus() {
        return status;
    }
}
