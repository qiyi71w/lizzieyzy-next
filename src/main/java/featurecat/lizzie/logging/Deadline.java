package featurecat.lizzie.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class Deadline {
  private static final long INTERRUPT_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

  private Deadline() {}

  static void run(long deadlineNanos, Runnable action) {
    runAll(deadlineNanos, List.of(action));
  }

  static void runAll(long deadlineNanos, Iterable<? extends Runnable> actions) {
    List<Thread> threads = new ArrayList<>();
    int index = 0;
    for (Runnable action : actions) {
      if (deadlineNanos - System.nanoTime() <= 0) {
        break;
      }
      Thread thread =
          new Thread(
              () -> {
                try {
                  action.run();
                } catch (RuntimeException | LinkageError ignored) {
                }
              },
              "lizzie-log-deadline-" + index++);
      thread.setDaemon(true);
      thread.start();
      threads.add(thread);
    }

    boolean interrupted = Thread.interrupted();
    long now = System.nanoTime();
    // Closing native file handles after interruption needs part of the same budget, not
    // another timeout per appender. Fast actions still return without using this reserve.
    long grace = Math.min(INTERRUPT_GRACE_NANOS, Math.max(0L, (deadlineNanos - now) / 2));
    long interruptAt = Math.max(now, deadlineNanos - grace);
    for (Thread thread : threads) {
      interrupted |= joinUntil(thread, interruptAt);
    }
    for (Thread thread : threads) {
      if (thread.isAlive()) {
        thread.interrupt();
      }
    }
    for (Thread thread : threads) {
      interrupted |= joinUntil(thread, deadlineNanos);
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean joinUntil(Thread thread, long deadlineNanos) {
    while (thread.isAlive()) {
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0) {
        return false;
      }
      try {
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
        thread.join(millis, nanos);
      } catch (InterruptedException e) {
        return true;
      }
    }
    return false;
  }
}
