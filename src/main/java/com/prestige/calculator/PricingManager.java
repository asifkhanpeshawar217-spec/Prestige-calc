package com.prestige.calculator;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Stores only prices actually observed from the server. It deliberately does
 * not guess a pricing curve or fill missing prestige levels.
 */
public final class PricingManager {
    private final Map<Integer, NavigableMap<Integer, BigDecimal>> pricesByRebirth = new TreeMap<>();

    public void recordIndividualPrice(int rebirth, int prestigeLevel, BigDecimal price) {
        if (rebirth < 0 || prestigeLevel < 1 || price == null || price.signum() < 0) return;
        pricesByRebirth
                .computeIfAbsent(rebirth, ignored -> new TreeMap<>())
                .put(prestigeLevel, price);
    }

    public BigDecimal getIndividualPrice(int rebirth, int prestigeLevel) {
        NavigableMap<Integer, BigDecimal> prices = pricesByRebirth.get(rebirth);
        return prices == null ? null : prices.get(prestigeLevel);
    }

    public BigDecimal getKnownTotal(int rebirth, int firstLevelInclusive, int lastLevelInclusive) {
        NavigableMap<Integer, BigDecimal> prices = pricesByRebirth.get(rebirth);
        if (prices == null || firstLevelInclusive > lastLevelInclusive) return null;

        BigDecimal total = BigDecimal.ZERO;
        for (int level = firstLevelInclusive; level <= lastLevelInclusive; level++) {
            BigDecimal price = prices.get(level);
            if (price == null) return null;
            total = total.add(price);
        }
        return total;
    }

    public Map<Integer, NavigableMap<Integer, BigDecimal>> snapshot() {
        return Collections.unmodifiableMap(pricesByRebirth);
    }
}
