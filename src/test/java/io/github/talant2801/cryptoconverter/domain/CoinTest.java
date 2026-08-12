package io.github.talant2801.cryptoconverter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CoinTest {

    @Test
    void upperCasesTheSymbolForDisplay() {
        Coin coin = new Coin("bitcoin", "btc", "Bitcoin", "https://example.invalid/btc.png");

        assertThat(coin.displaySymbol()).isEqualTo("BTC");
    }

    @Test
    void rejectsABlankIdBecauseEveryApiCallKeysOnIt() {
        assertThatThrownBy(() -> new Coin("  ", "btc", "Bitcoin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must not be blank");
    }

    @Test
    void allowsAMissingImageUrl() {
        assertThat(new Coin("bitcoin", "btc", "Bitcoin", null).imageUrl()).isNull();
    }

    @Test
    void requiresIdSymbolAndName() {
        assertThatThrownBy(() -> new Coin(null, "btc", "Bitcoin", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Coin("bitcoin", null, "Bitcoin", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Coin("bitcoin", "btc", null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
