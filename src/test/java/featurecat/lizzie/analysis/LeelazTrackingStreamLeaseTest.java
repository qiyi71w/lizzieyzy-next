package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import sun.misc.Unsafe;

class LeelazTrackingStreamLeaseTest {
  private final List<Leelaz> createdEngines = new ArrayList<>();

  @AfterEach
  void closeCreatedExclusiveSessions() throws Exception {
    for (Leelaz engine : createdEngines) {
      closeExclusiveSessionForTest(engine);
    }
    createdEngines.clear();
  }

  @Test
  void unsafeAllocatedEngineLazilyInitializesOneAnalysisInfoMutationLock() throws Exception {
    Leelaz engine = allocate(Leelaz.class);
    Field lockField = Leelaz.class.getDeclaredField("analysisInfoMutationLock");
    lockField.setAccessible(true);
    assertEquals(null, lockField.get(engine));

    engine.clearBestMoves();

    Object initializedLock = lockField.get(engine);
    assertTrue(initializedLock != null);
    assertEquals(List.of(), engine.getBestMoves());
    engine.clearBestMoves();
    assertSame(initializedLock, lockField.get(engine));
  }

  @Test
  void testOutputReplacementKeepsReaderIncarnationAndBindingCoherent() throws Exception {
    Leelaz engine = new Leelaz("");
    Object incarnation = engine.currentEngineIncarnation();
    ByteArrayOutputStream replacement = new ByteArrayOutputStream();

    engine.installCommandOutputForTest(replacement);

    assertSame(incarnation, engine.currentEngineIncarnation());
    Field outerOutputField = Leelaz.class.getDeclaredField("outputStream");
    outerOutputField.setAccessible(true);
    Field bindingOutputField = incarnation.getClass().getDeclaredField("output");
    bindingOutputField.setAccessible(true);
    Field rawOutputField = incarnation.getClass().getDeclaredField("rawOutput");
    rawOutputField.setAccessible(true);
    assertSame(outerOutputField.get(engine), bindingOutputField.get(incarnation));
    assertSame(replacement, rawOutputField.get(incarnation));
  }

