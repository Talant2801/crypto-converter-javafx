package io.github.talant2801.cryptoconverter.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A conversion as stored in history. This is deliberately flat rather than
 * holding a {@link ConversionResult}: the table is the source of truth after a
 * restart, and rows written by an older schema must still read back.
 *
 * <p>{@code id} is null before the row is inserted.
 */
public record ConversionRecord(
        Long id,
        String fromCurrency,
        String toCurrency,
        BigDecimal fromAmount,
        BigDecimal toAmount,
        BigDecimal rate,
        Instant convertedAt) {

    public ConversionRecord {
        Objects.requireNonNull(fromCurrency, "fromCurrency");
        Objects.requireNonNull(toCurrency, "toCurrency");
        Objects.requireNonNull(fromAmount, "fromAmount");
        Objects.requireNonNull(toAmount, "toAmount");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(convertedAt, "convertedAt");
    }

    /** Builds an unsaved record from a completed conversion. */
    public static ConversionRecord from(ConversionResult result, Instant convertedAt) {
        return new ConversionRecord(
                null,
                result.from().currencyCode(),
                result.to().currencyCode(),
                result.from().amount(),
                result.to().amount(),
                result.rate().rate(),
                convertedAt);
    }

    /** Returns a copy carrying the id assigned by the database. */
    public ConversionRecord withId(long assignedId) {
        return new ConversionRecord(
                assignedId, fromCurrency, toCurrency, fromAmount, toAmount, rate, convertedAt);
    }
}
