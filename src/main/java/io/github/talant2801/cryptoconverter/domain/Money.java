package io.github.talant2801.cryptoconverter.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount in a single currency. Crypto and fiat are both represented here;
 * the currency code is a CoinGecko coin id for crypto ("bitcoin") and an
 * ISO 4217 code for fiat ("USD").
 *
 * <p>Amounts are {@link BigDecimal} throughout — never double.
 */
public record Money(BigDecimal amount, String currencyCode) {

    /** Scale for crypto amounts, where fractions of a coin are the norm. */
    public static final int CRYPTO_SCALE = 8;

    /** Scale for fiat amounts. */
    public static final int FIAT_SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currencyCode, "currencyCode");
        if (currencyCode.isBlank()) {
            throw new IllegalArgumentException("currencyCode must not be blank");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), currencyCode);
    }

    /**
     * Returns this amount rounded to {@code scale} digits, HALF_UP.
     *
     * <p>The caller decides the scale because whether a currency is crypto or
     * fiat is not knowable from the code alone.
     */
    public Money withScale(int scale) {
        return new Money(amount.setScale(scale, RoundingMode.HALF_UP), currencyCode);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currencyCode);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }
}
