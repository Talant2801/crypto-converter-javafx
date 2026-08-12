package io.github.talant2801.cryptoconverter.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * The body of {@code /coins/{id}/market_chart}.
 *
 * <p>CoinGecko encodes each sample as a two-element array of
 * {@code [epochMillis, price]} rather than an object, so the mapping is a list
 * of lists; converting it into a typed
 * {@link io.github.talant2801.cryptoconverter.domain.PricePoint} is the client's
 * job. The sibling {@code market_caps} and {@code total_volumes} series are
 * ignored — the chart plots price only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketChartDto(List<List<BigDecimal>> prices) {
}
