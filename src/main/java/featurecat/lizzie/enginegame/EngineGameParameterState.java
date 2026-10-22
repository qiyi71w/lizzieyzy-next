package featurecat.lizzie.enginegame;

/**
 * Current game's analysis parameters per participant position, separate from the batch's frozen
 * opening settings. A confirmed value is reported only after the participant's readback matched.
 */
public record EngineGameParameterState(
    Position first, Position second, boolean pending, String failure) {

  /**
   * @param supported whether the participant accepts runtime breadth changes
   * @param wrn confirmed breadth, or null while unknown
   * @param targetWrn requested breadth while it differs from the confirmed value, otherwise null
   */
  public record Position(boolean supported, Double wrn, Double targetWrn) {}

  public Position position(boolean firstPosition) {
    return firstPosition ? first : second;
  }

  /** A failed update leaves a target unmet; the game stays paused until an apply succeeds. */
  public boolean unresolved() {
    return failure != null;
  }
}
