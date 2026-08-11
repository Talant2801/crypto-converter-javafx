package io.github.talant2801.cryptoconverter.domain;

import java.util.Objects;

/**
 * A cryptocurrency as CoinGecko describes it. {@code id} is the canonical
 * CoinGecko identifier ("bitcoin") and is what every API call uses; the symbol
 * ("btc") is for display only and is not unique across coins.
 */
public record Coin(String id, String symbol, String name, String imageUrl) {

    public Coin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(name, "name");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    /** Upper-cased symbol, the form shown in selectors and results. */
    public String displaySymbol() {
        return symbol.toUpperCase();
    }
}
