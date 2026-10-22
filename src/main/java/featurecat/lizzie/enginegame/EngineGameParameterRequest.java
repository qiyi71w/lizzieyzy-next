package featurecat.lizzie.enginegame;

/** One "Apply" from the game-parameter panel. A null value leaves that position unchanged. */
public record EngineGameParameterRequest(
    Double firstWrn, Double secondWrn, Double firstPda, Double secondPda) {

  public static EngineGameParameterRequest wrn(Double firstWrn, Double secondWrn) {
    return new EngineGameParameterRequest(firstWrn, secondWrn, null, null);
  }

  public Double wrn(boolean firstPosition) {
    return firstPosition ? firstWrn : secondWrn;
  }

  public boolean changesPda() {
    return firstPda != null || secondPda != null;
  }
}
