package io.github.talant2801.cryptoconverter.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The fiat set decides rounding scale and routing, so the lookup has to be
 * forgiving about casing and firm about everything else.
 */
class FiatTest {

    @Test
    void resolvesACodeInAnyCasing() {
        assertThat(Fiat.find("usd")).contains(Fiat.USD);
        assertThat(Fiat.find(" Pln ")).contains(Fiat.PLN);
        assertThat(Fiat.find("UAH")).contains(Fiat.UAH);
    }

    @Test
    void treatsACoinIdAsSomethingOtherThanFiat() {
        assertThat(Fiat.isFiat("bitcoin")).isFalse();
        assertThat(Fiat.isFiat("btc")).isFalse();
        assertThat(Fiat.isFiat("")).isFalse();
        assertThat(Fiat.isFiat(null)).isFalse();
    }

    @Test
    void quotesEveryPairThroughTheDollar() {
        assertThat(Fiat.ROUTING).isEqualTo(Fiat.USD);
        assertThat(Fiat.ROUTING.apiCode()).isEqualTo("usd");
    }

    @Test
    void offersTheSupportedCurrenciesInDisplayOrder() {
        assertThat(Fiat.all())
                .containsExactly(Fiat.USD, Fiat.EUR, Fiat.PLN, Fiat.GBP, Fiat.UAH)
                .allSatisfy(fiat -> assertThat(fiat.displayName()).isNotBlank());
    }

    @Test
    void reportsCodesInTheCasingEachConsumerExpects() {
        assertThat(Fiat.PLN.code()).isEqualTo("PLN");
        assertThat(Fiat.PLN.apiCode()).isEqualTo("pln");
    }
}
