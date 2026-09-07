package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.enginegame.EngineGameCompletionFacts;
import featurecat.lizzie.enginegame.EngineGameParticipantDescriptor;
import featurecat.lizzie.enginegame.EngineGameRecord;
import featurecat.lizzie.enginegame.EngineGameRecordContext;
import featurecat.lizzie.enginegame.EngineGameSide;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameOutcome;
import featurecat.lizzie.enginegame.MatchRulesAdmission;
import featurecat.lizzie.enginegame.MatchRulesSnapshot;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import featurecat.lizzie.gui.LizzieFrame;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class SGFParserEngineGameRecordTest {
  @Test
  void appendGameTimeAndPlayoutsUsesHistoryRecordNotCurrentGlobals() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.chkEngineSgfStart = true;
      if (LizzieFrame.toolbar != null) {
        LizzieFrame.toolbar.currentEnginePkSgfNum = 99;
      }

      GameInfo info = Lizzie.board.getHistory().getGameInfo();
      EngineGameRecordContext context = EngineGameRecordContext.saveFormattingMarker();
      info.attachEngineGameRecordContext(context);
      info.freezeEngineGameRecord(
          new EngineGameRecord(
              context,
              new EngineGameCompletionFacts(
                  new GameOutcome.Resign(EngineGameSide.BLACK), 1, 7, true, 1500, 2500, 11, 22),
              "BlackEngine",
              "WhiteEngine"));

      SGFParser.appendGameTimeAndPlayouts();
      String comment = Lizzie.board.getHistory().getStart().getData().comment;

      assertTrue(
          comment.contains(Lizzie.resourceBundle.getString("SGFParse.startGameSgf") + "7"),
          comment);
      assertFalse(comment.contains("99"), comment);
      assertTrue(comment.contains("1.5"), comment);
      assertTrue(comment.contains("2.5"), comment);
      assertTrue(comment.contains("11"), comment);
      assertTrue(comment.contains("22"), comment);
    }
  }

  @Test
  void saveUsesHistoryAttachmentWithoutCurrentEngineIndexes() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      GameInfo info = Lizzie.board.getHistory().getGameInfo();
      EngineParticipantIdentity black = new EngineParticipantIdentity("b", "BlackEngine");
      EngineParticipantIdentity white = new EngineParticipantIdentity("w", "WhiteEngine");
      info.attachEngineGameRecordContext(
          new EngineGameRecordContext(
              null,
              new EngineGameParticipantDescriptor(black, "BlackEngine", true, false, 1),
              new EngineGameParticipantDescriptor(white, "WhiteEngine", false, false, 0)));

      String sgf = SGFParser.saveToString(false);

      assertTrue(sgf.contains("DZ[KB]"), sgf);
    }
  }

  @Test
  void saveWritesFrozenMatchRulesNotLiveEngineClassification() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      ResourceBundle bundle = Lizzie.resourceBundle;
      KataGoRules chinese = KataGoRules.parse("chinese").orElseThrow();
      KataGoRules positional = KataGoRules.parse("chinese-ogs").orElseThrow();
      MatchRulesSnapshot snapshot =
          MatchRulesSnapshot.of(
              MatchRulesSnapshot.Phase.COMPLETED,
              chinese,
              side(new EngineParticipantIdentity("b", "BlackEngine"), chinese),
              side(new EngineParticipantIdentity("w", "WhiteEngine"), positional),
              MatchRulesAdmission.Outcome.REJECT);
      GameInfo info = Lizzie.board.getHistory().getGameInfo();
      EngineGameRecordContext context =
          new EngineGameRecordContext(
              null,
              new EngineGameParticipantDescriptor(
                  new EngineParticipantIdentity("b", "BlackEngine"), "BlackEngine", true, false, 1),
              new EngineGameParticipantDescriptor(
                  new EngineParticipantIdentity("w", "WhiteEngine"), "WhiteEngine", true, false, 1),
              snapshot);
      info.attachEngineGameRecordContext(context);
      info.freezeEngineGameRecord(
          new EngineGameRecord(
              context,
              new EngineGameCompletionFacts(
                  new GameOutcome.Resign(EngineGameSide.BLACK), 1, 1, true, 0, 0, 0, 0),
              "BlackEngine",
              "WhiteEngine",
              snapshot));
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.usingSpecificRules = 3;
      }

      String first = SGFParser.saveToString(false);
      assertTrue(first.contains(bundle.getString("MatchRules.sgf.begin")), first);
      assertTrue(first.contains("ko=SIMPLE"), first);
      assertTrue(first.contains("ko=POSITIONAL"), first);
      assertFalse(first.contains(bundle.getString("LizzieFrame.currentRules.japanese")), first);

      String second = SGFParser.saveToString(false);
      int firstIndex = first.indexOf(bundle.getString("MatchRules.sgf.begin"));
      int secondBegin = second.indexOf(bundle.getString("MatchRules.sgf.begin"));
      long beginLines =
          Lizzie.board.getHistory().getStart().getData().comment.lines()
              .filter(bundle.getString("MatchRules.sgf.begin")::equals)
              .count();
      assertTrue(firstIndex >= 0, first);
      assertTrue(secondBegin >= 0, second);
      assertEquals(1, beginLines, second);
    }
  }

  private static MatchRulesAdmission.SideResult side(
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
