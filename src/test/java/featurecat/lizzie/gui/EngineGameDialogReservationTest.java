package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EngineGameDialogReservationTest {
  @Test
  void dialogHandsExclusiveEngineToWorkerAndCanReserveAgainAfterFailedStart() throws Exception {
    Leelaz previous = Lizzie.leelaz;
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    LizzieFrame frame =
        (LizzieFrame) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(LizzieFrame.class);
    Leelaz engine = new Leelaz("");
    try {
      Lizzie.leelaz = engine;
      SwingUtilities.invokeAndWait(() -> assertTrue(frame.reserveEngineGameDialog()));
      assertFalse(workerCanReserve(engine), "editing dialog excludes another engine owner");
      SwingUtilities.invokeAndWait(frame::releaseEngineGameDialog);
      assertTrue(workerCanReserve(engine), "submitted game can own its first worker operation");
      SwingUtilities.invokeAndWait(() -> assertTrue(frame.reserveEngineGameDialog()));
      assertFalse(workerCanReserve(engine), "failed-start dialog resumes exclusion for retry");
      SwingUtilities.invokeAndWait(frame::releaseEngineGameDialog);
      assertTrue(workerCanReserve(engine));
    } finally {
      SwingUtilities.invokeAndWait(frame::releaseEngineGameDialog);
      Lizzie.leelaz = previous;
    }
  }

  private static boolean workerCanReserve(Leelaz engine) throws Exception {
    FutureTask<Boolean> result =
        new FutureTask<>(
            () -> {
              Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
              if (reservation == null) return false;
              reservation.close();
              return true;
            });
    Thread worker = new Thread(result, "game-dialog-reservation-consumer");
    worker.setDaemon(true);
    worker.start();
    return result.get(2, TimeUnit.SECONDS);
  }
}
