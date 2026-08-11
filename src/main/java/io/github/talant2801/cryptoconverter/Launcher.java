package io.github.talant2801.cryptoconverter;

/**
 * Entry point for the fat jar.
 *
 * <p>A main class that extends {@code Application} makes the JVM insist on the
 * JavaFX runtime being on the module path. Launching through a plain class
 * sidesteps that, so {@code java -jar} works on a stock JDK.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        CryptoConverterApp.main(args);
    }
}
