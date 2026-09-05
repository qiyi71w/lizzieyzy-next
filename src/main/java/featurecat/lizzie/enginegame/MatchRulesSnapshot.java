package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Immutable match-rules view for preparing, failed, playing, and completed games. Display names
 * are summaries; admission uses semantic equality.
 */
public final class MatchRulesSnapshot {
  public enum Phase {
    PREPARING,
    FAILED,
    PLAYING,
    COMPLETED
  }

  public record Side(
      EngineParticipantIdentity identity,
      KataGoRules original,
      KataGoRules observed,
      EngineRulesResult.Status status,
      EngineRulesResult.Reason reason,
      boolean canSet,
      boolean canQuery) {
    public Side {
      identity = Objects.requireNonNull(identity, "identity");
      status = status == null ? EngineRulesResult.Status.IDLE : status;
      reason = reason == null ? EngineRulesResult.Reason.NONE : reason;
    }

    public static Side idle(EngineParticipantIdentity identity) {
      return new Side(
          identity,
          null,
          null,
          EngineRulesResult.Status.PENDING,
          EngineRulesResult.Reason.NONE,
          false,
          false);
    }

    public static Side from(MatchRulesAdmission.SideResult result) {
      Objects.requireNonNull(result, "result");
      return new Side(
          result.identity(),
          result.original(),
          result.observed(),
          result.status(),
          result.reason(),
          result.canSet(),
          result.canQuery());
    }
  }

  private final Phase phase;
  private final KataGoRules target;
  private final Side black;
  private final Side white;
  private final MatchRulesAdmission.Outcome outcome;
  private final boolean unverified;

  private MatchRulesSnapshot(
      Phase phase,
      KataGoRules target,
      Side black,
      Side white,
      MatchRulesAdmission.Outcome outcome,
      boolean unverified) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.target = Objects.requireNonNull(target, "target");
    this.black = Objects.requireNonNull(black, "black");
    this.white = Objects.requireNonNull(white, "white");
    this.outcome = outcome;
    this.unverified = unverified;
  }

  public static MatchRulesSnapshot preparing(
      KataGoRules target, EngineParticipantIdentity black, EngineParticipantIdentity white) {
    return new MatchRulesSnapshot(
        Phase.PREPARING, target, Side.idle(black), Side.idle(white), null, false);
  }

  public static MatchRulesSnapshot of(
      Phase phase,
      KataGoRules target,
      MatchRulesAdmission.SideResult black,
      MatchRulesAdmission.SideResult white,
      MatchRulesAdmission.Outcome outcome) {
    return new MatchRulesSnapshot(
        phase,
        target,
        Side.from(black),
        Side.from(white),
        outcome,
        outcome == MatchRulesAdmission.Outcome.ADMIT_UNVERIFIED);
  }

  public MatchRulesSnapshot completed() {
    return new MatchRulesSnapshot(Phase.COMPLETED, target, black, white, outcome, unverified);
  }

  public Phase phase() {
    return phase;
  }

  public KataGoRules target() {
    return target;
  }

  public Side black() {
    return black;
  }

  public Side white() {
    return white;
  }

  public MatchRulesAdmission.Outcome outcome() {
    return outcome;
  }

  public boolean unverified() {
    return unverified;
  }

  public boolean confirmed() {
    return outcome == MatchRulesAdmission.Outcome.ADMIT_CONFIRMED;
  }

  public String mainSummary(ResourceBundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    if (phase == Phase.PREPARING) {
      return bundle.getString("MatchRules.checking");
    }
    if (confirmed()) {
      return ruleName(target, bundle);
    }
    return bundle.getString("MatchRules.black")
        + ": "
        + sideSummary(black, bundle)
        + " / "
        + bundle.getString("MatchRules.white")
        + ": "
        + sideSummary(white, bundle);
  }

  public static String ruleName(KataGoRules rules, ResourceBundle bundle) {
    if (rules == null) {
      return bundle.getString("MatchRules.unconfirmed");
    }
    return switch (rules.summary()) {
      case CHINESE -> bundle.getString("LizzieFrame.currentRules.chinese");
      case CHINESE_ANCIENT -> bundle.getString("LizzieFrame.currentRules.chn-ancient");
      case JAPANESE -> bundle.getString("LizzieFrame.currentRules.japanese");
      case TROMP_TAYLOR -> bundle.getString("LizzieFrame.currentRules.tromp-taylor");
      case OTHER -> bundle.getString("LizzieFrame.currentRules.others");
    };
  }

  private static String sideSummary(Side side, ResourceBundle bundle) {
    if (side.observed() != null) {
      String name = ruleName(side.observed(), bundle);
      if (side.status() == EngineRulesResult.Status.UNCONFIRMED
          || side.reason() == EngineRulesResult.Reason.QUERY_UNSUPPORTED) {
        return name + " (" + bundle.getString("MatchRules.unconfirmed") + ")";
      }
      return name;
    }
    return MatchRulesTexts.statusLabel(side.status(), side.reason(), bundle);
  }
}
