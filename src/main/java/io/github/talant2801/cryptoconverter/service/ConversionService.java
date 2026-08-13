package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.domain.ConversionResult;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.Fiat;
import io.github.talant2801.cryptoconverter.domain.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an amount in one currency into an amount in another.
 *
 * <p>The only place in the application that does money arithmetic. Everything
 * upstream — the converter pane, the natural-language query, a replayed history
 * row — funnels through {@link #convert}, so there is exactly one implementation
 * of rounding, of routing, and of what a result means.
 *
 * <p>Four directions, three of which need a rate:
 *
 * <ul>
 *   <li><b>crypto to fiat</b> multiplies by the quoted price.
 *   <li><b>fiat to crypto</b> multiplies by its inverse.
 *   <li><b>crypto to crypto</b> routes through USD, because CoinGecko quotes
 *       every coin against it and almost no coin against another coin.
 *   <li><b>a currency to itself</b> is the identity and costs no request.
 * </ul>
 *
 * <p>Fiat-to-fiat is deliberately unsupported: this is a cryptocurrency
 * converter, and pricing EUR in PLN through a coin would be a worse answer than
 * a plain refusal.
 */
public final class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    /**
     * Working precision for the two divisions in play — inverting a rate and
     * crossing two of them.
     *
     * <p>Significant digits rather than decimal places: a rate in this
     * application can be 1e-8 (a fiat unit priced in BTC) or 1e5 (BTC priced in
     * fiat), and any fixed scale would ruin one end. Sixteen digits leaves ample
     * headroom above the eight decimals a result is finally rounded to.
     */
    private static final MathContext RATE_PRECISION = MathContext.DECIMAL64;

    /** 100, for converting between a fraction and the percentage figure the API reports. */
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final RateService rates;
    private final HistoryService history;
    private final Clock clock;

    public ConversionService(RateService rates, HistoryService history, Clock clock) {
        this.rates = Objects.requireNonNull(rates, "rates");
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Converts {@code amount} into {@code targetCurrency}.
     *
     * <p>The result carries the rate that produced it, so a caller can display
     * "1 BTC = 94,203.11 USD" and judge how stale the figure is without a second
     * lookup.
     *
     * @param targetCurrency a fiat code or a CoinGecko coin id
     * @return a future completed with the result, or completed exceptionally
     *     with an {@link io.github.talant2801.cryptoconverter.client.ApiException}
     *     when the rate cannot be obtained
     */
    public CompletableFuture<ConversionResult> convert(Money amount, String targetCurrency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(targetCurrency, "targetCurrency");

        // Canonical from the first line: everything downstream — the cache key,
        // the result on screen, the history row — then agrees on one spelling of
        // each currency, whatever the caller typed.
        String from = Fiat.canonical(amount.currencyCode());
        String to = Fiat.canonical(targetCurrency);
        Money source = new Money(amount.amount(), from);

        if (from.equals(to)) {
            return CompletableFuture.completedFuture(identity(source));
        }

        boolean fromFiat = Fiat.isFiat(from);
        boolean toFiat = Fiat.isFiat(to);
        if (fromFiat && toFiat) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Fiat-to-fiat conversion is not supported: " + from + " to " + to));
        }

        if (!fromFiat && toFiat) {
            return rates.spotRate(from, to).thenApply(rate -> apply(source, rate, to));
        }
        if (fromFiat) {
            // The pair is always quoted coin-in-fiat, so the fiat leg runs the
            // same lookup and inverts the answer.
            return rates.spotRate(to, from).thenApply(rate -> apply(source, rate.inverted(), to));
        }
        return crossThroughRouting(source, from, to);
    }

    /**
     * Converts and writes the result to history.
     *
     * <p>Separate from {@link #convert} because the two have different triggers.
     * The converter pane recalculates on every pause in typing, and saving each
     * of those would bury a day's real conversions under half-typed amounts;
     * history is written when the user commits to a conversion.
     *
     * <p>A failed write does not fail the conversion — the number on screen is
     * still correct — so the result is returned regardless and the storage
     * failure is logged.
     */
    public CompletableFuture<ConversionResult> convertAndSave(Money amount, String targetCurrency) {
        return convert(amount, targetCurrency).thenCompose(result -> history.record(result)
                .handle((saved, error) -> {
                    if (error != null) {
                        log.warn("Converted {} to {} but could not save it to history",
                                result.from().currencyCode(), result.to().currencyCode(), error);
                    }
                    return result;
                }));
    }

    /**
     * Prices one coin in another by dividing their two USD quotes.
     *
     * <p>The two lookups are started before either is waited on, so the pair
     * costs one round trip rather than two — and when both legs are already
     * cached it costs none.
     */
    private CompletableFuture<ConversionResult> crossThroughRouting(Money amount, String from, String to) {
        String routing = Fiat.ROUTING.code();
        CompletableFuture<ExchangeRate> base = rates.spotRate(from, routing);
        CompletableFuture<ExchangeRate> quote = rates.spotRate(to, routing);
        return base.thenCombine(quote, (baseRate, quoteRate) -> apply(amount, cross(baseRate, quoteRate), to));
    }

    /**
     * Applies a rate and rounds both sides to the scale their own currency calls
     * for — two places for fiat, eight for a coin.
     *
     * <p>The multiplication happens at full {@link BigDecimal} precision and is
     * rounded exactly once, at the end. Rounding the rate first and multiplying
     * afterwards would drift, and on an amount the size of a portfolio the drift
     * is visible.
     */
    private static ConversionResult apply(Money amount, ExchangeRate rate, String targetCurrency) {
        Money converted = new Money(amount.amount().multiply(rate.rate()), targetCurrency).rounded();
        return new ConversionResult(amount.rounded(), converted, rate);
    }

    /**
     * Builds the {@code base -> quote} rate from two quotes in the routing
     * currency.
     *
     * <p>Timestamped with the older of the two legs: a cross rate is only as
     * fresh as its stalest half, and overstating that would let a stale price
     * slip past the UI's staleness check.
     */
    private static ExchangeRate cross(ExchangeRate base, ExchangeRate quote) {
        BigDecimal rate = base.rate().divide(quote.rate(), RATE_PRECISION);
        Instant fetchedAt = base.fetchedAt().isBefore(quote.fetchedAt()) ? base.fetchedAt() : quote.fetchedAt();
        return new ExchangeRate(base.base(), quote.base(), rate, crossChange(base, quote), fetchedAt);
    }

    /**
     * The 24h move of the cross pair, derived from the two legs' moves.
     *
     * <p>If the base rose by {@code a} and the quote by {@code b}, the ratio
     * between them changed by {@code (1+a)/(1+b) - 1}. That is arithmetic on
     * figures the API already gave us, not an estimate — but it is undefined
     * when either leg's move is missing, or when a quote that lost all of its
     * value would put a zero in the denominator, and in those cases the UI shows
     * no change indicator rather than a fabricated one.
     */
    private static BigDecimal crossChange(ExchangeRate base, ExchangeRate quote) {
        if (base.change24h() == null || quote.change24h() == null) {
            return null;
        }
        BigDecimal baseGrowth = BigDecimal.ONE.add(base.change24h().divide(PERCENT, RATE_PRECISION));
        BigDecimal quoteGrowth = BigDecimal.ONE.add(quote.change24h().divide(PERCENT, RATE_PRECISION));
        if (quoteGrowth.signum() == 0) {
            return null;
        }
        return baseGrowth.divide(quoteGrowth, RATE_PRECISION).subtract(BigDecimal.ONE).multiply(PERCENT);
    }

    /**
     * Converting a currency into itself, without asking the network what one
     * bitcoin is worth in bitcoin.
     */
    private ConversionResult identity(Money amount) {
        ExchangeRate rate = new ExchangeRate(
                amount.currencyCode(), amount.currencyCode(), BigDecimal.ONE, BigDecimal.ZERO, clock.instant());
        Money rounded = amount.rounded();
        return new ConversionResult(rounded, rounded, rate);
    }
}
