package io.github.talant2801.cryptoconverter.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The fiat currencies the application quotes prices in.
 *
 * <p>A closed enum rather than an open string set because the distinction
 * between fiat and crypto is what drives two decisions that must not be
 * guessed: how many decimal places an amount is rounded to, and whether a
 * conversion has to be routed through USD. Any code that is not listed here is
 * treated as a CoinGecko coin id, which is exactly how the rest of the
 * application reasons about currencies.
 *
 * <p>{@code USD} is also the routing currency: CoinGecko quotes every coin in
 * it, so crypto-to-crypto conversions cross through it rather than needing a
 * direct pair that may not exist.
 */
public enum Fiat {
    USD("US Dollar"),
    EUR("Euro"),
    PLN("Polish Zloty"),
    GBP("British Pound"),
    UAH("Ukrainian Hryvnia");

    /** Every conversion between two coins is priced through this currency. */
    public static final Fiat ROUTING = USD;

    private final String displayName;

    Fiat(String displayName) {
        this.displayName = displayName;
    }

    /** ISO 4217 code, upper case — the form stored and displayed. */
    public String code() {
        return name();
    }

    /** Lower-case code, the form CoinGecko expects in {@code vs_currency}. */
    public String apiCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return displayName;
    }

    /** The supported currencies in display order. */
    public static List<Fiat> all() {
        return List.of(values());
    }

    /** Resolves a code in any casing, or empty when it names something else. */
    public static Optional<Fiat> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalised = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(fiat -> fiat.name().equals(normalised)).findFirst();
    }

    /** True when {@code code} names a supported fiat rather than a coin. */
    public static boolean isFiat(String code) {
        return find(code).isPresent();
    }

    /**
     * The one spelling of a currency code the application stores and compares:
     * fiat upper case, coin ids lower case, no surrounding space.
     *
     * <p>Codes reach the application from a selector, a saved favourite, a
     * history row and a natural-language query, and each of those has its own
     * habits about casing. Funnelling them all through here is what keeps
     * "USD", "usd" and " Usd " from becoming three different currencies in the
     * history table.
     *
     * @throws IllegalArgumentException if the code is blank
     */
    public static String canonical(String code) {
        Objects.requireNonNull(code, "code");
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("currency code must not be blank");
        }
        return find(trimmed).map(Fiat::code).orElseGet(() -> trimmed.toLowerCase(Locale.ROOT));
    }
}
