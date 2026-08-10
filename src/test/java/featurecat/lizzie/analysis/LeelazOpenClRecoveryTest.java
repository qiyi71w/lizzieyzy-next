package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LeelazOpenClRecoveryTest {
  @Test
  void openClRecoveryCapturesRestoreBeforeLifecycleReservationAndStart() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-opencl-prepared-restore");
    PreparedRecoveryLeelaz engine = new PreparedRecoveryLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Lizzie.board = board;
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(SilentRecoveryFrame.class);
      LizzieFrame.menu = allocate(SilentRecoveryMenu.class);
      LizzieFrame.toolbar = allocate(SilentRecoveryToolbar.class);
      engine.mutateOnAttempt = () -> mutateHistory(board.getHistory());
      engine.mutateOnStart = () -> mutateHistory(board.getHistory());
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      ExitedProcess process = new ExitedProcess((int) 0xC0000409L);
      setField(engine, "process", process);
      setField(
          engine,
          "inputStream",
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;

      assertTrue(invokeOpenClRecovery(engine));
      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(board.preparedRestoreReceived);
      assertFalse(board.genericRestoreReceived);
      assertTrue(engine.loadedSgf.contains("AB[dd]"));
      assertTrue(engine.loadedSgf.contains("KM[6.5]"));
      assertNotNull(engine.restartAttempt);
      engine.restartAttempt.close();
    } finally {
      if (engine.restartAttempt != null) {
        engine.restartAttempt.close();
      }
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
    }
  }

  @Test
  void automaticRestartWaitsForTheFullStartupCommandSequence() {
    assertFalse(Leelaz.automaticRestartReady(false, false, true));
    assertFalse(Leelaz.automaticRestartReady(true, true, true));
    assertFalse(Leelaz.automaticRestartReady(true, false, false));
    assertTrue(Leelaz.automaticRestartReady(true, false, true));
  }

  @Test
  void currentOpenClNativeEofStartsAtMostOneAutomaticRecovery() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-opencl-recovery");
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      RecordingRecoveryLeelaz engine = new RecordingRecoveryLeelaz();
      ExitedProcess process = new ExitedProcess((int) 0xC0000409L);
      setField(engine, "process", process);
      setField(
          engine,
          "inputStream",
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;

      invokeRead(engine);
      assertTrue(engine.recoveryStarted.await(2, TimeUnit.SECONDS));
      assertFalse(invokeOpenClRecovery(engine));
      assertEquals(1, process.destroyCount);
      assertFalse(engine.isStarted());
      assertEquals(1, engine.restartCount);
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void staleOpenClNativeEofDoesNotStartRecoveryOrDestroyReboundProcess() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-stale-opencl-recovery");
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      RecordingRecoveryLeelaz engine = new RecordingRecoveryLeelaz();
      BlockingEofInputStream oldStdout = new BlockingEofInputStream();
      ExitedProcess oldProcess = new ExitedProcess((int) 0xC0000409L, oldStdout);
      setField(engine, "process", oldProcess);
      initializeStreams(engine, oldProcess);
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;

      AtomicReference<Throwable> readerFailure = new AtomicReference<>();
      Thread oldReader =
          new Thread(
              () -> {
                try {
                  invokeRead(engine);
                } catch (Throwable failure) {
                  readerFailure.set(failure);
                }
              },
              "stale-opencl-reader");
      oldReader.setDaemon(true);
      oldReader.start();
      assertTrue(oldStdout.awaitRead());

      ExitedProcess newProcess = new ExitedProcess(0);
      setField(engine, "process", newProcess);
      initializeStreams(engine, newProcess);
      oldStdout.release();
      oldReader.join(1000L);

      assertFalse(oldReader.isAlive());
      assertEquals(null, readerFailure.get());
      assertEquals(0, oldProcess.destroyCount);
      assertEquals(0, newProcess.destroyCount);
      assertEquals(0, engine.restartCount);
      assertTrue(engine.isStarted());
      assertTrue(engine.isLoaded());
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void asyncOpenClRecoveryFailureFailsClosedAndReleasesLifecycleAttempt() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-opencl-async-failure");
    AsyncOpenClRecoveryLeelaz engine = new AsyncOpenClRecoveryLeelaz();
    Leelaz.AutomaticRestartAttempt afterFailure = null;
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Lizzie.board = preparedRestoreBoard();
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(SilentRecoveryFrame.class);
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      ExitedProcess process = new ExitedProcess((int) 0xC0000409L);
      setField(engine, "process", process);
      setField(
          engine,
          "inputStream",
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;

      assertTrue(
          invokeOpenClRecovery(engine),
          "the production OpenCL entry must admit the automatic recovery attempt");
      assertTrue(
          engine.diagnosticInvoked.await(3, TimeUnit.SECONDS),
          "the asynchronous recovery failure must reach the existing diagnostic path");

      assertTrue(engine.isDownWithError, "the async recovery failure must mark the engine down");
      assertFalse(engine.isLoaded(), "the async recovery failure must fail the engine closed");
      assertEquals(1, engine.startCount, "the recovery must start the engine exactly once");
      assertFalse(engine.isPondering(), "no ponder after the async recovery failure");
      assertTrue(
          engine.transport.commands().isEmpty(),
          "no ponder or analyze commands after the async recovery failure");
      assertFalse(
          engine.isInitialBoardSynchronizationActive(),
          "the async recovery failure must release the board synchronization barriers");
      assertNotNull(
          engine.diagnosticMessage,
          "the diagnostic path must receive the OpenCL recovery failure detail");
      assertTrue(
          engine.diagnosticMessage.contains("NVIDIA OpenCL compatibility recovery failed"),
          "the diagnostic must describe the OpenCL recovery failure");
      assertEquals(
          EngineStartupStatus.State.START_FAILED,
          Lizzie.engineStartupStatus.snapshot().state,
          "the real diagnostic must publish the startup failure");

      afterFailure = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(
          afterFailure,
          "the async recovery failure must release the lifecycle attempt for a fresh admission");
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
    } finally {
      if (afterFailure != null) {
        afterFailure.close();
      }
      Lizzie.engineStartupStatus.ready();
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  private static Path createOpenClEngine(Path tempRoot) throws IOException {
    Path engineDirectory = Files.createDirectories(tempRoot.resolve("engines/katago/windows-x64"));
    Files.writeString(engineDirectory.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
    return touch(engineDirectory.resolve("katago.exe"));
  }

  private static Path touch(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[0]);
  }

  private static void invokeRead(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("read");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static boolean invokeOpenClRecovery(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("tryRecoverBundledOpenClNativeExit");
    method.setAccessible(true);
    return (Boolean) method.invoke(engine);
  }

  private static void initializeStreams(Leelaz engine, Process process) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "initializeStreams", InputStream.class, OutputStream.class, InputStream.class);
    method.setAccessible(true);
    method.invoke(
        engine, process.getInputStream(), process.getOutputStream(), process.getErrorStream());
  }

  private static void setField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static void restoreProperty(String name, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, previousValue);
    }
  }

  private static PreparedRestoreBoard preparedRestoreBoard() throws Exception {
    BoardData snapshot = BoardData.empty(19, 19);
    snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
    BoardHistoryList history = new BoardHistoryList(snapshot);
    history.getGameInfo().setKomiNoMenu(6.5);
    PreparedRestoreBoard board = allocate(PreparedRestoreBoard.class);
    board.restoreCompleted = new CountDownLatch(1);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static void mutateHistory(BoardHistoryList history) {
    history.getStart().getData().stones[Board.getIndex(3, 3)] = Stone.EMPTY;
    history.getGameInfo().setKomiNoMenu(7.5);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class PreparedRecoveryLeelaz extends Leelaz {
    private Runnable mutateOnAttempt;
    private Runnable mutateOnStart;
    private Leelaz.AutomaticRestartAttempt restartAttempt;
    private String loadedSgf = "";

    private PreparedRecoveryLeelaz() throws Exception {
      super("controlled-engine");
      installProtocol();
    }

    private void installProtocol() {
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            if (command.startsWith("loadsgf ")) {
              loadedSgf = Files.readString(Path.of(command.substring("loadsgf ".length())));
            }
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
    }

    @Override
    public Leelaz.AutomaticRestartAttempt beginAutomaticEngineRestartAttempt() {
      restartAttempt = super.beginAutomaticEngineRestartAttempt();
      if (restartAttempt != null && mutateOnAttempt != null) {
        mutateOnAttempt.run();
      }
      return restartAttempt;
    }

    @Override
    public void startEngine(int index) {
      if (mutateOnStart != null) {
        mutateOnStart.run();
      }
      started = true;
      isLoaded = true;
      isCheckingName = false;
      installProtocol();
      try {
        setField(this, "endGetCommandList", true);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  private static final class SilentRecoveryFrame extends LizzieFrame {
    @Override
    public void prepareQuickAnalysisForPrimaryOpenClRecovery() {}

    @Override
    public void reSetLoc() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  private static final class SilentRecoveryMenu extends Menu {
    @Override
    public void showPda(boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {}
  }

  private static final class SilentRecoveryToolbar extends BottomToolbar {
    @Override
    public void reSetButtonLocation() {}
  }

  private static final class PreparedRestoreBoard extends Board {
    private CountDownLatch restoreCompleted;
    private boolean preparedRestoreReceived;
    private boolean genericRestoreReceived;

    @Override
    public void resendMoveToEngine(
        Leelaz engine,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      if (preparedRestore == null) {
        genericRestoreReceived = true;
      } else {
        preparedRestoreReceived = true;
        preparedRestore.execute();
      }
      restoreCompleted.countDown();
    }

    @Override
    public void resendMoveToEngine(Leelaz engine, boolean loadEngine) {
      genericRestoreReceived = true;
      restoreCompleted.countDown();
    }
  }

  private static final class RecordingRecoveryLeelaz extends Leelaz {
    private final CountDownLatch recoveryStarted = new CountDownLatch(1);
    private int restartCount;

    private RecordingRecoveryLeelaz() throws Exception {
      super("");
    }

    @Override
    public void startEngine(int index) throws IOException {
      restartCount++;
      recoveryStarted.countDown();
    }
  }

  /**
   * Fixture that starts the engine but never completes startup, so the asynchronous readiness gate
   * fails after {@code restartClosedEngine} returns and routes the failure through the production
   * OpenCL failure handler (down-with-error plus the existing diagnostic entry).
   */
  private static final class AsyncOpenClRecoveryLeelaz extends Leelaz {
    private final CountDownLatch diagnosticInvoked = new CountDownLatch(1);
    private volatile String diagnosticMessage;
    private volatile int startCount;
    private volatile ExactSnapshotRestoreProtocolFixture.Transport transport;

    private AsyncOpenClRecoveryLeelaz() throws Exception {
      super("controlled-engine");
    }

    @Override
    long engineStartupSynchronizationTimeoutMillis() {
      return 25L;
    }

    @Override
    public void startEngine(int index) {
      startCount++;
      started = true;
      isCheckingName = false;
      transport =
          ExactSnapshotRestoreProtocolFixture.install(
              this, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      // Intentionally never becomes ready: isLoaded and endGetCommandList stay false, so the
      // asynchronous readiness gate fails after restartClosedEngine returns.
    }

    @Override
    public void tryToDignostic(String message, boolean isModal) {
      diagnosticMessage = message;
      try {
        super.tryToDignostic(message, isModal);
      } finally {
        diagnosticInvoked.countDown();
      }
    }
  }

  private static final class ExitedProcess extends Process {
    private final int exitCode;
    private final InputStream stdout;
    private int destroyCount;

    private ExitedProcess(int exitCode) {
      this(exitCode, new ByteArrayInputStream(new byte[0]));
    }

    private ExitedProcess(int exitCode, InputStream stdout) {
      this.exitCode = exitCode;
      this.stdout = stdout;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return exitCode;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {
      destroyCount++;
    }
  }

  private static final class BlockingEofInputStream extends InputStream {
    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      reading.countDown();
      try {
        if (!released.await(2, TimeUnit.SECONDS)) {
          throw new IOException("timed out waiting to release stale EOF");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
      return -1;
    }

    private boolean awaitRead() throws InterruptedException {
      return reading.await(1, TimeUnit.SECONDS);
    }

    private void release() {
      released.countDown();
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = load();

    private static sun.misc.Unsafe load() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException failure) {
        throw new ExceptionInInitializerError(failure);
      }
    }
  }
}
