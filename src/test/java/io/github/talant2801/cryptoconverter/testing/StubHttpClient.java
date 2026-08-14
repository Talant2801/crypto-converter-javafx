package io.github.talant2801.cryptoconverter.testing;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * An {@link HttpClient} that answers from a script instead of a network.
 *
 * <p>The tests need three things a mocking framework makes awkward here: an
 * ordered sequence of differing outcomes across retries, an exact count of how
 * many calls were made, and the URI of each one. A hand-written double gives
 * all three plainly, and guarantees the suite passes with no internet
 * connection because there is no code path that could reach one.
 *
 * <p>Only {@code sendAsync(request, handler)} is implemented — it is the sole
 * method the clients under test use, and the rest throwing loudly means a
 * future change that starts blocking cannot pass unnoticed.
 *
 * <p>Lives in its own package because two clients need it: the CoinGecko client
 * and the Anthropic one. Request bodies are captured as well as URIs, since a
 * POST body is the interesting half of an AI request.
 */
public final class StubHttpClient extends HttpClient {

    private final Deque<Outcome> scripted = new ArrayDeque<>();
    private final List<URI> requestedUris = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final List<HttpHeaders> requestHeaders = new ArrayList<>();
    private Outcome fallback;

    /** One scripted answer: either a response to deliver or a failure to raise. */
    private record Outcome(int status, String body, Map<String, List<String>> headers, Throwable error) {

        static Outcome ok(String body) {
            return new Outcome(200, body, Map.of(), null);
        }

        static Outcome status(int status, Map<String, List<String>> headers) {
            return new Outcome(status, "", headers, null);
        }

        static Outcome failure(Throwable error) {
            return new Outcome(0, null, Map.of(), error);
        }
    }

    public StubHttpClient respondingWith(int status, String body) {
        scripted.add(new Outcome(status, body, Map.of(), null));
        return this;
    }

    public StubHttpClient respondingOk(String body) {
        scripted.add(Outcome.ok(body));
        return this;
    }

    public StubHttpClient respondingWithStatus(int status) {
        scripted.add(Outcome.status(status, Map.of()));
        return this;
    }

    public StubHttpClient respondingWithStatus(int status, Map<String, List<String>> headers) {
        scripted.add(Outcome.status(status, headers));
        return this;
    }

    public StubHttpClient failingWith(Throwable error) {
        scripted.add(Outcome.failure(error));
        return this;
    }

    /** Answer used once the script runs dry, so "always 429" needs no repetition. */
    public StubHttpClient alwaysRespondingWithStatus(int status) {
        this.fallback = Outcome.status(status, Map.of());
        return this;
    }

    public StubHttpClient alwaysFailingWith(Throwable error) {
        this.fallback = Outcome.failure(error);
        return this;
    }

    public int callCount() {
        return requestedUris.size();
    }

    /** The body of the last request, for asserting on what was actually sent. */
    public String lastRequestBody() {
        requireRequest();
        return requestBodies.get(requestBodies.size() - 1);
    }

    /** The headers of the last request. */
    public HttpHeaders lastRequestHeaders() {
        requireRequest();
        return requestHeaders.get(requestHeaders.size() - 1);
    }

    private void requireRequest() {
        if (requestedUris.isEmpty()) {
            throw new IllegalStateException("No request was made");
        }
    }

    public URI lastUri() {
        if (requestedUris.isEmpty()) {
            throw new IllegalStateException("No request was made");
        }
        return requestedUris.get(requestedUris.size() - 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        requestedUris.add(request.uri());
        requestBodies.add(bodyOf(request));
        requestHeaders.add(request.headers());
        Outcome outcome = scripted.isEmpty() ? fallback : scripted.poll();
        if (outcome == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("StubHttpClient ran out of scripted outcomes for " + request.uri()));
        }
        if (outcome.error() != null) {
            return CompletableFuture.failedFuture(outcome.error());
        }
        HttpResponse<String> response = new StubResponse(request, outcome.status(), outcome.body(), outcome.headers());
        return CompletableFuture.completedFuture((HttpResponse<T>) response);
    }

    /**
     * Reads a request body back out of its publisher.
     *
     * <p>{@link HttpRequest} exposes the body only as a reactive publisher, so
     * the bytes have to be subscribed for rather than simply read. The
     * subscription requests everything at once and the future is already
     * complete by the time it is joined, because the publishers involved are
     * in-memory.
     */
    private static String bodyOf(HttpRequest request) {
        return request.bodyPublisher()
                .map(publisher -> {
                    CompletableFuture<String> body = new CompletableFuture<>();
                    StringBuilder collected = new StringBuilder();
                    publisher.subscribe(new Flow.Subscriber<>() {

                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(ByteBuffer item) {
                            collected.append(StandardCharsets.UTF_8.decode(item));
                        }

                        @Override
                        public void onError(Throwable error) {
                            body.completeExceptionally(error);
                        }

                        @Override
                        public void onComplete() {
                            body.complete(collected.toString());
                        }
                    });
                    return body.join();
                })
                .orElse("");
    }

    private record StubResponse(HttpRequest request, int statusCode, String body, Map<String, List<String>> rawHeaders)
            implements HttpResponse<String> {

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(rawHeaders, (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    // --- Everything below is unused by the client under test. -----------------

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException("StubHttpClient does not open connections");
    }

    @Override
    public SSLParameters sslParameters() {
        throw new UnsupportedOperationException("StubHttpClient does not open connections");
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("The client must never block the calling thread");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
        throw new UnsupportedOperationException("Push promises are not used");
    }
}
