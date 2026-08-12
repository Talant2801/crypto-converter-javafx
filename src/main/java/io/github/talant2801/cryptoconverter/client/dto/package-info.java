/**
 * Types shaped by the CoinGecko wire format.
 *
 * <p>These exist so Jackson has something to bind to without the domain records
 * having to carry upstream naming or nullability. Nothing outside
 * {@link io.github.talant2801.cryptoconverter.client} should reference them.
 */
package io.github.talant2801.cryptoconverter.client.dto;
