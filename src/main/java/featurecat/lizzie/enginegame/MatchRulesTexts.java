package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.EngineRulesResult;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

/** Localized match-rules copy. Protocol and admission codes stay internal. */
public final class MatchRulesTexts {
  private MatchRulesTexts() {}

  public static String failureMessage(String detail, ResourceBundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    String code = detail == null ? "" : detail.trim();
    String key =
        switch (code) {
          case "mismatch" -> "MatchRules.failed.mismatch";
          case "unverified-consent-refused" -> "MatchRules.failed.consentRefused";
          case "QUERY_FAILED" -> "MatchRules.failed.queryFailed";
          case "SET_FAILED" -> "MatchRules.failed.setFailed";
          case "CAPABILITY_FAILED" -> "MatchRules.failed.capabilityFailed";
          case "query-unconfirmed" -> "MatchRules.failed.queryUnconfirmed";
          case "incomplete" -> "MatchRules.failed.incomplete";
          case "Match-rules command timed out" -> "MatchRules.failed.timeout";
          default -> "MatchRules.failed";
        };
    return bundle.getString(key);
  }

  public static String consentMessage(
      MatchRulesAdmission.Decision decision, ResourceBundle bundle) {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(bundle, "bundle");
    List<String> engines = new ArrayList<>();
    for (MatchRulesAdmission.UnverifiedCause cause : decision.unverified()) {
      engines.add(
          MessageFormat.format(
              bundle.getString("MatchRules.consentEngine"),
              identityLabel(cause.identity()),
              colorLabel(cause.identity(), decision, bundle),
              statusLabel(null, cause.reason(), bundle)));
    }
    String target =
        decision.consentKey() == null
            ? ""
            : MatchRulesSnapshot.ruleName(decision.consentKey().target(), bundle);
    return MessageFormat.format(
        bundle.getString("MatchRules.consentMessage"), String.join("\n", engines), target);
  }

  public static String statusLabel(
      EngineRulesResult.Status status, EngineRulesResult.Reason reason, ResourceBundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    if (status == EngineRulesResult.Status.UNCONFIRMED
        || reason == EngineRulesResult.Reason.QUERY_UNSUPPORTED
        || reason == EngineRulesResult.Reason.SET_UNSUPPORTED) {
      return bundle.getString("MatchRules.unconfirmed");
    }
    if (reason != null && reason != EngineRulesResult.Reason.NONE) {
      return bundle.getString("MatchRules.reason." + reason.name());
    }
    if (status != null
        && status != EngineRulesResult.Status.CONFIRMED
        && status != EngineRulesResult.Status.UNCONFIRMED) {
      return bundle.getString("MatchRules.status." + status.name());
    }
    return bundle.getString("MatchRules.unconfirmed");
  }

  private static String identityLabel(EngineParticipantIdentity identity) {
    if (identity == null) {
      return "";
    }
    String name = identity.name();
    String commands = identity.commands();
    if (!name.isEmpty() && !commands.isEmpty()) {
      return name + " (" + commands + ")";
    }
    return !name.isEmpty() ? name : commands;
  }

  private static String colorLabel(
      EngineParticipantIdentity identity,
      MatchRulesAdmission.Decision decision,
      ResourceBundle bundle) {
    if (identity != null && identity.equals(decision.black())) {
      return bundle.getString("MatchRules.black");
    }
    if (identity != null && identity.equals(decision.white())) {
      return bundle.getString("MatchRules.white");
    }
    return bundle.getString("MatchRules.unconfirmed");
  }
}
