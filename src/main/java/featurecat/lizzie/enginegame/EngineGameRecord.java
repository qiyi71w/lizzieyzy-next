package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Immutable facts for one completed engine-game history. */
public record EngineGameRecord(
    EngineGameRecordContext context,
    EngineGameCompletionFacts facts,
    String blackDisplayName,
    String whiteDisplayName,
    MatchRulesSnapshot matchRules) {
  public EngineGameRecord {
    context = Objects.requireNonNull(context, "context");
    facts = Objects.requireNonNull(facts, "facts");
    blackDisplayName = blackDisplayName == null ? "" : blackDisplayName;
    whiteDisplayName = whiteDisplayName == null ? "" : whiteDisplayName;
  }

  public EngineGameRecord(
      EngineGameRecordContext context,
      EngineGameCompletionFacts facts,
      String blackDisplayName,
      String whiteDisplayName) {
    this(
        context,
        facts,
        blackDisplayName,
        whiteDisplayName,
        context == null ? null : context.matchRules());
  }

  public int openingIndex() {
    return facts.openingIndex();
  }

  public GameOutcome outcome() {
    return facts.outcome();
  }

  public long blackTimeMs() {
    return facts.firstPlayedBlack() ? facts.firstTimeMs() : facts.secondTimeMs();
  }

  public long whiteTimeMs() {
    return facts.firstPlayedBlack() ? facts.secondTimeMs() : facts.firstTimeMs();
  }

  public long blackVisits() {
    return facts.firstPlayedBlack() ? facts.firstVisits() : facts.secondVisits();
  }

  public long whiteVisits() {
    return facts.firstPlayedBlack() ? facts.secondVisits() : facts.firstVisits();
  }
}
