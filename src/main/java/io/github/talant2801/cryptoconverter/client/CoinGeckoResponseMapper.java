package io.github.talant2801.cryptoconverter.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.talant2801.cryptoconverter.client.dto.CoinMarketDto;
import io.github.talant2801.cryptoconverter.client.dto.MarketChartDto;
import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Turns CoinGecko response bodies into domain types.
 *
 * <p>Separated from {@link HttpCoinGeckoClient} so that the client is only
 * about HTTP — sending, classifying status codes, retrying — and this class is
 * only about the wire format. The two change for entirely different reasons:
 * one when the transport policy changes, the other when CoinGecko reshapes a
 * payload.
 *
 * <p>Package-private: nothing outside the client package has any business
 * knowing the upstream JSON exists.
 */
final class CoinGeckoResponseMapper {

    /** {@code /simple/price} returns coin id -> currency key -> value. */
    private static final TypeReference<Map<String, Map<String, BigDecimal>>> SIMPLE_PRICE_TYPE =
            new TypeReference<>() {};

    private static final TypeReference<List<CoinMarketDto>> COIN_MARKETS_TYPE = new TypeReference<>() {};

    /** Suffix CoinGecko appends for the change series when it is requested. */
    private static final String CHANGE_SUFFIX = "_24h_change";

    private final ObjectMapper objectMapper;

    CoinGeckoResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<Coin> toCoins(String body) {
        List<CoinMarketDto> markets = read(body, COIN_MARKETS_TYPE, "coin list");
        List<Coin> coins = new ArrayList<>(markets.size());
        for (CoinMarketDto market : markets) {
            // A row missing its identity is unusable, but one bad row should not
            // cost the user the other ninety-nine.
            if (market.id() == null || market.symbol() == null || market.name() == null) {
                continue;
            }
            coins.add(new Coin(market.id(), market.symbol(), market.name(), market.image()));
        }
        if (coins.isEmpty()) {
            throw new ApiException.Malformed("Coin list response contained no usable entries");
        }
        return List.copyOf(coins);
    }

    /**
     * Reads the spot-price matrix into one {@link ExchangeRate} per pair present.
     *
     * <p>Pairs the response omits are skipped rather than faked, so a coin that
     * is not quoted in one exotic fiat does not sink the whole batch. A response
     * that names none of the requested coins is a different matter: the ids were
     * wrong, and that is a {@link ApiException.NotFound}.
     *
     * @param fetchedAt stamped onto every rate so the cache can age them
     */
    List<ExchangeRate> toExchangeRates(
            String body, Collection<String> requestedCoinIds, Collection<String> fiatCodes, Instant fetchedAt) {

        Map<String, Map<String, BigDecimal>> matrix = read(body, SIMPLE_PRICE_TYPE, "spot prices");
        List<ExchangeRate> rates = new ArrayList<>();

        for (String coinId : requestedCoinIds) {
            Map<String, BigDecimal> quotes = matrix.get(coinId);
            if (quotes == null) {
                continue;
            }
            for (String fiat : fiatCodes) {
                String key = fiat.toLowerCase();
                BigDecimal price = quotes.get(key);
                // A zero or negative price is not a real quote; ExchangeRate
                // would reject it, so drop it before it gets there.
                if (price == null || price.signum() <= 0) {
                    continue;
                }
                rates.add(new ExchangeRate(
                        coinId, fiat.toUpperCase(), price, quotes.get(key + CHANGE_SUFFIX), fetchedAt));
            }
        }

        if (rates.isEmpty()) {
            throw new ApiException.NotFound(
                    "No prices returned for " + requestedCoinIds + " in " + fiatCodes);
        }
        return List.copyOf(rates);
    }

    /**
     * Reads the {@code [[epochMillis, price], ...]} series into ordered points.
     *
     * <p>Unlike the batched endpoints there is no partial success worth
     * salvaging here: a chart drawn from a series with holes in it silently
     * misleads, so a row of the wrong shape fails the whole response.
     */
    List<PricePoint> toPricePoints(String body) {
        MarketChartDto chart = read(body, new TypeReference<MarketChartDto>() {}, "price history");
        if (chart.prices() == null) {
            throw new ApiException.Malformed("Price history response had no prices array");
        }
        List<PricePoint> points = new ArrayList<>(chart.prices().size());
        for (List<BigDecimal> row : chart.prices()) {
            if (row == null || row.size() < 2 || row.get(0) == null || row.get(1) == null) {
                throw new ApiException.Malformed("Price history contained a malformed sample: " + row);
            }
            points.add(new PricePoint(Instant.ofEpochMilli(row.get(0).longValue()), row.get(1)));
        }
        return List.copyOf(points);
    }

    private <T> T read(String body, TypeReference<T> type, String description) {
        try {
            T value = objectMapper.readValue(body, type);
            if (value == null) {
                throw new ApiException.Malformed("Empty " + description + " response");
            }
            return value;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // The body itself is not echoed: it can be long, and on an error
            // page it is HTML noise rather than anything diagnostic.
            throw new ApiException.Malformed("Could not parse " + description + " response", e);
        }
    }
}
