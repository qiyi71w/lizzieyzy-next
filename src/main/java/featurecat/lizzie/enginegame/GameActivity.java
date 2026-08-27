package featurecat.lizzie.enginegame;

import java.util.Objects;

public sealed interface GameActivity {
  record Starting() implements GameActivity {}

  record Playing(EngineGameView view, RunState runState) implements GameActivity {
    public Playing {
      view = Objects.requireNonNull(view, "view");
      runState = Objects.requireNonNull(runState, "runState");
    }
  }

  record BetweenGames() implements GameActivity {}
}
