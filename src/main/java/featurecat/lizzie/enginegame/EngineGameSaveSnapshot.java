package featurecat.lizzie.enginegame;

import java.util.Objects;

/**
 * One-shot immutable save input for an in-progress history. Producing this snapshot does not
 * update batch totals.
 */
public record EngineGameSaveSnapshot(
    EngineGameRecordContext context,
    String blackDisplayName,
    String whiteDisplayName,
    long blackTimeMs,
    long whiteTimeMs,
    long blackVisits,
    long whiteVisits,
    long blackMoveTimeMs,
    long whiteMoveTimeMs) {
  public EngineGameSaveSnapshot {
    context = Objects.requireNonNull(context, "context");
    blackDisplayName = blackDisplayName == null ? "" : blackDisplayName;
    whiteDisplayName = whiteDisplayName == null ? "" : whiteDisplayName;
  }
}
