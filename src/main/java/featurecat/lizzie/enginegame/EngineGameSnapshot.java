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

  default boolean paused() {
    return this instanceof BatchActive active
        && active.activity() instanceof GameActivity.Playing playing
        && playing.runState() == RunState.PAUSED;
  }

  default boolean playingGenmove() {
    EngineGameView view = view();
    return playing() && view != null && view.genmove();
  }

  default EngineGameView view() {
    if (this instanceof BatchActive active) {
      if (active.activity() instanceof GameActivity.Playing playing) {
        return playing.view();
      }
      if (active.activity() instanceof GameActivity.Starting starting) {
        return starting.view();
      }
    }
    return null;
  }
}
