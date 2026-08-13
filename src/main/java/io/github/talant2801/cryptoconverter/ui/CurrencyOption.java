package io.github.talant2801.cryptoconverter.ui;

import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.Fiat;
import java.util.Objects;

/**
 * One entry in a currency selector.
 *
 * <p>The selectors have to show something a person recognises ("BTC — Bitcoin")
 * while the services below want the identifier CoinGecko uses ("bitcoin"). This
 * record holds both, so the display string never has to be parsed back into an
 * id — which is exactly the kind of shortcut that breaks the moment a coin is
 * called "Wrapped Bitcoin".
 *
 * @param code the canonical currency code: a coin id, or an upper-case fiat code
 * @param symbol the short form shown next to amounts, for example {@code BTC}
 * @param name the human name, for example {@code Bitcoin}
 * @param crypto true for a coin, false for fiat
 */
public record CurrencyOption(String code, String symbol, String name, boolean crypto) {

    public CurrencyOption {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(name, "name");
    }

    public static CurrencyOption of(Coin coin) {
        return new CurrencyOption(coin.id(), coin.displaySymbol(), coin.name(), true);
    }

    public static CurrencyOption of(Fiat fiat) {
        return new CurrencyOption(fiat.code(), fiat.code(), fiat.displayName(), false);
    }

    /** The label shown in the drop-down. */
    public String label() {
        return symbol + " — " + name;
    }
}
