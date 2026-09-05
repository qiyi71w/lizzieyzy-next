package featurecat.lizzie.enginegame;

/** Prepare or restore rejected the current game without starting first search. */
public final class MatchRulesPrepareException extends IllegalStateException {
  public MatchRulesPrepareException(String message) {
    super(message == null ? "Match rules prepare failed" : message);
  }
}
