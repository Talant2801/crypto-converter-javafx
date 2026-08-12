package io.github.talant2801.cryptoconverter.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * One entry of {@code /coins/markets}.
 *
 * <p>The endpoint returns some thirty fields per coin; only the handful the
 * application actually shows is mapped, and {@code ignoreUnknown} keeps the
 * client working when CoinGecko adds more.
 *
 * <p>This is an API-shaped type, not a domain type — it is mapped to
 * {@link io.github.talant2801.cryptoconverter.domain.Coin} at the client
 * boundary so the upstream field names never leak past the client package.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoinMarketDto(
        String id,
        String symbol,
        String name,
        String image,
        @JsonProperty("current_price") BigDecimal currentPrice,
        @JsonProperty("price_change_percentage_24h") BigDecimal priceChangePercentage24h) {
}
