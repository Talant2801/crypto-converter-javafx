package io.github.talant2801.cryptoconverter.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** One sample in a price history series, as plotted by the chart. */
public record PricePoint(Instant at, BigDecimal price) {

    public PricePoint {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(price, "price");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative: " + price);
        }
    }
}
