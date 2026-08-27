package featurecat.lizzie.enginegame;

import java.util.Objects;

public record BatchSummary(
    EngineParticipantIdentity first,
    EngineParticipantIdentity second,
    int gameOrdinal,
    int batchLimit,
    boolean exchangeColors) {
  public BatchSummary {
    first = Objects.requireNonNull(first, "first");
    second = Objects.requireNonNull(second, "second");
  }
}
