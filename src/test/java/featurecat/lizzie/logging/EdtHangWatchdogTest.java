package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EdtHangWatchdogTest {
  @AfterEach
  void tearDown() {
    ThreadSnapshot.resetHeldForTests();
    EdtHangWatchdog.uninstall();
  }

  @Test
  void stallCapturesOnceThenThrottlesUntilStop() {
    AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
    AtomicBoolean blocked = new AtomicBoolean();
    AtomicInteger dumps = new AtomicInteger();
    Deque<Runnable> edtQueue = new ArrayDeque<>();
    EdtHangWatchdog watchdog =
        new EdtHangWatchdog(
            now::get,
            EdtHangWatchdog.DEFAULT_STALL_NANOS,
            EdtHangWatchdog.DEFAULT_THROTTLE_NANOS,
            task -> edtQueue.addLast(task),
            () -> {
              dumps.incrementAndGet();
              return "hang-dump-" + dumps.get();
            });

    watchdog.enable();
    drainEdt(edtQueue, blocked);
    watchdog.tick();
    drainEdt(edtQueue, blocked);
    assertEquals(0, watchdog.captureCount());

    blocked.set(true);
    now.addAndGet(EdtHangWatchdog.DEFAULT_STALL_NANOS);
    watchdog.tick();
    drainEdt(edtQueue, blocked);
    assertEquals(1, watchdog.captureCount());
    assertEquals("hang-dump-1", watchdog.lastHangDump());
    assertEquals("hang-dump-1", ThreadSnapshot.heldRaw());

    now.addAndGet(TimeUnit.SECONDS.toNanos(1));
    watchdog.tick();
    drainEdt(edtQueue, blocked);
    assertEquals(1, watchdog.captureCount());

    blocked.set(false);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
    drainEdt(edtQueue, blocked);
    watchdog.tick();
    drainEdt(edtQueue, blocked);
    assertEquals(1, watchdog.captureCount());

    blocked.set(true);
    now.addAndGet(EdtHangWatchdog.DEFAULT_STALL_NANOS);
    watchdog.tick();
    drainEdt(edtQueue, blocked);
    assertEquals(1, watchdog.captureCount(), "throttle must suppress the same stall window");

    now.addAndGet(EdtHangWatchdog.DEFAULT_THROTTLE_NANOS);
    watchdog.tick();
    drainEdt(edtQueue, blocked);
    assertEquals(2, watchdog.captureCount());
    assertEquals("hang-dump-2", watchdog.lastHangDump());

    watchdog.stop();
    now.addAndGet(EdtHangWatchdog.DEFAULT_STALL_NANOS + EdtHangWatchdog.DEFAULT_THROTTLE_NANOS);
    watchdog.tick();
    assertEquals(2, watchdog.captureCount());
    assertEquals(2, dumps.get());
  }

  private static void drainEdt(Deque<Runnable> edtQueue, AtomicBoolean blocked) {
    if (blocked.get()) {
      return;
    }
    while (!edtQueue.isEmpty()) {
      edtQueue.removeFirst().run();
    }
  }

  @Test
  void stopPreventsCaptureEvenAfterStall() {
    AtomicLong now = new AtomicLong(0L);
    AtomicBoolean blocked = new AtomicBoolean(true);
    EdtHangWatchdog watchdog =
        new EdtHangWatchdog(
            now::get,
            TimeUnit.SECONDS.toNanos(1),
            TimeUnit.SECONDS.toNanos(1),
            task -> {
              if (!blocked.get()) {
                task.run();
              }
            },
            () -> "should-not-capture");
    watchdog.enable();
    watchdog.stop();
    now.addAndGet(TimeUnit.SECONDS.toNanos(5));
    watchdog.tick();
    assertEquals(0, watchdog.captureCount());
    assertTrue(watchdog.lastHangDump().isEmpty());
  }
}
