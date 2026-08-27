package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.enginegame.EngineGameCompletionFacts;
import featurecat.lizzie.enginegame.EngineGameParticipantDescriptor;
import featurecat.lizzie.enginegame.EngineGameRecord;
import featurecat.lizzie.enginegame.EngineGameRecordContext;
import featurecat.lizzie.enginegame.EngineGameSide;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameOutcome;
import featurecat.lizzie.gui.LizzieFrame;
import org.junit.jupiter.api.Test;

class SGFParserEngineGameRecordTest {
  @Test
  void appendGameTimeAndPlayoutsUsesHistoryRecordNotCurrentGlobals() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.chkEngineSgfStart = true;
      if (LizzieFrame.toolbar != null) {
        LizzieFrame.toolbar.currentEnginePkSgfNum = 99;
      }
      EngineManager.engineGameInfo.blackEngineIndex = 50;
      EngineManager.engineGameInfo.whiteEngineIndex = 51;

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
      EngineManager.engineGameInfo.blackEngineIndex = 50;
      EngineManager.engineGameInfo.whiteEngineIndex = 51;
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
}
