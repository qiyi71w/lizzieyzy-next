package featurecat.lizzie.rules;

/**
 * Canonical board node classification.
 *
 * <p>Markerless sync input cannot report a real pass event, so it must land on {@link #SNAPSHOT}.
 */
public enum BoardNodeKind {
  MOVE,
  PASS,
  SNAPSHOT;

  public boolean isHistoryAction() {
    return this == MOVE || this == PASS;
  }
}
