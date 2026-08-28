package featurecat.lizzie.enginegame;

public record EngineGameResignPolicy(int minMove, int consecutiveMoves, double winrate) {
  public static EngineGameResignPolicy defaults() {
    return new EngineGameResignPolicy(0, 2, 10.0);
  }
}
