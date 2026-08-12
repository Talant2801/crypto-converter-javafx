package io.github.talant2801.cryptoconverter.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The price of one unit of {@code base} expressed in {@code quote}, as of
 * {@code fetchedAt}.
 *
 * <p>{@code change24h} is a percentage, so -3.2 means the base fell 3.2% over
 * the last day. It is null when the upstream response omitted it.
 */
public record ExchangeRate(
        String base,
        String quote,
        BigDecimal rate,
        BigDecimal change24h,
        Instant fetchedAt) {

    public ExchangeRate {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive: " + rate);
        }
    }

    /** True when this rate was fetched longer ago than {@code ttl}. */
    public boolean isStaleAt(Instant now, Duration ttl) {
        return fetchedAt.plus(ttl).isBefore(now);
    }

    /**
     * The inverse rate, for driving a conversion in the opposite direction.
     *
     * <p>Precision is set in significant digits rather than decimal places:
     * rates in this application span roughly 1e-8 (a fiat unit priced in BTC)
     * to 1e5 (BTC priced in fiat), and a fixed scale would leave the small end
     * over-precise and the large end starved. {@code change24h} is dropped
     * because a percentage move of the old base does not describe the new one.
     */
    public ExchangeRate inverted() {
        BigDecimal inverse = BigDecimal.ONE.divide(rate, MathContext.DECIMAL64);
        return new ExchangeRate(quote, base, inverse, null, fetchedAt);
    }
}
