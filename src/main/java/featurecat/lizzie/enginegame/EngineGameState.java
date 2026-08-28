package featurecat.lizzie.enginegame;

/** Query side of the engine-game module. Public snapshot only. */
public interface EngineGameState {
  EngineGameSnapshot current();
}
