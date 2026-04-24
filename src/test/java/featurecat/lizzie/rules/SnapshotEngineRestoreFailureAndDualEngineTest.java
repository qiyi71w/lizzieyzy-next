package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SnapshotEngineRestoreFailureAndDualEngineTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final String AUTO_ID_RESPONSE = "__auto-id-response__";

  @Test
  void resendMoveToEngineThrowsWhenLoadsgfFlushFailsAndStopsRealReplay() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      RecordingOutputStream output = new RecordingOutputStream("loadsgf ");
      setOutputStream(engine, output);

      IllegalStateException thrown =
          assertThrows(IllegalStateException.class, () -> board.resendMoveToEngine(engine, false));

      assertTrue(
          thrown.getMessage().contains("loadsgf"),
          "loadsgf send failures should be exposed as restore failures.");
      assertEquals("clear_board", output.commands().get(0));
      assertTrue(isLoadSgfCommand(output.commands().get(1)));
      assertEquals(2, output.commands().size(), "restore should stop before replaying real moves.");
    }
  }

  @Test
  void resendMoveToEngineThrowsWhenLoadsgfReturnsErrorResponseAndStopsRealReplay()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      ScriptedResponseOutputStream output =
          new ScriptedResponseOutputStream(engine, null, "=", "? cannot loadsgf");
      setOutputStream(engine, output);

      IllegalStateException thrown =
          assertThrows(IllegalStateException.class, () -> board.resendMoveToEngine(engine, false));

      assertTrue(
          thrown.getMessage().contains("? cannot loadsgf"),
          "loadsgf GTP error responses should be exposed as restore failures.");
      assertEquals("clear_board", output.commands().get(0));
      assertTrue(isLoadSgfCommand(output.commands().get(1)));
      assertEquals(2, output.commands().size(), "restore should stop before replaying real moves.");
    }
  }

  @Test
  void resendMoveToEngineThrowsWhenQueuedLoadsgfFlushFailsAndCleansTempSgf() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.requireResponseBeforeSend = true;
      RecordingOutputStream output = new RecordingOutputStream("loadsgf ");
      setOutputStream(engine, output);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  board.resendMoveToEngine(engine, false);
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "queued-loadsgf-failure");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(output, 1);
      waitForCommandQueueSize(engine, 1);
      assertEquals("clear_board", output.commands().get(0));

      triggerQueuedSend(engine);

      restoreThread.join(2000L);
      assertFalse(restoreThread.isAlive(), "queued loadsgf send failure should not hang restore.");

      Throwable thrown = thrownRef.get();
      assertTrue(
          thrown instanceof IllegalStateException,
          "queued loadsgf send failures should surface as restore failures.");
      assertTrue(
          thrown.getMessage().contains("loadsgf"),
          "queued loadsgf send failures should keep loadsgf context.");

      List<String> commands = output.commands();
      assertEquals(2, commands.size(), "restore should stop before replaying real moves.");
      assertEquals("clear_board", commands.get(0));
      assertTrue(isLoadSgfCommand(commands.get(1)));

      Path tempSgf = extractLoadSgfPath(commands.get(1));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void resendMoveToEngineThrowsWhenQueuedLoadsgfOutputStreamUnavailableAndCleansTempSgf()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.requireResponseBeforeSend = true;
      RecordingOutputStream output = new RecordingOutputStream(null);
      setOutputStream(engine, output);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  board.resendMoveToEngine(engine, false);
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "queued-loadsgf-outputstream-unavailable");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(output, 1);
      waitForCommandQueueSize(engine, 1);
      assertEquals("clear_board", output.commands().get(0));

      setOutputStream(engine, null);
      triggerQueuedSend(engine);

      restoreThread.join(2000L);
      assertFalse(restoreThread.isAlive(), "queued outputStream failure should not hang restore.");

      Throwable thrown = thrownRef.get();
      assertTrue(
          thrown instanceof IllegalStateException,
          "queued outputStream failures should surface as restore failures.");
      assertTrue(
          thrown.getMessage().contains("outputStream unavailable"),
          "queued send failures should expose outputStream unavailable.");

      List<String> commands = output.commands();
      assertEquals(1, commands.size(), "loadsgf should not replay real moves after send failure.");
      assertEquals("clear_board", commands.get(0));

      Path tempSgf = extractLoadSgfPathFromFailure(thrown.getMessage());
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreKeepsTempFileUntilPrimaryConsumerFinishesAfterMirrorSendFailure()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, null, null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      secondaryOutput.failOnCommand("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () -> SnapshotEngineRestore.restoreExactSnapshotIfNeeded(primary, snapshotRoot()));

      assertTrue(
          thrown.getMessage().contains("loadsgf"),
          "mirror send failures should be exposed as restore failures.");
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(1, secondaryOutput.commands().size());
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));
      assertTrue(isLoadSgfCommand(secondaryOutput.commands().get(0)));

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      assertTrue(
          Files.exists(tempSgf),
          "temporary SGF should survive until the already-dispatched primary consumer finishes.");

      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(0)));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreMirrorsLoadSgfWhenStartedFromSecondaryEngine() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, "=", AUTO_ID_RESPONSE);
      ScriptedResponseOutputStream secondaryOutput =
          new ScriptedResponseOutputStream(secondary, null, "=", AUTO_ID_RESPONSE);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      assertTrue(SnapshotEngineRestore.restoreExactSnapshotIfNeeded(secondary, snapshotRoot()));
      assertEquals(1, secondaryOutput.commands().size());
      assertEquals(1, primaryOutput.commands().size());
      assertTrue(isLoadSgfCommand(secondaryOutput.commands().get(0)));
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));

      Path primaryTempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      Path secondaryTempSgf = extractLoadSgfPath(secondaryOutput.commands().get(0));
      assertEquals(primaryTempSgf, secondaryTempSgf, "mirrored restore should share one temp SGF.");
      assertEventuallyDeleted(primaryTempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreFromThirdEngineDoesNotMirrorToPrimaryOrSecondary() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Leelaz third = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      ScriptedResponseOutputStream thirdOutput =
          new ScriptedResponseOutputStream(third, null, null, AUTO_ID_RESPONSE);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);
      setOutputStream(third, thirdOutput);

      assertTrue(
          SnapshotEngineRestore.restoreExactSnapshotIfNeeded(third, snapshotRoot()),
          "third engine restore should only restore itself.");

      assertEquals(1, thirdOutput.commands().size(), "third engine should send one loadsgf.");
      assertTrue(
          isLoadSgfCommand(thirdOutput.commands().get(0)), "third engine should send loadsgf.");
      assertEquals(
          0, primaryOutput.commands().size(), "third engine restore should not mirror to primary.");
      assertEquals(
          0,
          secondaryOutput.commands().size(),
          "third engine restore should not mirror to secondary.");

      Path tempSgf = extractLoadSgfPath(thirdOutput.commands().get(0));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void thirdEngineResendReplaysTrailingRealActionsOnlyToItself() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Stone[] passStones = history.getData().stones.clone();
      history.add(
          BoardData.pass(
              passStones,
              Stone.WHITE,
              false,
              zobrist(passStones),
              5,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Leelaz third = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      ScriptedResponseOutputStream thirdOutput =
          new ScriptedResponseOutputStream(third, null, "=", AUTO_ID_RESPONSE);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);
      setOutputStream(third, thirdOutput);

      board.resendMoveToEngine(third, false);

      List<String> thirdCommands = thirdOutput.commands();
      assertEquals("clear_board", thirdCommands.get(0));
      assertTrue(isLoadSgfCommand(thirdCommands.get(1)));

      List<String> expectedReplay =
          List.of("play B " + Board.convertCoordinatesToName(2, 2), "play W pass");
      assertEquals(expectedReplay, collectPlayCommands(thirdCommands));
      assertEquals(
          0,
          collectPlayCommands(primaryOutput.commands()).size(),
          "third engine trailing replay should not mirror plays to primary.");
      assertEquals(
          0,
          collectPlayCommands(secondaryOutput.commands()).size(),
          "third engine trailing replay should not mirror plays to secondary.");

      Path tempSgf = extractLoadSgfPath(thirdCommands.get(1));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void secondaryEntryResendMirrorsTrailingRealActionsAfterSnapshotRestore() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Stone[] passStones = history.getData().stones.clone();
      history.add(
          BoardData.pass(
              passStones,
              Stone.WHITE,
              false,
              zobrist(passStones),
              5,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  board.resendMoveToEngine(secondary, false);
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "secondary-resend-mirror");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(secondaryOutput, 2);
      waitForCommandCount(primaryOutput, 1);
      assertEquals("clear_board", secondaryOutput.commands().get(0));

      invokeResponseHandlerForLine(secondary, "=");
      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(1)));
      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(0)));

      restoreThread.join(2000L);
      assertFalse(
          restoreThread.isAlive(), "secondary restore entry should finish after responses.");
      assertTrue(thrownRef.get() == null, "secondary restore entry should not fail.");

      waitForCommandCount(secondaryOutput, 4);
      waitForCommandCount(primaryOutput, 3);

      List<String> expectedReplay =
          List.of("play B " + Board.convertCoordinatesToName(2, 2), "play W pass");
      assertEquals(
          expectedReplay,
          collectPlayCommands(secondaryOutput.commands()),
          "secondary restore entry should replay trailing real actions in order.");
      assertEquals(
          expectedReplay,
          collectPlayCommands(primaryOutput.commands()),
          "secondary restore entry should mirror trailing real actions to primary engine.");
    }
  }

  @Test
  void secondaryEntryResendKeepsTempSgfAliveUntilTrailingReplayCommandsAreSent() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Stone[] passStones = history.getData().stones.clone();
      history.add(
          BoardData.pass(
              passStones,
              Stone.WHITE,
              false,
              zobrist(passStones),
              5,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      TailReplayAwareOutputStream primaryOutput = new TailReplayAwareOutputStream(primary);
      TailReplayAwareOutputStream secondaryOutput = new TailReplayAwareOutputStream(secondary);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      board.resendMoveToEngine(secondary, false);

      assertTrue(
          secondaryOutput.tempFileExistedDuringReplay(),
          "secondary replay should see temporary SGF while trailing real moves are being sent.");
      assertTrue(
          primaryOutput.tempFileExistedDuringReplay(),
          "mirrored primary replay should see temporary SGF while trailing real moves are being sent.");
      assertEventuallyDeleted(secondaryOutput.loadSgfPath());
    }
  }

  @Test
  void exactSnapshotRestoreFallbackCleansPrimaryHandlerWhenMirrorFailsAndPrimaryNeverResponds()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () -> SnapshotEngineRestore.restoreExactSnapshotIfNeeded(primary, snapshotRoot()));
      assertTrue(thrown.getMessage().contains("loadsgf"));
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(1, secondaryOutput.commands().size());

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      assertTrue(Files.exists(tempSgf), "temporary SGF should exist before fallback cleanup.");

      assertEventuallyPendingHandlerCount(primary, 0);
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreFailsAndCleansWhenPrimaryReturnsErrorAndMirrorStaysSilent()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  SnapshotEngineRestore.restoreExactSnapshotIfNeeded(primary, snapshotRoot());
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "error-response-with-silent-mirror");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(primaryOutput, 1);
      waitForCommandCount(secondaryOutput, 1);
      invokeResponseHandlerForLine(primary, "? cannot loadsgf");

      restoreThread.join(2500L);
      assertFalse(restoreThread.isAlive(), "? + silent mirror should still return a failure.");

      Throwable thrown = thrownRef.get();
      assertTrue(thrown instanceof IllegalStateException, "restore should fail on ? responses.");
      assertTrue(
          thrown.getMessage().contains("? cannot loadsgf"),
          "restore failure should preserve the GTP error detail.");
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(1, secondaryOutput.commands().size());

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      assertEventuallyPendingHandlerCount(primary, 0);
      assertEventuallyPendingHandlerCount(secondary, 0);
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreStillDispatchesMirrorWhenPrimaryReturnsImmediateError()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, null, "? cannot loadsgf");
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () -> SnapshotEngineRestore.restoreExactSnapshotIfNeeded(primary, snapshotRoot()));

      assertTrue(
          thrown.getMessage().contains("? cannot loadsgf"),
          "immediate ? failures should preserve the GTP error detail.");
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(
          1,
          secondaryOutput.commands().size(),
          "mirror loadsgf should still dispatch when primary fails immediately.");
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));
      assertTrue(isLoadSgfCommand(secondaryOutput.commands().get(0)));

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      assertEventuallyPendingHandlerCount(primary, 0);
      assertEventuallyPendingHandlerCount(secondary, 0);
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreFailsAndCleansWhenAllDispatchedEnginesStaySilent() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  SnapshotEngineRestore.restoreExactSnapshotIfNeeded(primary, snapshotRoot());
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "silent-success-all-engines");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(primaryOutput, 1);
      waitForCommandCount(secondaryOutput, 1);
      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));

      restoreThread.join(7000L);
      assertFalse(
          restoreThread.isAlive(), "silent-success dispatch should fail instead of hanging.");

      Throwable thrown = thrownRef.get();
      assertTrue(thrown instanceof IllegalStateException, "silent-success should surface failure.");
      assertTrue(thrown.getMessage().contains("loadsgf"), "failure should keep loadsgf context.");

      assertEventuallyPendingHandlerCount(primary, 0);
      assertEventuallyPendingHandlerCount(secondary, 0);
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void
      exactSnapshotRestoreKeepsTempFileForSlowPrimaryConsumerBeyondCurrentGraceAfterMirrorSendFailure()
          throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, null, null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () -> SnapshotEngineRestore.restoreExactSnapshotIfNeeded(primary, snapshotRoot()));
      assertTrue(
          thrown.getMessage().contains("loadsgf"), "mirror send failures should still be exposed.");
      assertEquals(1, primaryOutput.commands().size());
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      Thread.sleep(4300L);
      assertTrue(
          Files.exists(tempSgf),
          "slow primary consumers beyond current grace should keep temp SGF until real consumption.");
      assertEquals(
          1,
          pendingResponseHandlerCount(primary),
          "slow primary consumers beyond current grace should keep pending handlers until response.");

      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(0)));
      assertEventuallyPendingHandlerCount(primary, 0);
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void lateLoadSgfResponseAfterFailureCleanupDoesNotConsumeNextCommandHandler() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      assertThrows(
          IllegalStateException.class,
          () -> SnapshotEngineRestore.restoreExactSnapshotIfNeeded(primary, snapshotRoot()));
      assertEventuallyPendingHandlerCount(primary, 0);

      String loadSgfCommand = primaryOutput.commands().get(0);
      String lateLoadSgfResponse = buildSuccessResponseLine(loadSgfCommand);

      AtomicInteger callbackCount = new AtomicInteger(0);
      sendCommandWithResponse(primary, "name", callbackCount::incrementAndGet);
      assertEventuallyPendingHandlerCount(primary, 1);

      invokeResponseHandlerForLine(primary, lateLoadSgfResponse);
      assertEquals(
          0, callbackCount.get(), "late loadsgf response should not consume next command handler.");

      invokeResponseHandlerForLine(primary, "=");
      assertEquals(1, callbackCount.get(), "next command handler should run on its own response.");
      assertEventuallyPendingHandlerCount(primary, 0);
      assertEquals(0, commandQueueSize(primary), "late response isolation should not block queue.");
      assertCurrentCmdNumAligned(primary);
    }
  }

  private static BoardData snapshotRoot() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(0, 0)] = 1;
    moveNumberList[Board.getIndex(1, 0)] = 2;
    return BoardData.snapshot(
        stones,
        java.util.Optional.of(new int[] {1, 0}),
        Stone.WHITE,
        false,
        zobrist(stones),
        3,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static BoardData moveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = snapshotRoot().stones.clone();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static void setOutputStream(Leelaz engine, OutputStream stream) throws Exception {
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    outputField.set(engine, Leelaz.createCommandOutputStream(stream));
  }

  private static void invokeResponseHandlerForLine(Leelaz engine, String line) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod("runPendingResponseHandlerForLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void triggerQueuedSend(Leelaz engine) throws Exception {
    engine.setResponseUpToDate();
    Method method = Leelaz.class.getDeclaredMethod("trySendCommandFromQueue");
    method.setAccessible(true);
    try {
      method.invoke(engine);
    } catch (ReflectiveOperationException ex) {
      Throwable cause = ex.getCause();
      if (!(cause instanceof RuntimeException)) {
        throw ex;
      }
    }
  }

  private static int commandQueueSize(Leelaz engine) throws Exception {
    Field queueField = Leelaz.class.getDeclaredField("cmdQueue");
    queueField.setAccessible(true);
    Object queue = queueField.get(engine);
    if (queue == null) {
      return 0;
    }
    return ((java.util.ArrayDeque<?>) queue).size();
  }

  private static void assertCurrentCmdNumAligned(Leelaz engine) throws Exception {
    Field currentField = Leelaz.class.getDeclaredField("currentCmdNum");
    currentField.setAccessible(true);
    int currentCmdNum = (Integer) currentField.get(engine);

    Field cmdField = Leelaz.class.getDeclaredField("cmdNumber");
    cmdField.setAccessible(true);
    int cmdNumber = (Integer) cmdField.get(engine);

    assertTrue(currentCmdNum <= cmdNumber - 1, "currentCmdNum should stay within command range.");
  }

  private static void waitForCommandQueueSize(Leelaz engine, int expectedSize) throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      if (commandQueueSize(engine) >= expectedSize) {
        return;
      }
      Thread.sleep(25L);
    }
    assertEquals(expectedSize, commandQueueSize(engine), "queued loadsgf should stay pending.");
  }

  private static void waitForCommandCount(RecordingOutputStream output, int expectedCount)
      throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      if (output.commands().size() >= expectedCount) {
        return;
      }
      Thread.sleep(25L);
    }
    assertEquals(expectedCount, output.commands().size(), "expected queued command count.");
  }

  private static int pendingResponseHandlerCount(Leelaz engine) throws Exception {
    Field pendingField = Leelaz.class.getDeclaredField("pendingResponseHandlers");
    pendingField.setAccessible(true);
    Object pending = pendingField.get(engine);
    if (pending == null) {
      return 0;
    }
    return ((java.util.ArrayDeque<?>) pending).size();
  }

  private static void assertEventuallyPendingHandlerCount(Leelaz engine, int expectedCount)
      throws Exception {
    for (int attempt = 0; attempt < 160; attempt++) {
      if (pendingResponseHandlerCount(engine) == expectedCount) {
        return;
      }
      Thread.sleep(50L);
    }
    assertEquals(expectedCount, pendingResponseHandlerCount(engine), "pending handlers leaked.");
  }

  private static boolean isLoadSgfCommand(String command) {
    return command != null && command.contains("loadsgf ");
  }

  private static List<String> collectPlayCommands(List<String> commands) {
    List<String> replay = new ArrayList<>();
    for (String command : commands) {
      if (command != null && command.startsWith("play ")) {
        replay.add(command);
      }
    }
    return replay;
  }

  private static boolean matchesCommandPrefix(String command, String commandPrefix) {
    if (commandPrefix == null || command == null) {
      return false;
    }
    return command.startsWith(commandPrefix) || command.contains(" " + commandPrefix);
  }

  private static Path extractLoadSgfPath(String command) {
    String marker = "loadsgf ";
    int start = command.indexOf(marker);
    if (start < 0) {
      throw new IllegalStateException("Cannot extract loadsgf temp file from command: " + command);
    }
    return Path.of(command.substring(start + marker.length()).trim());
  }

  private static Path extractLoadSgfPathFromFailure(String message) {
    String marker = "loadsgf ";
    int start = message.indexOf(marker);
    int end = message.indexOf(".sgf", start);
    if (start < 0 || end < 0) {
      throw new IllegalStateException("Cannot extract loadsgf temp file from message: " + message);
    }
    return Path.of(message.substring(start + marker.length(), end + 4).trim());
  }

  private static void assertEventuallyDeleted(Path path) throws InterruptedException {
    for (int attempt = 0; attempt < 160; attempt++) {
      if (!Files.exists(path)) {
        return;
      }
      Thread.sleep(50L);
    }
    assertFalse(Files.exists(path), "temporary SGF should be deleted after both consumers finish.");
  }

  private static Config minimalConfig(boolean doubleEngine) throws Exception {
    Config config = allocate(Config.class);
    config.extraMode = doubleEngine ? ExtraMode.Double_Engine : ExtraMode.Normal;
    config.alwaysGtp = false;
    return config;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static String buildSuccessResponseLine(String command) {
    String trimmed = command.trim();
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace <= 0) {
      return "=";
    }
    String firstToken = trimmed.substring(0, firstSpace);
    for (int index = 0; index < firstToken.length(); index++) {
      if (!Character.isDigit(firstToken.charAt(index))) {
        return "=";
      }
    }
    return "=" + firstToken;
  }

  private static void sendCommandWithResponse(Leelaz engine, String command, Runnable onResponse)
      throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "sendCommand", String.class, Runnable.class, boolean.class, boolean.class);
    method.setAccessible(true);
    method.invoke(engine, command, onResponse, false, false);
  }

  private static final class RecordingOutputStream extends OutputStream {
    private String failCommandPrefix;
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    private RecordingOutputStream(String failCommandPrefix) {
      this.failCommandPrefix = failCommandPrefix;
    }

    @Override
    public void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.isEmpty()) {
        return;
      }
      commands.add(command);
      if (matchesCommandPrefix(command, failCommandPrefix)) {
        throw new IOException("simulated flush failure: " + command);
      }
    }

    private void failOnCommand(String commandPrefix) {
      this.failCommandPrefix = commandPrefix;
    }

    private List<String> commands() {
      return commands;
    }
  }

  private static final class ScriptedResponseOutputStream extends OutputStream {
    private final Leelaz engine;
    private final String failCommandPrefix;
    private final String clearBoardResponse;
    private final String loadSgfResponse;
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    private ScriptedResponseOutputStream(
        Leelaz engine,
        String failCommandPrefix,
        String clearBoardResponse,
        String loadSgfResponse) {
      this.engine = engine;
      this.failCommandPrefix = failCommandPrefix;
      this.clearBoardResponse = clearBoardResponse;
      this.loadSgfResponse = loadSgfResponse;
    }

    @Override
    public void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.isEmpty()) {
        return;
      }
      commands.add(command);
      if (matchesCommandPrefix(command, failCommandPrefix)) {
        throw new IOException("simulated flush failure: " + command);
      }
      String responseLine = responseFor(command);
      if (responseLine == null) {
        return;
      }
      try {
        invokeResponseHandlerForLine(engine, responseLine);
      } catch (Exception ex) {
        throw new IOException("failed to simulate loadsgf response: " + responseLine, ex);
      }
    }

    private String responseFor(String command) {
      if ("clear_board".equals(command)) {
        return clearBoardResponse;
      }
      if (isLoadSgfCommand(command)) {
        if (AUTO_ID_RESPONSE.equals(loadSgfResponse)) {
          return buildSuccessResponseLine(command);
        }
        return loadSgfResponse;
      }
      return null;
    }

    private List<String> commands() {
      return commands;
    }
  }

  private static final class TailReplayAwareOutputStream extends OutputStream {
    private final Leelaz engine;
    private final StringBuilder currentCommand = new StringBuilder();
    private Path loadSgfPath;
    private boolean tempFileExistedDuringReplay;

    private TailReplayAwareOutputStream(Leelaz engine) {
      this.engine = engine;
    }

    @Override
    public void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.isEmpty()) {
        return;
      }
      if (isLoadSgfCommand(command)) {
        loadSgfPath = extractLoadSgfPath(command);
        try {
          invokeResponseHandlerForLine(engine, buildSuccessResponseLine(command));
        } catch (Exception ex) {
          throw new IOException("failed to simulate loadsgf response: " + command, ex);
        }
        return;
      }
      if (command.startsWith("play ") && loadSgfPath != null) {
        tempFileExistedDuringReplay = tempFileExistedDuringReplay || Files.exists(loadSgfPath);
      }
    }

    private boolean tempFileExistedDuringReplay() {
      return tempFileExistedDuringReplay;
    }

    private Path loadSgfPath() {
      return loadSgfPath;
    }
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {
      super();
    }

    @Override
    public void refresh() {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addLine(String line) {}
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final boolean previousEngineGameFlag;
    private final int previousBoardWidth;
    private final int previousBoardHeight;

    private TestHarness() {
      this.previousConfig = Lizzie.config;
      this.previousBoard = Lizzie.board;
      this.previousFrame = Lizzie.frame;
      this.previousGtpConsole = Lizzie.gtpConsole;
      this.previousLeelaz = Lizzie.leelaz;
      this.previousLeelaz2 = Lizzie.leelaz2;
      this.previousEngineGameFlag = EngineManager.isEngineGame;
      this.previousBoardWidth = Board.boardWidth;
      this.previousBoardHeight = Board.boardHeight;
    }

    private static TestHarness open(boolean doubleEngine) throws Exception {
      TestHarness harness = new TestHarness();
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Lizzie.config = minimalConfig(doubleEngine);
      Lizzie.board = allocate(Board.class);
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      EngineManager.isEngineGame = false;
      return harness;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
      EngineManager.isEngineGame = previousEngineGameFlag;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
