package io.github.talant2801.cryptoconverter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.talant2801.cryptoconverter.client.ApiException;
import io.github.talant2801.cryptoconverter.domain.ConversionResult;
import io.github.talant2801.cryptoconverter.domain.Money;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic and the routing, one direction at a time.
 *
 * <p>Prices come from {@link StubRateService} rather than the network, so every
 * expected figure below is computed by hand and stays true forever.
 */
class ConversionServiceTest {

    private static final Instant NOW = Instant.parse("2024-05-01T12:00:00Z");
    private static final Instant EARLIER = NOW.minusSeconds(90);

    private final TickingClock clock = new TickingClock(NOW);
    private final InMemoryConversionHistoryDao historyDao = new InMemoryConversionHistoryDao();

    /** Runs storage work on the calling thread, so assertions need no waiting. */
    private final HistoryService history =
            new HistoryService(historyDao, new InMemoryFavouritesDao(), Runnable::run, clock);

    private ConversionService serviceOver(StubRateService rates) {
        return new ConversionService(rates, history, clock);
    }

    @Nested
    @DisplayName("crypto to fiat")
    class CryptoToFiat {

        @Test
        void multipliesByTheQuotedPriceAndRoundsToTwoPlaces() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000.50", "-1.25", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("0.5", "bitcoin"), "USD"));

            assertThat(result.to().amount()).isEqualByComparingTo("21000.25");
            assertThat(result.to().currencyCode()).isEqualTo("USD");
            assertThat(result.to().amount().scale()).isEqualTo(Money.FIAT_SCALE);
        }

        @Test
        void reportsTheAmountThatWentInAtCryptoScale() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000.50", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("0.5", "bitcoin"), "USD"));

