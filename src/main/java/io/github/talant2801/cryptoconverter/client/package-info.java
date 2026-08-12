/**
 * The boundary between the application and CoinGecko.
 *
 * <p>Everything that knows the upstream exists lives here: the URLs, the JSON
 * shapes, the status-code meanings, and the retry policy. Callers depend on
 * {@link io.github.talant2801.cryptoconverter.client.CoinGeckoClient} and see
 * only domain types and a sealed
 * {@link io.github.talant2801.cryptoconverter.client.ApiException} — swapping
 * the data source would not reach past this package.
 *
 * <p>This layer does not cache. Coalescing and TTL belong to the service layer
 * above, so the client stays a faithful description of one HTTP call.
 */
package io.github.talant2801.cryptoconverter.client;
