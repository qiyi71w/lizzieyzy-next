package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class ReadBoardLoggingHandshakeTest {
  private static final String CAPABILITY =
      "readboardLoggingV1 dGVzdFByb2Nlc3NJRA on off off healthy 0";

  @Test
  void parseLineCapabilityEstablishesProcessSessionWithoutSyncDispatch() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "tempcount", new java.util.ArrayList<Integer>());

    board.parseLine(CAPABILITY);
    board.parseLine("re=" + String.join(",", java.util.Collections.nCopies(19, "1")));

    assertEquals("dGVzdFByb2Nlc3NJRA", control.processSessionId());
    assertEquals(ReadBoardLoggingControl.Status.CAPABILITY_READY, control.status());
  }

  @Test
  void requestLoggingSetWritesFrozenSetLine() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    BufferedOutputStream output = new BufferedOutputStream(buffer);
    setField(board, "outputStream", output);
    board.parseLine(CAPABILITY);

    assertTrue(board.requestLoggingSet(true, false, true));
    output.flush();

    String sent = buffer.toString("UTF-8").trim();
    assertTrue(sent.startsWith("readboardLoggingSet "), sent);
    assertTrue(sent.endsWith(" on off on"), sent);
    ReadBoardLoggingProtocol.SetRequest parsed = ReadBoardLoggingProtocol.tryParseSet(sent);
    assertEquals(ReadBoardLoggingProtocol.Toggle.ON, parsed.diagnostics);
    assertEquals(ReadBoardLoggingProtocol.Toggle.OFF, parsed.capture);
    assertEquals(ReadBoardLoggingProtocol.Toggle.ON, parsed.trace);
  }

  @Test
  void completeLaunchArgumentsAppendsCaptureOffOnWindowsPath() throws Exception {
    List<String> positional = Arrays.asList("yzy", " ", " ", " ", "0", "cn", "-1");
    List<String> args =
        ReadBoardLoggingControl.appendNamedLoggingArguments(
            positional, "C:\\work\\logs\\readboard", "dGVzdEhvc3RTZXNzaW9u", false);
    assertEquals(17, args.size());
    assertEquals("off", args.get(16));
    assertFalse(args.contains("--trace"));
  }

  @Test
  void completeLaunchArgumentsPreservesLoggingAndTransactionPath(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    featurecat.lizzie.logging.LoggingRuntime.resetForTests();
    var runtime = featurecat.lizzie.logging.LoggingRuntime.initialize(
        new featurecat.lizzie.logging.WorkDirectoryResolution(directory, List.of()));
    try {
      ReadBoard board = allocateBoard();
      List<String> positional = Arrays.asList("yzy", " ", " ", " ", "0", "cn", "-1");
      List<String> ordinary = board.completeLaunchArguments(positional);
      List<String> expected = ReadBoardLoggingControl.appendNamedLoggingArguments(
          positional, ReadBoardLoggingControl.readBoardLogDirectory(runtime.logsDirectory()),
          runtime.applicationLogSessionId(), runtime.settings().diagnosticsEnabled());
      assertEquals(expected, ordinary);
      assertThrows(UnsupportedOperationException.class, () -> ordinary.add("extra"));
      if (java.io.File.separatorChar == '\\') assertEquals(17, ordinary.size());

      java.nio.file.Path transaction = directory.resolve("update with spaces").resolve("request.json");
      setField(board, "updateTransaction", transaction);
      List<String> restarting = board.completeLaunchArguments(positional);
      assertEquals(ordinary, restarting.subList(0, ordinary.size()));
      assertEquals(List.of("--update-transaction", transaction.toString()),
          restarting.subList(ordinary.size(), restarting.size()));
      assertEquals(positional, restarting.subList(0, 7));
    } finally {
      runtime.shutdown();
      featurecat.lizzie.logging.LoggingRuntime.resetForTests();
    }
  }

  @Test
  void loggingSnapshotIsDetachedUntilControlExists() throws Exception {
    ReadBoard board = allocateBoard();

    ReadBoardLoggingSnapshot snapshot = board.loggingSnapshot();

    assertFalse(snapshot.attached());
    assertEquals(
        ReadBoardLoggingControl.Presentation.UNKNOWN, snapshot.diagnosticsPresentation());
    assertEquals("no capture session", snapshot.captureSummary());
  }


  @Test
  void requestLoggingSetRequiresProcessSession() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    BufferedOutputStream output = new BufferedOutputStream(buffer);
    setField(board, "outputStream", output);

    assertFalse(board.requestLoggingSet(false, true, true));
    output.flush();
    assertEquals("", buffer.toString("UTF-8"));

    board.parseLine(CAPABILITY);
    assertTrue(board.requestLoggingSet(true, false, false));
    output.flush();
    assertTrue(buffer.toString("UTF-8").contains("readboardLoggingSet "));
  }

  @Test
  void setAcknowledgementTimeoutLeavesUnknownNotSuccess() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    control.onCapability(ReadBoardLoggingProtocol.tryParseCapability(CAPABILITY));
    control.beginSet(true, false, false);
    board.markCapabilityTimeout();
    assertEquals(ReadBoardLoggingControl.Status.UNKNOWN, control.status());
    assertEquals(ReadBoardLoggingControl.Presentation.UNKNOWN, control.presentation(true, ReadBoardLoggingProtocol.Toggle.ON, ReadBoardLoggingProtocol.Persistence.HEALTHY));
  }

  @Test
  void loggingTimeoutDoesNotUseBlockedReaderExecutor() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    setField(board, "outputStream", new BufferedOutputStream(buffer));
    ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor();
    setField(board, "executor", reader);
    CountDownLatch block = new CountDownLatch(1);
    reader.execute(
        () -> {
          try {
            block.await();
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        });
    board.parseLine(CAPABILITY);
    assertTrue(board.requestLoggingSet(true, false, false));
    ScheduledExecutorService timeoutExecutor =
        (ScheduledExecutorService) getField(board, "loggingTimeoutExecutor");
    assertTrue(timeoutExecutor != null);
    assertTrue(timeoutExecutor != reader);
    block.countDown();
    reader.shutdownNow();
    timeoutExecutor.shutdownNow();
  }

  @Test
  void acknowledgementAndTimeoutSettleOnlyTheirExactGeneration() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    setField(board, "outputStream", new BufferedOutputStream(buffer));
    ManualLoggingScheduler scheduler = new ManualLoggingScheduler();
    setField(board, "loggingTimeoutExecutor", scheduler);
    board.parseLine(CAPABILITY);

    assertTrue(board.requestLoggingSet(true, false, false));
    Runnable firstTimeout = scheduler.take();
    String firstObserved = observedFor(buffer.toString("UTF-8").trim());
    CountDownLatch timeoutMayRun = new CountDownLatch(1);
    AtomicReference<Throwable> timeoutFailure = new AtomicReference<>();
    Thread timeoutThread =
        new Thread(
            () -> {
              try {
                if (!timeoutMayRun.await(5, TimeUnit.SECONDS)) {
                  throw new AssertionError("stale timeout release timed out");
                }
                firstTimeout.run();
              } catch (Throwable failure) {
                timeoutFailure.set(failure);
              }
            },
            "readboard-logging-stale-timeout-test");
    timeoutThread.start();
    board.parseLine(firstObserved);
    timeoutMayRun.countDown();
    timeoutThread.join(5000);

    assertFalse(timeoutThread.isAlive());
    assertNull(timeoutFailure.get());
    assertEquals(ReadBoardLoggingControl.Status.OBSERVED, control.status());

    buffer.reset();
    assertTrue(board.requestLoggingSet(false, false, true));
    Runnable secondTimeout = scheduler.take();
    String lateObserved = observedFor(buffer.toString("UTF-8").trim());
    secondTimeout.run();
    assertEquals(ReadBoardLoggingControl.Status.UNKNOWN, control.status());
    board.parseLine(lateObserved);
    assertEquals(ReadBoardLoggingControl.Status.UNKNOWN, control.status());
    scheduler.shutdownNow();
  }

  @Test
  void disconnectDuringRequestCannotArmOrReviveTheOldWait() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    BlockingOutputStream blockedOutput = new BlockingOutputStream();
    setField(board, "outputStream", new BufferedOutputStream(blockedOutput));
    control.onCapability(ReadBoardLoggingProtocol.tryParseCapability(CAPABILITY));
    AtomicReference<Boolean> requestResult = new AtomicReference<>();
    AtomicReference<Throwable> requestFailure = new AtomicReference<>();
    Thread requestThread =
        new Thread(
            () -> {
              try {
                requestResult.set(board.requestLoggingSet(true, false, false));
              } catch (Throwable failure) {
                requestFailure.set(failure);
              }
            },
            "readboard-logging-disconnect-request-test");
    requestThread.start();
    assertTrue(blockedOutput.writeEntered.await(5, TimeUnit.SECONDS));

    board.disconnectLoggingControl();
    blockedOutput.releaseWrite.countDown();
    requestThread.join(5000);

    assertFalse(requestThread.isAlive());
    assertNull(requestFailure.get());
    assertEquals(Boolean.FALSE, requestResult.get());
    assertEquals(ReadBoardLoggingControl.Status.UNKNOWN, control.status());
    assertNull(control.processSessionId());
    assertNull(getField(board, "loggingTimeoutExecutor"));
  }

  @Test
  void shutdownRacingScheduleRejectsOnceAndNeverRecreatesExecutor() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    setField(board, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    control.onCapability(ReadBoardLoggingProtocol.tryParseCapability(CAPABILITY));
    BlockingShutdownScheduler scheduler = new BlockingShutdownScheduler();
    setField(board, "loggingTimeoutExecutor", scheduler);
    AtomicReference<Boolean> requestResult = new AtomicReference<>();
    AtomicReference<Throwable> requestFailure = new AtomicReference<>();
    Thread requestThread =
        new Thread(
            () -> {
              try {
                requestResult.set(board.requestLoggingSet(true, false, false));
              } catch (Throwable failure) {
                requestFailure.set(failure);
              }
            },
            "readboard-logging-shutdown-schedule-test");
    requestThread.start();
    assertTrue(scheduler.scheduleEntered.await(5, TimeUnit.SECONDS));

    board.shutdownLoggingTimeoutExecutor();
    scheduler.releaseSchedule.countDown();
    requestThread.join(5000);

    assertFalse(requestThread.isAlive());
    assertNull(requestFailure.get());
    assertEquals(Boolean.FALSE, requestResult.get());
    assertEquals(ReadBoardLoggingControl.Status.UNKNOWN, control.status());
    assertTrue(scheduler.isShutdown());
    assertNull(getField(board, "loggingTimeoutExecutor"));
    assertFalse(board.requestLoggingSet(false, false, true));
    assertNull(getField(board, "loggingTimeoutExecutor"));
  }

  @Test
  void schedulingRejectionFailsApplyingStateClosed() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    setField(board, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    control.onCapability(ReadBoardLoggingProtocol.tryParseCapability(CAPABILITY));
    RejectingLoggingScheduler scheduler = new RejectingLoggingScheduler();
    setField(board, "loggingTimeoutExecutor", scheduler);

    assertFalse(board.requestLoggingSet(true, false, false));

    assertEquals(ReadBoardLoggingControl.Status.UNKNOWN, control.status());
    assertEquals(
        ReadBoardLoggingControl.Presentation.UNKNOWN,
        control.snapshot().diagnosticsPresentation());
    board.shutdownLoggingTimeoutExecutor();
  }

  @Test
  void physicalSendErrorFailsApplyingStateBeforeItEscapes() throws Exception {
    ReadBoard board = allocateBoard();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    setField(board, "loggingControl", control);
    setField(board, "usePipe", true);
    setField(board, "outputStream", new BufferedOutputStream(new ErrorOutputStream()));
    control.onCapability(ReadBoardLoggingProtocol.tryParseCapability(CAPABILITY));

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> board.requestLoggingSet(true, false, false));

    assertEquals("controlled logging write failure", failure.getMessage());
    assertEquals(ReadBoardLoggingControl.Status.UNKNOWN, control.status());
    assertNull(getField(board, "loggingTimeoutExecutor"));
  }

  private static String observedFor(String setLine) {
    ReadBoardLoggingProtocol.SetRequest request =
        ReadBoardLoggingProtocol.tryParseSet(setLine);
    return ReadBoardLoggingProtocol.formatObserved(
        new ReadBoardLoggingProtocol.Observed(
            request.requestId,
            "dGVzdFByb2Nlc3NJRA",
            request.diagnostics,
            request.capture,
            request.trace,
            ReadBoardLoggingProtocol.Persistence.HEALTHY,
            0,
            ReadBoardLoggingProtocol.Reason.APPLIED));
  }

  private static final class ManualLoggingScheduler extends ScheduledThreadPoolExecutor {
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();

    private ManualLoggingScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(
        Runnable command, long delay, TimeUnit unit) {
      scheduled.add(command);
      return InertScheduledFuture.INSTANCE;
    }

    private Runnable take() throws InterruptedException {
      Runnable command = scheduled.poll(5, TimeUnit.SECONDS);
      if (command == null) {
        throw new AssertionError("logging timeout was not scheduled");
      }
      return command;
    }
  }

  private static final class BlockingShutdownScheduler extends ScheduledThreadPoolExecutor {
    private final CountDownLatch scheduleEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSchedule = new CountDownLatch(1);

    private BlockingShutdownScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(
        Runnable command, long delay, TimeUnit unit) {
      scheduleEntered.countDown();
      try {
        if (!releaseSchedule.await(5, TimeUnit.SECONDS)) {
          throw new RejectedExecutionException("test schedule release timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new RejectedExecutionException("test schedule interrupted", interrupted);
      }
      return super.schedule(command, delay, unit);
    }
  }

  private static final class RejectingLoggingScheduler extends ScheduledThreadPoolExecutor {
    private RejectingLoggingScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(
        Runnable command, long delay, TimeUnit unit) {
      throw new RejectedExecutionException("controlled logging timeout rejection");
    }
  }

  private static final class InertScheduledFuture implements ScheduledFuture<Object> {
    private static final InertScheduledFuture INSTANCE = new InertScheduledFuture();

    private InertScheduledFuture() {}

    @Override
    public long getDelay(TimeUnit unit) {
      return Long.MAX_VALUE;
    }

    @Override
    public int compareTo(java.util.concurrent.Delayed other) {
      return other == this ? 0 : 1;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return false;
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  }

  private static final class BlockingOutputStream extends OutputStream {
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);

    @Override
    public void write(int value) throws IOException {
      awaitRelease();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      awaitRelease();
    }

    private void awaitRelease() throws IOException {
      writeEntered.countDown();
      try {
        if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
          throw new IOException("test write release timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("test write interrupted", interrupted);
      }
    }
  }

  private static final class ErrorOutputStream extends OutputStream {
    @Override
    public void write(int value) {
      throw new AssertionError("controlled logging write failure");
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      throw new AssertionError("controlled logging write failure");
    }
  }

  private static ReadBoard allocateBoard() throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    Unsafe unsafe = (Unsafe) field.get(null);
    return (ReadBoard) unsafe.allocateInstance(ReadBoard.class);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = ReadBoard.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = ReadBoard.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
