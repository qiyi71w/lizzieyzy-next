package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import org.junit.jupiter.api.Test;

class MatchRulesAdmissionTest {
  private static final EngineParticipantIdentity BLACK =
      new EngineParticipantIdentity("black-cmd", "Black");
  private static final EngineParticipantIdentity WHITE =
      new EngineParticipantIdentity("white-cmd", "White");
  private static final KataGoRules CHINESE = KataGoRules.parse("chinese").orElseThrow();
  private static final KataGoRules JAPANESE = KataGoRules.parse("japanese").orElseThrow();
  private static final KataGoRules OGS = KataGoRules.parse("chinese-ogs").orElseThrow();

  @Test
  void bothReadWriteConfirmedToTargetAreAdmitted() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            confirmed(BLACK, true, true, JAPANESE, CHINESE, true),
            confirmed(WHITE, true, true, OGS, CHINESE, true),
            null);

    assertEquals(MatchRulesAdmission.Outcome.ADMIT_CONFIRMED, decision.outcome());
  }

  @Test
  void originalsAlreadyMatchingTargetStillRequireThisGamesConfirmation() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            confirmed(BLACK, true, true, CHINESE, CHINESE, false),
            confirmed(WHITE, true, true, CHINESE, CHINESE, false),
            null);

    assertEquals(MatchRulesAdmission.Outcome.ADMIT_CONFIRMED, decision.outcome());
  }

  @Test
  void sameChineseNameWithDifferentKoIsRejected() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            confirmed(BLACK, true, true, JAPANESE, CHINESE, true),
            confirmed(WHITE, true, true, JAPANESE, OGS, true),
            null);

    assertEquals(MatchRulesAdmission.Outcome.REJECT, decision.outcome());
  }

  @Test
  void bothConfirmedToTheSameNonTargetRulesAreRejected() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            confirmed(BLACK, true, true, CHINESE, JAPANESE, true),
            confirmed(WHITE, true, true, CHINESE, JAPANESE, true),
            null);

    assertEquals(MatchRulesAdmission.Outcome.REJECT, decision.outcome());
  }

  @Test
  void readOnlyMismatchIsRejectedWithoutTreatingItAsUnverified() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            confirmed(BLACK, false, true, JAPANESE, JAPANESE, false),
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            null);

    assertEquals(MatchRulesAdmission.Outcome.REJECT, decision.outcome());
    assertTrue(decision.unverified().isEmpty());
  }

  @Test
  void readOnlyMatchingTargetIsConfirmedWithoutOverride() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            confirmed(BLACK, false, true, CHINESE, CHINESE, false),
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            null);

    assertEquals(MatchRulesAdmission.Outcome.ADMIT_CONFIRMED, decision.outcome());
  }

  @Test
  void writeOnlyParticipantRequiresUnverifiedConsent() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            unsupported(BLACK, true, false),
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            null);

    assertEquals(MatchRulesAdmission.Outcome.ADMIT_UNVERIFIED, decision.outcome());
    assertEquals(1, decision.unverified().size());
    assertEquals(BLACK, decision.unverified().get(0).identity());
  }

  @Test
  void supportedQueryFailureIsRejectedNotUnverified() {
    MatchRulesAdmission.Decision decision =
        MatchRulesAdmission.decide(
            CHINESE,
            failed(
                BLACK,
                true,
                true,
                EngineRulesResult.Status.QUERY_FAILED,
                EngineRulesResult.Reason.QUERY_TIMEOUT),
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            null);

    assertEquals(MatchRulesAdmission.Outcome.REJECT, decision.outcome());
    assertTrue(decision.unverified().isEmpty());
  }

  @Test
  void matchingConsentIsReusedAndColorExchangeDoesNotChangeTheKeyIdentities() {
    MatchRulesAdmission.Decision first =
        MatchRulesAdmission.decide(
            CHINESE,
            unsupported(BLACK, true, false),
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            null);
    MatchRulesAdmission.ConsentKey granted =
        MatchRulesAdmission.consentKey(
            BLACK, WHITE, CHINESE, first.unverified());

    MatchRulesAdmission.Decision exchanged =
        MatchRulesAdmission.decide(
            CHINESE,
            BLACK,
            WHITE,
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            unsupported(BLACK, true, false),
            granted);

    assertEquals(MatchRulesAdmission.Outcome.ADMIT_UNVERIFIED, exchanged.outcome());
    assertTrue(granted.sameConsent(exchanged.consentKey()));
  }

  @Test
  void bothUnverifiedCausesStayConsentCompatibleAfterColorExchange() {
    MatchRulesAdmission.Decision first =
        MatchRulesAdmission.decide(
            CHINESE,
            BLACK,
            WHITE,
            unsupported(BLACK, true, false),
            unsupported(WHITE, true, false),
            null);
    MatchRulesAdmission.ConsentKey granted = first.consentKey();

    MatchRulesAdmission.Decision exchanged =
        MatchRulesAdmission.decide(
            CHINESE,
            BLACK,
            WHITE,
            unsupported(WHITE, true, false),
            unsupported(BLACK, true, false),
            granted);

    assertEquals(MatchRulesAdmission.Outcome.ADMIT_UNVERIFIED, exchanged.outcome());
    assertTrue(granted.sameConsent(exchanged.consentKey()));
  }

  @Test
  void changedUnverifiedReasonRequiresNewConsent() {
    MatchRulesAdmission.Decision first =
        MatchRulesAdmission.decide(
            CHINESE,
            unsupported(BLACK, true, false),
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            null);
    MatchRulesAdmission.ConsentKey granted =
        MatchRulesAdmission.consentKey(BLACK, WHITE, CHINESE, first.unverified());

    MatchRulesAdmission.SideResult neither =
        new MatchRulesAdmission.SideResult(
            BLACK,
            false,
            false,
            null,
            null,
            EngineRulesResult.Status.UNCONFIRMED,
            EngineRulesResult.Reason.QUERY_UNSUPPORTED,
            false);
    MatchRulesAdmission.Decision changed =
        MatchRulesAdmission.decide(
            CHINESE,
            neither,
            confirmed(WHITE, true, true, JAPANESE, CHINESE, true),
            granted);

    assertEquals(MatchRulesAdmission.Outcome.ADMIT_UNVERIFIED, changed.outcome());
    assertFalse(granted.sameConsent(changed.consentKey()));
  }

  private static MatchRulesAdmission.SideResult confirmed(
      EngineParticipantIdentity identity,
      boolean canSet,
      boolean canQuery,
      KataGoRules original,
      KataGoRules observed,
      boolean modified) {
    return new MatchRulesAdmission.SideResult(
        identity,
        canSet,
        canQuery,
        original,
        observed,
        EngineRulesResult.Status.CONFIRMED,
        EngineRulesResult.Reason.NONE,
        modified);
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

  private static MatchRulesAdmission.SideResult failed(
      EngineParticipantIdentity identity,
      boolean canSet,
      boolean canQuery,
      EngineRulesResult.Status status,
      EngineRulesResult.Reason reason) {
    return new MatchRulesAdmission.SideResult(
        identity, canSet, canQuery, null, null, status, reason, true);
  }
}
