package io.github.talant2801.cryptoconverter.domain;

import java.math.BigDecimal;
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

    /** The inverse rate, for driving a conversion in the opposite direction. */
    public ExchangeRate inverted() {
        BigDecimal inverse = BigDecimal.ONE.divide(rate, Money.CRYPTO_SCALE + 4, java.math.RoundingMode.HALF_UP);
        return new ExchangeRate(quote, base, inverse, null, fetchedAt);
    }
}
