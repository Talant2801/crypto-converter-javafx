package io.github.talant2801.cryptoconverter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A pair's identity is its two codes, so normalisation is what makes a
 * favourite saved from the UI equal to the same favourite read back from the
 * database.
 */
class CurrencyPairTest {

    @Test
    void normalisesFiatToUpperCaseAndCoinIdsToLower() {
        CurrencyPair pair = CurrencyPair.of(" BitCoin ", "usd");

        assertThat(pair.from()).isEqualTo("bitcoin");
        assertThat(pair.to()).isEqualTo("USD");
    }

    @Test
    void comparesEqualRegardlessOfHowItWasTyped() {
        assertThat(CurrencyPair.of("BITCOIN", "usd")).isEqualTo(CurrencyPair.of("bitcoin", "USD"));
    }

    @Test
    void distinguishesADirectionFromItsReverse() {
        CurrencyPair pair = CurrencyPair.of("bitcoin", "USD");

        assertThat(pair.swapped()).isEqualTo(CurrencyPair.of("USD", "bitcoin"));
        assertThat(pair.swapped()).isNotEqualTo(pair);
        assertThat(pair.swapped().swapped()).isEqualTo(pair);
    }

    @Test
    void rejectsAPairOfOneCurrency() {
        assertThatThrownBy(() -> CurrencyPair.of("bitcoin", "Bitcoin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two different");
    }

    @Test
    void rejectsMissingCodes() {
        assertThatThrownBy(() -> CurrencyPair.of(null, "USD")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CurrencyPair.of("bitcoin", "  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
