package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Product chrome event published through the internal Swing seam. */
public record EngineGameChromeTransition(Kind kind, EngineGameSnapshot snapshot) {
  public EngineGameChromeTransition {
    kind = Objects.requireNonNull(kind, "kind");
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  public enum Kind {
    STARTING,
    PLAYING,
    PAUSED,
    RESUMED,
    BETWEEN_GAMES,
    START_FAILED,
    USER_STOPPED,
    BATCH_ENDED,
    LATER_GAME_FAILED
  }
}
