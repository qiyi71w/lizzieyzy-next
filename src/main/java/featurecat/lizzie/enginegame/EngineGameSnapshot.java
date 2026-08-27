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

  default boolean starting() {
    return this instanceof BatchActive active && active.activity() instanceof GameActivity.Starting;
  }

  default boolean playing() {
    return this instanceof BatchActive active && active.activity() instanceof GameActivity.Playing;
  }

  default boolean betweenGames() {
    return this instanceof BatchActive active
        && active.activity() instanceof GameActivity.BetweenGames;
  }

  default boolean startingOrPlaying() {
    return starting() || playing();
  }

  default boolean playingGenmove() {
    return this instanceof BatchActive active
        && active.activity() instanceof GameActivity.Playing playing
        && playing.view().playMode() == EngineGamePlayMode.GENMOVE;
  }
}