            assertThat(result.from().amount()).isEqualByComparingTo("0.5");
            assertThat(result.from().amount().scale()).isEqualTo(Money.CRYPTO_SCALE);
        }

        @Test
        void carriesTheRateAndItsChangeFigureIntoTheResult() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000.50", "-1.25", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("1", "bitcoin"), "usd"));

            assertThat(result.rate().base()).isEqualTo("bitcoin");
            assertThat(result.rate().quote()).isEqualTo("USD");
            assertThat(result.rate().rate()).isEqualByComparingTo("42000.50");
            assertThat(result.rate().change24h()).isEqualByComparingTo("-1.25");
            assertThat(result.rate().fetchedAt()).isEqualTo(NOW);
        }

        @Test
        void roundsHalfUpAtTheBoundary() {
            // 0.001 * 5 is exactly 0.005, the case where HALF_UP and HALF_EVEN
            // disagree, and the one a rounding bug hides behind.
            StubRateService rates = new StubRateService().quoting("testcoin", "usd", "5", NOW);
            ConversionService service = serviceOver(rates);

            assertThat(valueOf(service.convert(Money.of("0.001", "testcoin"), "usd")).to().amount())
                    .isEqualByComparingTo("0.01");
            assertThat(valueOf(service.convert(Money.of("0.00098", "testcoin"), "usd")).to().amount())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        void convertsZeroWithoutComplaint() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000.50", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("0", "bitcoin"), "usd"));

            assertThat(result.to().isZero()).isTrue();
            assertThat(result.to().amount()).isEqualByComparingTo("0.00");
        }

        @Test
        void roundsAnAmountBelowTheSmallestFiatUnitDownToZero() {
            // One satoshi at 42000 USD/BTC is well under a cent; the honest
            // answer is 0.00 rather than a fabricated fraction of a cent.
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000.50", NOW);

            ConversionResult result =
                    valueOf(serviceOver(rates).convert(Money.of("0.00000001", "bitcoin"), "usd"));

            assertThat(result.to().amount()).isEqualByComparingTo("0.00");
        }

        @Test
        void keepsFullPrecisionOnALargeAmount() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000.50", NOW);

            ConversionResult result =
                    valueOf(serviceOver(rates).convert(Money.of("12345.67891234", "bitcoin"), "usd"));

            // 12345.67891234 * 42000.50 exactly, then rounded once at the end.
            assertThat(result.to().amount()).isEqualByComparingTo("518524687.16");
        }
    }

    @Nested
    @DisplayName("fiat to crypto")
    class FiatToCrypto {

        @Test
        void invertsTheQuotedPriceAndRoundsToEightPlaces() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000.50", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("100", "USD"), "bitcoin"));

            // 100 / 42000.50 = 0.0023809225...
            assertThat(result.to().amount()).isEqualByComparingTo("0.00238092");
            assertThat(result.to().currencyCode()).isEqualTo("bitcoin");
            assertThat(result.to().amount().scale()).isEqualTo(Money.CRYPTO_SCALE);
        }

        @Test
        void reportsTheInvertedRateSoTheUiCanShowTheDirectionAsked() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "40000", "-1.25", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("100", "USD"), "bitcoin"));

            assertThat(result.rate().base()).isEqualTo("USD");
            assertThat(result.rate().quote()).isEqualTo("bitcoin");
            assertThat(result.rate().rate()).isEqualByComparingTo("0.000025");
            // The base's 24h move does not describe the inverted pair, so it is
            // dropped rather than reported with the wrong sign.
            assertThat(result.rate().change24h()).isNull();
        }

        @Test
        void looksTheCoinUpInTheFiatItWasAskedFor() {
            StubRateService rates = new StubRateService()
                    .quoting("bitcoin", "usd", "42000.50", NOW)
                    .quoting("bitcoin", "pln", "168002.00", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("168002", "PLN"), "bitcoin"));

            assertThat(result.to().amount()).isEqualByComparingTo("1.00000000");
        }
    }

    @Nested
    @DisplayName("crypto to crypto")
    class CryptoToCrypto {

        private StubRateService market() {
            return new StubRateService()
                    .quoting("bitcoin", "usd", "42000", "10", NOW)
                    .quoting("ethereum", "usd", "2100", "0", EARLIER);
        }

        @Test
        void routesThroughTheRoutingCurrency() {
            ConversionResult result = valueOf(serviceOver(market()).convert(Money.of("1", "bitcoin"), "ethereum"));

            assertThat(result.to().amount()).isEqualByComparingTo("20.00000000");
            assertThat(result.to().currencyCode()).isEqualTo("ethereum");
            assertThat(result.rate().rate()).isEqualByComparingTo("20");
        }

        @Test
        void costsExactlyTwoLookups() {
            StubRateService rates = market();

            valueOf(serviceOver(rates).convert(Money.of("1", "bitcoin"), "ethereum"));

            assertThat(rates.lookups()).isEqualTo(2);
        }

        @Test
        void datesTheCrossRateFromItsStalestLeg() {
            ConversionResult result = valueOf(serviceOver(market()).convert(Money.of("1", "bitcoin"), "ethereum"));

            assertThat(result.rate().fetchedAt()).isEqualTo(EARLIER);
        }

        @Test
        void derivesTheCrossPairsDailyMoveFromBothLegs() {
            // Base up 10%, quote flat: the ratio between them is up 10%.
            ConversionResult result = valueOf(serviceOver(market()).convert(Money.of("1", "bitcoin"), "ethereum"));

            assertThat(result.rate().change24h()).isEqualByComparingTo("10");
        }

        @Test
        void reportsNoDailyMoveWhenALegDidNotCarryOne() {
            StubRateService rates = new StubRateService()
                    .quoting("bitcoin", "usd", "42000", "10", NOW)
                    .quoting("ethereum", "usd", "2100", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("1", "bitcoin"), "ethereum"));

            assertThat(result.rate().change24h()).isNull();
        }

        @Test
        void handlesAQuoteThatDoubledWithoutDistortingTheCross() {
            // Quote up 100%, base flat: one unit of base now buys half as much.
            StubRateService rates = new StubRateService()
                    .quoting("bitcoin", "usd", "42000", "0", NOW)
                    .quoting("ethereum", "usd", "2100", "100", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("1", "bitcoin"), "ethereum"));

            assertThat(result.rate().change24h()).isEqualByComparingTo("-50");
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        void convertsACurrencyIntoItselfWithoutAskingTheApi() {
            StubRateService rates = new StubRateService();

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("2.5", "bitcoin"), "bitcoin"));

            assertThat(rates.lookups()).isZero();
            assertThat(result.to().amount()).isEqualByComparingTo("2.5");
            assertThat(result.rate().rate()).isEqualByComparingTo("1");
            assertThat(result.rate().fetchedAt()).isEqualTo(NOW);
        }

        @Test
        void treatsTheSameCurrencyInDifferentCasingAsIdentity() {
            StubRateService rates = new StubRateService();

            valueOf(serviceOver(rates).convert(Money.of("10", "usd"), "USD"));

            assertThat(rates.lookups()).isZero();
        }

        @Test
        void reportsCurrencyCodesInOneCanonicalCasingWhateverWasTyped() {
            // The history table and the favourites table are compared by these
            // strings, so "usd" from one screen and "USD" from another must not
            // become two currencies.
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000", NOW);

            ConversionResult result = valueOf(serviceOver(rates).convert(Money.of("1", " BitCoin "), "usd"));

            assertThat(result.from().currencyCode()).isEqualTo("bitcoin");
            assertThat(result.to().currencyCode()).isEqualTo("USD");
        }

        @Test
        void refusesFiatToFiat() {
            StubRateService rates = new StubRateService();

            assertThat(failureOf(serviceOver(rates).convert(Money.of("10", "USD"), "EUR")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Fiat-to-fiat");
            assertThat(rates.lookups()).isZero();
        }

        @Test
        void rejectsMissingArguments() {
            ConversionService service = serviceOver(new StubRateService());

            assertThatThrownBy(() -> service.convert(null, "usd")).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> service.convert(Money.of("1", "bitcoin"), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> service.convert(Money.of("1", "bitcoin"), "  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void passesAnApiFailureStraightThroughToTheCaller() {
            StubRateService rates =
                    new StubRateService().failingWith(new ApiException.RateLimited("slow down", null));

            assertThat(failureOf(serviceOver(rates).convert(Money.of("1", "bitcoin"), "usd")))
                    .isInstanceOf(ApiException.RateLimited.class);
        }

        @Test
        void reportsAnUnknownCoinAsNotFound() {
            StubRateService rates = new StubRateService().quoting("bitcoin", "usd", "42000", NOW);

            assertThat(failureOf(serviceOver(rates).convert(Money.of("1", "not-a-coin"), "usd")))
                    .isInstanceOf(ApiException.NotFound.class);
        }
    }

    @Nested
    @DisplayName("saving to history")
    class SavingToHistory {

        private StubRateService market() {
            return new StubRateService().quoting("bitcoin", "usd", "42000.50", "-1.25", NOW);
        }

        @Test
        void aPreviewConversionWritesNothing() {
            valueOf(serviceOver(market()).convert(Money.of("0.5", "bitcoin"), "usd"));

            assertThat(historyDao.size()).isZero();
        }

        @Test
        void aCommittedConversionWritesOneRow() {
            ConversionResult result =
                    valueOf(serviceOver(market()).convertAndSave(Money.of("0.5", "bitcoin"), "usd"));

            assertThat(result.to().amount()).isEqualByComparingTo("21000.25");
            assertThat(valueOf(history.recent())).singleElement().satisfies(saved -> {
                assertThat(saved.id()).isNotNull();
                assertThat(saved.fromCurrency()).isEqualTo("bitcoin");
                assertThat(saved.toCurrency()).isEqualTo("USD");
                assertThat(saved.fromAmount()).isEqualByComparingTo("0.5");
                assertThat(saved.toAmount()).isEqualByComparingTo("21000.25");
                assertThat(saved.rate()).isEqualByComparingTo("42000.50");
                // Stamped with when the conversion happened, not when the price
                // was fetched.
                assertThat(saved.convertedAt()).isEqualTo(NOW);
            });
        }

        @Test
        void stillReturnsTheConversionWhenTheWriteFails() {
            historyDao.failWith(new IllegalStateException("disk full"));

            ConversionResult result =
                    valueOf(serviceOver(market()).convertAndSave(Money.of("0.5", "bitcoin"), "usd"));

            assertThat(result.to().amount()).isEqualByComparingTo("21000.25");
        }

        @Test
        void doesNotWriteAConversionThatFailed() {
            StubRateService rates = new StubRateService().failingWith(new ApiException.Transport("down"));

            assertThat(failureOf(serviceOver(rates).convertAndSave(Money.of("1", "bitcoin"), "usd")))
                    .isInstanceOf(ApiException.Transport.class);
            assertThat(historyDao.size()).isZero();
        }
    }

    private static <T> T valueOf(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + future, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("Expected a value but the future did not deliver one", e);
        }
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            Object value = future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected a failure but the future produced " + value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + future, e);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("The future never completed", e);
        }
    }
}
