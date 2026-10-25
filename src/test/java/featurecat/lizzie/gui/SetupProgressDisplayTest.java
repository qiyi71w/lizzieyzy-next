package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SetupProgressDisplayTest {
  @Test
  void benchmarkProgressDoesNotRegressAndCompletionResetsTheNextRun() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JProgressBar bar = new JProgressBar();
          SetupProgressDisplay.render(bar, true, 600, 1000, Long::toString, "");
          SetupProgressDisplay.render(bar, true, 200, 1000, Long::toString, "");
          assertEquals(600, bar.getValue());
          assertEquals("60%", bar.getString());
          SetupProgressDisplay.render(bar, false, 0, 0, Long::toString, "");
          assertFalse(bar.isIndeterminate());
          assertEquals("", bar.getString());
          SetupProgressDisplay.render(bar, true, 30, 1000, Long::toString, "");
          assertEquals(30, bar.getValue());
          assertEquals("3%", bar.getString());
        });
  }

  @Test
  void downloadsShowTransferredBytesAndUnknownTotalsRemainIndeterminate() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JProgressBar bar = new JProgressBar();
          SetupProgressDisplay.render(bar, true, 2048, -1, bytes -> bytes + " B", "");
          assertTrue(bar.isIndeterminate());
          assertEquals("2048 B", bar.getString());
          SetupProgressDisplay.render(bar, true, 2048, 4096, bytes -> bytes + " B", " ETA 2s");
          assertFalse(bar.isIndeterminate());
          assertEquals(500, bar.getValue());
          assertEquals("50%  2048 B / 4096 B ETA 2s", bar.getString());
        });
  }
}
