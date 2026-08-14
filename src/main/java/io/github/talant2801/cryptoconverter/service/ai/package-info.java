/**
 * The optional AI layer.
 *
 * <p>Everything here is reachable only through
 * {@link io.github.talant2801.cryptoconverter.service.ai.AiAssistant}, and the
 * implementation behind that interface is chosen once, at startup, by whether a
 * key was configured. No other package contains a branch on whether AI is
 * available.
 *
 * <p>The division of labour is deliberate and one-way: the model reads
 * sentences and writes sentences, and the application does every calculation.
 * A model reply is treated as untrusted input — validated against the
 * application's own list of currencies before any of it is acted on.
 */
package io.github.talant2801.cryptoconverter.service.ai;