  @Test
  void trackingLeaseOwnsOnlyItsStreamAndReleasesWithoutBoardRestoreOrPonder() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableLocalKatago();
    ByteArrayOutputStream output = installOutput(engine);
    List<String> lines = new ArrayList<>();
    AtomicReference<Leelaz.TrackingStreamLease> readyLease = new AtomicReference<>();
    AtomicInteger closed = new AtomicInteger();
    RecordingBoard board = allocate(RecordingBoard.class);
    try {
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      engine.Pondering();

      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          engine.acquireTrackingStreamLease(
              lines::add, readyLease::set, lease -> closed.incrementAndGet());

      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
      assertSame(engine, acquisition.receipt().engine());
      assertTrue(acquisition.receipt().engineIncarnation() > 0L);
      assertTrue(acquisition.receipt().wasPondering());
      assertEquals("800000000 stop\n", output.toString(StandardCharsets.UTF_8));
      assertFalse(dispatch(engine, "info move Q16 visits 2"));
      assertFalse(dispatch(engine, "= old-response"));
      assertFalse(dispatch(engine, ""));

      assertFalse(dispatch(engine, "=800000000"));
      processCommandResponse(engine, "=800000000");
      assertEquals(null, readyLease.get());
      assertTrue(dispatch(engine, ""));
      assertSame(acquisition.lease(), readyLease.get());

      assertTrue(acquisition.lease().send("kata-analyze 10 allow B D4 1 allow W D4 1"));
      assertEquals(
          "800000000 stop\n800000001 kata-analyze 10 allow B D4 1 allow W D4 1\n",
          output.toString(StandardCharsets.UTF_8));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, "info move D4 visits 10"));
      assertEquals(List.of("=800000001", "info move D4 visits 10"), lines);

      engine.sendCommand("version");
      assertFalse(acquisition.lease().release());
      assertEquals(
          "800000000 stop\n"
              + "800000001 kata-analyze 10 allow B D4 1 allow W D4 1\n"
              + "800000002 stop\n",
          output.toString(StandardCharsets.UTF_8));
      assertTrue(dispatch(engine, ""));
      assertTrue(dispatch(engine, "=800000002"));
      assertEquals(0, closed.get());
      assertTrue(dispatch(engine, ""));

      assertEquals(1, closed.get());
      assertFalse(acquisition.lease().isOwned());
      assertEquals(
          "800000000 stop\n"
              + "800000001 kata-analyze 10 allow B D4 1 allow W D4 1\n"
              + "800000002 stop\n"
              + "version\n",
          output.toString(StandardCharsets.UTF_8));
      assertFalse(engine.isPondering());
      assertEquals(0, board.resendCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void trackingLeaseReportsInitialAndFinalFenceFailuresWithoutReopeningTheStream()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    try {
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);

      Leelaz initialFailureEngine = reusableLocalKatago();
      installOutput(initialFailureEngine);
      Lizzie.leelaz = initialFailureEngine;
      AtomicInteger initialClosed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition initialAcquisition =
          initialFailureEngine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> initialClosed.incrementAndGet());

      assertTrue(dispatch(initialFailureEngine, "?800000000 cannot stop"));
      assertEquals(1, initialClosed.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.INITIAL_STOP_ERROR_RESPONSE),
          initialAcquisition.lease().failureReason());
      assertFalse(initialFailureEngine.isLoaded());
      assertFalse(initialAcquisition.lease().isOwned());

      Leelaz finalFailureEngine = reusableLocalKatago();
      ByteArrayOutputStream finalOutput = installOutput(finalFailureEngine);
      Lizzie.leelaz = finalFailureEngine;
      AtomicInteger finalClosed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition finalAcquisition =
          finalFailureEngine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> finalClosed.incrementAndGet());
      processCommandResponse(finalFailureEngine, "=800000000");
      assertTrue(dispatch(finalFailureEngine, ""));
      finalFailureEngine.sendCommand("version");

      assertTrue(dispatch(finalFailureEngine, "?800000001 cannot stop"));
      assertEquals(1, finalClosed.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.FINAL_STOP_ERROR_RESPONSE),
          finalAcquisition.lease().failureReason());
      assertFalse(finalFailureEngine.isLoaded());
      assertFalse(finalAcquisition.lease().isOwned());
      assertFalse(finalOutput.toString(StandardCharsets.UTF_8).contains("version\n"));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void staleIncarnationClosesOnlyTheOldTrackingLease() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableLocalKatago();
    installOutput(engine);
    AtomicInteger closed = new AtomicInteger();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      ByteArrayOutputStream currentOutput = new ByteArrayOutputStream();
      initializeStreams(engine, currentOutput);
      assertFalse(acquisition.lease().send("kata-analyze B 10"));

      assertEquals(1, closed.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.TRANSPORT_CLOSED),
          acquisition.lease().failureReason());
      assertFalse(acquisition.lease().isOwned());
      assertTrue(engine.isLoaded());
      assertTrue(engine.isStarted());
      assertEquals("", currentOutput.toString(StandardCharsets.UTF_8));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void staleRebindAfterActiveWriteClaimClosesTheOldLease() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      List<String> lines = new ArrayList<>();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              lines::add, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      BlockingOutput oldOutput = new BlockingOutput();
      installOutput(state.engine, Leelaz.createCommandOutputStream(oldOutput));
      AtomicReference<Boolean> sendResult = new AtomicReference<>();
      Thread sendThread =
          new Thread(
              () -> sendResult.set(acquisition.lease().send("kata-analyze B 10")),
              "tracking-send-before-rebind");
      sendThread.setDaemon(true);

      sendThread.start();
      assertTrue(oldOutput.writeStarted.await(1, TimeUnit.SECONDS));
      ByteArrayOutputStream currentOutput = new ByteArrayOutputStream();
      initializeStreams(state.engine, "=\n\n", currentOutput);
      try {
        state.engine.isNormalEnd = true;
        invokeRead(state.engine);
        assertEquals(List.of(), lines);
        assertEquals(1, closed.get());
        assertFalse(acquisition.lease().isOwned());
        assertEquals("", currentOutput.toString(StandardCharsets.UTF_8));
      } finally {
        oldOutput.continueWrite.countDown();
      }
      sendThread.join(1000L);

      assertFalse(sendThread.isAlive());
      assertFalse(sendResult.get());
      assertEquals(1, closed.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.TRANSPORT_CLOSED),
          acquisition.lease().failureReason());
      assertFalse(acquisition.lease().isOwned());
      assertTrue(state.engine.isLoaded());
      assertEquals("", currentOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void rebindCallbackFailureCannotLeaveReaderAndCommandGatesClosed() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      CountDownLatch callbackStarted = new CountDownLatch(1);
      CountDownLatch failCallback = new CountDownLatch(1);
      enqueueThrowingTrackedLoadSgf(state.engine, callbackStarted, failCallback);
      ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread firstRebind =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, firstOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "tracking-rebind-with-callback-failure");
      firstRebind.setDaemon(true);

      firstRebind.start();
      assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
      try {
        state.engine.sendCommand("version");
      } finally {
        failCallback.countDown();
      }
      firstRebind.join(1000L);

      assertFalse(firstRebind.isAlive());
      assertTrue(rebindFailure.get() instanceof java.lang.reflect.InvocationTargetException);
      assertEquals(
          "simulated rebind callback failure", rebindFailure.get().getCause().getMessage());
      assertEquals(1, closed.get());
      assertFalse(acquisition.lease().isOwned());
      assertEquals("version\n", firstOutput.toString(StandardCharsets.UTF_8));

      ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> secondRebindFailure = new AtomicReference<>();
      Thread secondRebind =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, secondOutput);
                } catch (Throwable failure) {
                  secondRebindFailure.set(failure);
                }
              },
              "tracking-rebind-after-callback-failure");
      secondRebind.setDaemon(true);
      secondRebind.start();
      secondRebind.join(1000L);

      assertFalse(secondRebind.isAlive());
      assertEquals(null, secondRebindFailure.get());
    }
  }

  @Test
  void stateResetCallbackRunsWithoutHoldingQueuedCommandMonitor() throws Exception {
    Class<?> failureHandlerType =
        Class.forName("featurecat.lizzie.analysis.Leelaz$CommandSendFailureHandler");
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch continueCallback = new CountDownLatch(1);
    Object failureHandler =
        java.lang.reflect.Proxy.newProxyInstance(
            failureHandlerType.getClassLoader(),
            new Class<?>[] {failureHandlerType},
            (proxy, method, arguments) -> {
              if (method.getName().equals("onStateResetAfterOutputWrite")) {
                callbackStarted.countDown();
                if (!continueCallback.await(3, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("timed out waiting to continue reset callback");
                }
              }
              return null;
            });
    Class<?> queuedCommandType = Class.forName("featurecat.lizzie.analysis.Leelaz$QueuedCommand");
    java.lang.reflect.Constructor<?> constructor =
        queuedCommandType.getDeclaredConstructor(
            String.class, Runnable.class, failureHandlerType, boolean.class);
    constructor.setAccessible(true);
    Object queuedCommand =
        constructor.newInstance("loadsgf /tmp/monitor.sgf", null, failureHandler, true);
    Method markReset =
        queuedCommandType.getDeclaredMethod(
            "markStateResetAfterOutputWrite", RuntimeException.class);
    markReset.setAccessible(true);
    markReset.invoke(queuedCommand, new IllegalStateException("controlled reset"));
    Method publishReset = queuedCommandType.getDeclaredMethod("publishStateResetAfterOutputWrite");
    publishReset.setAccessible(true);
    AtomicReference<Throwable> publishFailure = new AtomicReference<>();
    Thread publishThread =
        new Thread(
            () -> {
              try {
                publishReset.invoke(queuedCommand);
              } catch (Throwable failure) {
                publishFailure.set(failure);
              }
            },
            "publish-loadsgf-state-reset");
    publishThread.setDaemon(true);
    publishThread.start();
    assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
    CountDownLatch monitorAcquired = new CountDownLatch(1);
    Thread monitorThread =
        new Thread(
            () -> {
              synchronized (queuedCommand) {
                monitorAcquired.countDown();
              }
            },
            "acquire-queued-command-during-reset-callback");
    monitorThread.setDaemon(true);
    monitorThread.start();
    try {
      assertTrue(monitorAcquired.await(1, TimeUnit.SECONDS));
    } finally {
      continueCallback.countDown();
    }
    publishThread.join(1000L);
    monitorThread.join(1000L);

    assertFalse(publishThread.isAlive());
    assertFalse(monitorThread.isAlive());
    assertEquals(null, publishFailure.get());
  }

  @ParameterizedTest
  @EnumSource(SuccessfulTrackingClosePhase.class)
  void successfulTrackingCloseCannotMoveOldQueueToConcurrentRebind(
      SuccessfulTrackingClosePhase phase) throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      phase.prepareCloseBoundary(state.engine, acquisition.lease());
      state.engine.sendCommand("version");
      Object commandQueue = commandQueue(state.engine);
      AtomicReference<Throwable> closeFailure = new AtomicReference<>();
      Thread closeThread =
          new Thread(
              () -> {
                try {
                  dispatch(state.engine, "");
                } catch (Throwable failure) {
                  closeFailure.set(failure);
                }
              },
              "complete-successful-tracking-close");
      closeThread.setDaemon(true);
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebindThread =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, reboundOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "rebind-after-successful-tracking-close");
      rebindThread.setDaemon(true);
      boolean rebindWaitedForCutover;

      synchronized (commandQueue) {
        closeThread.start();
        waitUntil(() -> !acquisition.lease().isOwned());
        rebindThread.start();
        waitUntil(
            () ->
                !rebindThread.isAlive()
                    || rebindThread.getState() == Thread.State.BLOCKED);
        rebindWaitedForCutover = rebindThread.isAlive();
      }
      closeThread.join(1000L);
      rebindThread.join(1000L);

      assertTrue(rebindWaitedForCutover);
      assertFalse(closeThread.isAlive());
      assertFalse(rebindThread.isAlive());
      assertEquals(null, closeFailure.get());
      assertEquals(null, rebindFailure.get());
      assertEquals(1, closed.get());
      assertEquals(java.util.Optional.empty(), acquisition.lease().failureReason());
      assertFalse(reboundOutput.toString(StandardCharsets.UTF_8).contains("version\n"));
    }
  }

  @ParameterizedTest
  @EnumSource(TrackingTimeoutPhase.class)
  void rebindWinnerMakesStartedTrackingTimeoutAStaleNoOp(TrackingTimeoutPhase phase)
      throws Exception {
    TimeoutLeelaz engine = reusableTimeoutKatago();
    phase.useShortTimeout(engine);
    engine.blockTimeout = true;
    try (TestState state = TestState.open(engine)) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      phase.startTimeout(state.engine, acquisition.lease());
      assertTrue(engine.timeoutStarted.await(1, TimeUnit.SECONDS));
      CountDownLatch callbackStarted = new CountDownLatch(1);
      CountDownLatch continueCallback = new CountDownLatch(1);
      enqueueBlockingTrackedLoadSgf(
          state.engine, callbackStarted, continueCallback, null);
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebindThread =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, reboundOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "tracking-rebind-cutover");
      rebindThread.setDaemon(true);
      rebindThread.start();
      assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
      state.engine.sendCommand("version");
      engine.continueTimeout.countDown();
      assertTrue(engine.timeoutFinished.await(1, TimeUnit.SECONDS));
      assertEquals(null, engine.timeoutFailure.get());
      continueCallback.countDown();
      rebindThread.join(1000L);

      assertFalse(rebindThread.isAlive());
      assertEquals(null, rebindFailure.get());
      assertEquals(1, closed.get());
      assertFalse(acquisition.lease().isOwned());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.TRANSPORT_CLOSED),
          acquisition.lease().failureReason());
      assertTrue(state.engine.isLoaded());
      assertEquals("version\n", reboundOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @ParameterizedTest
  @EnumSource(TrackingTimeoutPhase.class)
  void timeoutWinnerOwnsCleanupAcrossTrackingRebind(TrackingTimeoutPhase phase) throws Exception {
    TimeoutLeelaz engine = reusableTimeoutKatago();
    phase.useShortTimeout(engine);
    engine.blockTimeout = true;
    try (TestState state = TestState.open(engine)) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      phase.startTimeout(state.engine, acquisition.lease());
      assertTrue(engine.timeoutStarted.await(1, TimeUnit.SECONDS));
      CountDownLatch callbackStarted = new CountDownLatch(1);
      CountDownLatch continueCallback = new CountDownLatch(1);
      enqueueBlockingTrackedLoadSgf(
          state.engine, callbackStarted, continueCallback, null);

      engine.continueTimeout.countDown();
      assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebindThread =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, reboundOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "tracking-rebind-after-timeout-claim");
      rebindThread.setDaemon(true);
      rebindThread.start();
      waitUntil(() -> readerStreamRebindInProgress(state.engine));
      CountDownLatch rebindCallbackStarted = new CountDownLatch(1);
      CountDownLatch continueRebindCallback = new CountDownLatch(1);
      enqueueBlockingTrackedLoadSgf(
          state.engine, rebindCallbackStarted, continueRebindCallback, null);
      continueCallback.countDown();
      assertTrue(engine.timeoutFinished.await(1, TimeUnit.SECONDS));
      assertEquals(null, engine.timeoutFailure.get());
      assertTrue(rebindCallbackStarted.await(1, TimeUnit.SECONDS));
      state.engine.sendCommand("version");
      continueRebindCallback.countDown();
      rebindThread.join(1000L);

      assertFalse(rebindThread.isAlive());
      assertEquals(null, rebindFailure.get());
      assertEquals(1, closed.get());
      assertFalse(acquisition.lease().isOwned());
      assertEquals(
          java.util.Optional.of(phase.failure()), acquisition.lease().failureReason());
      assertEquals("version\n", reboundOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @ParameterizedTest
  @EnumSource(TrackingTimeoutPhase.class)
  void readerRebindRetiresCommandQueuedAfterTimeoutSnapshotBeforeGate(
      TrackingTimeoutPhase phase) throws Exception {
    TimeoutLeelaz engine = reusableTimeoutKatago();
    phase.useShortTimeout(engine);
    engine.blockTimeout = true;
    try (TestState state = TestState.open(engine)) {
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      phase.startTimeout(state.engine, acquisition.lease());
      assertTrue(engine.timeoutStarted.await(1, TimeUnit.SECONDS));
      CountDownLatch callbackStarted = new CountDownLatch(1);
      CountDownLatch continueCallback = new CountDownLatch(1);
      enqueueBlockingTrackedLoadSgf(
          state.engine, callbackStarted, continueCallback, null);

      engine.continueTimeout.countDown();
      assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
      state.engine.sendCommand("version");
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebindThread =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, reboundOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "tracking-rebind-after-timeout-snapshot");
      rebindThread.setDaemon(true);
      rebindThread.start();
      waitUntil(() -> readerStreamRebindInProgress(state.engine));
      continueCallback.countDown();
      assertTrue(engine.timeoutFinished.await(1, TimeUnit.SECONDS));
      assertEquals(null, engine.timeoutFailure.get());
      rebindThread.join(1000L);

      assertFalse(rebindThread.isAlive());
      assertEquals(null, rebindFailure.get());
      assertEquals("", reboundOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @ParameterizedTest
  @EnumSource(TrackingTimeoutPhase.class)
  void readerRebindRetiresSentLoadSgfHandlerPreservedByTimeoutReset(
      TrackingTimeoutPhase phase) throws Exception {
    TimeoutLeelaz engine = reusableTimeoutKatago();
    phase.useShortTimeout(engine);
    engine.blockTimeout = true;
    try (TestState state = TestState.open(engine)) {
      CountDownLatch callbackStarted = new CountDownLatch(1);
      CountDownLatch continueCallback = new CountDownLatch(1);
      AtomicInteger stateResetCallbacks = new AtomicInteger();
      enqueueBlockingTrackedLoadSgf(
          state.engine, callbackStarted, continueCallback, null, stateResetCallbacks);
      assertEquals(1, pendingResponseHandlerCount(state.engine));
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      phase.startTimeoutAfterPendingLoadSgf(state.engine, acquisition.lease());
      assertTrue(engine.timeoutStarted.await(1, TimeUnit.SECONDS));

      engine.continueTimeout.countDown();
      assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebindThread =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, reboundOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "tracking-rebind-after-timeout-preserved-handler");
      rebindThread.setDaemon(true);
      rebindThread.start();
      waitUntil(() -> readerStreamRebindInProgress(state.engine));
      continueCallback.countDown();
      assertTrue(engine.timeoutFinished.await(1, TimeUnit.SECONDS));
      assertEquals(null, engine.timeoutFailure.get());
      rebindThread.join(1000L);

      assertFalse(rebindThread.isAlive());
      assertEquals(null, rebindFailure.get());
      assertEquals(0, pendingResponseHandlerCount(state.engine));
      assertEquals(1, stateResetCallbacks.get());
      processCommandResponse(state.engine, "=1");
      assertEquals(1, stateResetCallbacks.get());
    }
  }

  @Test
  void currentIncarnationTerminalClosesTrackingLeaseAsTransportFailure() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableLocalKatago();
    installOutput(engine);
    installInput(engine, "");
    AtomicInteger closed = new AtomicInteger();
    List<Leelaz.TrackingReleaseDisposition> dispositions = new ArrayList<>();
    RecordingHandoffTarget target = RecordingHandoffTarget.retained();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      engine.isNormalEnd = true;
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet(), dispositions::add);
      Leelaz.TrackingHandoffClaim claim = engine.claimTrackingHandoff(target);

      invokeRead(engine);

      assertEquals(1, closed.get());
      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(0, target.activations.get());
      assertEquals(1, target.failures.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.TRANSPORT_CLOSED),
          acquisition.lease().failureReason());
      assertFalse(acquisition.lease().isOwned());
      assertFalse(engine.isStarted());
      assertEquals(List.of(Leelaz.TrackingReleaseDisposition.CLEARED), dispositions);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, acquisition.lease().disposition());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void releaseWhileAcquiringClosesAfterInitialFenceWithoutBecomingReady() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger ready = new AtomicInteger();
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> ready.incrementAndGet(), lease -> closed.incrementAndGet());

      assertTrue(acquisition.lease().release());
      assertFalse(acquisition.lease().release());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(0, ready.get());
      assertEquals(1, closed.get());
      assertFalse(acquisition.lease().isOwned());
      assertEquals("800000000 stop\n", state.output.toString(StandardCharsets.UTF_8));
      assertTrue(state.engine.isLoaded());
    }
  }

  @Test
  void acquiringTrackingLeaseAcceptsOneTypedHandoffAndActivatesAfterInitialFence()
      throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingHandoffTarget target = RecordingHandoffTarget.retained();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});

      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      Leelaz.TrackingHandoffClaim second =
          state.engine.claimTrackingHandoff(RecordingHandoffTarget.foreground());

      assertEquals(Leelaz.TrackingHandoffAvailability.ACCEPTED_PENDING, claim.availability());
      assertEquals(Leelaz.TrackingHandoffState.ACCEPTED_PENDING, claim.state());
      assertEquals(Leelaz.TrackingHandoffAvailability.BUSY, second.availability());
      assertEquals(0, target.activations.get());
      assertFalse(acquisition.lease().send("kata-analyze B 10"));

      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(1, target.activations.get());
      assertEquals(0, target.failures.get());
      assertEquals(Leelaz.TrackingHandoffState.ACTIVE, claim.state());
      assertEquals("800000000 stop\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void foregroundHandoffOwnsExclusiveGateAfterInitialFence() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingHandoffTarget target = RecordingHandoffTarget.foreground();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);

      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      state.engine.sendCommand("version");

      assertEquals(Leelaz.TrackingHandoffState.ACTIVE, claim.state());
      assertEquals(1, target.activations.get());
      assertTrue(state.engine.hasExclusiveGtpLeaseOwnedBy(target));
      assertFalse(state.engine.beginExclusiveGtpLifecycleTransition());
      assertEquals("800000000 stop\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void lifecycleReservationClaimsTrackingAndRejectsOrdinaryCommandBehindQueueGate()
      throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      List<Leelaz.TrackingReleaseDisposition> dispositions = new ArrayList<>();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> {}, dispositions::add);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));

      Leelaz.ExclusiveGtpLifecycleReservation reservation =
          state.engine.beginExclusiveGtpLifecycleReservation();
      state.engine.sendCommand("stop");

      assertTrue(reservation != null, "destructive lifecycle must win active tracking once");
      assertEquals(List.of(Leelaz.TrackingReleaseDisposition.CLEARED), dispositions);
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          state.output.toString(StandardCharsets.UTF_8));
      assertEquals(null, state.engine.beginExclusiveGtpLifecycleReservation());

      assertTrue(dispatch(state.engine, ""));
      assertTrue(dispatch(state.engine, "=800000002"));
      assertTrue(dispatch(state.engine, ""));
      assertFalse(state.output.toString(StandardCharsets.UTF_8).endsWith("stop\nstop\n"));

      reservation.close();

      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          state.output.toString(StandardCharsets.UTF_8),
          "an uncredentialed ordinary command rejected behind the lifecycle gate must not replay");
    }
  }

  @Test
  void typedHandoffClearsReleaseDispositionBeforeActivationDespiteObserverFailure()
      throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      List<Leelaz.TrackingReleaseDisposition> dispositions = new ArrayList<>();
      AtomicInteger activations = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {},
              lease -> {},
              lease -> {},
              disposition -> {
                dispositions.add(disposition);
                throw new IllegalStateException("simulated disposition observer failure");
              });
      Leelaz.TrackingHandoffTarget target =
          new Leelaz.TrackingHandoffTarget() {
            @Override
            public Leelaz.TrackingHandoffKind kind() {
              return Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE;
            }

            @Override
            public boolean isCurrent() {
              return true;
            }

            @Override
            public void activate(Leelaz.TrackingHandoffActivation activation) {
              assertEquals(List.of(Leelaz.TrackingReleaseDisposition.CLEARED), dispositions);
              activations.incrementAndGet();
              assertTrue(activation.completeRetainedEngineMode());
            }

            @Override
            public void fail(Leelaz.TrackingHandoffFailure failure) {}
          };

      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(Leelaz.TrackingHandoffAvailability.ACCEPTED_PENDING, claim.availability());
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, acquisition.lease().disposition());
      assertEquals(1, activations.get());
      assertEquals(Leelaz.TrackingHandoffState.ACTIVE, claim.state());
    }
  }

  @Test
  void activeTrackingHandoffKeepsOrdinaryQueueClosedUntilRetainedTargetCompletes()
      throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      BlockingHandoffTarget target = new BlockingHandoffTarget();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));

      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      assertEquals(Leelaz.TrackingHandoffAvailability.ACCEPTED_PENDING, claim.availability());
      state.engine.sendCommand("version");
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          state.output.toString(StandardCharsets.UTF_8));

      assertTrue(dispatch(state.engine, ""));
      assertTrue(dispatch(state.engine, "=800000002"));
      AtomicReference<Throwable> fenceFailure = new AtomicReference<>();
      Thread fenceThread =
          new Thread(
              () -> {
                try {
                  dispatch(state.engine, "");
                } catch (Throwable failure) {
                  fenceFailure.set(failure);
                }
              },
              "tracking-handoff-final-fence");
      fenceThread.setDaemon(true);
      fenceThread.start();
      assertTrue(target.activationStarted.await(1, TimeUnit.SECONDS));

      assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("version\n"));
      assertEquals(
          Leelaz.TrackingHandoffAvailability.BUSY,
          state.engine.claimTrackingHandoff(RecordingHandoffTarget.foreground()).availability());
      assertFalse(state.engine.beginExclusiveGtpLifecycleTransition());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.EXISTING_LEASE,
          state.engine.previewForegroundAnalysisLeaseAvailability());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.EXISTING_LEASE,
          state
              .engine
              .acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {})
              .availability());

      target.allowCompletion.countDown();
      fenceThread.join(1000L);

      assertFalse(fenceThread.isAlive());
      assertEquals(null, fenceFailure.get());
      assertEquals(Leelaz.TrackingHandoffState.ACTIVE, claim.state());
      assertTrue(state.output.toString(StandardCharsets.UTF_8).endsWith("version\n"));
    }
  }

  @Test
  void readerRebindFailsPendingHandoffOnceBeforeReopeningOrdinaryQueue() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      List<Leelaz.TrackingReleaseDisposition> dispositions = new ArrayList<>();
      RecordingHandoffTarget target = RecordingHandoffTarget.retained();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> {}, dispositions::add);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();

      initializeStreams(state.engine, reboundOutput);
      state.engine.sendCommand("version");

      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(0, target.activations.get());
      assertEquals(1, target.failures.get());
      assertEquals(List.of(Leelaz.TrackingReleaseDisposition.CLEARED), dispositions);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, acquisition.lease().disposition());
      assertEquals("version\n", reboundOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void readerRebindWinsAgainstInFlightHandoffActivation() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      BlockingHandoffTarget target =
          new BlockingHandoffTarget(() -> state.engine.sendCommand("name"), true);
      AtomicReference<Throwable> fenceFailure = new AtomicReference<>();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      CountDownLatch rebindFinished = new CountDownLatch(1);
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      assertTrue(dispatch(state.engine, ""));
      assertTrue(dispatch(state.engine, "=800000002"));
      Thread fenceThread =
          new Thread(
              () -> {
                try {
                  dispatch(state.engine, "");
                } catch (Throwable failure) {
                  fenceFailure.set(failure);
                }
              },
              "tracking-handoff-rebind-during-activation");
      fenceThread.setDaemon(true);
      fenceThread.start();
      assertTrue(target.activationStarted.await(1, TimeUnit.SECONDS));
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      Thread rebindThread =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, reboundOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                } finally {
                  rebindFinished.countDown();
                }
              },
              "tracking-handoff-rebind-cutover");
      rebindThread.setDaemon(true);

      rebindThread.start();
      waitUntil(() -> readerStreamRebindInProgress(state.engine));
      state.engine.sendCommand("version");

      assertEquals(0, target.failures.get());
      assertEquals("", reboundOutput.toString(StandardCharsets.UTF_8));

      target.allowCompletion.countDown();
      assertTrue(target.failureStarted.await(1, TimeUnit.SECONDS));
      notifyEngineArbitrationWaiters(state.engine);
      assertFalse(rebindFinished.await(100, TimeUnit.MILLISECONDS));
      assertEquals(0, target.failures.get());
      assertEquals("", reboundOutput.toString(StandardCharsets.UTF_8));

      target.allowFailureCompletion.countDown();
      fenceThread.join(1000L);
      rebindThread.join(1000L);
      state.engine.sendCommand("version");

      assertFalse(fenceThread.isAlive());
      assertFalse(rebindThread.isAlive());
      assertEquals(null, fenceFailure.get());
      assertEquals(null, rebindFailure.get());
      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(1, target.failures.get());
      assertEquals("version\n", reboundOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void targetCancellationSettlesOnceAndCallbackFailureCannotLoseQueueWakeup() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger activations = new AtomicInteger();
      AtomicInteger failures = new AtomicInteger();
      Leelaz.TrackingHandoffTarget target =
          new Leelaz.TrackingHandoffTarget() {
            @Override
            public Leelaz.TrackingHandoffKind kind() {
              return Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE;
            }

            @Override
            public boolean isCurrent() {
              return true;
            }

            @Override
            public void activate(Leelaz.TrackingHandoffActivation activation) {
              activations.incrementAndGet();
            }

            @Override
            public void fail(Leelaz.TrackingHandoffFailure failure) {
              failures.incrementAndGet();
              throw new IllegalStateException("simulated target failure callback exception");
            }
          };
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);

      assertTrue(claim.cancel());
      assertFalse(claim.cancel());
      state.engine.sendCommand("version");
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(0, activations.get());
      assertEquals(1, failures.get());
      assertFalse(acquisition.lease().isOwned());
      assertEquals("800000000 stop\nversion\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void retainedHandoffCannotActivateAsForegroundOwner() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicReference<Boolean> mismatchedActivation = new AtomicReference<>();
      AtomicReference<Leelaz.TrackingHandoffFailure> failure = new AtomicReference<>();
      Leelaz.TrackingHandoffTarget target =
          new Leelaz.TrackingHandoffTarget() {
            @Override
            public Leelaz.TrackingHandoffKind kind() {
              return Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE;
            }

            @Override
            public boolean isCurrent() {
              return true;
            }

            @Override
            public void activate(Leelaz.TrackingHandoffActivation activation) {
              mismatchedActivation.set(activation.activateForegroundAnalysis(line -> {}, () -> {}));
            }

            @Override
            public void fail(Leelaz.TrackingHandoffFailure reason) {
              failure.compareAndSet(null, reason);
            }
          };
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      state.engine.sendCommand("version");

      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(false, mismatchedActivation.get());
      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(Leelaz.TrackingHandoffFailure.ACTIVATION_FAILED, failure.get());
      assertFalse(state.engine.hasExclusiveGtpLeaseOwnedBy(target));
      assertEquals("800000000 stop\nversion\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void handoffKindIsCapturedOnceAtClaim() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger kindReads = new AtomicInteger();
      Leelaz.TrackingHandoffTarget target =
          new Leelaz.TrackingHandoffTarget() {
            @Override
            public Leelaz.TrackingHandoffKind kind() {
              return kindReads.incrementAndGet() == 1
                  ? Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE
                  : Leelaz.TrackingHandoffKind.FOREGROUND_ANALYSIS;
            }

            @Override
            public boolean isCurrent() {
              return true;
            }

            @Override
            public void activate(Leelaz.TrackingHandoffActivation activation) {
              assertTrue(activation.completeRetainedEngineMode());
            }

            @Override
            public void fail(Leelaz.TrackingHandoffFailure failure) {}
          };
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});

      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(1, kindReads.get());
      assertEquals(Leelaz.TrackingHandoffState.ACTIVE, claim.state());
    }
  }

  @Test
  void targetValidationExceptionFailsHandoffAndReopensOrdinaryQueue() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger failures = new AtomicInteger();
      AtomicReference<Leelaz.TrackingHandoffFailure> reason = new AtomicReference<>();
      Leelaz.TrackingHandoffTarget target =
          new Leelaz.TrackingHandoffTarget() {
            @Override
            public Leelaz.TrackingHandoffKind kind() {
              return Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE;
            }

            @Override
            public boolean isCurrent() {
              throw new IllegalStateException("simulated target validation failure");
            }

            @Override
            public void activate(Leelaz.TrackingHandoffActivation activation) {
              throw new AssertionError("invalid target must not activate");
            }

            @Override
            public void fail(Leelaz.TrackingHandoffFailure failure) {
              failures.incrementAndGet();
              reason.compareAndSet(null, failure);
            }
          };
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      state.engine.sendCommand("version");

      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(1, failures.get());
      assertEquals(Leelaz.TrackingHandoffFailure.ACTIVATION_FAILED, reason.get());
      assertEquals("800000000 stop\nversion\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void trackingClosedCallbackFailureCannotBlockHandoffActivationOrQueueWakeup() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      RecordingHandoffTarget target = RecordingHandoffTarget.retained();
      state.engine.acquireTrackingStreamLease(
          line -> {},
          lease -> {},
          lease -> {
            closed.incrementAndGet();
            throw new IllegalStateException("simulated tracking closed callback failure");
          });
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      state.engine.sendCommand("version");

      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertEquals(1, closed.get());
      assertEquals(1, target.activations.get());
      assertEquals(0, target.failures.get());
      assertEquals(Leelaz.TrackingHandoffState.ACTIVE, claim.state());
      assertEquals("800000000 stop\nversion\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void inFlightActivationWinsAgainstLateCancellation() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      CountDownLatch activationStarted = new CountDownLatch(1);
      CountDownLatch continueActivation = new CountDownLatch(1);
      AtomicReference<Boolean> completion = new AtomicReference<>();
      AtomicInteger failures = new AtomicInteger();
      AtomicReference<Throwable> activationFailure = new AtomicReference<>();
      Leelaz.TrackingHandoffTarget target =
          new Leelaz.TrackingHandoffTarget() {
            @Override
            public Leelaz.TrackingHandoffKind kind() {
              return Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE;
            }

            @Override
            public boolean isCurrent() {
              return true;
            }

            @Override
            public void activate(Leelaz.TrackingHandoffActivation activation) {
              activationStarted.countDown();
              try {
                assertTrue(continueActivation.await(1, TimeUnit.SECONDS));
                completion.set(activation.completeRetainedEngineMode());
              } catch (Throwable failure) {
                activationFailure.set(failure);
              }
            }

            @Override
            public void fail(Leelaz.TrackingHandoffFailure failure) {
              failures.incrementAndGet();
            }
          };
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);
      state.engine.sendCommand("version");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(dispatch(state.engine, "=800000002"));
      Thread fenceThread =
          new Thread(
              () -> {
                try {
                  dispatch(state.engine, "");
                } catch (Throwable failure) {
                  activationFailure.compareAndSet(null, failure);
                }
              },
              "tracking-handoff-cancel-during-activation");
      fenceThread.setDaemon(true);

      fenceThread.start();
      assertTrue(activationStarted.await(1, TimeUnit.SECONDS));
      assertFalse(claim.cancel());

      assertEquals(0, failures.get());
      assertFalse(state.output.toString(StandardCharsets.UTF_8).endsWith("version\n"));

      continueActivation.countDown();
      fenceThread.join(1000L);

      assertFalse(fenceThread.isAlive());
      assertEquals(null, activationFailure.get());
      assertEquals(true, completion.get());
      assertEquals(0, failures.get());
      assertEquals(Leelaz.TrackingHandoffState.ACTIVE, claim.state());
      assertTrue(state.output.toString(StandardCharsets.UTF_8).endsWith("version\n"));
    }
  }

  @Test
  void releaseRequestedTrackingLeaseRejectsLateHandoff() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      assertTrue(acquisition.lease().release());

      Leelaz.TrackingHandoffClaim claim =
          state.engine.claimTrackingHandoff(RecordingHandoffTarget.retained());

      assertEquals(Leelaz.TrackingHandoffAvailability.BUSY, claim.availability());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
    }
  }

  @Test
  void ordinaryQueueWinnerMakesLaterTypedHandoffBusy() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      state.engine.sendCommand("version");

      Leelaz.TrackingHandoffClaim claim =
          state.engine.claimTrackingHandoff(RecordingHandoffTarget.retained());

      assertEquals(Leelaz.TrackingHandoffAvailability.BUSY, claim.availability());
      assertFalse(acquisition.lease().release());
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      assertTrue(state.output.toString(StandardCharsets.UTF_8).endsWith("version\n"));
    }
  }

  @Test
  void ordinaryCommandClaimsTrackingReleaseAfterQueueAdmission() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingDispositionObserver observer = new RecordingDispositionObserver();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {}, observer);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      state.engine.sendCommand("komi 7.5");

      assertEquals(List.of(Leelaz.TrackingReleaseReason.ORDINARY_OPERATION), observer.reasons);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, acquisition.lease().disposition());
      assertEquals(
          Leelaz.TrackingHandoffAvailability.BUSY,
          state.engine.claimTrackingHandoff(RecordingHandoffTarget.retained()).availability());
      assertEquals(
          "800000000 stop\n800000001 stop\n", state.output.toString(StandardCharsets.UTF_8));

      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      assertTrue(state.output.toString(StandardCharsets.UTF_8).endsWith("komi 7.5\n"));
    }
  }

  @Test
  void safeRawQueryFreezesThenOrdinaryCommandClearsBeforeFinalFence() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingDispositionObserver observer = new RecordingDispositionObserver();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {}, observer);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertTrue(state.engine.sendRawConsoleCommand("KnOwN_CoMmAnD showboard"));
      state.engine.sendCommand("komi 6.5");

      assertEquals(
          List.of(
              Leelaz.TrackingReleaseReason.SAFE_READ_ONLY_QUERY,
              Leelaz.TrackingReleaseReason.ORDINARY_OPERATION),
          observer.reasons);
      assertEquals(
          List.of(
              Leelaz.TrackingReleaseDisposition.FROZEN_BY_SAFE,
              Leelaz.TrackingReleaseDisposition.CLEARED),
          observer.dispositions);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, acquisition.lease().disposition());
      assertEquals(
          "800000000 stop\n800000001 stop\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void rawConsoleWhitelistRejectsIdsWrongArityAndUnsafeCommandsBeforeRelease() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingDispositionObserver observer = new RecordingDispositionObserver();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {}, observer);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      for (String command :
          List.of(
              "1 name",
              "name extra",
              "known_command",
              "known_command showboard extra",
              "kata-get-rules",
              "kata-raw-nn 0",
              "unknown_command")) {
        assertFalse(state.engine.sendRawConsoleCommand(command), command);
      }

      assertEquals(List.of(), observer.reasons);
      assertEquals(Leelaz.TrackingReleaseDisposition.ACTIVE, acquisition.lease().disposition());
      assertEquals("800000000 stop\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void rawConsoleWhitelistAcceptsEveryStrictSafeQueryCaseInsensitively() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingDispositionObserver observer = new RecordingDispositionObserver();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {}, observer);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      for (String command :
          List.of(
              "NaMe",
              "VERSION",
              "Protocol_Version",
              "LIST_COMMANDS",
              "known_COMMAND kata-analyze",
              "ShowBoard")) {
        assertTrue(state.engine.sendRawConsoleCommand(command), command);
      }

      assertEquals(List.of(Leelaz.TrackingReleaseReason.SAFE_READ_ONLY_QUERY), observer.reasons);
      assertEquals(
          List.of(Leelaz.TrackingReleaseDisposition.FROZEN_BY_SAFE), observer.dispositions);
      assertEquals(
          "800000000 stop\n800000001 stop\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void ordinaryThenSafeCannotDowngradeDispositionAndClosedCommandsDoNotNotify() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingDispositionObserver observer = new RecordingDispositionObserver();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {}, observer);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      state.engine.sendCommand("komi 7.5");
      assertTrue(state.engine.sendRawConsoleCommand("name"));
      assertEquals(List.of(Leelaz.TrackingReleaseReason.ORDINARY_OPERATION), observer.reasons);
      assertEquals(List.of(Leelaz.TrackingReleaseDisposition.CLEARED), observer.dispositions);

      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      state.engine.sendCommand("version");

      assertEquals(List.of(Leelaz.TrackingReleaseReason.ORDINARY_OPERATION), observer.reasons);
      assertEquals(List.of(Leelaz.TrackingReleaseDisposition.CLEARED), observer.dispositions);
    }
  }

  @Test
  void secondOrdinaryEnqueuePathClaimsReleaseAndSharesCoalescingCleanup() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingDispositionObserver observer = new RecordingDispositionObserver();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {}, observer);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      state.engine.sendCommandNoLeelaz2("version");

      assertEquals(List.of(Leelaz.TrackingReleaseReason.ORDINARY_OPERATION), observer.reasons);
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      assertTrue(state.output.toString(StandardCharsets.UTF_8).endsWith("version\n"));
    }
  }

  @Test
  void trackingFaultEndsBlockingLoadSgfOnceWithoutWritingLoadCommand() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Path sgf = Files.createTempFile("tracking-fault-loadsgf-", ".sgf");
      AtomicInteger consumed = new AtomicInteger();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  state.engine.loadSgf(sgf, consumed::incrementAndGet);
                } catch (Throwable thrown) {
                  failure.set(thrown);
                }
              },
              "tracking-fault-blocking-loadsgf");
      loadThread.setDaemon(true);
      try {
        state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
        loadThread.start();
        waitUntil(() -> commandQueueSize(state.engine) == 1);

        ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
        initializeStreams(state.engine, reboundOutput);
        loadThread.join(1000L);

        assertFalse(loadThread.isAlive());
        assertEquals(1, consumed.get());
        assertTrue(failure.get() instanceof RuntimeException);
        assertFalse(
            state.output.toString(StandardCharsets.UTF_8).contains("loadsgf "),
            state.output.toString(StandardCharsets.UTF_8));
        assertEquals("", reboundOutput.toString(StandardCharsets.UTF_8));
      } finally {
        Files.deleteIfExists(sgf);
      }
    }
  }


  @Test
  void komiQueuesBehindOrdinaryTrackingReleaseWithoutReportingBusy() throws Exception {
    Board previousBoard = Lizzie.board;
    FeedbackRecordingLeelaz engine = new FeedbackRecordingLeelaz();
    configureLocalKatago(engine);
    try (TestState state = TestState.open(engine)) {
      Lizzie.board = new Board();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      state.engine.sendCommand("clear_board");
      state.engine.komi(7.5);

      assertEquals(0, engine.feedbackCount.get());
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      List<String> commands = outputCommandPayloads(state.output);
      assertEquals(
          List.of("clear_board", "komi 7.5"),
          commands.subList(commands.size() - 2, commands.size()),
          commands.toString());
    } finally {
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void textKomiQueuesBehindOrdinaryTrackingReleaseWithoutReportingBusy() throws Exception {
    Board previousBoard = Lizzie.board;
    FeedbackRecordingLeelaz engine = new FeedbackRecordingLeelaz();
    configureLocalKatago(engine);
    try (TestState state = TestState.open(engine)) {
      Lizzie.board = new Board();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      state.engine.sendCommand("clear_board");
      state.engine.komiNoMenu(7.5);

      assertEquals(0, engine.feedbackCount.get());
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      List<String> commands = outputCommandPayloads(state.output);
      assertEquals(
          List.of("clear_board", "komi 7.5"),
          commands.subList(commands.size() - 2, commands.size()),
          commands.toString());
    } finally {
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void boardSizeQueuesBehindOrdinaryTrackingReleaseWithoutReportingBusy() throws Exception {
    Board previousBoard = Lizzie.board;
    FeedbackRecordingLeelaz engine = new FeedbackRecordingLeelaz();
    configureLocalKatago(engine);
    try (TestState state = TestState.open(engine)) {
      Lizzie.board =
          new Board() {
            @Override
            public void reopen(int width, int height) {}
          };
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      state.engine.sendCommand("clear_board");
      state.engine.boardSize(13, 13);

      assertEquals(0, engine.feedbackCount.get());
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      List<String> commands = outputCommandPayloads(state.output);
      assertEquals(
          List.of("clear_board", "boardsize 13"),
          commands.subList(commands.size() - 2, commands.size()),
          commands.toString());
    } finally {
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void boardSizeMirrorsBeforeRealBoardReopenCommands() throws Exception {
    Board previousBoard = Lizzie.board;
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Leelaz previousSecondEngine = Lizzie.leelaz2;
    WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Leelaz secondEngine = reusableLocalKatago();
      ByteArrayOutputStream secondOutput = installOutput(secondEngine);
      Lizzie.frame = allocate(PonderTrackingFrame.class);
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Zobrist.init();
      Lizzie.board = new Board();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = secondEngine;

      state.engine.boardSize(13, 13);

      List<String> mirroredCommands = outputCommandPayloads(secondOutput);
      assertTrue(mirroredCommands.size() >= 2, mirroredCommands.toString());
      assertEquals(
          List.of("boardsize 13", "clear_board"),
          mirroredCommands.subList(0, 2),
          mirroredCommands.toString());
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.leelaz2 = previousSecondEngine;
      LizzieFrame.winrateGraph = previousWinrateGraph;
    }
  }

  @Test
  void boardSizeRejectsBeforeReopenWhenMirrorLifecycleOwnsAdmission() throws Exception {
    Board previousBoard = Lizzie.board;
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Leelaz previousSecondEngine = Lizzie.leelaz2;
    WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
    try (TestState state = TestState.open(reusableLocalKatago())) {
      PonderTrackingFrame frame = allocate(PonderTrackingFrame.class);
      Leelaz secondEngine = reusableLocalKatago();
      ByteArrayOutputStream secondOutput = installOutput(secondEngine);
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Zobrist.init();
      Lizzie.board = new Board();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      int originalEngineWidth = state.engine.width;
      int originalEngineHeight = state.engine.height;
      try (HeldLifecycleReservation ignored = HeldLifecycleReservation.open(secondEngine)) {
        Lizzie.config.extraMode = ExtraMode.Double_Engine;
        Lizzie.leelaz2 = secondEngine;

        state.engine.boardSize(13, 13);

        assertEquals(19, Board.boardWidth);
        assertEquals(19, Board.boardHeight);
        assertEquals(originalEngineWidth, state.engine.width);
        assertEquals(originalEngineHeight, state.engine.height);
        assertEquals(0, frame.redrawCount);
        assertEquals(0, frame.refreshCount);
        assertEquals("", secondOutput.toString(StandardCharsets.UTF_8));
        assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("boardsize 13\n"));
      }
      assertTrue(acquisition.lease().release());
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      assertEquals(19, Board.boardWidth);
      assertEquals(19, Board.boardHeight);
      assertEquals(originalEngineWidth, state.engine.width);
      assertEquals(originalEngineHeight, state.engine.height);
      assertEquals(0, frame.redrawCount);
      assertEquals(0, frame.refreshCount);
      assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("boardsize 13\n"));
      assertEquals("", secondOutput.toString(StandardCharsets.UTF_8));
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.leelaz2 = previousSecondEngine;
      LizzieFrame.winrateGraph = previousWinrateGraph;
    }
  }

  @Test
  void lifecycleWinnerKeepsKomiAndBoardSizeBusyWhileTrackingSettles() throws Exception {
    Board previousBoard = Lizzie.board;
    FeedbackRecordingLeelaz engine = new FeedbackRecordingLeelaz();
    configureLocalKatago(engine);
    try (TestState state = TestState.open(engine)) {
      Lizzie.board =
          new Board() {
            @Override
            public void reopen(int width, int height) {}
          };
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      float originalKomi = state.engine.komi;
      int originalWidth = state.engine.width;
      int originalHeight = state.engine.height;

      Leelaz.ExclusiveGtpLifecycleReservation reservation =
          state.engine.beginExclusiveGtpLifecycleReservation();
      assertTrue(reservation != null);
      try {
        state.engine.komi(5.5);
        state.engine.komiNoMenu(6.5);
        state.engine.boardSize(13, 13);
      } finally {
        reservation.close();
      }

      assertEquals(3, engine.feedbackCount.get());
      assertEquals(originalKomi, state.engine.komi);
      assertEquals(originalWidth, state.engine.width);
      assertEquals(originalHeight, state.engine.height);
      assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("komi 5.5\n"));
      assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("komi 6.5\n"));
      assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("boardsize 13\n"));
    } finally {
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void statefulPublicEntriesDoNotPublishWhenMirrorRejectsAtomicAdmission() throws Exception {
    Board previousBoard = Lizzie.board;
    Leelaz previousSecondEngine = Lizzie.leelaz2;
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Lizzie.board =
          new Board() {
            @Override
            public void reopen(int width, int height) {}
          };
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      Leelaz secondEngine = reusableLocalKatago();
      ByteArrayOutputStream secondOutput = installOutput(secondEngine);
      float originalKomi = state.engine.komi;
      double originalGameKomi = Lizzie.board.getHistory().getGameInfo().getKomi();
      int originalWidth = state.engine.width;
      int originalHeight = state.engine.height;

      try (HeldLifecycleReservation ignored = HeldLifecycleReservation.open(secondEngine)) {
        Lizzie.config.extraMode = ExtraMode.Double_Engine;
        Lizzie.leelaz2 = secondEngine;
        state.engine.komi(5.5);
        state.engine.komiNoMenu(6.5);
        state.engine.boardSize(13, 13);

        assertEquals(originalKomi, state.engine.komi);
        assertEquals(originalGameKomi, Lizzie.board.getHistory().getGameInfo().getKomi());
        assertEquals(originalWidth, state.engine.width);
        assertEquals(originalHeight, state.engine.height);
        String primaryCommands = state.output.toString(StandardCharsets.UTF_8);
        assertFalse(primaryCommands.contains("komi 5.5\n"));
        assertFalse(primaryCommands.contains("komi 6.5\n"));
        assertFalse(primaryCommands.contains("boardsize 13\n"));
        assertEquals("", secondOutput.toString(StandardCharsets.UTF_8));
      }
      assertTrue(acquisition.lease().release());
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      assertEquals(originalKomi, state.engine.komi);
      assertEquals(originalGameKomi, Lizzie.board.getHistory().getGameInfo().getKomi());
      assertEquals(originalWidth, state.engine.width);
      assertEquals(originalHeight, state.engine.height);
      String primaryCommands = state.output.toString(StandardCharsets.UTF_8);
      assertFalse(primaryCommands.contains("komi 5.5\n"));
      assertFalse(primaryCommands.contains("komi 6.5\n"));
      assertFalse(primaryCommands.contains("boardsize 13\n"));
      assertEquals("", secondOutput.toString(StandardCharsets.UTF_8));
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.leelaz2 = previousSecondEngine;
    }
  }

  private static List<String> rawOutputCommands(ByteArrayOutputStream output) {
    List<String> commands = new ArrayList<>();
    for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
      String command = line.trim();
      if (!command.isEmpty()) {
        commands.add(command);
      }
    }
    return commands;
  }

  private static String commandPayload(String command) {
    int separator = command.indexOf(' ');
    if (separator > 0 && command.substring(0, separator).chars().allMatch(Character::isDigit)) {
      return command.substring(separator + 1);
    }
    return command;
  }

  private static List<String> outputCommandPayloads(ByteArrayOutputStream output) {
    List<String> commands = new ArrayList<>();
    for (String command : rawOutputCommands(output)) {
      commands.add(commandPayload(command));
    }
    return commands;
  }

  private static void acknowledgeCompoundPositionRestore(TestState state, Thread restore)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    int inspected = 0;
    boolean finalFenceAcknowledged = false;
    while (restore.isAlive() && System.nanoTime() < deadline) {
      List<String> commands = rawOutputCommands(state.output);
      while (inspected < commands.size()) {
        String command = commands.get(inspected++);
        int separator = command.indexOf(' ');
        if (separator <= 0
            || !command.substring(0, separator).chars().allMatch(Character::isDigit)) {
          continue;
        }
        long commandId = Long.parseLong(command.substring(0, separator));
        if (commandId < 900_000_000L) {
          continue;
        }
        String payload = command.substring(separator + 1);
        assertFalse(payload.contains("analyze"), "analysis must wait for the position ACK");
        processCommandResponse(state.engine, "=" + commandId);
        if (payload.equals("name")) {
          finalFenceAcknowledged = true;
        }
      }
      if (restore.isAlive()) {
        Thread.sleep(10L);
      }
    }
    restore.join(1_000L);
    assertFalse(restore.isAlive(), "compound position restore must finish after its ACKs");
    assertTrue(finalFenceAcknowledged, "compound position restore must finish through final name");
  }

  private static void acknowledgeLastPositionCommand(TestState state) throws Exception {
    String commands = state.output.toString(StandardCharsets.UTF_8).trim();
    String last = commands.substring(commands.lastIndexOf('\n') + 1);
    String first = last.split(" ", 2)[0];
    assertFalse(last.contains("analyze"), "analysis must wait for the position ACK");
    processCommandResponse(state.engine, "=" + (Character.isDigit(first.charAt(0)) ? first : ""));
  }

  @Test
  void navigationAfterTrackingQueuesPonderForTheNewPosition() throws Exception {
    Board previousBoard = Lizzie.board;
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Lizzie.board = new Board();
      Lizzie.frame = allocate(PonderTrackingFrame.class);
      Lizzie.config.analyzeBlack = true;
      Lizzie.config.analyzeWhite = true;
      Lizzie.config.analyzeUpdateIntervalCentisec = 10;
      state.engine.Pondering();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze 10 allow B D4 1 allow W D4 1"));
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, "info move D4 visits 1"));
      assertTrue(dispatch(state.engine, ""));

      state.engine.playMove(Stone.BLACK, "D4", true, false);

      assertTrue(state.engine.isPondering());
      assertTrue(dispatch(state.engine, "=800000002"));
      assertTrue(dispatch(state.engine, ""));
      acknowledgeLastPositionCommand(state);
      String output = state.output.toString(StandardCharsets.UTF_8);
      assertTrue(output.lastIndexOf("kata-analyze") > output.lastIndexOf("play B D4"), output);
    } finally {
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void backwardNavigationAfterTrackingQueuesPonderForTheNewPosition() throws Exception {
    Board previousBoard = Lizzie.board;
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Lizzie.board = new Board();
      Lizzie.frame = allocate(PonderTrackingFrame.class);
      Lizzie.config.analyzeBlack = true;
      Lizzie.config.analyzeWhite = true;
      Lizzie.config.analyzeUpdateIntervalCentisec = 10;
      state.engine.Pondering();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze 10 allow B D4 1 allow W D4 1"));
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));

      state.engine.undo(true, false);

      assertTrue(state.engine.isPondering());
      assertTrue(dispatch(state.engine, "=800000002"));
      assertTrue(dispatch(state.engine, ""));
      acknowledgeLastPositionCommand(state);
      String output = state.output.toString(StandardCharsets.UTF_8);
      assertTrue(output.lastIndexOf("kata-analyze") > output.lastIndexOf("undo"), output);
    } finally {
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void positionRestoreAfterTrackingQueuesPonderForTheRestoredPosition() throws Exception {
    Board previousBoard = Lizzie.board;
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Lizzie.board = new Board();
      Lizzie.frame = allocate(PonderTrackingFrame.class);
      Lizzie.config.analyzeBlack = true;
      Lizzie.config.analyzeWhite = true;
      Lizzie.config.analyzeUpdateIntervalCentisec = 10;
      state.engine.Pondering();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze 10 allow B D4 1 allow W D4 1"));
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));

      AtomicReference<Throwable> restoreFailure = new AtomicReference<>();
      Thread restore =
          new Thread(
              () -> {
                try {
                  Lizzie.board.resendMoveToEngine(state.engine, false);
                } catch (Throwable failure) {
                  restoreFailure.set(failure);
                }
              },
              "tracking-position-restore");
      restore.start();
      waitUntil(
          () ->
              rawOutputCommands(state.output).stream()
                  .anyMatch(command -> command.equals("800000002 stop")));
      assertTrue(restore.isAlive(), "restore must wait for the tracking final stop");

      assertTrue(dispatch(state.engine, "=800000002"));
      assertTrue(dispatch(state.engine, ""));
      acknowledgeCompoundPositionRestore(state, restore);
      assertEquals(null, restoreFailure.get());
      assertTrue(state.engine.isPondering());
      waitUntil(
          () ->
              outputCommandPayloads(state.output).stream()
                  .anyMatch(command -> command.startsWith("kata-analyze")));
      List<String> commands = outputCommandPayloads(state.output);
      int finalFence = commands.lastIndexOf("name");
      int analysis = -1;
      for (int index = 0; index < commands.size(); index++) {
        if (commands.get(index).startsWith("kata-analyze")) {
          analysis = index;
        }
      }
      assertTrue(finalFence >= 0, commands.toString());
      assertTrue(analysis > finalFence, commands.toString());
    } finally {
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void pendingTypedHandoffRejectsStatefulOrdinaryRequestsWithoutStateOrBytes() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      Leelaz.TrackingHandoffClaim claim =
          state.engine.claimTrackingHandoff(RecordingHandoffTarget.retained());

      assertEquals(Leelaz.TrackingHandoffAvailability.ACCEPTED_PENDING, claim.availability());
      assertFalse(state.engine.genmove("B", true));
      assertFalse(state.engine.isInputCommand);
      assertFalse(state.engine.isThinking);
      assertEquals("800000000 stop\n", state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void manualGenmoveSetsAndClearsRequestStateAtWriterBoundary() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertTrue(state.engine.genmove("B", true));
      assertFalse(state.engine.isInputCommand);
      assertFalse(state.engine.isThinking);

      assertTrue(dispatch(state.engine, "=800000001"));
      installOutput(
          state.engine,
          Leelaz.createCommandOutputStream(
              new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                  throw new IOException("simulated manual genmove write failure");
                }
              }));
      assertTrue(dispatch(state.engine, ""));

      assertFalse(state.engine.isInputCommand);
      assertFalse(state.engine.isThinking);
      assertFalse(acquisition.lease().isOwned());
      assertEquals(List.of(List.of(false, true), List.of(false, false)), state.menu.transitions);
    }
  }

  @Test
  void acceptedManualGenmoveDoesNotReportExistingLeaseBusy() throws Exception {
    FeedbackRecordingLeelaz engine = new FeedbackRecordingLeelaz();
    configureLocalKatago(engine);
    try (TestState state = TestState.open(engine)) {
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertTrue(state.engine.genmove("B", true));

      assertEquals(0, engine.feedbackCount.get());
    }
  }

  @Test
  void mirrorAdmissionRejectionLeavesNeitherOrdinaryQueue() throws Exception {
    Leelaz previousSecondEngine = Lizzie.leelaz2;
    try (TestState state = TestState.open(reusableLocalKatago())) {
      RecordingDispositionObserver observer = new RecordingDispositionObserver();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {}, observer);
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      Leelaz secondEngine = reusableLocalKatago();
      ByteArrayOutputStream secondOutput = installOutput(secondEngine);
      try (HeldLifecycleReservation ignored = HeldLifecycleReservation.open(secondEngine)) {
        Lizzie.config.extraMode = ExtraMode.Double_Engine;
        Lizzie.leelaz2 = secondEngine;

        state.engine.sendCommand("komi 7.5");

        assertTrue(observer.reasons.isEmpty());
        assertEquals("800000000 stop\n", state.output.toString(StandardCharsets.UTF_8));
        assertEquals("", secondOutput.toString(StandardCharsets.UTF_8));
      }
      assertTrue(acquisition.lease().release());
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("komi 7.5\n"));
      assertEquals("", secondOutput.toString(StandardCharsets.UTF_8));
    } finally {
      Lizzie.leelaz2 = previousSecondEngine;
    }
  }

  @Test
  void preWriteRebindCancelsStatefulRequestsWithoutArmingState() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertTrue(state.engine.genmove("B", true));
      assertFalse(state.engine.isInputCommand);
      assertFalse(state.engine.isThinking);

      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      initializeStreams(state.engine, reboundOutput);

      assertFalse(state.engine.isInputCommand);
      assertFalse(state.engine.isThinking);
      assertEquals(List.of(), state.menu.transitions);
      assertEquals("", reboundOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void postWriteRebindCleansManualGenmoveStateExactlyOnce() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      ensureReaderStreamBinding(state.engine);

      assertTrue(state.engine.genmove("B", true));
      assertTrue(state.engine.isInputCommand);
      assertTrue(state.engine.isThinking);

      ByteArrayOutputStream secondReboundOutput = new ByteArrayOutputStream();
      initializeStreams(state.engine, secondReboundOutput);
      assertFalse(state.engine.isInputCommand);
      assertFalse(state.engine.isThinking);
      assertEquals(List.of(List.of(false, true), List.of(false, false)), state.menu.transitions);

      ByteArrayOutputStream thirdReboundOutput = new ByteArrayOutputStream();
      initializeStreams(state.engine, thirdReboundOutput);
      assertEquals(List.of(List.of(false, true), List.of(false, false)), state.menu.transitions);
    }
  }


  @Test
  void trackingFenceTimeoutsFailClosedAndReportOnce() throws Exception {
    TimeoutLeelaz initialEngine = reusableTimeoutKatago();
    initialEngine.initialStopTimeoutMillis = 25L;
    try (TestState state = TestState.open(initialEngine)) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());

      waitUntil(() -> closed.get() == 1);
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.INITIAL_STOP_TIMEOUT),
          acquisition.lease().failureReason());
      assertFalse(state.engine.isLoaded());
    }

    TimeoutLeelaz finalEngine = reusableTimeoutKatago();
    finalEngine.releaseStopTimeoutMillis = 25L;
    try (TestState state = TestState.open(finalEngine)) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().release());

      waitUntil(() -> closed.get() == 1);
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.FINAL_STOP_TIMEOUT),
          acquisition.lease().failureReason());
      assertFalse(state.engine.isLoaded());
    }
  }

  @Test
  void trackingFenceTimeoutsFailPendingHandoffExactlyOnce() throws Exception {
    TimeoutLeelaz initialEngine = reusableTimeoutKatago();
    initialEngine.initialStopTimeoutMillis = 25L;
    try (TestState state = TestState.open(initialEngine)) {
      RecordingHandoffTarget target = RecordingHandoffTarget.retained();
      state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);

      waitUntil(() -> target.failures.get() == 1);

      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(0, target.activations.get());
      assertEquals(1, target.failures.get());
    }

    TimeoutLeelaz finalEngine = reusableTimeoutKatago();
    finalEngine.releaseStopTimeoutMillis = 25L;
    try (TestState state = TestState.open(finalEngine)) {
      RecordingHandoffTarget target = RecordingHandoffTarget.foreground();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));
      Leelaz.TrackingHandoffClaim claim = state.engine.claimTrackingHandoff(target);

      waitUntil(() -> target.failures.get() == 1);

      assertEquals(Leelaz.TrackingHandoffState.FAILED, claim.state());
      assertEquals(0, target.activations.get());
      assertEquals(1, target.failures.get());
    }
  }

  @Test
  void partialActiveCommandWriteInvalidatesTransportAndClosesLease() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      installOutput(state.engine, Leelaz.createCommandOutputStream(new PartialCommandOutput(4)));

      assertFalse(acquisition.lease().send("kata-analyze B 10"));

      assertEquals(1, closed.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.ACTIVE_COMMAND_SEND_FAILED),
          acquisition.lease().failureReason());
      assertFalse(state.engine.isLoaded());
      assertEquals(null, currentOutput(state.engine));
    }
  }

  @Test
  void trackingAdmissionRejectsRemoteAndIncompleteCommandDiscovery() throws Exception {
    Leelaz remote = reusableLocalKatago();
    remote.useRemoteCompute = true;
    try (TestState state = TestState.open(remote)) {
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, acquisition.availability());
      assertEquals(null, acquisition.lease());
      assertEquals("", state.output.toString(StandardCharsets.UTF_8));
    }

    Leelaz missingAnalyze = reusableLocalKatago();
    missingAnalyze.commandLists.remove("kata-analyze");
    try (TestState state = TestState.open(missingAnalyze)) {
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY, acquisition.availability());
      assertEquals(null, acquisition.lease());
    }
  }

  @Test
  void initialStopSendFailureReturnsStableAcquisitionFailure() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      installOutput(
          state.engine,
          Leelaz.createCommandOutputStream(
              new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                  throw new IOException("simulated initial stop failure");
                }
              }));

      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      state.engine.sendCommand("version");

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, acquisition.availability());
      assertEquals(null, acquisition.lease());
      assertEquals(null, acquisition.receipt());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.INITIAL_STOP_SEND_FAILED),
          acquisition.failureReason());
      assertEquals(1, closed.get());
      assertFalse(state.engine.isLoaded());
      assertEquals(null, currentOutput(state.engine));
    }
  }

  @Test
  void initialBoundaryCannotPublishReadyBeforeWriteResult() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      BlockingFailingFlushOutput blockedOutput = new BlockingFailingFlushOutput();
      installOutput(state.engine, Leelaz.createCommandOutputStream(blockedOutput));
      AtomicInteger ready = new AtomicInteger();
      AtomicInteger closed = new AtomicInteger();
      AtomicReference<Leelaz.TrackingStreamLeaseAcquisition> acquisition = new AtomicReference<>();
      Thread acquireThread =
          new Thread(
              () ->
                  acquisition.set(
                      state.engine.acquireTrackingStreamLease(
                          line -> {},
                          lease -> ready.incrementAndGet(),
                          lease -> closed.incrementAndGet())),
              "tracking-initial-write-result");
      acquireThread.setDaemon(true);

      acquireThread.start();
      assertTrue(blockedOutput.flushStarted.await(1, TimeUnit.SECONDS));
      int readyBeforeWriteResult;
      int closedBeforeWriteResult;
      try {
        processCommandResponse(state.engine, "=800000000");
        assertTrue(dispatch(state.engine, ""));
        readyBeforeWriteResult = ready.get();
        closedBeforeWriteResult = closed.get();
      } finally {
        blockedOutput.failFlush.countDown();
      }
      acquireThread.join(1000L);

      assertFalse(acquireThread.isAlive());
      assertEquals(0, readyBeforeWriteResult);
      assertEquals(0, closedBeforeWriteResult);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, acquisition.get().availability());
      assertEquals(null, acquisition.get().lease());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.INITIAL_STOP_SEND_FAILED),
          acquisition.get().failureReason());
      assertEquals(0, ready.get());
      assertEquals(1, closed.get());
      assertFalse(state.engine.isLoaded());
    }
  }

  @Test
  void releaseBeforeActiveSendClaimDoesNotFailTheTransport() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertTrue(acquisition.lease().release());
      assertFalse(acquisition.lease().send("kata-analyze B 10"));
      assertEquals(java.util.Optional.empty(), acquisition.lease().failureReason());
      assertTrue(state.engine.isLoaded());
      assertEquals(
          "800000000 stop\n800000001 stop\n", state.output.toString(StandardCharsets.UTF_8));
      assertTrue(dispatch(state.engine, "=800000001"));
      assertTrue(dispatch(state.engine, ""));
      assertEquals(1, closed.get());
    }
  }

  @Test
  void activeSendFailureAfterClaimStillFailsClosedWhenReleaseRaces() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      BlockingOneShotPartialOutput partialOutput = new BlockingOneShotPartialOutput(4);
      installOutput(state.engine, Leelaz.createCommandOutputStream(partialOutput));
      AtomicReference<Boolean> sendResult = new AtomicReference<>();
      AtomicReference<Boolean> releaseResult = new AtomicReference<>();
      Thread sendThread =
          new Thread(
              () -> sendResult.set(acquisition.lease().send("kata-analyze B 10")),
              "tracking-send-claimed-failure");
      Thread releaseThread =
          new Thread(
              () -> releaseResult.set(acquisition.lease().release()),
              "tracking-release-after-send-claim");
      sendThread.setDaemon(true);
      releaseThread.setDaemon(true);

      sendThread.start();
      assertTrue(partialOutput.writeBlocked.await(1, TimeUnit.SECONDS));
      releaseThread.start();
      waitUntil(
          () -> releaseResult.get() != null || releaseThread.getState() == Thread.State.BLOCKED);
      try {
        assertTrue(dispatch(state.engine, "=800000002"));
        assertTrue(dispatch(state.engine, ""));
        assertEquals(0, closed.get());
        assertTrue(acquisition.lease().isOwned());
      } finally {
        partialOutput.failClaimedWrite.countDown();
      }
      sendThread.join(1000L);
      releaseThread.join(1000L);

      assertFalse(sendThread.isAlive());
      assertFalse(releaseThread.isAlive());
      assertFalse(sendResult.get());
      assertTrue(releaseResult.get());
      assertEquals(1, closed.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.ACTIVE_COMMAND_SEND_FAILED),
          acquisition.lease().failureReason());
      assertFalse(state.engine.isLoaded());
      assertEquals(null, currentOutput(state.engine));
    }
  }

  @Test
  void trackingLeaseAllowsOnlyOneActiveCommand() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));

      assertTrue(acquisition.lease().send("kata-analyze B 10"));
      assertFalse(acquisition.lease().send("kata-analyze W 10"));

      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n",
          state.output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void finalFenceDoesNotCloseBeforeAnalyzeTerminator() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));
      state.engine.sendCommand("version");

      assertTrue(dispatch(state.engine, "=800000002"));
      assertTrue(dispatch(state.engine, ""));

      assertEquals(0, closed.get());
      assertTrue(acquisition.lease().isOwned());
      assertFalse(state.output.toString(StandardCharsets.UTF_8).contains("version\n"));

      assertTrue(dispatch(state.engine, "=800000002"));
      assertTrue(dispatch(state.engine, ""));
      assertEquals(1, closed.get());
      assertTrue(state.output.toString(StandardCharsets.UTF_8).endsWith("version\n"));
    }
  }

  @Test
  void finalBoundaryCannotCloseBeforeWriteResult() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      AtomicInteger closed = new AtomicInteger();
      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet());
      processCommandResponse(state.engine, "=800000000");
      assertTrue(dispatch(state.engine, ""));
      assertTrue(acquisition.lease().send("kata-analyze B 10"));
      BlockingFailingFlushOutput blockedOutput = new BlockingFailingFlushOutput();
      installOutput(state.engine, Leelaz.createCommandOutputStream(blockedOutput));
      AtomicReference<Boolean> commandFinished = new AtomicReference<>(false);
      Thread releaseThread =
          new Thread(
              () -> {
                state.engine.sendCommand("version");
                commandFinished.set(true);
              },
              "tracking-final-write-result");
      releaseThread.setDaemon(true);

      releaseThread.start();
      assertTrue(blockedOutput.flushStarted.await(1, TimeUnit.SECONDS));
      assertTrue(dispatch(state.engine, ""));
      assertTrue(dispatch(state.engine, "=800000002"));
      AtomicReference<Throwable> frameFailure = new AtomicReference<>();
      Thread frameThread =
          new Thread(
              () -> {
                try {
                  dispatch(state.engine, "");
                } catch (Throwable failure) {
                  frameFailure.set(failure);
                }
              },
              "tracking-early-final-boundary");
      frameThread.setDaemon(true);
      frameThread.start();
      waitUntil(() -> !acquisition.lease().isOwned() || !frameThread.isAlive());
      boolean ownedBeforeWriteResult = acquisition.lease().isOwned();
      int closedBeforeWriteResult = closed.get();
      blockedOutput.failFlush.countDown();
      releaseThread.join(1000L);
      frameThread.join(1000L);

      assertFalse(releaseThread.isAlive());
      assertFalse(frameThread.isAlive());
      assertTrue(ownedBeforeWriteResult);
      assertEquals(0, closedBeforeWriteResult);
      assertEquals(null, frameFailure.get());
      assertTrue(commandFinished.get());
      assertEquals(1, closed.get());
      assertEquals(
          java.util.Optional.of(Leelaz.TrackingStreamLeaseFailure.FINAL_STOP_SEND_FAILED),
          acquisition.lease().failureReason());
      assertFalse(state.engine.isLoaded());
      assertFalse(blockedOutput.output.toString(StandardCharsets.UTF_8).contains("version\n"));
    }
  }

  @Test
  void trackingAdmissionRejectsForegroundBusinessOwners() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      EngineManager previousManager = Lizzie.engineManager;
      Leelaz dummy = new Leelaz("");
      EngineManager manager = new EngineManager(List.of(state.engine, dummy));
      try {
        Lizzie.engineManager = manager;
        EngineManager.resetEngineGameTransactionStateForTest();
        EngineManager.beginEngineGameTransaction(
            manager, EngineGamePlans.harness(0, 1, false), null, true);
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_GAME,
            state
                .engine
                .acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {})
                .availability());
      } finally {
        EngineManager.resetEngineGameTransactionStateForTest();
        Lizzie.engineManager = previousManager;
      }

      state.engine.isThinking = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.GENMOVE,
          state
              .engine
              .acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {})
              .availability());
      state.engine.isThinking = false;

      Lizzie.frame.isPlayingAgainstLeelaz = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.PLAY_MODE,
          state
              .engine
              .acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {})
              .availability());
      Lizzie.frame.isPlayingAgainstLeelaz = false;

      Lizzie.frame.isContributing = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE,
          state
              .engine
              .acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {})
              .availability());
    }
  }

  @Test
  void trackingAdmissionDoesNotWaitForOrdinaryWriteInFlight() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      BlockingOutput blockingOutput = new BlockingOutput();
      installOutput(state.engine, Leelaz.createCommandOutputStream(blockingOutput));
      Thread commandThread = new Thread(() -> state.engine.sendCommand("name"));
      commandThread.setDaemon(true);
      commandThread.start();
      assertTrue(blockingOutput.writeStarted.await(1, TimeUnit.SECONDS));

      Leelaz.TrackingStreamLeaseAcquisition acquisition =
          state.engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, acquisition.availability());
      assertEquals(null, acquisition.lease());
      blockingOutput.continueWrite.countDown();
      commandThread.join(1000L);
      assertFalse(commandThread.isAlive());
      assertFalse(blockingOutput.output.toString(StandardCharsets.UTF_8).contains("stop\n"));
    }
  }

  @Test
  void readerRebindWaitsForClaimedOrdinaryWriteBeforeCutover() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      ensureReaderStreamBinding(state.engine);
      BlockingOutput oldOutput = new BlockingOutput();
      installOutput(state.engine, Leelaz.createCommandOutputStream(oldOutput));
      Thread commandThread = new Thread(() -> state.engine.sendCommand("version"));
      commandThread.setDaemon(true);
      commandThread.start();
      assertTrue(oldOutput.writeStarted.await(1, TimeUnit.SECONDS));
      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebindThread =
          new Thread(
              () -> {
                try {
                  initializeStreams(state.engine, reboundOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "rebind-during-claimed-ordinary-write");
      rebindThread.setDaemon(true);
      rebindThread.start();
      waitUntil(
          () ->
              !rebindThread.isAlive()
                  || rebindThread.getState() == Thread.State.WAITING);
      boolean rebindWaitedForWrite = rebindThread.isAlive();
      oldOutput.continueWrite.countDown();
      commandThread.join(1000L);
      rebindThread.join(1000L);

      assertTrue(rebindWaitedForWrite);
      assertFalse(commandThread.isAlive());
      assertFalse(rebindThread.isAlive());
      assertEquals(null, rebindFailure.get());
      assertTrue(oldOutput.output.toString(StandardCharsets.UTF_8).contains("version\n"));
      assertEquals("", reboundOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void readerRebindRetiresSentTrackedLoadSgfHandlerAtCutover() throws Exception {
    try (TestState state = TestState.open(reusableLocalKatago())) {
      ensureReaderStreamBinding(state.engine);
      ByteArrayOutputStream oldOutput = installOutput(state.engine);
      AtomicInteger stateResetCallbacks = new AtomicInteger();
      sendTrackedLoadSgf(state.engine, stateResetCallbacks);
      assertTrue(oldOutput.toString(StandardCharsets.UTF_8).contains("loadsgf "));
      assertEquals(1, pendingResponseHandlerCount(state.engine));

      ByteArrayOutputStream reboundOutput = new ByteArrayOutputStream();
      initializeStreams(state.engine, reboundOutput);

      assertEquals(1, stateResetCallbacks.get());
      assertEquals(0, pendingResponseHandlerCount(state.engine));
      processCommandResponse(state.engine, "=1");
      assertEquals(1, stateResetCallbacks.get());
    }
  }

  private Leelaz reusableLocalKatago() throws Exception {
    Leelaz engine = new Leelaz("");
    configureLocalKatago(engine);
    createdEngines.add(engine);
    return engine;
  }

  private TimeoutLeelaz reusableTimeoutKatago() throws Exception {
    TimeoutLeelaz engine = new TimeoutLeelaz();
    configureLocalKatago(engine);
    createdEngines.add(engine);
    return engine;
  }

  private static void configureLocalKatago(Leelaz engine) throws Exception {
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.commandLists.addAll(List.of("stop", "kata-analyze"));
    Field capabilityDiscovery = Leelaz.class.getDeclaredField("endGetCommandList");
    capabilityDiscovery.setAccessible(true);
    capabilityDiscovery.set(engine, true);
  }

  private static ByteArrayOutputStream installOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    engine.installCommandOutputForTest(new BufferedOutputStream(output));
    return output;
  }

  private static void installOutput(Leelaz engine, BufferedOutputStream output) throws Exception {
    engine.installCommandOutputForTest(output);
  }

  private static BufferedOutputStream currentOutput(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    return (BufferedOutputStream) field.get(engine);
  }

  private static boolean readerStreamRebindInProgress(Leelaz engine) {
    try {
      Field field = Leelaz.class.getDeclaredField("readerStreamRebindInProgress");
      field.setAccessible(true);
      return field.getBoolean(engine);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void notifyEngineArbitrationWaiters(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("engineArbitrationLock");
    field.setAccessible(true);
    Object lock = field.get(engine);
    synchronized (lock) {
      lock.notifyAll();
    }
  }

  private static Object commandQueue(Leelaz engine) {
    try {
      Method method = Leelaz.class.getDeclaredMethod("commandQueue");
      method.setAccessible(true);
      return method.invoke(engine);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }


  private static int commandQueueSize(Leelaz engine) {
    try {
      Method method = Leelaz.class.getDeclaredMethod("commandQueue");
      method.setAccessible(true);
      return ((java.util.Collection<?>) method.invoke(engine)).size();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static int pendingResponseHandlerCount(Leelaz engine) {
    try {
      Field field = Leelaz.class.getDeclaredField("pendingResponseHandlers");
      field.setAccessible(true);
      Object handlers = field.get(engine);
      return handlers == null ? 0 : ((java.util.Collection<?>) handlers).size();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void closeExclusiveSessionForTest(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("exclusiveGtpSession");
    field.setAccessible(true);
    Object session = field.get(engine);
    if (session == null) {
      return;
    }
    Method cancelInitial =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpInitialStopTimeout", session.getClass());
    cancelInitial.setAccessible(true);
    cancelInitial.invoke(engine, session);
    Method cancelRelease =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpReleaseStopTimeout", session.getClass());
    cancelRelease.setAccessible(true);
    cancelRelease.invoke(engine, session);
    Method close = Leelaz.class.getDeclaredMethod("closeExclusiveGtpSession", session.getClass());
    close.setAccessible(true);
    close.invoke(engine, session);
  }

  private static void ensureReaderStreamBinding(Leelaz engine) {
    try {
      Method method = Leelaz.class.getDeclaredMethod("currentReaderStreamBinding");
      method.setAccessible(true);
      method.invoke(engine);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void initializeStreams(Leelaz engine, ByteArrayOutputStream output)
      throws Exception {
    initializeStreams(engine, "", output);
  }

  private static void initializeStreams(Leelaz engine, String stdout, ByteArrayOutputStream output)
      throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "initializeStreams",
            java.io.InputStream.class,
            java.io.OutputStream.class,
            java.io.InputStream.class);
    method.setAccessible(true);
    method.invoke(
        engine,
        new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8)),
        output,
        new ByteArrayInputStream(new byte[0]));
  }

  private static void enqueueThrowingTrackedLoadSgf(
      Leelaz engine, CountDownLatch callbackStarted, CountDownLatch failCallback) throws Exception {
    enqueueBlockingTrackedLoadSgf(
        engine,
        callbackStarted,
        failCallback,
        new IllegalStateException("simulated rebind callback failure"));
  }

  private static void sendTrackedLoadSgf(Leelaz engine, AtomicInteger stateResetCallbacks)
      throws Exception {
    Class<?> failureHandlerType =
        Class.forName("featurecat.lizzie.analysis.Leelaz$CommandSendFailureHandler");
    Object failureHandler =
        java.lang.reflect.Proxy.newProxyInstance(
            failureHandlerType.getClassLoader(),
            new Class<?>[] {failureHandlerType},
            (proxy, method, arguments) -> {
              if (method.getName().equals("onStateResetAfterOutputWrite")) {
                stateResetCallbacks.incrementAndGet();
              }
              return null;
            });
    Method method =
        Leelaz.class.getDeclaredMethod(
            "sendCommand",
            String.class,
            Runnable.class,
            failureHandlerType,
            boolean.class,
            boolean.class);
    method.setAccessible(true);
    method.invoke(
        engine, "loadsgf /tmp/tracking-rebind-sent.sgf", null, failureHandler, true, false);
  }

  private static void enqueueBlockingTrackedLoadSgf(
      Leelaz engine,
      CountDownLatch callbackStarted,
      CountDownLatch continueCallback,
      RuntimeException callbackFailure)
      throws Exception {
    enqueueBlockingTrackedLoadSgf(
        engine, callbackStarted, continueCallback, callbackFailure, null);
  }

  private static void enqueueBlockingTrackedLoadSgf(
      Leelaz engine,
      CountDownLatch callbackStarted,
      CountDownLatch continueCallback,
      RuntimeException callbackFailure,
      AtomicInteger stateResetCallbacks)
      throws Exception {
    Class<?> failureHandlerType =
        Class.forName("featurecat.lizzie.analysis.Leelaz$CommandSendFailureHandler");
    Object failureHandler =
        java.lang.reflect.Proxy.newProxyInstance(
            failureHandlerType.getClassLoader(),
            new Class<?>[] {failureHandlerType},
            (proxy, method, arguments) -> {
              if (stateResetCallbacks != null
                  && method.getName().equals("onStateResetAfterOutputWrite")) {
                stateResetCallbacks.incrementAndGet();
              }
              callbackStarted.countDown();
              if (!continueCallback.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to continue reset callback");
              }
              if (callbackFailure != null) {
                throw callbackFailure;
              }
              return null;
            });
    Method method =
        Leelaz.class.getDeclaredMethod(
            "sendCommand",
            String.class,
            Runnable.class,
            failureHandlerType,
            boolean.class,
            boolean.class);
    method.setAccessible(true);
    method.invoke(engine, "loadsgf /tmp/tracking-rebind.sgf", null, failureHandler, true, false);
  }

  private static void installInput(Leelaz engine, String input) throws Exception {
    Field field = Leelaz.class.getDeclaredField("inputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedReader(new StringReader(input)));
  }

  private static void invokeRead(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("read");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static boolean dispatch(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void waitUntil(Check condition) throws Exception {
    long deadline = System.currentTimeMillis() + 3000L;
    while (!condition.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(condition.get(), "timed out waiting for tracking lease state");
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    return (T) ((Unsafe) unsafeField.get(null)).allocateInstance(type);
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}
  }

  private static final class RecordingMenu extends Menu {
    private List<List<Boolean>> transitions;

    private RecordingMenu() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {
      transitions.add(List.of(isPondering, isThinking));
    }
  }

  private static final class RecordingBoard extends Board {
    private int resendCount;

    private RecordingBoard() {
      super();
    }

    @Override
    public void resendMoveToEngine(Leelaz engine, boolean loadEngine) {
      resendCount++;
    }
  }

  @FunctionalInterface
  private interface Check {
    boolean get() throws Exception;
  }

  private static final class PartialCommandOutput extends OutputStream {
    private int remainingSuccessfulBytes;

    private PartialCommandOutput(int remainingSuccessfulBytes) {
      this.remainingSuccessfulBytes = remainingSuccessfulBytes;
    }

    @Override
    public void write(int value) throws IOException {
      if (remainingSuccessfulBytes-- <= 0) {
        throw new IOException("simulated partial tracking write");
      }
    }
  }

  private static final class BlockingOutput extends OutputStream {
    private final CountDownLatch writeStarted = new CountDownLatch(1);
    private final CountDownLatch continueWrite = new CountDownLatch(1);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private boolean blocked;

    @Override
    public synchronized void write(int value) throws IOException {
      if (!blocked) {
        blocked = true;
        writeStarted.countDown();
        try {
          if (!continueWrite.await(1, TimeUnit.SECONDS)) {
            throw new IOException("timed out waiting to continue ordinary write");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
      }
      output.write(value);
    }
  }

  private static final class BlockingOneShotPartialOutput extends OutputStream {
    private final CountDownLatch writeBlocked = new CountDownLatch(1);
    private final CountDownLatch failClaimedWrite = new CountDownLatch(1);
    private int remainingSuccessfulBytes;
    private boolean failed;

    private BlockingOneShotPartialOutput(int remainingSuccessfulBytes) {
      this.remainingSuccessfulBytes = remainingSuccessfulBytes;
    }

    @Override
    public synchronized void write(int value) throws IOException {
      if (!failed && remainingSuccessfulBytes-- <= 0) {
        writeBlocked.countDown();
        try {
          if (!failClaimedWrite.await(1, TimeUnit.SECONDS)) {
            throw new IOException("timed out waiting to fail claimed tracking write");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
        failed = true;
        throw new IOException("simulated claimed partial tracking write");
      }
    }
  }

  private static final class BlockingFailingFlushOutput extends OutputStream {
    private final CountDownLatch flushStarted = new CountDownLatch(1);
    private final CountDownLatch failFlush = new CountDownLatch(1);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Override
    public synchronized void write(int value) {
      output.write(value);
    }

    @Override
    public void flush() throws IOException {
      flushStarted.countDown();
      try {
        if (!failFlush.await(1, TimeUnit.SECONDS)) {
          throw new IOException("timed out waiting to fail tracking flush");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
      throw new IOException("simulated tracking flush failure");
    }
  }

  private static final class TimeoutLeelaz extends Leelaz {
    private long initialStopTimeoutMillis = 8000L;
    private long releaseStopTimeoutMillis = 8000L;
    private boolean blockTimeout;
    private final CountDownLatch timeoutStarted = new CountDownLatch(1);
    private final CountDownLatch continueTimeout = new CountDownLatch(1);
    private final CountDownLatch timeoutFinished = new CountDownLatch(1);
    private final AtomicReference<Throwable> timeoutFailure = new AtomicReference<>();

    private TimeoutLeelaz() throws Exception {
      super("");
    }

    @Override
    protected long foregroundInitialStopTimeoutMillis() {
      return initialStopTimeoutMillis;
    }

    @Override
    protected long foregroundReleaseStopTimeoutMillis() {
      return releaseStopTimeoutMillis;
    }

    @Override
    void executeForegroundInitialStopTimeout(Runnable timeoutAction) {
      executeTimeout(timeoutAction);
    }

    @Override
    void executeForegroundReleaseStopTimeout(Runnable timeoutAction) {
      executeTimeout(timeoutAction);
    }

    private void executeTimeout(Runnable timeoutAction) {
      if (!blockTimeout) {
        timeoutAction.run();
        return;
      }
      timeoutStarted.countDown();
      try {
        if (!continueTimeout.await(1, TimeUnit.SECONDS)) {
          throw new IllegalStateException("timed out waiting to continue tracking timeout");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
      try {
        timeoutAction.run();
      } catch (Throwable failure) {
        timeoutFailure.compareAndSet(null, failure);
      } finally {
        timeoutFinished.countDown();
      }
    }
  }

  private static final class FeedbackRecordingLeelaz extends Leelaz {
    private final AtomicInteger feedbackCount = new AtomicInteger();

    private FeedbackRecordingLeelaz() throws Exception {
      super("");
    }

    @Override
    void showExclusiveGtpConflictMessage() {
      feedbackCount.incrementAndGet();
    }
  }

  private static final class PonderTrackingFrame extends LizzieFrame {
    private int redrawCount;
    private int refreshCount;

    @Override
    public void clearTryPlay() {}

    @Override
    public void clearSelectImage() {}

    @Override
    public void onMainEnginePonder() {}

    @Override
    public void resetTitle() {}

    @Override
    public void clearKataEstimate() {}

    @Override
    public void redrawBoardrendererBackground() {
      redrawCount++;
    }

    @Override
    public void refresh() {
      refreshCount++;
    }
  }

  private static final class HeldLifecycleReservation implements AutoCloseable {
    private final CountDownLatch acquired = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread owner;

    private HeldLifecycleReservation(Leelaz engine) {
      owner =
          new Thread(
              () -> {
                Leelaz.ExclusiveGtpLifecycleReservation reservation = null;
                try {
                  reservation = engine.beginExclusiveGtpLifecycleReservation();
                  if (reservation == null) {
                    throw new AssertionError("failed to acquire mirror lifecycle reservation");
                  }
                  acquired.countDown();
                  release.await();
                } catch (Throwable reservationFailure) {
                  failure.compareAndSet(null, reservationFailure);
                } finally {
                  acquired.countDown();
                  if (reservation != null) {
                    try {
                      reservation.close();
                    } catch (Throwable closeFailure) {
                      failure.compareAndSet(null, closeFailure);
                    }
                  }
                }
              },
              "held-mirror-lifecycle-reservation");
      owner.setDaemon(true);
    }

    private static HeldLifecycleReservation open(Leelaz engine) throws Exception {
      HeldLifecycleReservation held = new HeldLifecycleReservation(engine);
      held.owner.start();
      try {
        if (!held.acquired.await(2, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out acquiring mirror lifecycle reservation");
        }
        held.rethrowFailure();
        return held;
      } catch (Throwable openFailure) {
        try {
          held.close();
        } catch (Throwable cleanupFailure) {
          if (cleanupFailure != openFailure) {
            openFailure.addSuppressed(cleanupFailure);
          }
        }
        if (openFailure instanceof Exception) {
          throw (Exception) openFailure;
        }
        if (openFailure instanceof Error) {
          throw (Error) openFailure;
        }
        throw new AssertionError(openFailure);
      }
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      owner.join(TimeUnit.SECONDS.toMillis(2));
      if (owner.isAlive()) {
        throw new AssertionError("mirror lifecycle reservation owner did not stop");
      }
      rethrowFailure();
    }

    private void rethrowFailure() throws Exception {
      Throwable reservationFailure = failure.get();
      if (reservationFailure == null) {
        return;
      }
      if (reservationFailure instanceof Exception) {
        throw (Exception) reservationFailure;
      }
      if (reservationFailure instanceof Error) {
        throw (Error) reservationFailure;
      }
      throw new AssertionError(reservationFailure);
    }
  }

  private static class RecordingHandoffTarget implements Leelaz.TrackingHandoffTarget {
    private final Leelaz.TrackingHandoffKind kind;
    protected final AtomicInteger activations = new AtomicInteger();
    protected final AtomicInteger failures = new AtomicInteger();

    private RecordingHandoffTarget(Leelaz.TrackingHandoffKind kind) {
      this.kind = kind;
    }

    private static RecordingHandoffTarget foreground() {
      return new RecordingHandoffTarget(Leelaz.TrackingHandoffKind.FOREGROUND_ANALYSIS);
    }

    private static RecordingHandoffTarget retained() {
      return new RecordingHandoffTarget(Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE);
    }

    @Override
    public Leelaz.TrackingHandoffKind kind() {
      return kind;
    }

    @Override
    public boolean isCurrent() {
      return true;
    }

    @Override
    public void activate(Leelaz.TrackingHandoffActivation activation) {
      activations.incrementAndGet();
      if (kind == Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE) {
        assertTrue(activation.completeRetainedEngineMode());
      } else {
        assertTrue(activation.activateForegroundAnalysis(line -> {}, () -> {}));
      }
    }

    @Override
    public void fail(Leelaz.TrackingHandoffFailure failure) {
      failures.incrementAndGet();
    }
  }

  private static final class RecordingDispositionObserver
      implements Leelaz.TrackingReleaseDispositionObserver {
    private final List<Leelaz.TrackingReleaseReason> reasons = new ArrayList<>();
    private final List<Leelaz.TrackingReleaseDisposition> dispositions = new ArrayList<>();

    @Override
    public void onDispositionChanged(Leelaz.TrackingReleaseDisposition disposition) {
      dispositions.add(disposition);
    }

    @Override
    public void onReleaseClaimed(Leelaz.TrackingReleaseReason reason) {
      reasons.add(reason);
    }
  }

  private static final class BlockingHandoffTarget extends RecordingHandoffTarget {
    private final CountDownLatch activationStarted = new CountDownLatch(1);
    private final CountDownLatch allowCompletion = new CountDownLatch(1);
    private final CountDownLatch failureStarted = new CountDownLatch(1);
    private final CountDownLatch allowFailureCompletion = new CountDownLatch(1);
    private final Runnable beforeCompletion;
    private final boolean blockFailure;

    private BlockingHandoffTarget() {
      this(() -> {}, false);
    }

    private BlockingHandoffTarget(Runnable beforeCompletion, boolean blockFailure) {
      super(Leelaz.TrackingHandoffKind.RETAINED_ENGINE_MODE);
      this.beforeCompletion = beforeCompletion;
      this.blockFailure = blockFailure;
    }

    @Override
    public void activate(Leelaz.TrackingHandoffActivation activation) {
      activationStarted.countDown();
      try {
        assertTrue(allowCompletion.await(1, TimeUnit.SECONDS));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      beforeCompletion.run();
      super.activate(activation);
    }

    @Override
    public void fail(Leelaz.TrackingHandoffFailure failure) {
      failureStarted.countDown();
      if (blockFailure) {
        try {
          assertTrue(allowFailureCompletion.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
      super.fail(failure);
    }
  }

  private enum TrackingTimeoutPhase {
    INITIAL {
      @Override
      void useShortTimeout(TimeoutLeelaz engine) {
        engine.initialStopTimeoutMillis = 25L;
      }

      @Override
      void startTimeout(Leelaz engine, Leelaz.TrackingStreamLease lease) {}

      @Override
      Leelaz.TrackingStreamLeaseFailure failure() {
        return Leelaz.TrackingStreamLeaseFailure.INITIAL_STOP_TIMEOUT;
      }
    },
    FINAL {
      @Override
      void useShortTimeout(TimeoutLeelaz engine) {
        engine.releaseStopTimeoutMillis = 25L;
      }

      @Override
      void startTimeout(Leelaz engine, Leelaz.TrackingStreamLease lease) throws Exception {
        processCommandResponse(engine, "=800000000");
        assertTrue(dispatch(engine, ""));
        assertTrue(lease.release());
      }

      @Override
      Leelaz.TrackingStreamLeaseFailure failure() {
        return Leelaz.TrackingStreamLeaseFailure.FINAL_STOP_TIMEOUT;
      }
    };

    abstract void useShortTimeout(TimeoutLeelaz engine);

    abstract void startTimeout(Leelaz engine, Leelaz.TrackingStreamLease lease) throws Exception;

    void startTimeoutAfterPendingLoadSgf(Leelaz engine, Leelaz.TrackingStreamLease lease)
        throws Exception {
      startTimeout(engine, lease);
    }

    abstract Leelaz.TrackingStreamLeaseFailure failure();
  }

  private enum SuccessfulTrackingClosePhase {
    ACQUIRING {
      @Override
      void prepareCloseBoundary(Leelaz engine, Leelaz.TrackingStreamLease lease)
          throws Exception {
        assertTrue(lease.release());
        processCommandResponse(engine, "=800000000");
      }
    },
    FINAL {
      @Override
      void prepareCloseBoundary(Leelaz engine, Leelaz.TrackingStreamLease lease)
          throws Exception {
        processCommandResponse(engine, "=800000000");
        assertTrue(dispatch(engine, ""));
        assertTrue(lease.release());
        assertTrue(dispatch(engine, "=800000001"));
      }
    };

    abstract void prepareCloseBoundary(Leelaz engine, Leelaz.TrackingStreamLease lease)
        throws Exception;
  }

  private static final class TestState implements AutoCloseable {
    private final Leelaz previousEngine;
    private final LizzieFrame previousFrame;
    private final Config previousConfig;
    private final GtpConsolePane previousGtpConsole;
    private final Menu previousMenu;
    private final Leelaz engine;
    private final ByteArrayOutputStream output;
    private final RecordingMenu menu;

    private TestState(Leelaz engine) throws Exception {
      previousEngine = Lizzie.leelaz;
      previousFrame = Lizzie.frame;
      previousConfig = Lizzie.config;
      previousGtpConsole = Lizzie.gtpConsole;
      previousMenu = LizzieFrame.menu;
      this.engine = engine;
      output = installOutput(engine);
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      menu = allocate(RecordingMenu.class);
      menu.transitions = new ArrayList<>();
      LizzieFrame.menu = menu;
    }

    private static TestState open(Leelaz engine) throws Exception {
      return new TestState(engine);
    }

    @Override
    public void close() throws Exception {
      closeExclusiveSessionForTest(engine);
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
      LizzieFrame.menu = previousMenu;
    }
  }
}
