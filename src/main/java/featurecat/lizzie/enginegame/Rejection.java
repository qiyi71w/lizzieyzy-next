package featurecat.lizzie.enginegame;

public sealed interface Rejection {
  record InvalidParticipantCombination() implements Rejection {}

  record OccupiedLifecycle() implements Rejection {}

  record UnsupportedMode() implements Rejection {}

  record InvalidAnalysisLimits() implements Rejection {}
}
