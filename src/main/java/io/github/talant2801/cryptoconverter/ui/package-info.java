/**
 * The JavaFX layer: panes, layout and the wiring between them.
 *
 * <p>Nothing below this package imports JavaFX, and nothing in this package
 * does arithmetic, caching or SQL. A pane's job is to turn a control event into
 * a service call and a future into a label — everything it displays was decided
 * somewhere else.
 *
 * <p>The views are built in Java rather than FXML: the layout here is small and
 * dynamic, and keeping it in code means a change to a control and a change to
 * its behaviour live in the same file and are checked by the same compiler.
 * Colour and spacing are not built in code — those live in {@code styles.css}.
 */
package io.github.talant2801.cryptoconverter.ui;
