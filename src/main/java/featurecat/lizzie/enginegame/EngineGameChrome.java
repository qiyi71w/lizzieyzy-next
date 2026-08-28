package featurecat.lizzie.enginegame;

/**
 * Internal presentation adapter for engine-game PK chrome. Owns no product or lifecycle state.
 */
public interface EngineGameChrome {
  void publish(EngineGameChromeTransition transition);
}
