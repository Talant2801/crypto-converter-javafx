package io.github.talant2801.cryptoconverter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void keepsTheExactValueItWasGiven() {
            Money money = Money.of("0.12345678", "bitcoin");

            assertThat(money.amount()).isEqualByComparingTo("0.12345678");
            assertThat(money.currencyCode()).isEqualTo("bitcoin");
        }

        @Test
        void preservesTrailingZerosBecauseScaleIsMeaningful() {
            // 1.50 USD and 1.5 USD are the same value but not the same
            // presentation; setScale is what normalises them, not the ctor.
            assertThat(Money.of("1.50", "USD").amount().scale()).isEqualTo(2);
            assertThat(Money.of("1.5", "USD").amount().scale()).isEqualTo(1);
        }

        @Test
        void rejectsNegativeAmounts() {
            assertThatThrownBy(() -> Money.of("-0.01", "USD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be negative");
        }

        @Test
        void acceptsZero() {
            assertThat(Money.of("0", "USD").isZero()).isTrue();
            assertThat(Money.of("0.00000000", "bitcoin").isZero()).isTrue();
        }

        @Test
        void rejectsBlankCurrencyCode() {
            assertThatThrownBy(() -> Money.of("1", "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currencyCode");
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> new Money(null, "USD"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("multiply")
    class Multiply {

        @Test
        void appliesTheRateWithoutLosingPrecision() {
            Money result = Money.of("0.35", "bitcoin").multiply(new BigDecimal("94203.117654"));

            // 0.35 * 94203.117654 = 32971.0911789 exactly; no rounding yet.
            assertThat(result.amount()).isEqualByComparingTo("32971.0911789");
        }

        @Test
        void keepsTheCurrencyOfTheReceiver() {
            // multiply scales a quantity; re-labelling the currency is the
            // conversion service's job, not this record's.
            assertThat(Money.of("2", "USD").multiply(new BigDecimal("3")).currencyCode())
                    .isEqualTo("USD");
        }

        @Test
        void zeroTimesAnyRateIsZero() {
            assertThat(Money.of("0", "bitcoin").multiply(new BigDecimal("94203.11")).isZero())
                    .isTrue();
        }

        @Test
        void survivesVerySmallAmounts() {
            Money dust = Money.of("0.00000001", "bitcoin");

            assertThat(dust.multiply(new BigDecimal("94203.11")).amount())
                    .isEqualByComparingTo("0.0009420311");
        }
    }

    @Nested
    @DisplayName("withScale rounds HALF_UP")
    class Rounding {

        @ParameterizedTest(name = "{0} at scale {1} -> {2}")
        @CsvSource({
            // exact halves round away from zero
            "0.005,     2, 0.01",
            "0.015,     2, 0.02",
            "2.675,     2, 2.68",
            "1.005,     2, 1.01",
            // just below a half rounds down
            "0.0049999, 2, 0.00",
            "2.674999,  2, 2.67",
            // just above rounds up
            "0.00500001, 2, 0.01",
            // crypto scale boundaries
            "0.000000005,  8, 0.00000001",
            "0.0000000049, 8, 0.00000000",
            "1.234567895,  8, 1.23456790",
            "1.234567894,  8, 1.23456789",
        })
        void roundsAtTheBoundary(String input, int scale, String expected) {
            assertThat(Money.of(input, "USD").withScale(scale).amount())
                    .isEqualByComparingTo(expected);
        }

        @Test
        void setsTheScaleEvenWhenPaddingIsNeeded() {
            assertThat(Money.of("7", "USD").withScale(2).amount().toPlainString())
                    .isEqualTo("7.00");
            assertThat(Money.of("7", "bitcoin").withScale(8).amount().toPlainString())
                    .isEqualTo("7.00000000");
        }

        @Test
        void roundingToFiatScaleCanProduceZeroFromDust() {
            // A fraction of a cent is not a cent; the UI must be able to show
            // that honestly rather than inventing 0.01.
            assertThat(Money.of("0.004", "USD").withScale(Money.FIAT_SCALE).isZero()).isTrue();
        }

        @Test
        void isIdempotent() {
            Money once = Money.of("2.675", "USD").withScale(2);

            assertThat(once.withScale(2)).isEqualTo(once);
        }

        @Test
        void keepsTheCurrencyCode() {
            assertThat(Money.of("2.675", "EUR").withScale(2).currencyCode()).isEqualTo("EUR");
        }
    }

    @Test
    void declaresTheScalesTheApplicationRoundsTo() {
        assertThat(Money.CRYPTO_SCALE).isEqualTo(8);
        assertThat(Money.FIAT_SCALE).isEqualTo(2);
    }

    @Test
    void equalityFollowsScaleBecauseBigDecimalEqualsDoes() {
        // Worth pinning: two Money values that compare equal numerically are
        // not equal() if their scales differ. Callers must normalise first.
        assertThat(Money.of("1.5", "USD")).isNotEqualTo(Money.of("1.50", "USD"));
        assertThat(Money.of("1.50", "USD")).isEqualTo(Money.of("1.5", "USD").withScale(2));
    }
}
