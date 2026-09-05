package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
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
