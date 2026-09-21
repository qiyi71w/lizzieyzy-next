package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import org.junit.jupiter.api.Test;

/** A failed engine startup must not disable the offline board and SGF editor. */
class BoardMissingEngineTest {
  @Test
  void importsSgfWithoutAnEngine() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.leelaz = null;
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5]KM[6.5];B[aa];W[bb])"));
      assertEquals(2, env.board().getHistory().mainTrunkLength());
      assertEquals(6.5, env.board().getHistory().getGameInfo().getKomi());
    }
  }

  @Test
  void navigatesExistingHistoryAfterEngineFailure() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      env.board().place(0, 0, Stone.BLACK);
      env.board().place(1, 1, Stone.WHITE);
      Lizzie.leelaz = null;
      assertTrue(env.board().previousMove(false));
      assertEquals(1, env.board().getHistory().getMoveNumber());
      assertTrue(env.board().nextMove(false));
      assertEquals(2, env.board().getHistory().getMoveNumber());
    }
  }

  @Test
  void playsPassesAndChangesKomiWithoutAnEngine() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.leelaz = null;
      env.board().place(0, 0, Stone.BLACK);
      env.board().pass(Stone.WHITE);
      env.board().setKomi(6.5);
      assertEquals(Stone.BLACK, env.stoneAt(0, 0));
      assertEquals(2, env.board().getHistory().getMoveNumber());
      assertEquals(6.5, env.board().getHistory().getGameInfo().getKomi());
      assertTrue(SGFParser.saveToString(false).contains("B[aa]"));
    }
  }

  @Test
  void replaysHistoryWithoutAnEngine() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      env.board().place(0, 0, Stone.BLACK);
      env.board().place(1, 1, Stone.WHITE);
      Lizzie.leelaz = null;
      env.board().getHistory().toStart();
      assertTrue(env.board().getHistory().next().isPresent());
      assertTrue(env.board().getHistory().next().isPresent());
      assertEquals(2, env.board().getHistory().getMoveNumber());
    }
  }

  @Test
  void transformsOfflineGameAndPreservesKomi() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.leelaz = null;
      env.board().place(0, 0, Stone.BLACK);
      env.board().setKomi(6.5);
      env.board().SpinAndMirror(3);
      assertEquals(1, env.board().getHistory().getMoveNumber());
      assertEquals(6.5, env.board().getHistory().getGameInfo().getKomi());
      env.board().exchangeBlackWhite();
      assertEquals(Stone.WHITE, env.board().getData().lastMoveColor);
      assertEquals(6.5, env.board().getHistory().getGameInfo().getKomi());
    }
  }

  @Test
  void clearsOfflineBoardForOnlineImport() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.leelaz = null;
      env.board().place(0, 0, Stone.BLACK);
      env.board().setKomi(6.5);
      env.board().clearForOnline();
      assertEquals(0, env.board().getHistory().getMoveNumber());
      assertEquals(6.5, env.board().getHistory().getGameInfo().getKomi());
    }
  }
}
