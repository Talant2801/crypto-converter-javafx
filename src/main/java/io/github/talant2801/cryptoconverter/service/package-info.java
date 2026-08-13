/**
 * Application logic: what a conversion means, and how often the network is
 * allowed to be asked.
 *
 * <p>This layer depends on {@link io.github.talant2801.cryptoconverter.client}
 * and {@link io.github.talant2801.cryptoconverter.domain} and on nothing above
 * it — there is no JavaFX import anywhere in this package, which is what makes
 * every rule in it testable without a display.
 *
 * <p>Caching lives here rather than in the client because it is a policy about
 * usage, not about HTTP: how long a price stays interesting, and what to show
 * when the network refuses. Swapping the data source would leave this package
 * untouched.
 */
package io.github.talant2801.cryptoconverter.service;
