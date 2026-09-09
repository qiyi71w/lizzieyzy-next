package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SGFParserPrimaryEngineIsolationTest {
  @Test
  void deferredSgfLoadDoesNotForwardAnIntermediateClearToThePrimaryEngine() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      AtomicInteger forwardingAttempts = new AtomicInteger();
      Runnable previousHook = Board.beforeHistoryOverwriteEngineForward;
      Board.beforeHistoryOverwriteEngineForward = forwardingAttempts::incrementAndGet;
      try {
        assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5];B[aa];W[bb])", false));
        assertEquals(
            0,
            forwardingAttempts.get(),
            "deferred synchronization must not send a partial clear while parsing");
      } finally {
        Board.beforeHistoryOverwriteEngineForward = previousHook;
      }
    }
  }

  @Test
  void deferredLoadPublishesRulesOnlyAtExplicitExternalAdoptionBoundary() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5]RU[Japanese];B[aa])", false));
      assertEquals(
          BoardHistoryList.SessionRulesSource.NONE,
          Lizzie.board.getHistory().captureSessionRules().source());

      BoardHistoryList.SessionRulesTarget adopted = SGFParser.adoptCurrentHistoryExternalRules();

      assertEquals(BoardHistoryList.SessionRulesKind.VALID, adopted.kind());
      assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, adopted.source());
    }
  }

  @Test
  void synchronousParserEntryAdoptsRootRules() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5]RU[Chinese];B[aa])", true));
      BoardHistoryList.SessionRulesTarget adopted = Lizzie.board.getHistory().captureSessionRules();
      assertEquals(BoardHistoryList.SessionRulesKind.VALID, adopted.kind());
      assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, adopted.source());
    }
  }

  @Test
  void legacySgfLoadStillForwardsItsInitialPrimaryEngineClear() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      AtomicInteger forwardingAttempts = new AtomicInteger();
      Runnable previousHook = Board.beforeHistoryOverwriteEngineForward;
      Board.beforeHistoryOverwriteEngineForward = forwardingAttempts::incrementAndGet;
      try {
        assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5];B[aa])"));
        assertTrue(
            forwardingAttempts.get() > 0,
            "existing SGF callers must retain synchronous primary-engine forwarding");
      } finally {
        Board.beforeHistoryOverwriteEngineForward = previousHook;
      }
    }
  }

  @Test
  void failedSynchronousParserRestoresHistoryAndSessionRules() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5]RU[Japanese];B[aa])", false));
      BoardHistoryList originalHistory = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget originalTarget =
          SGFParser.adoptCurrentHistoryExternalRules();

      assertFalse(SGFParser.loadFromString("", true));

      assertSame(originalHistory, Lizzie.board.getHistory());
      assertSame(originalTarget, Lizzie.board.getHistory().captureSessionRules());
    }
  }
}
