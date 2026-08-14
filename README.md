# CryptoConverter

A JavaFX desktop application for converting between cryptocurrencies and fiat
currencies at live rates. It converts crypto to fiat, fiat to crypto and crypto
to crypto (routed through USD), charts the selected pair over 7, 30 or 90 days,
and keeps a searchable history of past conversions in a local SQLite database.
Prices come from the CoinGecko public API through a cache that coalesces
duplicate requests and falls back to the last known value when the network is
unavailable. An optional AI layer, enabled by configuring an Anthropic API key,
adds natural-language conversion ("how much is 0.35 BTC in Polish zloty") and
plain-language market summaries.

![screenshot](docs/screenshot.png)

> The screenshot is captured on first run — see [Build and run](#build-and-run).

---

## Features

- **Conversion in every direction** — crypto to fiat, fiat to crypto, and
  crypto to crypto routed through USD. Amounts are `BigDecimal` end to end;
  no monetary value is ever a `double`. Results round `HALF_UP` at eight
  decimals for coins and two for fiat.
- **Live rates with a 24h change** shown beside the result and colour-coded
  green or red.
- **Top 100 coins loaded dynamically** by market capitalisation, plus USD, EUR,
  PLN, GBP and UAH. If the coin list cannot be fetched, the selectors fall back
  to the built-in majors (BTC, ETH, SOL, XRP, ADA, DOGE) rather than emptying.
- **Request coalescing and a TTL cache** in front of CoinGecko's rate-limited
  free tier: concurrent requests for the same pair produce exactly one HTTP
  call, and a failed refresh serves the previous value with a
  "stale — last updated HH:mm" marker instead of an error.
- **Exponential backoff with full jitter** on HTTP 429 and transport failures,
  honouring `Retry-After`, capped at three retries; 404s and unreadable bodies
  fail fast.
- **Price chart** over 7, 30 or 90 days with explicit loading, error and retry
  states.
- **Conversion history** in SQLite — the last 100 conversions, with per-row
  delete and a confirmed clear-all.
- **Debounced input** — a conversion is recalculated at most once per 400 ms of
  typing pause, and a late reply from a superseded request is discarded rather
  than painted over a newer result.
- **Optional AI layer** — natural-language conversion and market summaries. The
  model only ever parses intent; every number on screen is computed by the same
  arithmetic the ordinary UI path uses.

---

## Tech stack

| Concern            | Choice                                   | Why |
| ------------------ | ---------------------------------------- | --- |
| Language           | Java 21                                  | Records, sealed interfaces, pattern matching in `switch`, text blocks |
| UI                 | JavaFX 21 (`org.openjfx.javafxplugin`)   | Built programmatically; no FXML |
| Build              | Gradle with the Kotlin DSL               | Type-safe build script, toolchain-pinned JDK |
| HTTP               | `java.net.http.HttpClient`               | In the JDK; fully asynchronous, no third-party client |
| JSON               | Jackson Databind                         | Record binding, `@JsonIgnoreProperties` tolerance for upstream additions |
| Persistence        | SQLite via `org.xerial:sqlite-jdbc`      | Embedded, zero-configuration, single-file |
| Logging            | SLF4J with Logback                       | No `printStackTrace` anywhere |
| Tests              | JUnit 5, Mockito, AssertJ                | |
| Coverage           | JaCoCo                                   | HTML and XML reports |
| Packaging          | Shadow plugin                            | One runnable fat jar |
| Dependency wiring  | A hand-written composition root          | No Spring, no Guice, no annotation processing |

---

## Architecture

```
io.github.talant2801.cryptoconverter
├── Launcher.java            main() entry point (not an Application subclass, so the fat jar starts)
├── CryptoConverterApp.java  JavaFX Application; owns the stage and the context lifecycle
├── AppContext.java          composition root: builds and wires every collaborator
├── config/
│   ├── AppConfig.java       record: API key, cache TTLs, db path, http timeouts
│   └── ConfigLoader.java    environment variables > properties file > defaults
├── domain/
│   ├── Coin.java  Money.java  ExchangeRate.java  ConversionResult.java
│   ├── PricePoint.java  ConversionRecord.java  CurrencyPair.java
│   └── Fiat.java            the supported fiat set, and the one canonical spelling of a code
├── client/
│   ├── CoinGeckoClient.java      interface
│   ├── HttpCoinGeckoClient.java  HttpClient + retry/backoff
│   ├── CoinGeckoResponseMapper.java
│   ├── dto/                      API-shaped types, mapped at the boundary
│   └── ApiException.java         sealed: RateLimited | NotFound | Transport | Malformed
├── service/
│   ├── RateService.java          interface
│   ├── CachedRateService.java    TTL cache, request coalescing, stale fallback
│   ├── TtlCache.java             the cache itself
│   ├── ConversionService.java    rate lookup, arithmetic, crypto-to-crypto routing
│   ├── HistoryService.java       history and favourites, off the calling thread
│   └── ai/
│       ├── AiAssistant.java      the seam that makes AI optional
│       ├── ClaudeAiAssistant.java
│       └── NoOpAiAssistant.java
├── persistence/
│   ├── Database.java             SQLite bootstrap and idempotent schema
│   ├── ConversionHistoryDao.java + SqliteConversionHistoryDao.java
│   └── FavouritesDao.java        + SqliteFavouritesDao.java
└── ui/
    ├── MainView.java             layout, and the wiring between panes
    ├── ConverterPane.java  ChartPane.java  HistoryPane.java  AiPane.java
    ├── CurrencyCatalog.java  CurrencyOption.java
    └── util/UiUtils.java         formatting, debounce, dialogs, the FX-thread funnel
```

Dependencies point downward only, and each layer is defined by what it is
allowed to know. The `client` package is the only code aware that CoinGecko
exists — its URLs, JSON shapes and status codes stop there, and callers see
domain types and a sealed `ApiException` they can switch over exhaustively.
The `service` package holds every rule about arithmetic and about how often the
network may be asked, and imports no JavaFX at all, which is what makes those
rules testable without a display. The `persistence` package is the only place
that catches `SQLException`. The `ui` package computes nothing: a pane turns a
control event into a service call and a future into a label.

The one class that knows about every layer is `AppContext`, and that is the
point — the object graph is a few dozen lines of ordinary Java read top to
bottom, so a dependency-injection framework would add indirection without
removing any. It is also where the AI layer is decided: an API key produces a
`ClaudeAiAssistant`, its absence produces a `NoOpAiAssistant` and a window built
without the AI pane, and nothing downstream contains a branch on which one it
got.

---

## Build and run

Requires JDK 21. The Gradle wrapper and the JavaFX plugin fetch everything else.

```bash
./gradlew run          # compile and launch the application
./gradlew build        # compile, test and produce the coverage report
./gradlew shadowJar    # build a runnable fat jar
java -jar build/libs/crypto-converter-1.0.0-all.jar
```

To capture the screenshot referenced above, run the application and save a
window capture to `docs/screenshot.png`.

---

## Configuration

Every setting has a working default, so the application runs with no
configuration at all. Values are resolved in this order:

1. an environment variable,
2. `~/.cryptoconverter/config.properties`,
3. the built-in default.

The properties file lives outside the repository on purpose, and `.gitignore`
excludes `*.properties`, so a real key cannot be committed by accident. Copy
[`config.properties.example`](config.properties.example) there and edit it:

```bash
mkdir -p ~/.cryptoconverter
cp config.properties.example ~/.cryptoconverter/config.properties
```

| Setting | Environment variable | Default |
| ------- | -------------------- | ------- |
| Anthropic API key | `ANTHROPIC_API_KEY` | unset — AI features are hidden |
| CoinGecko base URL | `COINGECKO_BASE_URL` | `https://api.coingecko.com/api/v3` |
| HTTP timeout (ms) | `CRYPTOCONVERTER_HTTP_TIMEOUT_MS` | `10000` |
| Retries after the first attempt | `CRYPTOCONVERTER_MAX_RETRIES` | `3` |
| Retry base delay (ms) | `CRYPTOCONVERTER_RETRY_BASE_DELAY_MS` | `500` |
| Rate cache TTL (s) | `CRYPTOCONVERTER_RATE_CACHE_TTL_SECONDS` | `60` |
| Coin list cache TTL (s) | `CRYPTOCONVERTER_COIN_LIST_CACHE_TTL_SECONDS` | `1800` |
| History database path | `CRYPTOCONVERTER_DB_PATH` | `~/.cryptoconverter/history.db` |

### The optional Anthropic key

CoinGecko needs no key; the AI layer does. Set one to enable natural-language
conversion and market summaries:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew run
```

Without it the application starts normally and the AI pane is not built at all —
not a disabled control, but an absent one. The key is read once at startup,
written into exactly one request header, and is never logged, printed, or
included in an exception message or an error dialog.

---

## Testing

```bash
./gradlew test                # run the suite
./gradlew build               # suite plus the coverage report
```

The coverage report is written to
`build/reports/jacoco/test/html/index.html`, and the test report to
`build/reports/tests/test/index.html`.

The suite passes with no internet connection and touches no file outside the
build directory. Every HTTP client is replaced by a scripted stub, the DAO
tests run against a real SQLite database held in memory, and clocks are
injected so that TTL and expiry are tested by moving time rather than waiting
for it. Coverage of `service/`, `client/` and `persistence/` sits comfortably
above the 80% line target; there are no UI tests, which is a deliberate choice
about where testing effort pays.

---

## Not financial advice

CryptoConverter is a portfolio and educational project. Rates come from a free
public API, may be delayed, cached, or stale, and are shown for information
only. Nothing this application displays — including any AI-generated market
summary — is financial advice, and none of it should be used to make investment
decisions.
