package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameControl;
import featurecat.lizzie.enginegame.StartObserver;
import org.junit.jupiter.api.Test;

class EngineGameDesktopTest {
  @Test
  void invalidLiveLimitDoesNotCallReviseBatchLimit() {
    CountingControl control = new CountingControl();

    assertFalse(EngineGameDesktop.reviseLiveBatchLimit(control, "abc"));
    assertFalse(EngineGameDesktop.reviseLiveBatchLimit(control, " "));
    assertFalse(EngineGameDesktop.reviseLiveBatchLimit(control, null));
    assertEquals(0, control.reviseCalls);
  }

  @Test
  void validLiveLimitCallsReviseBatchLimitOnce() {
    CountingControl control = new CountingControl();

    assertTrue(EngineGameDesktop.reviseLiveBatchLimit(control, " 8 "));
    assertEquals(1, control.reviseCalls);
    assertEquals(8, control.lastLimit);
  }

  private static final class CountingControl implements EngineGameControl {
    private int reviseCalls;
    private int lastLimit;

    @Override
    public Acceptance accept(EngineGameBatchSpec spec, StartObserver observer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stop() {}

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void reviseBatchLimit(int gameCount) {
      reviseCalls++;
      lastLimit = gameCount;
    }
  }
}
