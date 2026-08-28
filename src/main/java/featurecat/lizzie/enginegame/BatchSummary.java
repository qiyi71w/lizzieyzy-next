package featurecat.lizzie.enginegame;

import java.util.List;
import java.util.Objects;

public record BatchSummary(
    EngineParticipantIdentity first,
    EngineParticipantIdentity second,
    int gameOrdinal,
    int batchLimit,
    boolean exchangeColors,
    int firstWins,
    int secondWins,
    int firstWinAsBlack,
    int firstWinAsWhite,
    int secondWinAsBlack,
    int secondWinAsWhite,
    int doublePassGames,
    int maxMoveGames,
    long firstTotalTimeMs,
    long secondTotalTimeMs,
    long firstTotalVisits,
    long secondTotalVisits,
    List<OpeningStanding> openingStandings) {
  public BatchSummary {
    first = Objects.requireNonNull(first, "first");
    second = Objects.requireNonNull(second, "second");
    openingStandings = openingStandings == null ? List.of() : List.copyOf(openingStandings);
  }
}
