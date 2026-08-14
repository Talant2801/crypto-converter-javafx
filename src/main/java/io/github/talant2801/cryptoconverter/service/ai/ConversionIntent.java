package io.github.talant2801.cryptoconverter.service.ai;

import io.github.talant2801.cryptoconverter.domain.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a natural-language query turned out to mean.
 *
 * <p>Deliberately just three fields: an amount and two currency codes. The model
 * produces this and nothing else — it never sees a rate and never returns a
 * result — so the worst a bad answer can do is convert the wrong amount, which
 * the user can see on screen, rather than quietly return a wrong number.
 *
 * @param amount the quantity to convert, positive
 * @param from the canonical code being converted out of
 * @param to the canonical code being converted into
 */
public record ConversionIntent(BigDecimal amount, String from, String to) {

    public ConversionIntent {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (from.isBlank() || to.isBlank()) {
            throw new IllegalArgumentException("currency codes must not be blank");
        }
    }

    /** The source amount, ready to hand to the conversion service. */
    public Money money() {
        return new Money(amount, from);
    }
}
