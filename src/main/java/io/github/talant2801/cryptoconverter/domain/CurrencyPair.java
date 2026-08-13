package io.github.talant2801.cryptoconverter.domain;

import java.util.Objects;

/**
 * A direction of conversion, as saved in favourites: bitcoin to USD is not the
 * same favourite as USD to bitcoin.
 *
 * <p>Codes are normalised on construction — fiat upper case, coin ids lower —
 * so a pair saved from a selector and the same pair read back from the database
 * compare equal. Because the record's identity is its two codes, favourites can
 * be looked up and removed by value without the caller ever handling a row id.
 */
public record CurrencyPair(String from, String to) {

    public CurrencyPair {
        from = normalise(from, "from");
        to = normalise(to, "to");
        if (from.equalsIgnoreCase(to)) {
            throw new IllegalArgumentException("A pair must have two different currencies: " + from);
        }
    }

    public static CurrencyPair of(String from, String to) {
        return new CurrencyPair(from, to);
    }

    /** The same pair the other way round, as the swap button produces. */
    public CurrencyPair swapped() {
        return new CurrencyPair(to, from);
    }

    private static String normalise(String code, String name) {
        Objects.requireNonNull(code, name);
        if (code.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return Fiat.canonical(code);
    }
}
