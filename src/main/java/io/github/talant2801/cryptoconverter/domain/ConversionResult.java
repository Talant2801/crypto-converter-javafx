package io.github.talant2801.cryptoconverter.domain;

import java.util.Objects;

/**
 * The outcome of one conversion: what went in, what came out, and the rate
 * that produced it. Carrying the rate keeps the result self-describing, so the
 * UI can show "1 BTC = 94,203.11 USD" without a second lookup.
 */
public record ConversionResult(Money from, Money to, ExchangeRate rate) {

    public ConversionResult {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(rate, "rate");
    }
}
