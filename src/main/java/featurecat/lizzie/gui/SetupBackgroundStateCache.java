package featurecat.lizzie.gui;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** The EDT owns a single immutable view; slow inspection never runs while rendering controls. */
final class SetupBackgroundStateCache<K, V> {
  private K key;
  private V value;
  private boolean requested;
  private long generation;
  private SwingWorker<V, Void> worker;

  V get(K requestedKey, Callable<V> inspect, Runnable updated, Consumer<Throwable> failed) {
    requireEventThread();
    if (requested && Objects.equals(key, requestedKey)) return value;
    invalidate();
    requested = true;
    key = requestedKey;
    long request = generation;
    worker =
        KataGoAutoSetupDialog.createUiBackgroundWorker(
            inspect,
            result -> {
              if (request != generation) return;
              worker = null;
              value = result;
              updated.run();
            },
            error -> {
              if (request != generation) return;
              worker = null;
              // Keep this failed request resolved until explicit refresh or a new target.
              failed.accept(error);
            });
    worker.execute();
    return null;
  }

  void invalidate() {
    requireEventThread();
    generation++;
    if (worker != null) worker.cancel(true);
    worker = null;
    key = null;
    value = null;
    requested = false;
  }

  private static void requireEventThread() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Setup display state belongs to the event thread");
    }
  }
}
