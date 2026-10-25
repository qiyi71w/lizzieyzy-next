package featurecat.lizzie.gui;

import java.util.function.LongFunction;
import javax.swing.JProgressBar;

/** Pure progress presentation shared by setup downloads and benchmark measurements. */
final class SetupProgressDisplay {
  private static final String BENCHMARK_PROGRESS_KEY = "lizzie.benchmark.dialog.progress";

  private SetupProgressDisplay() {}

  static void render(
      JProgressBar bar,
      boolean busy,
      long completed,
      long total,
      LongFunction<String> size,
      String eta) {
    bar.setIndeterminate(busy && total <= 0L);
    if (!busy) {
      reset(bar);
      return;
    }
    if (total > 0L) {
      bar.setMaximum(1000);
      int value = (int) Math.min(1000, (completed * 1000L) / total);
      boolean benchmark = total == 1000L && completed >= 0L && completed <= 1000L;
      if (benchmark) {
        int previous =
            bar.getClientProperty(BENCHMARK_PROGRESS_KEY) instanceof Integer progress
                ? progress
                : 0;
        value = Math.max(previous, value);
      }
      bar.putClientProperty(BENCHMARK_PROGRESS_KEY, benchmark ? value : 0);
      bar.setValue(value);
      bar.setString(
          benchmark
              ? Math.min(100, value / 10L) + "%"
              : Math.min(100, (completed * 100L) / total)
                  + "%  "
                  + size.apply(completed)
                  + " / "
                  + size.apply(total)
                  + eta);
    } else if (completed > 0L) {
      bar.setValue(0);
      bar.setString(size.apply(completed));
    } else {
      reset(bar);
    }
  }

  private static void reset(JProgressBar bar) {
    bar.setValue(0);
    bar.putClientProperty(BENCHMARK_PROGRESS_KEY, 0);
    bar.setString("");
  }
}
