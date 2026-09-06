package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure admission decision for one game after both participants have produced rule results. Does not
 * send GTP or own engine identity.
 */
public final class MatchRulesAdmission {
  public enum Outcome {
    ADMIT_CONFIRMED,
    ADMIT_UNVERIFIED,
    REJECT
  }

  public record SideResult(
      EngineParticipantIdentity identity,
      boolean canSet,
      boolean canQuery,
      KataGoRules original,
      KataGoRules observed,
      EngineRulesResult.Status status,
      EngineRulesResult.Reason reason,
      boolean modifiedOrUncertain) {
    public SideResult {
      identity = Objects.requireNonNull(identity, "identity");
      status = Objects.requireNonNull(status, "status");
      reason = Objects.requireNonNull(reason, "reason");
    }

    public boolean unsupportedConfirmation() {
      return !canQuery
          && (status == EngineRulesResult.Status.UNCONFIRMED
              || reason == EngineRulesResult.Reason.QUERY_UNSUPPORTED
              || reason == EngineRulesResult.Reason.SET_UNSUPPORTED);
    }
  }

  public record UnverifiedCause(
      EngineParticipantIdentity identity,
      boolean canSet,
      boolean canQuery,
      EngineRulesResult.Reason reason) {
    public UnverifiedCause {
      identity = Objects.requireNonNull(identity, "identity");
      reason = Objects.requireNonNull(reason, "reason");
    }
  }

  public record ConsentKey(
      EngineParticipantIdentity first,
      EngineParticipantIdentity second,
      KataGoRules target,
      List<UnverifiedCause> causes) {
    public ConsentKey {
      first = Objects.requireNonNull(first, "first");
      second = Objects.requireNonNull(second, "second");
      target = Objects.requireNonNull(target, "target");
      causes = List.copyOf(Objects.requireNonNull(causes, "causes"));
    }

    public boolean sameConsent(ConsentKey other) {
      return other != null
          && first.equals(other.first)
          && second.equals(other.second)
          && target.semanticallyEquals(other.target)
          && causes.equals(other.causes);
    }
  }

  public record Decision(
      Outcome outcome,
      ConsentKey consentKey,
      String rejectReason,
      List<UnverifiedCause> unverified,
      EngineParticipantIdentity black,
      EngineParticipantIdentity white) {
    public Decision {
      outcome = Objects.requireNonNull(outcome, "outcome");
      unverified = List.copyOf(Objects.requireNonNull(unverified, "unverified"));
      black = Objects.requireNonNull(black, "black");
      white = Objects.requireNonNull(white, "white");
    }

    public boolean admitted() {
      return outcome == Outcome.ADMIT_CONFIRMED || outcome == Outcome.ADMIT_UNVERIFIED;
    }
  }

  private MatchRulesAdmission() {}

  public static Decision decide(
      KataGoRules target, SideResult black, SideResult white, ConsentKey existingConsent) {
    return decide(target, black.identity, white.identity, black, white, existingConsent);
  }

  public static Decision decide(
      KataGoRules target,
      EngineParticipantIdentity batchFirst,
      EngineParticipantIdentity batchSecond,
      SideResult black,
      SideResult white,
      ConsentKey existingConsent) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(batchFirst, "batchFirst");
    Objects.requireNonNull(batchSecond, "batchSecond");
    Objects.requireNonNull(black, "black");
    Objects.requireNonNull(white, "white");

    Optional<Decision> blackFailure = hardFailure(black, black, white);
    if (blackFailure.isPresent()) {
      return blackFailure.get();
    }
    Optional<Decision> whiteFailure = hardFailure(white, black, white);
    if (whiteFailure.isPresent()) {
      return whiteFailure.get();
    }

    List<UnverifiedCause> unverified = new ArrayList<>();
    addUnverified(unverified, sideFor(batchFirst, black, white));
    addUnverified(unverified, sideFor(batchSecond, black, white));

    if (unverified.isEmpty()) {
      if (!matchesTarget(black, target) || !matchesTarget(white, target)) {
        return reject("mismatch", black, white);
      }
      if (!black.observed.semanticallyEquals(white.observed)) {
        return reject("mismatch", black, white);
      }
      return admit(Outcome.ADMIT_CONFIRMED, null, List.of(), black, white);
    }

    if (!queryableMatchesTarget(black, target) || !queryableMatchesTarget(white, target)) {
      return reject("mismatch", black, white);
    }

    ConsentKey key = consentKey(batchFirst, batchSecond, target, unverified);
    return admit(Outcome.ADMIT_UNVERIFIED, key, unverified, black, white);
  }

  public static boolean isHardFailure(SideResult side) {
    return hardFailureReason(side) != null;
  }

  public static ConsentKey consentKey(
      EngineParticipantIdentity first,
      EngineParticipantIdentity second,
      KataGoRules target,
      List<UnverifiedCause> causes) {
    return new ConsentKey(first, second, target, causes);
  }

  private static Optional<Decision> hardFailure(
      SideResult failed, SideResult black, SideResult white) {
    String reason = hardFailureReason(failed);
    if (reason == null) {
      return Optional.empty();
    }
    return Optional.of(reject(reason, black, white));
  }

  private static String hardFailureReason(SideResult side) {
    if (side.status == EngineRulesResult.Status.SET_FAILED
        || side.status == EngineRulesResult.Status.QUERY_FAILED
        || side.status == EngineRulesResult.Status.CAPABILITY_FAILED) {
      return side.status.name();
    }
    if (side.canQuery && side.status == EngineRulesResult.Status.UNCONFIRMED) {
      return "query-unconfirmed";
    }
    if (side.canQuery
        && side.observed != null
        && side.status == EngineRulesResult.Status.CONFIRMED
        && !side.observed.hasRequiredFields()) {
      return "incomplete";
    }
    return null;
  }

  private static SideResult sideFor(
      EngineParticipantIdentity identity, SideResult black, SideResult white) {
    if (identity.equals(black.identity)) {
      return black;
    }
    if (identity.equals(white.identity)) {
      return white;
    }
    throw new IllegalArgumentException("batch participant is not a current side");
  }

  private static void addUnverified(List<UnverifiedCause> unverified, SideResult side) {
    if (side.unsupportedConfirmation()) {
      unverified.add(new UnverifiedCause(side.identity, side.canSet, side.canQuery, side.reason));
    }
  }

  private static boolean matchesTarget(SideResult side, KataGoRules target) {
    return side.observed != null && side.observed.semanticallyEquals(target);
  }

  private static boolean queryableMatchesTarget(SideResult side, KataGoRules target) {
    if (!side.canQuery) {
      return true;
    }
    return matchesTarget(side, target);
  }

  private static Decision reject(String reason, SideResult black, SideResult white) {
    return new Decision(Outcome.REJECT, null, reason, List.of(), black.identity, white.identity);
  }

  private static Decision admit(
      Outcome outcome,
      ConsentKey key,
      List<UnverifiedCause> unverified,
      SideResult black,
      SideResult white) {
    return new Decision(outcome, key, "", unverified, black.identity, white.identity);
  }
}
