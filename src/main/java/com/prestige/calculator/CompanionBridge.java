package com.prestige.calculator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CompanionBridge {
    private static final URI PAGE_ENDPOINT = URI.create("http://127.0.0.1:8765/api/page");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(700))
            .build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PrestigeCompanion-Bridge");
        thread.setDaemon(true);
        return thread;
    });

    private CompanionBridge() {}

    public static void sendPage(int page, List<PrestigeInventoryScanner.PriceData> prices,
                                int scannedItems, int missingCosts, int rebirthContext) {
        JsonObject payload = new JsonObject();
        payload.addProperty("page", page);
        payload.addProperty("scannedItems", scannedItems);
        payload.addProperty("missingCosts", missingCosts);
        payload.addProperty("rebirthContext", rebirthContext);

        JsonArray records = new JsonArray();
        for (PrestigeInventoryScanner.PriceData price : prices) {
            JsonObject item = new JsonObject();
            item.addProperty("rebirth", price.rebirth());
            item.addProperty("prestige", price.prestige());
            item.addProperty("individualCost", price.individualCost());
            if (price.cumulativeSpent() != null) {
                item.addProperty("cumulativeSpent", price.cumulativeSpent());
            }
            records.add(item);
        }
        payload.add("prices", records);

        HttpRequest request = HttpRequest.newBuilder(PAGE_ENDPOINT)
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        WORKER.execute(() -> {
            try {
                CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // The companion is optional. The mod's own JSON file remains the fallback.
            }
        });
    }
}
