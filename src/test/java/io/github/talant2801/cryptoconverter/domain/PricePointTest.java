package io.github.talant2801.cryptoconverter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PricePointTest {

    @Test
    void holdsATimestampedPrice() {
        Instant at = Instant.parse("2025-01-15T12:00:00Z");

        PricePoint point = new PricePoint(at, new BigDecimal("94203.11"));

        assertThat(point.at()).isEqualTo(at);
        assertThat(point.price()).isEqualByComparingTo("94203.11");
    }

    @Test
    void allowsZeroButNotNegativePrices() {
        assertThat(new PricePoint(Instant.EPOCH, BigDecimal.ZERO).price()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> new PricePoint(Instant.EPOCH, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }
}
