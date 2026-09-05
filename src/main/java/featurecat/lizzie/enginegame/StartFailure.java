package featurecat.lizzie.enginegame;

public sealed interface StartFailure {
  record CancelledByUser() implements StartFailure {}

  record ParticipantStartupFailed() implements StartFailure {}

  record Timeout() implements StartFailure {}

  record MatchRulesFailed(String detail) implements StartFailure {
    public MatchRulesFailed {
      detail = detail == null ? "" : detail;
    }
  }
}
