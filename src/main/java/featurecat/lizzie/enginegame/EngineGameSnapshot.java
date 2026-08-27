package featurecat.lizzie.enginegame;

import java.util.Objects;

public sealed interface EngineGameSnapshot {
  record Idle() implements EngineGameSnapshot {}

  record BatchActive(BatchSummary batch, GameActivity activity) implements EngineGameSnapshot {
    public BatchActive {
      batch = Objects.requireNonNull(batch, "batch");
      activity = Objects.requireNonNull(activity, "activity");
    }
  }
}
