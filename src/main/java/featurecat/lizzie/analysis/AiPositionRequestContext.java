package featurecat.lizzie.analysis;

import java.util.Objects;

/** Identity of one AI形势 request: display position, rules, komi, and engine incarnation. */
public final class AiPositionRequestContext {
  private final Object displayPositionIdentity;
  private final long contextRevision;
  private final String stonesFingerprint;
  private final boolean blackToPlay;
  private final int boardWidth;
  private final int boardHeight;
  private final String rules;
  private final double komi;
  private final Leelaz engine;
  private final long engineIncarnation;

  public AiPositionRequestContext(
      Object displayPositionIdentity,
      long contextRevision,
      String stonesFingerprint,
      boolean blackToPlay,
      int boardWidth,
      int boardHeight,
      String rules,
      double komi,
      Leelaz engine,
      long engineIncarnation) {
    if (boardWidth <= 0 || boardHeight <= 0 || engineIncarnation <= 0L) {
      throw new IllegalArgumentException("invalid AI position context");
    }
    this.displayPositionIdentity =
        Objects.requireNonNull(displayPositionIdentity, "displayPositionIdentity");
    this.contextRevision = contextRevision;
    this.stonesFingerprint = Objects.requireNonNull(stonesFingerprint, "stonesFingerprint");
    this.blackToPlay = blackToPlay;
    this.boardWidth = boardWidth;
    this.boardHeight = boardHeight;
    this.rules = Objects.requireNonNull(rules, "rules");
    this.komi = komi;
    this.engine = engine;
    this.engineIncarnation = engineIncarnation;
  }

  public Object displayPositionIdentity() {
    return displayPositionIdentity;
  }

  public long contextRevision() {
    return contextRevision;
  }

  public String stonesFingerprint() {
    return stonesFingerprint;
  }

  public boolean blackToPlay() {
    return blackToPlay;
  }

  public int boardWidth() {
    return boardWidth;
  }

  public int boardHeight() {
    return boardHeight;
  }

  public String rules() {
    return rules;
  }

  public double komi() {
    return komi;
  }

  public Leelaz engine() {
    return engine;
  }

  public long engineIncarnation() {
    return engineIncarnation;
  }

  public boolean matches(AiPositionRequestContext other) {
    return other != null
        && displayPositionIdentity == other.displayPositionIdentity
        && contextRevision == other.contextRevision
        && stonesFingerprint.equals(other.stonesFingerprint)
        && blackToPlay == other.blackToPlay
        && boardWidth == other.boardWidth
        && boardHeight == other.boardHeight
        && rules.equals(other.rules)
        && Double.doubleToLongBits(komi) == Double.doubleToLongBits(other.komi)
        && engine == other.engine
        && engineIncarnation == other.engineIncarnation;
  }
}
