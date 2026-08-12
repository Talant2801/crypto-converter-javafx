package io.github.talant2801.cryptoconverter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class ExchangeRateTest {

    private static final Instant NOON = Instant.parse("2025-01-15T12:00:00Z");

    private static ExchangeRate rate(String value) {
        return new ExchangeRate("bitcoin", "USD", new BigDecimal(value), new BigDecimal("-3.2"), NOON);
    }

    @Test
    void rejectsNonPositiveRates() {
        assertThatThrownBy(() -> rate("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> rate("-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsAMissingChangeBecauseTheApiSometimesOmitsIt() {
        ExchangeRate withoutChange =
                new ExchangeRate("bitcoin", "USD", new BigDecimal("94203.11"), null, NOON);

        assertThat(withoutChange.change24h()).isNull();
    }

    @Test
    void isNotStaleBeforeTheTtlElapses() {
        assertThat(rate("94203.11").isStaleAt(NOON.plusSeconds(59), Duration.ofSeconds(60)))
                .isFalse();
    }

    @Test
    void isNotStaleExactlyAtTheTtlBoundary() {
        assertThat(rate("94203.11").isStaleAt(NOON.plusSeconds(60), Duration.ofSeconds(60)))
                .isFalse();
    }

    @Test
    void isStaleOnceThePriceIsOlderThanTheTtl() {
        assertThat(rate("94203.11").isStaleAt(NOON.plusSeconds(61), Duration.ofSeconds(60)))
                .isTrue();
    }

    @Test
    void invertedSwapsTheCurrenciesAndReciprocatesTheRate() {
        ExchangeRate inverted = rate("2").inverted();

        assertThat(inverted.base()).isEqualTo("USD");
        assertThat(inverted.quote()).isEqualTo("bitcoin");
        assertThat(inverted.rate()).isEqualByComparingTo("0.5");
    }

    @Test
    void invertedKeepsSignificantDigitsForALargeRate() {
        // 1/94203.11 is a repeating decimal near 1e-5. A fixed decimal scale
        // would leave only a handful of significant digits here, so precision
        // is counted in significant digits instead.
        BigDecimal inverse = rate("94203.11").inverted().rate();

        assertThat(inverse.precision()).isEqualTo(16);
        assertThat(inverse.multiply(new BigDecimal("94203.11")).doubleValue())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-14));
    }

    @Test
    void invertedKeepsSignificantDigitsForATinyRate() {
        // The other extreme: a fiat unit priced in BTC inverts to a large number.
        BigDecimal inverse = rate("0.0000106").inverted().rate();

        assertThat(inverse.multiply(new BigDecimal("0.0000106")).doubleValue())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-14));
    }

    @Test
    void invertingTwiceReturnsToTheOriginalRate() {
        BigDecimal roundTripped = rate("94203.11").inverted().inverted().rate();

        assertThat(roundTripped).isCloseTo(new BigDecimal("94203.11"), Offset.offset(new BigDecimal("0.0000001")));
    }

    @Test
    void invertedDropsTheChangeBecauseItNoLongerDescribesTheNewBase() {
        assertThat(rate("2").inverted().change24h()).isNull();
    }

    @Test
    void invertedKeepsTheOriginalFetchTimeSoStalenessIsNotReset() {
        assertThat(rate("2").inverted().fetchedAt()).isEqualTo(NOON);
    }
}
