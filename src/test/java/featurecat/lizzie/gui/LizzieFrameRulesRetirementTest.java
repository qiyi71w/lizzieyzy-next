package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class LizzieFrameRulesRetirementTest {
  @Test
  void manualSelectionRetiresImportedRequestAndMatchingContinuePermit() throws Exception {
    Board previousBoard = Lizzie.board;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousCanGoAfterload = LizzieFrame.canGoAfterload;
    try {
      Leelaz primary = new Leelaz("");
      Lizzie.leelaz = primary;
      Board board = new Board();
      Lizzie.board = board;
      BoardHistoryList history = board.getHistory();
      BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("Japanese");
      history.authorizeAnalysisWithCurrentRules(target, primary, 7L, null);

      LizzieFrame frame = allocate(LizzieFrame.class);
      BoardHistoryNode root = history.getStart();
      setField(frame, "pendingKifuEngineSyncRoot", root);
      setField(frame, "kifuAnalysisResumeGeneration", 11);
      LizzieFrame.canGoAfterload = false;

      frame.retireImportedRulesForManualSelection(history, target);

      assertNull(getField(frame, "pendingKifuEngineSyncRoot"));
      assertEquals(12, getField(frame, "kifuAnalysisResumeGeneration"));
      assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, null));
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousPrimary;
      LizzieFrame.canGoAfterload = previousCanGoAfterload;
    }
  }

  @Test
  void nullEngineIncarnationFenceRemainsCurrentUntilAnEngineAppears() {
    assertTrue(LizzieFrame.exactEngineIncarnationsRemainCurrent(null, null, null, null));
  }

  @Test
  void rulesCapabilityRetryStopsAtDeadlineOrTerminalEngineState() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;

    assertTrue(
        LizzieFrame.rulesCapabilityDiscoveryMayStillComplete(
            engine, System.nanoTime() + 1_000_000_000L));
    assertFalse(
        LizzieFrame.rulesCapabilityDiscoveryMayStillComplete(engine, System.nanoTime() - 1L));
    engine.isDownWithError = true;
    assertFalse(
        LizzieFrame.rulesCapabilityDiscoveryMayStillComplete(
            engine, System.nanoTime() + 1_000_000_000L));
    assertTrue(
        LizzieFrame.exactEngineIncarnationsRemainCurrent(null, null, null, null),
        "a captured no-engine request remains current until an engine appears");
    assertFalse(
        LizzieFrame.exactEngineIncarnationsRemainCurrent(null, new Object(), null, null),
        "a synthetic incarnation cannot stand in for a captured no-engine request");
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
