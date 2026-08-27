package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LeelazPonderStateTest {
  @Test
  void enginePlayLineDuringNormalAnalysisDoesNotStopPonder() throws Exception {
    try (TestHarness ignored = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      engine.Pondering();

      invokeParseLine(engine, "play D4");

      assertTrue(engine.isPondering());
    }
  }

  @Test
  void kataNameStartupCommandsDoNotHoldEngineMonitorWhileCommandQueueIsBlocked()
      throws Exception {
    try (TestHarness ignored = TestHarness.open()) {
      StartupWorkerLeelaz engine = new StartupWorkerLeelaz();
      engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      Lizzie.engineManager =
          new EngineManager(new java.util.ArrayList<>(java.util.List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isCheckingName = true;
      engine.isCheckingVersion = true;
      Object commandQueue = commandQueue(engine);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      Future<?> parseFuture = null;
      try {
        synchronized (commandQueue) {
          CountDownLatch parseStarted = new CountDownLatch(1);
          parseFuture =
              executor.submit(
                  () -> {
                    parseStarted.countDown();
                    invokeParseLineUnchecked(engine, "= KataGo");
                  });
          assertTrue(parseStarted.await(1, TimeUnit.SECONDS));
          parseFuture.get(1, TimeUnit.SECONDS);
          assertTrue(engine.workerStarted.await(1, TimeUnit.SECONDS));
          assertTrue(
              awaitThreadState(engine.worker, Thread.State.BLOCKED, 1, TimeUnit.SECONDS),
              "the startup worker must actually be waiting for the occupied command queue");

          CountDownLatch engineLockAcquired = new CountDownLatch(1);
          Future<?> lockFuture =
              executor.submit(
                  () -> {
                    synchronized (engine) {
                      engineLockAcquired.countDown();
                    }
                  });

          assertTrue(
              engineLockAcquired.await(1, TimeUnit.SECONDS),
              "KataGo startup command initialization must not wait for the command queue while holding the engine monitor");
          lockFuture.get(1, TimeUnit.SECONDS);
        }
        engine.worker.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(engine.worker.isAlive());
        assertEquals(null, engine.workerFailure.get());
      } finally {
        executor.shutdownNow();
      }
    }
  }

  private static void invokeParseLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeParseLineUnchecked(Leelaz engine, String line) {
    try {
      invokeParseLine(engine, line);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static ArrayDeque<?> commandQueue(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("cmdQueue");
    field.setAccessible(true);
    return (ArrayDeque<?>) field.get(engine);
  }

  private static boolean awaitThreadState(
      Thread thread, Thread.State expected, long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      if (thread != null && thread.getState() == expected) {
        return true;
      }
      Thread.sleep(5L);
    }
    return thread != null && thread.getState() == expected;
  }

  private static final class StartupWorkerLeelaz extends Leelaz {
    private final CountDownLatch workerStarted = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure =
        new java.util.concurrent.atomic.AtomicReference<>();
    private volatile Thread worker;

    private StartupWorkerLeelaz() throws Exception {
      super("");
    }

    @Override
    void dispatchStartupPostActionWorker(Runnable action) {
      worker =
          new Thread(
              () -> {
                workerStarted.countDown();
                try {
                  action.run();
                } catch (Throwable failure) {
                  workerFailure.set(failure);
                }
              },
              "test-startup-post-action");
      worker.setDaemon(true);
      worker.start();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {
      super();
    }
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
    public void addLine(String line) {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void changeEngineIcon(int index, int mode) {}

    @Override
    public void changeEngineIcon2(int index, int mode) {}
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final EngineManager previousEngineManager;
    private final int previousCurrentEngineNo;
    private final Menu previousMenu;

    private TestHarness() {
      previousConfig = Lizzie.config;
      previousFrame = Lizzie.frame;
      previousGtpConsole = Lizzie.gtpConsole;
      previousLeelaz = Lizzie.leelaz;
      previousEngineManager = Lizzie.engineManager;
      previousCurrentEngineNo = EngineManager.currentEngineNo;
      previousMenu = LizzieFrame.menu;
    }

    private static TestHarness open() throws Exception {
      TestHarness harness = new TestHarness();
      Lizzie.config = allocate(Config.class);
      Lizzie.config.extraMode = ExtraMode.Normal;
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = null;
      LizzieFrame.menu = allocate(SilentMenu.class);
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.engineManager = previousEngineManager;
      EngineManager.currentEngineNo = previousCurrentEngineNo;
      LizzieFrame.menu = previousMenu;
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
