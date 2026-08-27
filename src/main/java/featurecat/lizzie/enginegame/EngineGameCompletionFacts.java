package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Exact normal-completion facts for one game. Ticket 06 freezes a record from these. */
public record EngineGameCompletionFacts(
    GameOutcome outcome,
    int gameOrdinal,
    int openingIndex,
    boolean firstPlayedBlack,
    long firstTimeMs,
    long secondTimeMs,
    long firstVisits,
    long secondVisits) {
  public EngineGameCompletionFacts {
    outcome = Objects.requireNonNull(outcome, "outcome");
  }
}
