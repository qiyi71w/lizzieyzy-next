package featurecat.lizzie.logging;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight EDT stall detector. Captures one thread snapshot when the event queue stops
 * responding, then throttles until the EDT recovers. Never opens a dialog.
 */
public final class EdtHangWatchdog {
  static final long DEFAULT_STALL_NANOS = TimeUnit.SECONDS.toNanos(5);
  static final long DEFAULT_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(60);
  static final long DEFAULT_POLL_NANOS = TimeUnit.SECONDS.toNanos(1);

  private static final Logger DIAG = LoggerFactory.getLogger(LogCategories.DIAGNOSTICS);
  private static final AtomicReference<EdtHangWatchdog> INSTALLED = new AtomicReference<>();

  private final LongSupplier nanoTime;
  private final long stallNanos;
  private final long throttleNanos;
  private final long pollNanos;
  private final Consumer<Runnable> edtDispatcher;
  private final Supplier<String> snapshotter;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean pingInFlight = new AtomicBoolean();
  private final AtomicBoolean stallCaptured = new AtomicBoolean();
  private final AtomicLong lastEdtNanos = new AtomicLong();
  private final AtomicLong lastCaptureNanos = new AtomicLong(Long.MIN_VALUE / 2);
  private final AtomicInteger captureCount = new AtomicInteger();
  private volatile String lastHangDump = "";

  public static void installDefault() {
    try {
      if (GraphicsEnvironment.isHeadless()) {
        return;
      }
      EdtHangWatchdog watchdog =
          new EdtHangWatchdog(
              System::nanoTime,
              DEFAULT_STALL_NANOS,
              DEFAULT_THROTTLE_NANOS,
              DEFAULT_POLL_NANOS,
              SwingUtilities::invokeLater,
              () -> ThreadSnapshot.captureRaw("edt-hang"),
              newWatchdogScheduler());
      if (INSTALLED.compareAndSet(null, watchdog)) {
        watchdog.start();
      } else {
        watchdog.stop();
      }
    } catch (Throwable ignored) {
    }
  }

  public static void uninstall() {
    EdtHangWatchdog current = INSTALLED.getAndSet(null);
    if (current != null) {
      current.stop();
    }
  }

  EdtHangWatchdog(
      LongSupplier nanoTime,
      long stallNanos,
      long throttleNanos,
      Consumer<Runnable> edtDispatcher,
      Supplier<String> snapshotter) {
    this(nanoTime, stallNanos, throttleNanos, DEFAULT_POLL_NANOS, edtDispatcher, snapshotter, null);
  }

  private EdtHangWatchdog(
      LongSupplier nanoTime,
      long stallNanos,
      long throttleNanos,
      long pollNanos,
      Consumer<Runnable> edtDispatcher,
      Supplier<String> snapshotter,
      ScheduledExecutorService scheduler) {
    this.nanoTime = nanoTime;
    this.stallNanos = stallNanos;
    this.throttleNanos = throttleNanos;
    this.pollNanos = pollNanos;
    this.edtDispatcher = edtDispatcher;
    this.snapshotter = snapshotter;
    this.scheduler = scheduler;
  }

  void start() {
    enable();
    if (scheduler == null) {
      return;
    }
    scheduler.scheduleWithFixedDelay(
        this::safeTick, pollNanos, pollNanos, TimeUnit.NANOSECONDS);
  }

  void enable() {
    lastEdtNanos.set(nanoTime.getAsLong());
    pingInFlight.set(false);
    stallCaptured.set(false);
    running.set(true);
    pingEdt();
  }

  void stop() {
    running.set(false);
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  void tick() {
    if (!running.get()) {
      return;
    }
    pingEdt();
    long now = nanoTime.getAsLong();
    long lastEdt = lastEdtNanos.get();
    boolean stalled = now - lastEdt >= stallNanos;
    if (!stalled) {
      stallCaptured.set(false);
      return;
    }
    if (stallCaptured.get()) {
      return;
    }
    if (now - lastCaptureNanos.get() < throttleNanos) {
      return;
    }
    if (!stallCaptured.compareAndSet(false, true)) {
      return;
    }
    lastCaptureNanos.set(now);
    try {
      String dump = snapshotter.get();
      lastHangDump = dump == null ? "" : dump;
      ThreadSnapshot.holdRaw(lastHangDump);
      captureCount.incrementAndGet();
      DIAG.warn("edt hang thread snapshot captured stallMs={}", (now - lastEdt) / 1_000_000L);
    } catch (Throwable ignored) {
      stallCaptured.set(false);
    }
  }

  int captureCount() {
    return captureCount.get();
  }

  String lastHangDump() {
    return lastHangDump == null ? "" : lastHangDump;
  }

  private void safeTick() {
    try {
      tick();
    } catch (Throwable ignored) {
    }
  }

  private void pingEdt() {
    if (!pingInFlight.compareAndSet(false, true)) {
      return;
    }
    try {
      edtDispatcher.accept(
          () -> {
            lastEdtNanos.set(nanoTime.getAsLong());
            pingInFlight.set(false);
          });
    } catch (Throwable ignored) {
      pingInFlight.set(false);
    }
  }

  private static ScheduledExecutorService newWatchdogScheduler() {
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "lizzie-edt-hang-watchdog");
          thread.setDaemon(true);
          return thread;
        };
    return Executors.newSingleThreadScheduledExecutor(factory);
  }
}
