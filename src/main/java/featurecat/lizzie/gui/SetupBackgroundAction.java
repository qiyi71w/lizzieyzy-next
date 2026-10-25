package featurecat.lizzie.gui;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Runs filesystem mutations off the EDT and returns their result to the UI owner. */
final class SetupBackgroundAction {
  private SetupBackgroundAction() {}

  static <T> Thread start(
      String name,
      Callable<T> action,
      Consumer<T> succeeded,
      Consumer<Exception> failed,
      Consumer<Thread> finished) {
    Thread worker =
        new Thread(
            () -> {
              try {
                T result = action.call();
                SwingUtilities.invokeLater(() -> succeeded.accept(result));
              } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> failed.accept(failure));
              } finally {
                Thread completed = Thread.currentThread();
                SwingUtilities.invokeLater(() -> finished.accept(completed));
              }
            },
            name);
    worker.start();
    return worker;
  }
}
