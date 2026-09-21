package featurecat.lizzie.enginegame;

/** Command side of the engine-game module. */
public interface EngineGameControl {
  Acceptance accept(EngineGameBatchSpec spec, StartObserver observer);

  void stop();

  void pause();

  void resume();

  boolean reviseKomi(double komi);

  void reviseBatchLimit(int gameCount);
}
