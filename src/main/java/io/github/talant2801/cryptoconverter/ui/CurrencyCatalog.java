package io.github.talant2801.cryptoconverter.ui;

import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.Fiat;
import io.github.talant2801.cryptoconverter.service.RateService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the currency selectors are filled with: the supported fiats plus the top
 * coins by market capitalisation.
 *
 * <p>Kept apart from {@link ConverterPane} so the pane deals with controls and
 * this deals with what goes in them — including the awkward part, which is what
 * to do when the coin list cannot be fetched.
 *
 * <p>The answer is a built-in list of the majors. An application that shows an
 * empty drop-down because a request failed is broken; one that offers six coins
 * instead of a hundred is merely reduced, and it recovers on its own the next
 * time the list loads.
 */
public final class CurrencyCatalog {

    private static final Logger log = LoggerFactory.getLogger(CurrencyCatalog.class);

    /** The coins the application supports even with no network at all. */
    private static final List<Coin> FALLBACK_COINS = List.of(
            new Coin("bitcoin", "btc", "Bitcoin", null),
            new Coin("ethereum", "eth", "Ethereum", null),
            new Coin("solana", "sol", "Solana", null),
            new Coin("ripple", "xrp", "XRP", null),
            new Coin("cardano", "ada", "Cardano", null),
            new Coin("dogecoin", "doge", "Dogecoin", null));

    private final RateService rates;

    public CurrencyCatalog(RateService rates) {
        this.rates = Objects.requireNonNull(rates, "rates");
    }

    /**
     * Every selectable currency, fiats first, then coins in market-cap order.
     *
     * <p>Never completes exceptionally: a failed coin list degrades to the
     * built-in majors rather than leaving the user with nothing to select.
     */
    public CompletableFuture<List<CurrencyOption>> load() {
        return rates.topCoins()
                .exceptionally(error -> {
                    log.warn("Could not load the coin list, offering the built-in majors: {}",
                            error.getMessage());
                    return FALLBACK_COINS;
                })
                .thenApply(CurrencyCatalog::toOptions);
    }

    /** The pair the converter opens on, before the user picks anything. */
    public static CurrencyOption defaultFrom(List<CurrencyOption> options) {
        return options.stream()
                .filter(option -> option.code().equals("bitcoin"))
                .findFirst()
                .orElse(options.get(0));
    }

    /** The other half of the opening pair. */
    public static CurrencyOption defaultTo(List<CurrencyOption> options) {
        return options.stream()
                .filter(option -> option.code().equals(Fiat.USD.code()))
                .findFirst()
                .orElse(options.get(options.size() - 1));
    }

    private static List<CurrencyOption> toOptions(List<Coin> coins) {
        List<CurrencyOption> options = new ArrayList<>(coins.size() + Fiat.all().size());
        Fiat.all().forEach(fiat -> options.add(CurrencyOption.of(fiat)));
        coins.forEach(coin -> options.add(CurrencyOption.of(coin)));
        return List.copyOf(options);
    }
}
