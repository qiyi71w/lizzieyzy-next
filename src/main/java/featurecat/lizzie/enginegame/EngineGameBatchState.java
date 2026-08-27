package featurecat.lizzie.enginegame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal mutable batch shell owned only by {@link EngineGameModule}. */
final class EngineGameBatchState {
  private final EngineGameBatchSpec spec;
  private final int firstIndex;
  private final int secondIndex;
  private int batchLimit;
  private int gameOrdinal;
  private int sequentialOpeningCursor;
  private boolean firstIsBlack = true;
  private int firstWinAsBlack;
  private int firstWinAsWhite;
  private int secondWinAsBlack;
  private int secondWinAsWhite;
  private int doublePassGames;
  private int maxMoveGames;
  private int completedGames;
  private long firstTotalTimeMs;
  private long secondTotalTimeMs;
  private long firstTotalVisits;
  private long secondTotalVisits;
  private final Map<Integer, OpeningAccumulator> openings = new LinkedHashMap<>();
  private String batchGameName = "";
  private String timestamp = "";

  EngineGameBatchState(EngineGameBatchSpec spec, int firstIndex, int secondIndex) {
    this.spec = Objects.requireNonNull(spec, "spec");
    this.firstIndex = firstIndex;
    this.secondIndex = secondIndex;
    this.batchLimit = Math.max(1, spec.initialBatchLimit());
    this.gameOrdinal = 1;
    this.sequentialOpeningCursor = 0;
  }

  EngineGameBatchSpec spec() {
    return spec;
  }

  int firstIndex() {
    return firstIndex;
  }

  int secondIndex() {
    return secondIndex;
  }

  boolean firstIsBlack() {
    return firstIsBlack;
  }

  int batchLimit() {
    return batchLimit;
  }

  void setBatchLimit(int batchLimit) {
    this.batchLimit = batchLimit;
  }

  int gameOrdinal() {
    return gameOrdinal;
  }

  int sequentialOpeningCursor() {
    return sequentialOpeningCursor;
  }

  void setSequentialOpeningCursor(int sequentialOpeningCursor) {
    this.sequentialOpeningCursor = sequentialOpeningCursor;
  }

  int completedGames() {
    return completedGames;
  }

  boolean shouldCreateSuccessor() {
    return gameOrdinal < batchLimit;
  }

  void beginSuccessor() {
    gameOrdinal++;
    if (spec.exchangeColors()) {
      firstIsBlack = !firstIsBlack;
    }
  }

  void rememberOutputIdentity(String batchGameName, String timestamp) {
    if (batchGameName != null && !batchGameName.isEmpty()) {
      this.batchGameName = batchGameName;
    }
    if (timestamp != null && !timestamp.isEmpty()) {
      this.timestamp = timestamp;
    }
  }

  String batchGameName() {
    return batchGameName;
  }

  String timestamp() {
    return timestamp;
  }

  void apply(EngineGameCompletionFacts facts) {
    Objects.requireNonNull(facts, "facts");
    completedGames++;
    firstTotalTimeMs += facts.firstTimeMs();
    secondTotalTimeMs += facts.secondTimeMs();
    firstTotalVisits += facts.firstVisits();
    secondTotalVisits += facts.secondVisits();
    if (facts.outcome() instanceof GameOutcome.DoublePass) {
      doublePassGames++;
    } else if (facts.outcome() instanceof GameOutcome.MaxMoves) {
      maxMoveGames++;
    } else if (facts.outcome() instanceof GameOutcome.Resign resign) {
      recordWin(resign.side(), facts.firstPlayedBlack(), facts.openingIndex());
    }
  }

  BatchSummary summary() {
    List<OpeningStanding> standings = new ArrayList<>(openings.size());
    for (OpeningAccumulator accumulator : openings.values()) {
      standings.add(accumulator.standing());
    }
    return new BatchSummary(
        spec.first(),
        spec.second(),
        gameOrdinal,
        batchLimit,
        spec.exchangeColors(),
        firstWinAsBlack + firstWinAsWhite,
        secondWinAsBlack + secondWinAsWhite,
        firstWinAsBlack,
        firstWinAsWhite,
        secondWinAsBlack,
        secondWinAsWhite,
        doublePassGames,
        maxMoveGames,
        firstTotalTimeMs,
        secondTotalTimeMs,
        firstTotalVisits,
        secondTotalVisits,
        standings);
  }

  private void recordWin(EngineGameSide resigned, boolean firstPlayedBlack, int openingIndex) {
    boolean blackWon = resigned == EngineGameSide.WHITE;
    boolean firstWon = blackWon == firstPlayedBlack;
    if (firstWon) {
      if (firstPlayedBlack) {
        firstWinAsBlack++;
      } else {
        firstWinAsWhite++;
      }
    } else if (firstPlayedBlack) {
      secondWinAsWhite++;
    } else {
      secondWinAsBlack++;
    }
    if (openingIndex >= 0) {
      openings
          .computeIfAbsent(openingIndex, OpeningAccumulator::new)
          .record(firstWon, firstPlayedBlack);
    }
  }

  private static final class OpeningAccumulator {
    private final int openingIndex;
    private int firstWinAsBlack;
    private int firstWinAsWhite;
    private int secondWinAsBlack;
    private int secondWinAsWhite;

    private OpeningAccumulator(int openingIndex) {
      this.openingIndex = openingIndex;
    }

    private void record(boolean firstWon, boolean firstPlayedBlack) {
      if (firstWon) {
        if (firstPlayedBlack) {
          firstWinAsBlack++;
        } else {
          firstWinAsWhite++;
        }
      } else if (firstPlayedBlack) {
        secondWinAsWhite++;
      } else {
        secondWinAsBlack++;
      }
    }

    private OpeningStanding standing() {
      return new OpeningStanding(
          openingIndex,
          firstWinAsBlack + firstWinAsWhite,
          firstWinAsBlack,
          firstWinAsWhite,
          secondWinAsBlack + secondWinAsWhite,
          secondWinAsBlack,
          secondWinAsWhite);
    }
  }
}
