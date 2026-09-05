package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class EngineGamePresentationTest {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("first-cmd", "First");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("second-cmd", "Second");

  @Test
  void exchangedPlayingMapsFirstWinsOntoWhite() {
    EngineGameSnapshot snapshot =
        playing(true, 3, 1);

    assertTrue(EngineGamePresentation.showLiveBatchScores(snapshot));
    assertEquals(1, EngineGamePresentation.blackWins(snapshot));
    assertEquals(3, EngineGamePresentation.whiteWins(snapshot));
  }

  @Test
  void unexchangedPlayingMapsFirstWinsOntoBlack() {
    EngineGameSnapshot snapshot = playing(false, 4, 2);

    assertEquals(4, EngineGamePresentation.blackWins(snapshot));
    assertEquals(2, EngineGamePresentation.whiteWins(snapshot));
  }

  @Test
  void betweenGamesAndIdleDoNotShowLiveBatchScores() {
    EngineGameSnapshot between =
        new EngineGameSnapshot.BatchActive(summary(3, 1), new GameActivity.BetweenGames());
    EngineGameSnapshot idle = new EngineGameSnapshot.Idle();

    assertFalse(EngineGamePresentation.showLiveBatchScores(between));
    assertFalse(EngineGamePresentation.showLiveBatchScores(idle));
    assertFalse(between.paused());
    assertFalse(idle.paused());
  }

  @Test
  void pausedPlayingIsDistinctFromRunning() {
    EngineGameSnapshot paused =
        new EngineGameSnapshot.BatchActive(
            summary(0, 0),
            new GameActivity.Playing(view(false), RunState.PAUSED));

    assertTrue(paused.playing());
    assertTrue(paused.paused());
    assertTrue(EngineGamePresentation.showLiveBatchScores(paused));
  }

  @Test
  void historicalRecordKatagoWinsOverLiveSnapshot() {
    GameInfo info = new GameInfo();
    EngineGameParticipantDescriptor black =
        new EngineGameParticipantDescriptor(FIRST, "OldBlack", false, false, 0);
    EngineGameParticipantDescriptor white =
        new EngineGameParticipantDescriptor(SECOND, "OldWhite", true, true, 2);
    info.attachEngineGameRecordContext(new EngineGameRecordContext(null, black, white));
    EngineGameSnapshot liveNextGame = playing(false, 9, 8);

    assertTrue(EngineGamePresentation.whiteKatago(info, liveNextGame));
    assertTrue(EngineGamePresentation.whiteSai(info, liveNextGame));
    assertEquals(2, EngineGamePresentation.whiteSpecificRules(info, liveNextGame));
    assertFalse(EngineGamePresentation.blackKatago(info, liveNextGame));
    assertEquals("OldWhite", EngineGamePresentation.whiteDescriptor(info).displayName());
  }

  private static EngineGameSnapshot playing(boolean exchanged, int firstWins, int secondWins) {
    return new EngineGameSnapshot.BatchActive(
        summary(firstWins, secondWins),
        new GameActivity.Playing(view(exchanged), RunState.RUNNING));
  }

  @Test
  void idleFailedMatchRulesCaptionUsesSnapshotSummaryNotBlank() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesSnapshot failed =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.FAILED,
            KataGoRules.parse("chinese").orElseThrow(),
            new MatchRulesAdmission.SideResult(
                FIRST,
                true,
                true,
                null,
                null,
                EngineRulesResult.Status.QUERY_FAILED,
                EngineRulesResult.Reason.QUERY_REJECTED,
                false),
            new MatchRulesAdmission.SideResult(
                SECOND,
                true,
                true,
                KataGoRules.parse("chinese").orElseThrow(),
                KataGoRules.parse("chinese").orElseThrow(),
                EngineRulesResult.Status.CONFIRMED,
                EngineRulesResult.Reason.NONE,
                false),
            MatchRulesAdmission.Outcome.REJECT);
    String caption =
        EngineGamePresentation.matchRulesCaption(
            new EngineGameSnapshot.Idle(), failed, new GameInfo(), bundle);
    assertEquals(failed.mainSummary(bundle), caption);
    assertFalse(caption.isEmpty());
  }

  @Test
  void idleCompletedMatchRulesCaptionDoesNotUseStaleLiveSnapshot() {
    ResourceBundle bundle = Lizzie.resourceBundle;
    MatchRulesSnapshot completed =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.COMPLETED,
            KataGoRules.parse("chinese").orElseThrow(),
            new MatchRulesAdmission.SideResult(
                FIRST,
                true,
                true,
                KataGoRules.parse("chinese").orElseThrow(),
                KataGoRules.parse("chinese").orElseThrow(),
                EngineRulesResult.Status.CONFIRMED,
                EngineRulesResult.Reason.NONE,
                false),
            new MatchRulesAdmission.SideResult(
                SECOND,
                true,
                true,
                KataGoRules.parse("chinese").orElseThrow(),
                KataGoRules.parse("chinese").orElseThrow(),
                EngineRulesResult.Status.CONFIRMED,
                EngineRulesResult.Reason.NONE,
                false),
            MatchRulesAdmission.Outcome.ADMIT_CONFIRMED);
    String caption =
        EngineGamePresentation.matchRulesCaption(
            new EngineGameSnapshot.Idle(), completed, new GameInfo(), bundle);
    assertEquals("", caption);
  }

  private static EngineGameView view(boolean exchanged) {
    return new EngineGameView(
        exchanged ? SECOND : FIRST,
        exchanged ? FIRST : SECOND,
        EngineGamePlayMode.ANALYSIS,
        2,
        exchanged ? 1 : 0,
        exchanged ? 0 : 1,
        0,
        1,
        true,
        3);
  }

  private static BatchSummary summary(int firstWins, int secondWins) {
    return new BatchSummary(
        FIRST,
        SECOND,
        2,
        4,
        true,
        firstWins,
        secondWins,
        firstWins,
        0,
        0,
        secondWins,
        0,
        0,
        0L,
        0L,
        0L,
        0L,
        List.of());
  }
}
