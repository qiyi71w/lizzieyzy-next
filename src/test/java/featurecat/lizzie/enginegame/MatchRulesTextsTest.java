package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class MatchRulesTextsTest {
  private static final EngineParticipantIdentity BLACK =
      new EngineParticipantIdentity("black-cmd", "BlackEngine");
  private static final EngineParticipantIdentity WHITE =
      new EngineParticipantIdentity("white-cmd", "WhiteEngine");
  private static final KataGoRules CHINESE = KataGoRules.parse("chinese").orElseThrow();

  @Test
  void failureMessageDoesNotExposeInternalCodes() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    assertEquals(
        bundle.getString("MatchRules.failed.mismatch"),
        MatchRulesTexts.failureMessage("mismatch", bundle));
    assertEquals(
        bundle.getString("MatchRules.failed.queryFailed"),
        MatchRulesTexts.failureMessage("QUERY_FAILED", bundle));
    assertEquals(
        bundle.getString("MatchRules.failed.consentRefused"),
        MatchRulesTexts.failureMessage("unverified-consent-refused", bundle));
    assertFalse(MatchRulesTexts.failureMessage("QUERY_FAILED", bundle).contains("QUERY_FAILED"));
    assertFalse(MatchRulesTexts.failureMessage("mismatch", bundle).equals("mismatch"));
  }

  @Test
  void detailsShowsFieldDifferencesWhenChineseNamesMatch() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    KataGoRules simpleChinese = CHINESE;
    KataGoRules positionalChinese = KataGoRules.parse("chinese-ogs").orElseThrow();
    assertEquals(simpleChinese.summary(), positionalChinese.summary());
    MatchRulesSnapshot snapshot =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.FAILED,
            simpleChinese,
            confirmed(BLACK, simpleChinese),
            confirmed(WHITE, positionalChinese),
            MatchRulesAdmission.Outcome.REJECT);
    String details = MatchRulesTexts.details(snapshot, bundle);
    assertTrue(details.contains(bundle.getString("LizzieFrame.currentRules.chinese")), details);
    assertTrue(details.contains("ko=SIMPLE"), details);
    assertTrue(details.contains("ko=POSITIONAL"), details);
    assertTrue(details.contains("BlackEngine"), details);
    assertTrue(details.contains("WhiteEngine"), details);
  }

  @Test
  void detailsOmitsGuessedActualRulesForUnconfirmedWriteOnlySide() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesAdmission.SideResult black =
        new MatchRulesAdmission.SideResult(
            BLACK,
            true,
            false,
            KataGoRules.parse("japanese").orElseThrow(),
            KataGoRules.parse("japanese").orElseThrow(),
            EngineRulesResult.Status.UNCONFIRMED,
            EngineRulesResult.Reason.QUERY_UNSUPPORTED,
            false);
    MatchRulesSnapshot snapshot =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.PLAYING,
            CHINESE,
            black,
            confirmed(WHITE, CHINESE),
            MatchRulesAdmission.Outcome.ADMIT_UNVERIFIED);
    String details = MatchRulesTexts.details(snapshot, bundle);
    assertTrue(details.contains(bundle.getString("MatchRules.unconfirmed")), details);
    assertTrue(details.contains(bundle.getString("MatchRules.reason.QUERY_UNSUPPORTED")), details);
    assertTrue(details.contains("BlackEngine"), details);
    assertTrue(details.contains("WhiteEngine"), details);
    assertFalse(details.contains("TERRITORY"), details);
    assertFalse(details.contains("tax=SEKI"), details);
    assertTrue(details.contains("scoring=AREA"), details);
  }

  @Test
  void detailsDoesNotPresentStaleObservationAsCurrentSuccessOnQueryFailure() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesAdmission.SideResult black =
        new MatchRulesAdmission.SideResult(
            BLACK,
            true,
            true,
            CHINESE,
            CHINESE,
            EngineRulesResult.Status.QUERY_FAILED,
            EngineRulesResult.Reason.QUERY_TIMEOUT,
            false);
    MatchRulesSnapshot snapshot =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.FAILED,
            CHINESE,
            black,
            confirmed(WHITE, KataGoRules.parse("japanese").orElseThrow()),
            MatchRulesAdmission.Outcome.REJECT);
    String details = MatchRulesTexts.details(snapshot, bundle);
    String lastObservedPrefix =
        bundle.getString("MatchRules.details.lastObserved").replace("{0}", "").trim();
    assertTrue(details.contains(bundle.getString("MatchRules.reason.QUERY_TIMEOUT")), details);
    assertTrue(details.contains(lastObservedPrefix), details);
    assertTrue(details.contains("scoring=AREA"), details);
    assertTrue(details.contains("scoring=TERRITORY"), details);
    String chineseActual =
        MessageFormat.format(
            bundle.getString("MatchRules.details.actual"),
            MatchRulesSnapshot.ruleName(CHINESE, bundle));
    assertFalse(details.contains(chineseActual), details);
  }

  @Test
  void consentMessageListsIdentityColorTargetAndReason() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            unsupported(BLACK, true, false),
            unsupported(WHITE, true, false),
            null);
    String message = MatchRulesTexts.consentMessage(decision, bundle);
    assertTrue(message.contains("BlackEngine"));
    assertTrue(message.contains("WhiteEngine"));
    assertTrue(message.contains(bundle.getString("MatchRules.black")));
    assertTrue(message.contains(bundle.getString("MatchRules.white")));
    assertTrue(message.contains(bundle.getString("LizzieFrame.currentRules.chinese")));
    assertTrue(message.contains(bundle.getString("MatchRules.unconfirmed")));
    assertFalse(message.contains("QUERY_UNSUPPORTED"));
  }

  private static MatchRulesAdmission.SideResult confirmed(
      EngineParticipantIdentity identity, KataGoRules rules) {
    return new MatchRulesAdmission.SideResult(
        identity,
        true,
        true,
        rules,
        rules,
        EngineRulesResult.Status.CONFIRMED,
        EngineRulesResult.Reason.NONE,
        false);
  }

  private static MatchRulesAdmission.SideResult unsupported(
      EngineParticipantIdentity identity, boolean canSet, boolean canQuery) {
    return new MatchRulesAdmission.SideResult(
        identity,
        canSet,
        canQuery,
        null,
        null,
        EngineRulesResult.Status.UNCONFIRMED,
        EngineRulesResult.Reason.QUERY_UNSUPPORTED,
        false);
  }
}
