package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class MatchRulesSnapshotTest {
  private static final EngineParticipantIdentity BLACK =
      new EngineParticipantIdentity("black-cmd", "Black");
  private static final EngineParticipantIdentity WHITE =
      new EngineParticipantIdentity("white-cmd", "White");
  private static final KataGoRules CHINESE = KataGoRules.parse("chinese").orElseThrow();
  private static final KataGoRules JAPANESE = KataGoRules.parse("japanese").orElseThrow();

  @Test
  void preparingShowsCheckingStatus() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesSnapshot snapshot = MatchRulesSnapshot.preparing(CHINESE, BLACK, WHITE);
    assertEquals(bundle.getString("MatchRules.checking"), snapshot.mainSummary(bundle));
  }

  @Test
  void confirmedPlayingShowsRuleNameOnly() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesSnapshot snapshot =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.PLAYING,
            CHINESE,
            confirmed(BLACK, CHINESE),
            confirmed(WHITE, CHINESE),
            MatchRulesAdmission.Outcome.ADMIT_CONFIRMED);
    assertEquals(
        bundle.getString("LizzieFrame.currentRules.chinese"), snapshot.mainSummary(bundle));
    assertEquals(
        bundle.getString("LizzieFrame.currentRules.chinese"),
        snapshot.completed().mainSummary(bundle));
  }

  @Test
  void unverifiedShowsBothSides() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesAdmission.SideResult black =
        new MatchRulesAdmission.SideResult(
            BLACK,
            true,
            false,
            JAPANESE,
            JAPANESE,
            EngineRulesResult.Status.UNCONFIRMED,
            EngineRulesResult.Reason.QUERY_UNSUPPORTED,
            false);
    MatchRulesAdmission.SideResult white = confirmed(WHITE, CHINESE);
    MatchRulesSnapshot snapshot =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.PLAYING,
            CHINESE,
            black,
            white,
            MatchRulesAdmission.Outcome.ADMIT_UNVERIFIED);
    String summary = snapshot.mainSummary(bundle);
    assertTrue(summary.contains(bundle.getString("MatchRules.black")));
    assertTrue(summary.contains(bundle.getString("MatchRules.white")));
    assertTrue(summary.contains(bundle.getString("MatchRules.unconfirmed")));
    assertFalse(summary.equals(bundle.getString("LizzieFrame.currentRules.chinese")));
  }

  @Test
  void failedQuerySummaryDoesNotUseRawStatusName() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesAdmission.SideResult black =
        new MatchRulesAdmission.SideResult(
            BLACK,
            true,
            true,
            null,
            null,
            EngineRulesResult.Status.QUERY_FAILED,
            EngineRulesResult.Reason.QUERY_REJECTED,
            false);
    MatchRulesAdmission.SideResult white = confirmed(WHITE, CHINESE);
    MatchRulesSnapshot snapshot =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.FAILED,
            CHINESE,
            black,
            white,
            MatchRulesAdmission.Outcome.REJECT);
    String summary = snapshot.mainSummary(bundle);
    assertFalse(summary.contains("QUERY_FAILED"));
    assertFalse(summary.contains("QUERY_REJECTED"));
    assertTrue(summary.contains(bundle.getString("MatchRules.reason.QUERY_REJECTED")));
  }

  @Test
  void confirmedReadbackShowsRuleFamilyDuringAndAfterMatch() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    for (String preset : java.util.List.of("korean", "bga")) {
      KataGoRules target = KataGoRules.parse(preset).orElseThrow();
      KataGoRules observed = KataGoRules.parse(target.toResponseLine()).orElseThrow();
      MatchRulesSnapshot snapshot =
          MatchRulesSnapshot.of(
              MatchRulesSnapshot.Phase.PLAYING,
              target,
              confirmed(BLACK, observed),
              confirmed(WHITE, observed),
              MatchRulesAdmission.Outcome.ADMIT_CONFIRMED);
      String key = preset.equals("korean") ? "japaneseKorean" : "agaBga";
      assertEquals(bundle.getString("MatchRules.option." + key), snapshot.mainSummary(bundle));
      assertEquals(snapshot.mainSummary(bundle), snapshot.completed().mainSummary(bundle));
    }
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
}
