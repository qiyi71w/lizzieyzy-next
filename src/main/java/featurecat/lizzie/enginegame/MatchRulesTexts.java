package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
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

  public static String startFailureMessage(StartFailure failure, ResourceBundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    if (failure instanceof StartFailure.MatchRulesFailed matchRulesFailed) {
      return failureMessage(matchRulesFailed.detail(), bundle);
    }
    if (failure == null || failure instanceof StartFailure.CancelledByUser) {
      return null;
    }
    return bundle.getString("EngineManager.engineGameStartFailed");
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

  public static String details(MatchRulesSnapshot snapshot, ResourceBundle bundle) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(bundle, "bundle");
    StringBuilder text = new StringBuilder();
    text.append(
        MessageFormat.format(
            bundle.getString("MatchRules.details.target"),
            MatchRulesSnapshot.ruleName(snapshot.target(), bundle)));
    text.append('\n').append(outcomeLabel(snapshot, bundle));
    appendSide(text, snapshot.black(), true, bundle);
    appendSide(text, snapshot.white(), false, bundle);
    return text.toString();
  }

  public static String sgfComment(MatchRulesSnapshot snapshot, ResourceBundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    if (snapshot == null) {
      return "";
    }
    return bundle.getString("MatchRules.sgf.begin")
        + "\n"
        + details(snapshot, bundle)
        + "\n"
        + bundle.getString("MatchRules.sgf.end");
  }

  public static String replaceSgfComment(String comment, MatchRulesSnapshot snapshot, ResourceBundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    String section = sgfComment(snapshot, bundle);
    if (section.isEmpty()) {
      return comment == null ? "" : comment;
    }
    String current = comment == null ? "" : comment;
    String begin = bundle.getString("MatchRules.sgf.begin");
    String end = bundle.getString("MatchRules.sgf.end");
    int start = current.indexOf(begin);
    int stop = current.indexOf(end);
    if (start >= 0 && stop >= start) {
      int after = stop + end.length();
      String before = current.substring(0, start).stripTrailing();
      String tail = current.substring(after).stripLeading();
      StringBuilder replaced = new StringBuilder();
      if (!before.isEmpty()) {
        replaced.append(before).append('\n');
      }
      replaced.append(section);
      if (!tail.isEmpty()) {
        replaced.append('\n').append(tail);
      }
      return replaced.toString();
    }
    if (current.isEmpty()) {
      return section;
    }
    return current.stripTrailing() + "\n" + section;
  }

  public static String statusLabel(
      EngineRulesResult.Status status, EngineRulesResult.Reason reason, ResourceBundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    if (status == EngineRulesResult.Status.CONFIRMED
        && (reason == null || reason == EngineRulesResult.Reason.NONE)) {
      return bundle.getString("MatchRules.status.CONFIRMED");
    }
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

  private static void appendSide(
      StringBuilder text, MatchRulesSnapshot.Side side, boolean black, ResourceBundle bundle) {
    text.append('\n')
        .append(
            MessageFormat.format(
                bundle.getString("MatchRules.details.side"),
                black ? bundle.getString("MatchRules.black") : bundle.getString("MatchRules.white"),
                identityLabel(side.identity())));
    text.append('\n')
        .append(
            MessageFormat.format(
                bundle.getString("MatchRules.details.status"),
                statusLabel(side.status(), side.reason(), bundle)));
    if (side.status() == EngineRulesResult.Status.CONFIRMED && side.observed() != null) {
      text.append('\n')
          .append(
              MessageFormat.format(
                  bundle.getString("MatchRules.details.actual"), namedFields(side.observed(), bundle)));
      return;
    }
    text.append('\n')
        .append(
            MessageFormat.format(
                bundle.getString("MatchRules.details.unavailable"),
                reasonOrStatus(side, bundle)));
    if (failedOperation(side) && side.observed() != null) {
      text.append('\n')
          .append(
              MessageFormat.format(
                  bundle.getString("MatchRules.details.lastObserved"),
                  namedFields(side.observed(), bundle)));
    }
  }

  private static String outcomeLabel(MatchRulesSnapshot snapshot, ResourceBundle bundle) {
    if (snapshot.phase() == MatchRulesSnapshot.Phase.PREPARING) {
      return bundle.getString("MatchRules.checking");
    }
    if (snapshot.phase() == MatchRulesSnapshot.Phase.FAILED) {
      return bundle.getString("MatchRules.failed");
    }
    if (snapshot.confirmed()) {
      return bundle.getString("MatchRules.status.CONFIRMED");
    }
    return bundle.getString("MatchRules.unconfirmed");
  }

  private static boolean failedOperation(MatchRulesSnapshot.Side side) {
    EngineRulesResult.Status status = side.status();
    return status == EngineRulesResult.Status.QUERY_FAILED
        || status == EngineRulesResult.Status.SET_FAILED
        || status == EngineRulesResult.Status.CAPABILITY_FAILED;
  }

  private static String reasonOrStatus(MatchRulesSnapshot.Side side, ResourceBundle bundle) {
    if (side.reason() != null && side.reason() != EngineRulesResult.Reason.NONE) {
      return bundle.getString("MatchRules.reason." + side.reason().name());
    }
    return statusLabel(side.status(), side.reason(), bundle);
  }

  private static String namedFields(KataGoRules rules, ResourceBundle bundle) {
    String name = MatchRulesSnapshot.ruleName(rules, bundle);
    String fields = rules.fieldSummary();
    if (fields.isEmpty()) {
      return name;
    }
    return name + " (" + fields + ")";
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
