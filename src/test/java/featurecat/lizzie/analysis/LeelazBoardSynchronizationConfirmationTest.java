package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LeelazBoardSynchronizationConfirmationTest {

  @Test
  void failedLastResponseCannotBecomeSuccessfulWhileItsListenerIsDelayed() throws Exception {
    assertFailureDuringConfirmation(false);
  }

  @Test
  void failedMirrorResponseCannotBecomeSuccessfulWhileItsListenerIsDelayed() throws Exception {
    assertFailureDuringConfirmation(true);
  }

  private void assertFailureDuringConfirmation(boolean failMirror) throws Exception {
    try (TestEnvironment ignored = new TestEnvironment()) {
      ReadinessControlledLeelaz engine = new ReadinessControlledLeelaz();
      Lizzie.leelaz = engine;
      engine.isLoaded = true;
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      setOutput(engine, output);
      Leelaz mirror = failMirror ? new ReadinessControlledLeelaz() : null;
      ByteArrayOutputStream mirrorOutput = new ByteArrayOutputStream();
      if (mirror != null) {
        mirror.isLoaded = true;
        setOutput(mirror, mirrorOutput);
      }
      Method bindingMethod = Leelaz.class.getDeclaredMethod("currentReaderStreamBinding");
      bindingMethod.setAccessible(true);
      Object binding = bindingMethod.invoke(failMirror ? mirror : engine);
      Field lineageField = binding.getClass().getDeclaredField("analysisStateLineage");
      lineageField.setAccessible(true);
      Object lineage = lineageField.get(binding);
      Method register = lineage.getClass().getDeclaredMethod("registerResponse");
      register.setAccessible(true);
      register.invoke(lineage);
      Method settle = lineage.getClass().getDeclaredMethod("settleResponse", boolean.class);
      settle.setAccessible(true);
      CountDownLatch failurePublished = new CountDownLatch(1);
      CountDownLatch releaseListener = new CountDownLatch(1);
      Method onChange = lineage.getClass().getDeclaredMethod("onChange", Runnable.class);
      onChange.setAccessible(true);
      onChange.invoke(
          lineage,
          (Runnable) () -> {
            failurePublished.countDown();
            try {
              assertTrue(releaseListener.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
          });
      AtomicInteger successes = new AtomicInteger();
      AtomicInteger failures = new AtomicInteger();
      engine.confirmBoardSynchronization(
          mirror, successes::incrementAndGet, detail -> failures.incrementAndGet());
      if (mirror != null) {
        mirror.processCommandResponseLineForTest("=" + commandId(mirrorOutput) + " KataGo");
      }
      AtomicReference<Throwable> workerFailure = new AtomicReference<>();
      Thread timeout =
          new Thread(
              () -> {
                try {
                  settle.invoke(lineage, false);
                } catch (Throwable failure) {
                  workerFailure.set(failure);
                }
              },
              "controlled-position-timeout");
      timeout.setDaemon(true);
      engine.beforeReadiness =
          () -> {
            timeout.start();
            try {
              assertTrue(failurePublished.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
          };
      try {
        engine.processCommandResponseLineForTest("=" + commandId(output) + " KataGo");
        assertEquals(0, successes.get(), "failed quiescent lineage must not publish success");
      } finally {
        releaseListener.countDown();
        timeout.join(5_000L);
      }
      assertFalse(timeout.isAlive());
      assertNull(workerFailure.get());
      assertEquals(1, failures.get());
      assertFalse((failMirror ? mirror : engine).isLoaded);
      if (failMirror) assertTrue(engine.isLoaded, "confirmed authority remains usable");
      engine.processCommandResponseLineForTest("=" + commandId(output) + " late");
      assertEquals(0, successes.get());
      assertEquals(1, failures.get());
    }
  }

  @Test
  void synchronousResponseDuringDispatchSettlesBeforeTimeoutScheduling() throws Exception {
    try (TestEnvironment ignored = new TestEnvironment()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      AtomicInteger successes = new AtomicInteger();
      AtomicReference<String> failure = new AtomicReference<>();
      SynchronousResponseOutput output = new SynchronousResponseOutput(engine);
      setOutput(engine, output);

      engine.confirmBoardSynchronization(successes::incrementAndGet, failure::set);

      assertEquals(1, successes.get());
      assertNull(failure.get());
      assertTrue(output.command().endsWith("name"));
      assertTrue(pendingResponses(engine).isEmpty());
    }
  }

  @Test
  void timeoutRetiresPendingBoardSynchronizationResponse() throws Exception {
    try (TestEnvironment ignored = new TestEnvironment()) {
      ShortTimeoutLeelaz engine = new ShortTimeoutLeelaz();
      Lizzie.leelaz = engine;
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      setOutput(engine, output);
      CountDownLatch failed = new CountDownLatch(1);
      AtomicInteger failures = new AtomicInteger();
      AtomicReference<String> detail = new AtomicReference<>();

      engine.confirmBoardSynchronization(
          () -> {},
          message -> {
            detail.set(message);
            failures.incrementAndGet();
            failed.countDown();
          });

      assertTrue(failed.await(1, TimeUnit.SECONDS));
      assertTrue(detail.get().contains("timeout"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (!pendingResponses(engine).isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(1L);
      }
      assertTrue(pendingResponses(engine).isEmpty());
      assertEquals(1, failures.get());
      engine.processCommandResponseLineForTest("=" + commandId(output) + " late");
      assertEquals(1, failures.get());
      assertTrue(pendingResponses(engine).isEmpty());
    }
  }
  @Test
  void sendFailureRetiresPendingBoardSynchronizationResponseAndIgnoresLateReply()
      throws Exception {
    try (TestEnvironment ignored = new TestEnvironment()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      AtomicInteger successes = new AtomicInteger();
      AtomicInteger failures = new AtomicInteger();
      AtomicReference<String> detail = new AtomicReference<>();
      FailingOutput output = new FailingOutput();
      setOutput(engine, output);

      engine.confirmBoardSynchronization(
          successes::incrementAndGet,
          message -> {
            detail.set(message);
            failures.incrementAndGet();
          });

      assertEquals(0, successes.get());
      assertEquals(1, failures.get());
      assertTrue(detail.get().contains("controlled board fence send failure"));
      assertTrue(pendingResponses(engine).isEmpty());

      engine.processCommandResponseLineForTest("=" + output.commandId() + " late");

      assertEquals(0, successes.get());
      assertEquals(1, failures.get());
      assertTrue(pendingResponses(engine).isEmpty());
    }
  }


  @Test
  void settledResponseMayCancelTimeoutBeforeItIsScheduled() {
    Timer timeout = new Timer(true);
    timeout.cancel();

    assertDoesNotThrow(
        () ->
            Leelaz.scheduleBoardSynchronizationTimeout(
                timeout, noOpTask(), 1L, new AtomicBoolean(true)));
  }

  @Test
  void unexpectedCancelledTimeoutStillFails() {
    Timer timeout = new Timer(true);
    timeout.cancel();

    assertThrows(
        IllegalStateException.class,
        () ->
            Leelaz.scheduleBoardSynchronizationTimeout(
                timeout, noOpTask(), 1L, new AtomicBoolean(false)));
  }

  private static void setOutput(Leelaz engine, ByteArrayOutputStream output) throws Exception {
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedOutputStream(output));
  }
  private static String commandId(ByteArrayOutputStream output) {
    String command = output.toString(StandardCharsets.UTF_8).trim();
    return command.substring(0, command.indexOf(' '));
  }


  @SuppressWarnings("unchecked")
  private static ArrayDeque<Object> pendingResponses(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("pendingResponseHandlers");
    field.setAccessible(true);
    ArrayDeque<Object> handlers = (ArrayDeque<Object>) field.get(engine);
    synchronized (handlers) {
      return new ArrayDeque<>(handlers);
    }
  }

  private static TimerTask noOpTask() {
    return new TimerTask() {
      @Override
      public void run() {}
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final Leelaz previousEngine = Lizzie.leelaz;

    private TestEnvironment() throws Exception {
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = allocate(LizzieFrame.class);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
    }
  }

  private static final class ReadinessControlledLeelaz extends Leelaz {
    private Runnable beforeReadiness;

    private ReadinessControlledLeelaz() throws IOException {
      super("");
    }

    @Override
    protected long readBoardGmaRestoreResponseTimeoutMillis() {
      return 30_000L;
    }

    @Override
    void beforeBoardSynchronizationReadinessForTest() {
      Runnable action = beforeReadiness;
      beforeReadiness = null;
      if (action != null) action.run();
    }
  }

  private static final class ShortTimeoutLeelaz extends Leelaz {
    private ShortTimeoutLeelaz() throws IOException {
      super("");
    }

    @Override
    protected long readBoardGmaRestoreResponseTimeoutMillis() {
      return 5L;
    }
  }

  private static final class FailingOutput extends ByteArrayOutputStream {
    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      super.write(bytes, offset, length);
      throw new IllegalStateException("controlled board fence send failure");
    }

    private String commandId() {
      return LeelazBoardSynchronizationConfirmationTest.commandId(this);
    }
  }

  private static final class SynchronousResponseOutput extends ByteArrayOutputStream {
    private final Leelaz engine;
    private boolean responded;

    private SynchronousResponseOutput(Leelaz engine) {
      this.engine = engine;
    }

    @Override
    public void flush() throws IOException {
      super.flush();
      if (responded) {
        return;
      }
      responded = true;
      String command = command();
      String id = command.substring(0, command.indexOf(' '));
      engine.processCommandResponseLineForTest("=" + id + " KataGo");
    }

    private String command() {
      return toString(StandardCharsets.UTF_8).trim();
    }
  }
}
