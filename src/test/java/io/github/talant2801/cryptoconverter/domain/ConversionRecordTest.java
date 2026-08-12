package io.github.talant2801.cryptoconverter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversionRecordTest {

    private static final Instant AT = Instant.parse("2025-01-15T12:00:00Z");

    private static ConversionResult result() {
        ExchangeRate rate =
                new ExchangeRate("bitcoin", "USD", new BigDecimal("94203.11"), new BigDecimal("1.4"), AT);
        return new ConversionResult(
                Money.of("0.35000000", "bitcoin"), Money.of("32971.09", "USD"), rate);
    }

    @Test
    void flattensACompletedConversionIntoAnUnsavedRow() {
        ConversionRecord record = ConversionRecord.from(result(), AT);

        assertThat(record.id()).isNull();
        assertThat(record.fromCurrency()).isEqualTo("bitcoin");
        assertThat(record.toCurrency()).isEqualTo("USD");
        assertThat(record.fromAmount()).isEqualByComparingTo("0.35");
        assertThat(record.toAmount()).isEqualByComparingTo("32971.09");
        assertThat(record.rate()).isEqualByComparingTo("94203.11");
        assertThat(record.convertedAt()).isEqualTo(AT);
    }

    @Test
    void withIdReturnsACopyAndLeavesTheOriginalUnsaved() {
        ConversionRecord unsaved = ConversionRecord.from(result(), AT);

        ConversionRecord saved = unsaved.withId(42L);

        assertThat(saved.id()).isEqualTo(42L);
        assertThat(unsaved.id()).isNull();
        assertThat(saved.fromAmount()).isEqualByComparingTo(unsaved.fromAmount());
        assertThat(saved.convertedAt()).isEqualTo(unsaved.convertedAt());
    }

    @Test
    void requiresEveryPersistedColumnExceptTheId() {
        assertThatThrownBy(
                        () ->
                                new ConversionRecord(
                                        null,
                                        "bitcoin",
                                        "USD",
                                        new BigDecimal("1"),
                                        new BigDecimal("1"),
                                        new BigDecimal("1"),
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("convertedAt");
    }
}
