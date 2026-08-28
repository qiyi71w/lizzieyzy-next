package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Normal per-game completion. User stop is not a GameOutcome. */
public sealed interface GameOutcome {
  record Resign(EngineGameSide side) implements GameOutcome {
    public Resign {
      side = Objects.requireNonNull(side, "side");
    }
  }

  record DoublePass() implements GameOutcome {}

  record MaxMoves() implements GameOutcome {}
}
