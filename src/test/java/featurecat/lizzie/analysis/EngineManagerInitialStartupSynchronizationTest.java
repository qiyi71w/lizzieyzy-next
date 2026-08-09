package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the initial engine startup restore barrier (Issue #223): frozen immutable
 * startup route, catch-up convergence on navigation, linearized barrier end, narrow live-board
 * admission and fail-closed failure semantics.
 */
class EngineManagerInitialStartupSynchronizationTest {

  @Test
  void noNavigationExecutesFrozenRouteOnceThenMarksReady() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      AtomicInteger barrierRounds = new AtomicInteger();
      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      startup.beforeRestore =
          () -> {
            barrierRounds.incrementAndGet();
            // Ordinary live-board play must be dropped while the barrier is active...
            engine.sendCommand("play B Q4");
            // ...but startup handshake commands keep flowing through the same queue.
            engine.sendCommand("name");
          };
      int readyBaseline = env.readyTransitions.get();

      runStartupInThread(startup, engine);

      assertEquals(1, barrierRounds.get(), "no navigation must not produce catch-up rounds");
      assertEquals(1, env.clearBoardCount(engine), "frozen root replay executes once");
      assertFalse(engine.commands.contains("play B Q4"), "live-board play must be dropped");
      assertTrue(engine.commands.contains("name"), "startup handshake command must flow");
      assertEquals(1, engine.analyzeCount(), "one analysis starts after the stable restore point");
      assertEquals(0, engine.analyzePosition(), "analysis must start from the restored position");
      assertEquals(1, engine.ponderCount, "ponder must run exactly once");
      assertEquals(1, engine.responseFreshenedCount, "response freshening must run exactly once");
      assertEquals(1, env.readyTransitions.get() - readyBaseline, "markEngineReady exactly once");
      assertFalse(
          engine.isInitialBoardSynchronizationActive(),
          "barrier must be ended after the stable restore point");
      assertTrue(engine.isLoaded, "engine must stay available on success");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void liveUpdateAdmissionIsLinearizedWithBarrierStart() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      BlockingStartupConfig config = allocate(BlockingStartupConfig.class);
      config.doubleEngineQueryEntered = new CountDownLatch(1);
      config.allowDoubleEngineQuery = new CountDownLatch(1);
      Lizzie.config = config;
      AtomicReference<Throwable> sendFailure = new AtomicReference<>();
      Thread liveUpdateThread =
          new Thread(
              () -> {
                try {
                  engine.sendCommand("play B Q4");
                } catch (Throwable failure) {
                  sendFailure.set(failure);
                }
              },
              "issue-223-live-update-admission-race");
      liveUpdateThread.start();

      assertTrue(
          config.doubleEngineQueryEntered.await(2, TimeUnit.SECONDS),
          "the live update must pass its precheck before the barrier starts");
      engine.beginInitialBoardSynchronization();
      config.allowDoubleEngineQuery.countDown();
      liveUpdateThread.join(2_000L);

      assertFalse(liveUpdateThread.isAlive(), "the live update admission race must settle");
      assertNull(sendFailure.get(), "the live update must be rejected without throwing");
      assertFalse(
          engine.commands.contains("play B Q4"),
          "a live update crossing barrier start must not enter the command queue");
      engine.endInitialBoardSynchronization();
    }
  }

  @Test
  void lifecycleReservationIsReleasedBeforeReadyPublication() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicBoolean observeReady = new AtomicBoolean(false);
      AtomicReference<Boolean> reservationReleasedAtReady = new AtomicReference<>();
      java.util.function.Consumer<EngineStartupStatus.Snapshot> listener =
          snapshot -> {
            if (!observeReady.get() || snapshot.state != EngineStartupStatus.State.READY) {
              return;
            }
            Leelaz.ExclusiveGtpLifecycleReservation reservation =
                engine.beginExclusiveGtpLifecycleReservation(new Object());
            reservationReleasedAtReady.set(reservation != null);
            if (reservation != null) {
              reservation.close();
            }
          };
      Lizzie.engineStartupStatus.addListener(listener);
      try {
        observeReady.set(true);
        runStartupInThread(startup, engine);
      } finally {
        Lizzie.engineStartupStatus.removeListener(listener);
      }

      assertEquals(
          Boolean.TRUE,
          reservationReleasedAtReady.get(),
          "READY observers must see the lifecycle reservation already released");
    }
  }

  @Test
  void navigationDuringStartupConvergesWithCatchUpRoute() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(2);
      history.toStart(); // capture matches "initial capture at move 0" from the spec
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              // 0 -> 1 -> 2 while the engine is starting
              assertTrue(board.nextMove(false));
              assertTrue(board.nextMove(false));
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(2, env.clearBoardCount(engine), "frozen route plus one catch-up route");
      assertEquals(2, engine.analyzePosition(), "engine must converge on the final node");
      assertEquals(2, engine.playsAfterLastClear().size());
      assertEquals(
          List.of(play("B", 4, 3), play("W", 5, 3)),
          engine.playsAfterLastClear(),
          "catch-up replay must rebuild the full position from the root");
      assertEquals(1, engine.ponderCount, "analysis must start only at the stable point");
      assertSame(board.getHistory().getCurrentHistoryNode(), board.getHistory().getEnd());
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void delayedFakeGtpConvergesToMoveFiveBeforeAnalysis() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      env.publish(engine, board);

      CountDownLatch firstClearBoardReceived = new CountDownLatch(1);
      CountDownLatch navigationCompleted = new CountDownLatch(1);
      AtomicBoolean delayFirstClearBoard = new AtomicBoolean(true);
      AtomicReference<Throwable> navigationFailure = new AtomicReference<>();
      engine.beforeCommand =
          command -> {
            if (command.equals("clear_board") && delayFirstClearBoard.compareAndSet(true, false)) {
              firstClearBoardReceived.countDown();
              if (!navigationCompleted.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("navigation did not complete while GTP was delayed");
              }
            }
          };

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      engine.startEngine(0);
      Thread navigationThread =
          new Thread(
              () -> {
                try {
                  if (!firstClearBoardReceived.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("fake GTP did not receive the frozen clear_board");
                  }
                  for (int move = 1; move <= 5; move++) {
                    assertTrue(board.nextMove(false), "navigation must reach move " + move);
                  }
                } catch (Throwable failure) {
                  navigationFailure.set(failure);
                } finally {
                  navigationCompleted.countDown();
                }
              },
              "issue-223-delayed-gtp-navigation");
      navigationThread.start();

      runStartupInThread(startup, engine);
      navigationThread.join(2_000L);

      assertFalse(navigationThread.isAlive(), "navigation thread must settle");
      assertNull(navigationFailure.get(), "navigation during delayed GTP must succeed");
      assertEquals(5, board.getHistory().getMoveNumber(), "currentMove");
      assertEquals(5, engine.enginePosition.get(), "engineMove");
      assertEquals(5, engine.analyzePosition(), "analyzeAtMove");
      assertEquals(2, engine.clearBoardCount.get(), "frozen route plus one catch-up route");
      assertEngineMatchesBoard(engine, board, 19, 19);
    }
  }

  @Test
  void foregroundActivationConvergesAfterDelayedReadyAndOrdinaryMoveNavigation() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.delayReadyAfterStart = true;
      BoardHistoryList history = emptyRootHistory(9);
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;
      env.expectedReadyEngineIndex = 0;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS), "engine startup must begin");

      for (int move = 1; move <= 8; move++) {
        assertTrue(board.nextMove(false), "navigation must reach move " + move);
      }
      assertTrue(board.nextMove(false), "navigation must reach move 9");
      assertTrue(board.previousMove(false), "navigation must return to move 8");
      engine.publishReady();

      assertTrue(
          engine.analysisStarted.await(2, TimeUnit.SECONDS),
          "analysis must start after the production activation converges");
      assertTrue(
          manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS),
          "production synchronization worker must complete before assertions");
      assertTrue(
          env.readyObservedCommittedOwner.get(),
          "READY observers must see the committed foreground owner");
      assertEquals(8, board.getHistory().getMoveNumber(), "history cursor");
      for (int move = 1; move <= 8; move++) {
        assertFalse(
            board.getHistory().getData().stones[Board.getIndex(3 + move, 3)] == Stone.EMPTY,
            "ordinary move prefix stone " + move + " must remain present");
      }
      assertEquals(8, engine.enginePosition.get(), "engine position");
      assertEquals(8, engine.analyzePosition(), "analysis position");
      assertEquals(1, engine.ponderCount, "analysis starts once at the stable position");
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void foregroundActivationCaptureFailureIsNotReportedAsLeaseConflict() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.reservationFailure = new IllegalStateException("controlled capture failure");
      BoardHistoryList history = emptyRootHistory(1);
      history.toStart();
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;

      assertFalse(manager.switchEngineIfAvailable(0, true));

      assertEquals(1, manager.synchronizationFailureCount);
      assertEquals(0, manager.leaseConflictCount);
      assertFalse(engine.isLoaded);
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void foregroundActivationReadyTimeoutPreservesEmptyStateAndCanRetry() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz timedOutEngine = new StartupSyncLeelaz();
      timedOutEngine.delayReadyAfterStart = true;
      StartupSyncLeelaz recoveryEngine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(3);
      history.toStart();
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      Lizzie.config.fastChange = true;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(List.of(timedOutEngine, recoveryEngine)));
      manager.timeoutMillis = 25L;
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(timedOutEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertTrue(EngineManager.isEmpty, "failed first activation must remain an empty owner");
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(timedOutEngine.isLoaded);
      assertFalse(timedOutEngine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(timedOutEngine);

      assertTrue(manager.switchEngineIfAvailable(1, true), "a later activation must retry startup");
      assertTrue(recoveryEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(recoveryEngine.analysisStarted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertFalse(EngineManager.isEmpty);
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(0, recoveryEngine.enginePosition.get());
      assertFalse(recoveryEngine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(recoveryEngine);
    }
  }

  @Test
  void foregroundActivationInitializationFailureRollsBackCommittedOwner() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.ponderFailure = new IllegalStateException("controlled initialization failure");
      BoardHistoryList history = emptyRootHistory(2);
      history.toStart();
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertEquals(1, manager.synchronizationFailureCount);
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(engine.isLoaded);
      assertEquals(1, engine.ponderCount);
      assertEquals(0, engine.analyzeCount());
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void navigationForwardThenBackConvergesToFinalNode() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(3);
      history.toStart();
      BoardHistoryNode expectedFinalNode =
          history.getCurrentHistoryNode().next().get().next().get(); // node 2
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            int round = rounds.getAndIncrement();
            if (round == 0) {
              assertTrue(board.nextMove(false));
              assertTrue(board.nextMove(false));
              assertTrue(board.nextMove(false)); // 0 -> 3
            } else if (round == 1) {
              assertTrue(board.previousMove(false)); // 3 -> 2
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(3, engine.clearBoardCount.get(), "frozen route plus two catch-up routes");
      assertEquals(2, engine.analyzePosition(), "engine must converge on the final node (2)");
      assertEquals(1, engine.ponderCount);
      assertSame(expectedFinalNode, board.getHistory().getCurrentHistoryNode());
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void snapshotRouteBackwardNavigationCatchUpRestoresSnapshotWithoutTail() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      BoardHistoryList history = snapshotHistoryWithTail(true);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              assertTrue(board.previousMove(false)); // tail -> move node
              assertTrue(board.previousMove(false)); // move node -> snapshot root
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(2, engine.loadSgfCount.get(), "frozen exact route plus one catch-up route");
      assertEquals(
          List.of("play B " + Board.convertCoordinatesToName(5, 5), "play W pass"),
          engine.tailPlays(),
          "frozen route replays the SNAPSHOT MOVE/PASS tail");
      assertEquals(
          0,
          engine.playsAfterLastLoadSgf().size(),
          "catch-up route lands on the snapshot anchor without a tail");
      assertEquals(2, engine.analyzePosition(), "analysis must start from the snapshot position");
      assertEquals(1, engine.ponderCount);
      assertSame(history.getCurrentHistoryNode(), history.getStart());
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void branchNavigationDuringStartupConverges() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(2); // 0 -> 1 -> 2
      history.toStart();
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      BoardHistoryNode node1 = history.getCurrentHistoryNode().next().get();
      node1.addOrGoto(moveNode(node1.getData(), 6, 3, Stone.WHITE, true, 2), true, false, false);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              assertTrue(board.nextMove(false)); // 0 -> 1
              assertTrue(board.nextVariation(1)); // 1 -> branch child
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(2, engine.analyzePosition(), "engine must converge on the branch child");
      assertEquals(
          List.of(play("B", 4, 3), play("W", 6, 3)),
          engine.playsAfterLastClear(),
          "catch-up replay must select the branch child, not the same-number main-line node");
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertEquals(1, engine.ponderCount);
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void boardSizeReopenDefersEngineResizeWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      AtomicReference<Double> postReopenKomi = new AtomicReference<>();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              board.reopen(9, 9); // board-size UI / SGF-load path during startup
              postReopenKomi.set(board.getHistory().getGameInfo().getKomi());
              assertFalse(
                  engine.commands.stream().anyMatch(command -> command.startsWith("boardsize")),
                  "ordinary board-size resync must not reach the engine while the barrier is active");
            }
          };

      runStartupInThread(startup, engine);

      List<String> boardSizeCommands =
          engine.commands.stream().filter(command -> command.startsWith("boardsize")).toList();
      assertEquals(
          List.of("boardsize 9"),
          boardSizeCommands,
          "only the restore-owned reconcile may resize the engine during the barrier");
      assertEquals(9, engine.width, "engine width cache must reconcile to the captured frame");
      assertEquals(9, engine.height, "engine height cache must reconcile to the captured frame");
      assertEquals(
          postReopenKomi.get(),
          board.getHistory().getGameInfo().getKomi(),
          "the size reconcile must not overwrite the resized board's komi");
      assertEquals(
          postReopenKomi.get().floatValue(),
          engine.komi,
          0.001f,
          "engine komi must converge to the resized board's game komi");
      assertEquals(0, engine.analyzePosition(), "converged on the resized empty board");
      assertEquals(1, engine.ponderCount);
      assertEquals(9, Board.boardWidth, "board resize itself must remain effective");
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void ordinaryClearSkipsEngineCommandsWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(0);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              int before = engine.commands.size();
              board.clear(false); // File>New style board overwrite during startup
              assertEquals(
                  before,
                  engine.commands.size(),
                  "ordinary clear must not forward komi/clear commands during the barrier");
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(1, engine.ponderCount);
      assertEquals(
          (float) board.getHistory().getGameInfo().getKomi(),
          engine.komi,
          0.001f,
          "engine komi must converge to the cleared game komi");
      assertEquals(0, engine.analyzePosition(), "converged on the cleared empty board");
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void clearDuringFinalHandoffIsCaughtUpBeforeReady() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger handoffs = new AtomicInteger();
      AtomicReference<Boolean> barrierActiveAfterRelease = new AtomicReference<>();
      startup.afterReservationRelease =
          () -> {
            if (handoffs.getAndIncrement() == 0) {
              barrierActiveAfterRelease.set(engine.isInitialBoardSynchronizationActive());
              board.clear(false);
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(
          Boolean.TRUE,
          barrierActiveAfterRelease.get(),
          "the initial-sync barrier must stay active until the post-release frame judgment");
      assertEquals(
          2,
          engine.clearBoardCount.get(),
          "the board mutation in the final handoff gap must force a catch-up route");
      assertEquals(0, engine.enginePosition.get());
      assertTrue(engine.engineStones.isEmpty());
      assertEquals(0, engine.analyzePosition(), "analysis must start on the cleared board");
      assertEquals(0, board.getHistory().getData().moveNumber);
      assertEquals(1, engine.ponderCount);
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void catchUpReservationReacquisitionFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicReference<Leelaz.ExclusiveGtpLifecycleReservation> conflictingReservation =
          new AtomicReference<>();
      AtomicInteger releases = new AtomicInteger();
      startup.afterReservationRelease =
          () -> {
            if (releases.getAndIncrement() == 0) {
              board.clear(false);
              conflictingReservation.set(
                  engine.beginExclusiveGtpLifecycleReservation(new Object()));
              assertNotNull(
                  conflictingReservation.get(),
                  "fixture must occupy the lifecycle before catch-up reacquisition");
            }
          };
      int readyBaseline = env.readyTransitions.get();

      RuntimeException failure = runStartupExpectingFailure(startup, engine);
      Leelaz.ExclusiveGtpLifecycleReservation blocker = conflictingReservation.getAndSet(null);
      if (blocker != null) {
        blocker.close();
      }

      assertNotNull(failure, "rejected catch-up reservation must fail closed");
      assertFalse(engine.isLoaded);
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertEquals(0, engine.ponderCount);
      assertEquals(0, engine.analyzeCount());
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void ordinaryBoardResyncIsSkippedWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              int before = engine.commands.size();
              board.resendMoveToEngine(engine, false); // ordinary board-following resync
              board.getHistory().getCurrentHistoryNode().clearAndSyncBoard(false);
              assertFalse(
                  board.resendCurrentPositionToPrimaryEngine(),
                  "live primary resync must be refused while the barrier is active");
              assertFalse(
                  board.trySyncCurrentPositionToPrimaryEngineIncrementally(
                      board.getHistory().getData(), 19, 19),
                  "incremental primary resync must be refused while the barrier is active");
              assertEquals(
                  before,
                  engine.commands.size(),
                  "ordinary board-following resync must not interleave with the barrier");
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(1, engine.loadSgfCount.get(), "only the frozen route executes");
      assertEquals(1, engine.ponderCount);
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void initialRestoreFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      engine.failLoadSgfAt = 1;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      int readyBaseline = env.readyTransitions.get();

      RuntimeException failure = runStartupExpectingFailure(startup, engine);

      assertNotNull(failure, "initial restore failure must surface");
      assertFalse(engine.isLoaded, "target engine must be marked unavailable");
      assertFalse(
          engine.isInitialBoardSynchronizationActive(),
          "initial synchronization state must end on failure");
      assertEquals(0, engine.ponderCount, "no analysis after failure");
      assertEquals(0, engine.analyzeCount(), "no analysis command after failure");
      assertEquals(0, env.readyTransitions.get() - readyBaseline, "never marked ready");
      assertEquals(1, engine.loadSgfCount.get(), "only the failed loadsgf attempt");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void failureCleanupKeepsBarrierActiveUntilReservationIsReleased() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      startup.beforeRestore =
          () -> {
            throw new IllegalStateException("controlled startup failure");
          };
      Object arbitrationLock = engineArbitrationLock(engine);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread cleanupThread =
          new Thread(
              () -> {
                try {
                  startup.run();
                } catch (Throwable thrown) {
                  failure.set(thrown);
                } finally {
                  startup.close();
                }
              },
              "initial-startup-failure-cleanup-test");

      synchronized (arbitrationLock) {
        cleanupThread.start();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (cleanupThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
          Thread.sleep(10L);
        }
        assertEquals(
            Thread.State.BLOCKED,
            cleanupThread.getState(),
            "failure cleanup must reach the blocked reservation-release phase");
        assertTrue(
            engine.isInitialBoardSynchronizationActive(),
            "the barrier must remain active while failure cleanup still holds the reservation");
      }

      cleanupThread.join(2_000L);
      assertFalse(cleanupThread.isAlive(), "failure cleanup must settle after reservation release");
      assertNotNull(failure.get(), "controlled startup failure must surface");
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void cleanupEndsBarrierWhenReservationReleaseFails() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      setLeelazField(engine, "exclusiveGtpLifecycleQueueGate", true);
      sendFailOnErrorCommand(engine, "name");
      engine.installCommandOutputForTest(new FailingOutputStream());

      RuntimeException failure = assertThrows(RuntimeException.class, startup::close);

      assertTrue(
          failure.getMessage().contains("Failed to send GTP command"),
          "reservation release failure must propagate");
      assertFalse(
          engine.isInitialBoardSynchronizationActive(),
          "cleanup must end the startup barrier even when reservation release fails");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void captureFailureRetainsPrimaryFailureWhenReservationCleanupAlsoFails() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      RuntimeException captureFailure = new IllegalStateException("controlled route capture failure");
      CaptureFailureBoard board = allocate(CaptureFailureBoard.class);
      board.startStonelist = new ArrayList<>();
      board.movelistwr = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(emptyRootHistory(0));
      board.captureFailure = captureFailure;
      board.failOnHistoryRead = true;
      env.publish(engine, board);
      engine.startEngine(0);

      setLeelazField(engine, "exclusiveGtpLifecycleQueueGate", true);
      sendFailOnErrorCommand(engine, "name");
      engine.installCommandOutputForTest(new FailingOutputStream());

      RuntimeException failure =
          assertThrows(
              RuntimeException.class,
              () -> EngineManager.InitialEngineStartupSynchronization.capture(engine, board, false));

      assertSame(captureFailure, failure, "capture failure must remain the primary exception");
      assertEquals(1, failure.getSuppressed().length, "cleanup failure must be attached once");
      assertTrue(
          failure.getSuppressed()[0].getMessage().contains("Failed to send GTP command"),
          "reservation cleanup failure must remain diagnosable");
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void catchUpRestoreFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      engine.failLoadSgfAt = 2;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              assertTrue(board.previousMove(false));
            }
          };
      int readyBaseline = env.readyTransitions.get();

      RuntimeException failure = runStartupExpectingFailure(startup, engine);

      assertNotNull(failure, "catch-up restore failure must surface");
      assertFalse(engine.isLoaded, "target engine must be marked unavailable");
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertEquals(0, engine.ponderCount);
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertEquals(2, engine.loadSgfCount.get(), "frozen route then failed catch-up route");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void continuousNavigationRequiresMultipleCatchUpsBeforeReady() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(2);
      history.toStart();
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            int round = rounds.getAndIncrement();
            if (round == 0) {
              assertTrue(board.nextMove(false)); // 0 -> 1
            } else if (round == 1) {
              assertTrue(board.nextMove(false)); // 1 -> 2
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(3, engine.clearBoardCount.get(), "frozen route plus two catch-up rounds");
      assertEquals(2, engine.analyzePosition(), "final convergence at node 2");
      assertEquals(1, engine.ponderCount, "analysis must wait for the final stable point");
      assertFalse(engine.isInitialBoardSynchronizationActive());
    }
  }

  @Test
  void markEngineReadyIsDeferredWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger readyDuringBarrier = new AtomicInteger(-1);
      startup.beforeRestore =
          () -> {
            try {
              invokeCloseBundledStartupDialog(engine);
            } catch (Exception ex) {
              throw new AssertionError("closeBundledStartupDialog invocation failed", ex);
            }
            readyDuringBarrier.set(env.readyTransitions.get());
          };
      int readyBaseline = env.readyTransitions.get();

      runStartupInThread(startup, engine);

      assertEquals(
          readyBaseline,
          readyDuringBarrier.get(),
          "markEngineReady must be deferred while the initial synchronization barrier is active");
      assertEquals(
          1,
          env.readyTransitions.get() - readyBaseline,
          "marked ready exactly once at the stable point");
    }
  }

  @Test
  void conflictingLifecycleWorkIsRejectedWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      try {
        assertNull(
            engine.beginExclusiveGtpLifecycleReservation(new Object()),
            "a different lifecycle owner must not grab the engine during initial synchronization");
      } finally {
        startup.close();
      }
      assertFalse(engine.isInitialBoardSynchronizationActive());
      assertLifecycleReservationReleased(engine);
    }
  }

  private static EngineManager.InitialEngineStartupSynchronization captureStartup(
      StartupSyncLeelaz engine, Board board) {
    return EngineManager.InitialEngineStartupSynchronization.capture(engine, board, false);
  }

  private static void runStartupInThread(
      EngineManager.InitialEngineStartupSynchronization startup, StartupSyncLeelaz engine)
      throws Exception {
    RuntimeException failure = runStartupExpectingFailure(startup, engine);
    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException runStartupExpectingFailure(
      EngineManager.InitialEngineStartupSynchronization startup, StartupSyncLeelaz engine)
      throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread barrierThread =
        new Thread(
            () -> {
              try {
                startup.run();
              } catch (Throwable thrown) {
                // Mirrors the production startup thread: fail closed, then release the barrier.
                engine.isLoaded = false;
                failure.set(thrown);
              } finally {
                startup.close();
              }
            },
            "initial-startup-barrier-test");
    barrierThread.start();
    barrierThread.join(15_000L);
    assertFalse(barrierThread.isAlive(), "startup barrier did not settle within timeout");
    Throwable thrown = failure.get();
    if (thrown instanceof RuntimeException) {
      return (RuntimeException) thrown;
    }
    if (thrown != null) {
      throw new AssertionError("startup barrier failed unexpectedly", thrown);
    }
    return null;
  }

  private static void assertLifecycleReservationReleased(StartupSyncLeelaz engine) {
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        engine.beginExclusiveGtpLifecycleReservation();
    assertNotNull(reservation, "lifecycle reservation must be released after the barrier");
    reservation.close();
  }

  private static void invokeCloseBundledStartupDialog(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("closeBundledStartupDialog");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static Object engineArbitrationLock(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("engineArbitrationLock");
    field.setAccessible(true);
    return field.get(engine);
  }

  private static void setLeelazField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static void sendFailOnErrorCommand(Leelaz engine, String command) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "sendCommand", String.class, Runnable.class, boolean.class, boolean.class);
    method.setAccessible(true);
    method.invoke(engine, command, null, true, false);
  }

  private static final class FailingOutputStream extends OutputStream {
    @Override
    public void write(int value) {}

    @Override
    public void flush() throws IOException {
      throw new IOException("controlled startup send failure");
    }
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(StartupTestBoard.class);
    board.startStonelist = new ArrayList<>();
    board.movelistwr = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static BoardHistoryList emptyRootHistory(int moveCount) {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(19, 19));
    history.getGameInfo().setKomiNoMenu(6.5);
    for (int move = 1; move <= moveCount; move++) {
      int x = 3 + move;
      int y = 3;
      Stone color = move % 2 == 1 ? Stone.BLACK : Stone.WHITE;
      history.add(moveNode(history.getData(), x, y, color, color != Stone.BLACK, move));
    }
    return history;
  }

  private static BoardHistoryList snapshotHistoryWithTail(boolean withPass) {
    BoardData root = snapshotRoot();
    BoardHistoryList history = new BoardHistoryList(root);
    history.getGameInfo().setKomiNoMenu(6.5);
    Stone[] tailStones = root.stones.clone();
    tailStones[Board.getIndex(5, 5)] = Stone.BLACK;
    history.add(
        BoardData.move(
            tailStones,
            new int[] {5, 5},
            Stone.BLACK,
            false,
            new Zobrist(77L),
            4,
            new int[19 * 19],
            0,
            0,
            50,
            0));
    if (withPass) {
      Stone[] passStones = tailStones.clone();
      history.add(
          BoardData.pass(
              passStones, Stone.WHITE, true, new Zobrist(88L), 5, new int[19 * 19], 0, 0, 50, 0));
    }
    return history;
  }

  private static BoardData snapshotRoot() {
    Stone[] stones = new Stone[19 * 19];
    Arrays.fill(stones, Stone.EMPTY);
    stones[Board.getIndex(3, 3)] = Stone.BLACK;
    stones[Board.getIndex(4, 4)] = Stone.WHITE;
    int[] moveNumberList = new int[19 * 19];
    moveNumberList[Board.getIndex(3, 3)] = 1;
    moveNumberList[Board.getIndex(4, 4)] = 2;
    return BoardData.snapshot(
        stones,
        Optional.of(new int[] {4, 4}),
        Stone.WHITE,
        false,
        new Zobrist(42L),
        3,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static BoardData moveNode(
      BoardData parent, int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = parent.stones.clone();
    stones[Board.getIndex(x, y)] = color;
    int[] moveNumberList = parent.moveNumberList.clone();
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    Zobrist zobrist = parent.zobrist.clone();
    zobrist.toggleStone(x, y, color);
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist,
        moveNumber,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static void assertEngineMatchesBoard(
      StartupSyncLeelaz engine, Board board, int expectedWidth, int expectedHeight) {
    BoardData application = board.getHistory().getData();
    assertEquals(application.blackToPlay, engine.engineBlackToPlay, "side-to-play must match");
    assertEquals(expectedWidth, engine.engineBoardWidth, "board width must match");
    assertEquals(expectedHeight, engine.engineBoardHeight, "board height must match");
    assertEquals(
        board.getHistory().getGameInfo().getKomi(), engine.engineKomi, 0.0001, "komi must match");
    for (int x = 0; x < expectedWidth; x++) {
      for (int y = 0; y < expectedHeight; y++) {
        assertEquals(
            application.stones[Board.getIndex(x, y)],
            engine.stoneAt(x, y),
            "stone mismatch at " + x + "," + y);
      }
    }
  }

  private static String play(String color, int x, int y) {
    return "play " + color + " " + Board.convertCoordinatesToName(x, y);
  }

  @FunctionalInterface
  private interface CommandDelay {
    void beforeCommand(String command) throws Exception;
  }

  private static final class StartupSyncLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();
    private final AtomicInteger enginePosition = new AtomicInteger();
    private final AtomicInteger analyzePosition = new AtomicInteger(-1);
    private final AtomicInteger analyzeCount = new AtomicInteger();
    private final AtomicInteger loadSgfCount = new AtomicInteger();
    private final AtomicInteger clearBoardCount = new AtomicInteger();
    private final Map<String, Stone> engineStones = new HashMap<>();
    private final CountDownLatch startCompleted = new CountDownLatch(1);
    private final CountDownLatch analysisStarted = new CountDownLatch(1);
    private CommandDelay beforeCommand;
    private int snapshotBaseMove;
    private int failLoadSgfAt = Integer.MAX_VALUE;
    private int ponderCount;
    private int responseFreshenedCount;
    private int engineBoardWidth = 19;
    private int engineBoardHeight = 19;
    private double engineKomi = Double.NaN;
    private boolean engineBlackToPlay = true;
    private boolean delayReadyAfterStart;
    private RuntimeException reservationFailure;
    private RuntimeException ponderFailure;

    private StartupSyncLeelaz() throws Exception {
      super("");
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            if (beforeCommand != null) {
              beforeCommand.beforeCommand(command);
            }
            commands.add(command);
            if (command.startsWith("play ")) {
              enginePosition.incrementAndGet();
              String[] parts = command.split("\\s+");
              if (!"pass".equalsIgnoreCase(parts[2])) {
                engineStones.put(
                    parts[2].toUpperCase(Locale.ROOT),
                    "B".equalsIgnoreCase(parts[1]) ? Stone.BLACK : Stone.WHITE);
              }
              engineBlackToPlay = "W".equalsIgnoreCase(parts[1]);
            } else if (command.equals("clear_board")) {
              clearBoardCount.incrementAndGet();
              enginePosition.set(0);
              engineStones.clear();
              engineBlackToPlay = true;
            } else if (command.startsWith("boardsize ")) {
              engineBoardWidth = Integer.parseInt(command.substring("boardsize ".length()).trim());
              engineBoardHeight = engineBoardWidth;
              engineStones.clear();
              engineBlackToPlay = true;
            } else if (command.startsWith("rectangular_boardsize ")) {
              String[] parts = command.split("\\s+");
              engineBoardWidth = Integer.parseInt(parts[1]);
              engineBoardHeight = Integer.parseInt(parts[2]);
              engineStones.clear();
              engineBlackToPlay = true;
            } else if (command.startsWith("komi ")) {
              engineKomi = Double.parseDouble(command.substring("komi ".length()).trim());
            } else if (command.startsWith("loadsgf ")) {
              int count = loadSgfCount.incrementAndGet();
              enginePosition.set(snapshotBaseMove);
              if (count >= failLoadSgfAt) {
                return ExactSnapshotRestoreProtocolFixture.Response.error(
                    "controlled startup restore failure");
              }
            } else if (command.startsWith("lz-analyze") || command.startsWith("kata-analyze")) {
              analyzeCount.incrementAndGet();
              analyzePosition.set(enginePosition.get());
              analysisStarted.countDown();
            }
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
    }

    @Override
    ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
      if (reservationFailure != null) {
        throw reservationFailure;
      }
      return super.beginExclusiveGtpLifecycleReservation(owner);
    }

    @Override
    public void startEngine(int index) {
      started = true;
      isLoaded = !delayReadyAfterStart;
      isCheckingName = delayReadyAfterStart;
      startCompleted.countDown();
    }

    private void publishReady() {
      isLoaded = true;
      isCheckingName = false;
    }

    @Override
    public void ponder(boolean addPlayer, boolean blackToPlay) {
      ponderCount++;
      if (ponderFailure != null) {
        throw ponderFailure;
      }
      if (noAnalyze) {
        return;
      }
      // Exercise the real command gate: while the initial synchronization barrier is active this
      // analyze must be dropped; after the stable restore point it reaches the transport.
      sendCommand("lz-analyze 10");
    }

    @Override
    public void setResponseUpToDate() {
      responseFreshenedCount++;
    }

    @Override
    public void notPondering() {}

    @Override
    public void clearBestMoves() {}

    private int analyzeCount() {
      return analyzeCount.get();
    }

    private int analyzePosition() {
      return analyzePosition.get();
    }

    private Stone stoneAt(int x, int y) {
      return engineStones.getOrDefault(
          Board.convertCoordinatesToName(x, y).toUpperCase(Locale.ROOT), Stone.EMPTY);
    }

    private List<String> tailPlays() {
      List<String> plays = new ArrayList<>();
      boolean tailStarted = false;
      for (String command : commands) {
        if (command.startsWith("loadsgf ")) {
          tailStarted = true;
        } else if (command.equals("clear_board")) {
          tailStarted = false;
        } else if (tailStarted && command.startsWith("play ")) {
          plays.add(command);
        }
      }
      return plays;
    }

    private List<String> playsAfterLastLoadSgf() {
      List<String> plays = new ArrayList<>();
      for (String command : commands) {
        if (command.startsWith("loadsgf ")) {
          plays.clear();
        } else if (command.startsWith("play ")) {
          plays.add(command);
        }
      }
      return plays;
    }

    private List<String> playsAfterLastClear() {
      List<String> plays = new ArrayList<>();
      for (String command : commands) {
        if (command.equals("clear_board")) {
          plays.clear();
        } else if (command.startsWith("play ")) {
          plays.add(command);
        }
      }
      return plays;
    }
  }

  private static final class StartupTestBoard extends Board {
    @Override
    public void clearAfterMove() {
      // Avoid headless UI dependencies during navigation-driven startup tests.
    }
  }

  private static final class CaptureFailureBoard extends Board {
    private RuntimeException captureFailure;
    private boolean failOnHistoryRead;

    @Override
    public void clearAfterMove() {
      // Avoid headless UI dependencies during capture failure setup.
    }

    @Override
    public BoardHistoryList getHistory() {
      if (failOnHistoryRead) {
        throw captureFailure;
      }
      return super.getHistory();
    }
  }

  private static final class BlockingStartupConfig extends Config {
    private CountDownLatch doubleEngineQueryEntered;
    private CountDownLatch allowDoubleEngineQuery;

    private BlockingStartupConfig() throws IOException {
      super();
    }

    @Override
    public boolean isDoubleEngineMode() {
      doubleEngineQueryEntered.countDown();
      try {
        if (!allowDoubleEngineQuery.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("double-engine query was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("double-engine query was interrupted", interrupted);
      }
      return false;
    }
  }

  private static final class ProductionEntryEngineManager extends EngineManager {
    private final AtomicInteger synchronizationCompletionCount = new AtomicInteger();
    private final CountDownLatch firstSynchronizationCompleted = new CountDownLatch(1);
    private final CountDownLatch secondSynchronizationCompleted = new CountDownLatch(1);
    private final CountDownLatch synchronizationFailed = new CountDownLatch(1);
    private int synchronizationFailureCount;
    private int leaseConflictCount;
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(5);

    private ProductionEntryEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void synchronizeEngineWhenReady(
        Leelaz engine, Runnable synchronization, Runnable afterSync) {
      super.synchronizeEngineWhenReady(
          engine,
          synchronization,
          () -> {
            try {
              if (afterSync != null) {
                afterSync.run();
              }
            } finally {
              if (synchronizationCompletionCount.incrementAndGet() == 1) {
                firstSynchronizationCompleted.countDown();
              } else {
                secondSynchronizationCompleted.countDown();
              }
            }
          });
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return timeoutMillis;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      synchronizationFailureCount++;
      synchronizationFailed.countDown();
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      leaseConflictCount++;
    }
  }

  private static final class StartupTestEnvironment implements AutoCloseable {
    private final Leelaz previousPrimary = Lizzie.leelaz;
    private final Leelaz previousSecondary = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    private final Menu previousMenu = LizzieFrame.menu;
    private final EngineManager previousEngineManager = Lizzie.engineManager;
    private final JFontMenu previousEngineMenu = Menu.engineMenu;
    private final BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
    private final Config previousConfig = Lizzie.config;
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final boolean previousEngineGame = EngineManager.isEngineGame;
    private final boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    private final int previousEngineNo = EngineManager.currentEngineNo;
    private final int previousEngineNo2 = EngineManager.currentEngineNo2;
    private final int previousBoardWidth = Board.boardWidth;
    private final int previousBoardHeight = Board.boardHeight;
    private final WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
    private final AtomicInteger readyTransitions = new AtomicInteger();
    private final java.util.function.Consumer<EngineStartupStatus.Snapshot> readyListener;
    private final AtomicBoolean readyObservedCommittedOwner = new AtomicBoolean();
    private volatile Integer expectedReadyEngineIndex;

    private StartupTestEnvironment() throws Exception {
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = allocate(SilentStartupFrame.class);
      LizzieFrame.toolbar = allocate(SilentStartupToolbar.class);
      LizzieFrame.menu = allocate(SilentStartupMenu.class);
      Menu.engineMenu = allocate(SilentStartupEngineMenu.class);
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      Lizzie.leelaz2 = null;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = -1;
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Zobrist.init();
      readyListener =
          snapshot -> {
            if (snapshot.state == EngineStartupStatus.State.READY) {
              readyTransitions.incrementAndGet();
              Integer expectedEngineIndex = expectedReadyEngineIndex;
              if (expectedEngineIndex != null) {
                readyObservedCommittedOwner.set(
                    !EngineManager.isEmpty && EngineManager.currentEngineNo == expectedEngineIndex);
              }
            }
          };
      Lizzie.engineStartupStatus.addListener(readyListener);
    }

    private static StartupTestEnvironment open() throws Exception {
      return new StartupTestEnvironment();
    }

    private void publish(StartupSyncLeelaz engine, Board board) {
      Lizzie.leelaz = engine;
      Lizzie.board = board;
    }

    private int clearBoardCount(StartupSyncLeelaz engine) {
      return engine.clearBoardCount.get();
    }

    @Override
    public void close() throws Exception {
      try {
        SwingUtilities.invokeAndWait(() -> {});
      } catch (Exception ignored) {
        // EDT may be unavailable in headless test runs.
      }
      Lizzie.engineStartupStatus.removeListener(readyListener);
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
      Lizzie.engineManager = previousEngineManager;
      Menu.engineMenu = previousEngineMenu;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      LizzieFrame.winrateGraph = previousWinrateGraph;
    }
  }

  private static final class SilentStartupFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void reSetLoc() {}

    @Override
    public void resetTitle() {}

    @Override
    public void redrawBoardrendererBackground() {}

    @Override
    public void clearKataEstimate() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  private static final class SilentStartupToolbar extends BottomToolbar {
    @Override
    public void reSetButtonLocation() {}
  }

  private static final class SilentStartupMenu extends Menu {
    @Override
    public void showPda(boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void changeicon(int index) {}

    @Override
    public void changeEngineIcon(int index, int mode) {}
  }

  private static final class SilentStartupEngineMenu extends JFontMenu {
    @Override
    public void setText(String text) {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
