package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeadlineTest {
  @Test
  void fastActionsDoNotWaitForCleanupReserveAndPreserveCallerInterrupt() {
    AtomicInteger completed = new AtomicInteger();
    long started = System.nanoTime();
    Thread.currentThread().interrupt();
    try {
      Deadline.runAll(
          started + TimeUnit.SECONDS.toNanos(3),
          List.of(completed::incrementAndGet, completed::incrementAndGet));
      assertEquals(2, completed.get());
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void interruptedActionsHaveTimeToCloseResourcesBeforeReturning() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch finished = new CountDownLatch(2);
    Runnable slowClose =
        () -> {
          started.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException expected) {
            // Model interruptible stop followed by Windows file-handle cleanup.
            try {
              new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
          } finally {
            finished.countDown();
          }
        };

    try {
      Deadline.runAll(
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600), List.of(slowClose, slowClose));
      assertEquals(0L, started.getCount());
      assertEquals(0L, finished.getCount(), "cleanup must complete inside the shared deadline");
    } finally {
      assertTrue(finished.await(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void runAllReturnsByOneSharedDeadlineWhenActionsIgnoreInterrupts() throws Exception {
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(2);
    Runnable blocked =
        () -> {
          entered.countDown();
          try {
            while (release.getCount() > 0) {
              try {
                release.await();
              } catch (InterruptedException ignored) {
              }
            }
          } finally {
            finished.countDown();
          }
        };

    long started = System.nanoTime();
    try {
      Deadline.runAll(
          started + TimeUnit.MILLISECONDS.toNanos(300), List.of(blocked, blocked));
      long elapsed = System.nanoTime() - started;
      assertEquals(0L, entered.getCount(), "both cleanup actions must start in parallel");
      assertTrue(elapsed <= TimeUnit.MILLISECONDS.toNanos(500), "elapsedNanos=" + elapsed);
    } finally {
      release.countDown();
    }
    assertTrue(finished.await(3, TimeUnit.SECONDS));
  }
}
