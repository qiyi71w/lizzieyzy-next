package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SetupBackgroundStateCacheTest {
  @Test
  void progressReadsShareOneInspectionAndNeverWaitOnTheEventThread() throws Exception {
    var cache = new SetupBackgroundStateCache<String, String>();
    AtomicInteger inspections = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    SwingUtilities.invokeAndWait(
        () -> {
          for (int tick = 0; tick < 100; tick++) {
            assertNull(
                cache.get(
                    "setup",
                    () -> {
                      assertFalse(SwingUtilities.isEventDispatchThread());
                      inspections.incrementAndGet();
                      started.countDown();
                      assertTrue(release.await(5, TimeUnit.SECONDS));
                      return "ready";
                    },
                    () -> {
                      assertTrue(SwingUtilities.isEventDispatchThread());
                      completed.countDown();
                    },
                    failure -> {
                      throw new AssertionError(failure);
                    }));
          }
        });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    try {
      SwingUtilities.invokeAndWait(() -> assertEquals(1, inspections.get()));
    } finally {
      release.countDown();
    }
    assertTrue(completed.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> {
          assertEquals("ready", cache.get("setup", () -> "wrong", () -> {}, failure -> {}));
          assertEquals(1, inspections.get());
          cache.invalidate();
        });
  }

  @Test
  void replacedTargetCannotPublishItsLateInspection() throws Exception {
    var cache = new SetupBackgroundStateCache<String, String>();
    CountDownLatch oldStarted = new CountDownLatch(1);
    CountDownLatch releaseOld = new CountDownLatch(1);
    CountDownLatch oldFinished = new CountDownLatch(1);
    CountDownLatch currentCompleted = new CountDownLatch(1);
    AtomicInteger oldPublications = new AtomicInteger();
    SwingUtilities.invokeAndWait(
        () ->
            cache.get(
                "old",
                () -> {
                  oldStarted.countDown();
                  try {
                    releaseOld.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException ignored) {
                    // Model an inspector that finishes its current filesystem read on cancel.
                  } finally {
                    oldFinished.countDown();
                  }
                  return "stale";
                },
                oldPublications::incrementAndGet,
                failure -> oldPublications.incrementAndGet()));
    assertTrue(oldStarted.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> cache.get("new", () -> "current", currentCompleted::countDown, failure -> {}));
    releaseOld.countDown();
    assertTrue(oldFinished.await(5, TimeUnit.SECONDS));
    assertTrue(currentCompleted.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> {
          assertEquals("current", cache.get("new", () -> "wrong", () -> {}, failure -> {}));
          assertEquals(0, oldPublications.get());
          cache.invalidate();
        });
  }

  @Test
  void inspectionFailureWaitsForExplicitRefreshBeforeRetry() throws Exception {
    var cache = new SetupBackgroundStateCache<String, String>();
    CountDownLatch failed = new CountDownLatch(1);
    AtomicInteger inspections = new AtomicInteger();
    SwingUtilities.invokeAndWait(
        () ->
            cache.get(
                "setup",
                () -> {
                  inspections.incrementAndGet();
                  throw new IOException("Unreadable runtime");
                },
                () -> {},
                error -> failed.countDown()));
    assertTrue(failed.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> {
          for (int tick = 0; tick < 100; tick++) {
            assertNull(
                cache.get(
                    "setup",
                    () -> {
                      inspections.incrementAndGet();
                      return "unexpected retry";
                    },
                    () -> {},
                    error -> {}));
          }
          assertEquals(1, inspections.get());
          cache.invalidate();
        });
    CountDownLatch retried = new CountDownLatch(1);
    SwingUtilities.invokeAndWait(
        () -> cache.get("setup", () -> "refreshed", retried::countDown, error -> {}));
    assertTrue(retried.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(cache::invalidate);
  }
}
