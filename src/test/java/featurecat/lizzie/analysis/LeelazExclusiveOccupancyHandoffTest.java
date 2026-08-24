package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LeelazExclusiveOccupancyHandoffTest {
  @Test
  void delayedExclusivePromptIsDroppedAfterSuccessfulRestartOccupancyHandoff() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      PromptRecordingLeelaz engine = new PromptRecordingLeelaz();
      Lizzie.frame = allocate(DisplayableFrame.class);

      assertTrue(engine.holdUnfinishedForegroundRestoreOccupancyForTest());
      engine.showExclusiveGtpConflictMessage();

      assertTrue(
          engine.beginExclusiveGtpLifecycleTransition(),
          "an unfinished end-game restore must hand off occupancy to the next start");
      assertFalse(engine.isUnfinishedForegroundRestoreOccupancyHeldForTest());

      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(
          List.of(),
          engine.displayedKeys,
          "a delayed exclusive-task prompt from the previous game must not appear after occupancy already succeeded");
    } finally {
      Lizzie.frame = previousFrame;
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  @Test
  void realExclusiveOccupancyStillRefusesAndShowsThePrompt() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz.ExclusiveGtpLifecycleReservation reservation = null;
    try {
      PromptRecordingLeelaz engine = new PromptRecordingLeelaz();
      Lizzie.frame = allocate(DisplayableFrame.class);
      reservation = engine.beginExclusiveGtpLifecycleReservation(new Object());
      assertTrue(reservation != null);

      assertFalse(engine.beginExclusiveGtpLifecycleTransition());
      engine.showExclusiveGtpConflictMessage();
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(
          List.of("AnalysisSettings.reuseStatus.existing_lease"), engine.displayedKeys);
    } finally {
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.frame = previousFrame;
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }

  private static final class PromptRecordingLeelaz extends Leelaz {
    private final List<String> displayedKeys = new ArrayList<>();

    private PromptRecordingLeelaz() throws Exception {
      super("");
    }

    @Override
    protected void displayExclusiveGtpConflictMessage(String key) {
      displayedKeys.add(key);
    }
  }

  private static final class DisplayableFrame extends LizzieFrame {
    @Override
    public boolean isDisplayable() {
      return true;
    }
  }
}
