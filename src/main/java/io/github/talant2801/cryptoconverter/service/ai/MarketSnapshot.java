package io.github.talant2801.cryptoconverter.service.ai;

import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The facts a market summary is written from.
 *
 * <p>Assembled by the caller and sent verbatim, so the model has no way to reach
 * for anything the application did not measure. Everything it says has to come
 * from these numbers.
 *
 * @param baseSymbol the symbol being priced, for example {@code BTC}
 * @param quoteSymbol what it is priced in, for example {@code USD}
 * @param rate the current price of one base unit
 * @param change24h the percentage move over the last day, or null when unknown
 * @param series recent prices, oldest first
 */
public record MarketSnapshot(
        String baseSymbol, String quoteSymbol, BigDecimal rate, BigDecimal change24h, List<PricePoint> series) {

    /** How many samples of the series are sent; enough for a shape, not a wall of numbers. */
    private static final int MAX_SAMPLES = 14;

    public MarketSnapshot {
        Objects.requireNonNull(baseSymbol, "baseSymbol");
        Objects.requireNonNull(quoteSymbol, "quoteSymbol");
        Objects.requireNonNull(rate, "rate");
        series = List.copyOf(Objects.requireNonNull(series, "series"));
    }

    /**
     * The series thinned to at most {@link #MAX_SAMPLES} evenly spaced points.
     *
     * <p>A seven-day chart is hundreds of samples; sending them all would cost
     * tokens without telling the model anything the shape does not already say.
     */
    public List<PricePoint> sampledSeries() {
        if (series.size() <= MAX_SAMPLES) {
            return series;
        }
        int step = series.size() / MAX_SAMPLES;
        List<PricePoint> sampled = new ArrayList<>(MAX_SAMPLES + 1);
        for (int i = 0; i < series.size(); i += step) {
            sampled.add(series.get(i));
        }
        // The most recent point is the one a reader cares about most, so it is
        // kept even when the stride would have skipped it.
        PricePoint last = series.get(series.size() - 1);
        if (!sampled.get(sampled.size() - 1).equals(last)) {
            sampled.add(last);
        }
        return List.copyOf(sampled);
    }

    /** The pair as a person would say it, for example {@code BTC/USD}. */
    public String pair() {
        return baseSymbol + "/" + quoteSymbol;
    }
}
