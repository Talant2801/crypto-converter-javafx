package io.github.talant2801.cryptoconverter.service.ai;

/**
 * An AI request that could not be turned into something usable.
 *
 * <p>Every message on this exception is written by the application, never
 * echoed from the model or from an HTTP response body — both are untrusted
 * input, and an error dialog is the wrong place to render either. That also
 * removes any path by which a request header could reach a log or a screen.
 */
public class AiException extends RuntimeException {

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
