package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EngineManagerEngineGameStartTest {
  @Test
  void startNewEngineGameDoesNotEnterPreGameWhenLifecycleTransitionIsRejected() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousEngine = Lizzie.leelaz;
    try {
      RejectingLifecycleLeelaz engine = new RejectingLifecycleLeelaz();
      CountingLeaseEngineManager manager = new CountingLeaseEngineManager(List.of(engine));
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.leelaz = engine;

      manager.startNewEngineGame(true);

      assertFalse(EngineManager.hasActiveEngineGameTransaction());
      assertTrue(engine.stoppedPondering);
      assertEqualsOneLeaseConflict(manager);
    } finally {
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void asynchronousEngineGameStartFailureRestoresEveryDisabledControl() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    Board previousBoard = Lizzie.board;
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousEngine = Lizzie.leelaz;
    try {
      TrackingFrame frame = allocate(TrackingFrame.class);
      TrackingToolbar toolbar = allocate(TrackingToolbar.class);
      JFontMenu engineMenu = new JFontMenu();
      engineMenu.setEnabled(false);
      Lizzie.frame = frame;
      LizzieFrame.toolbar = toolbar;
      Menu.engineMenu = engineMenu;
      Board board = allocate(Board.class);
      board.isPkBoard = true;
      Lizzie.board = board;
      Leelaz blackEngine = new Leelaz("");
      Leelaz whiteEngine = new Leelaz("");
      EngineManager manager = new EngineManager(List.of(blackEngine, whiteEngine));
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(blackEngine);
      assertTrue(
          EngineManager.beginEngineGameTransaction(
                  manager, EngineGamePlans.harness(0, 1, true), null, true)
              != null);

      EngineManager.PkEngineSynchronization black =
          manager.startEngineForPkSynchronization(-1);
      EngineManager.PkEngineSynchronization white =
          manager.startEngineForPkSynchronization(-1);

      assertFalse(manager.finishPkEngineSynchronizations(black, white));
      SwingUtilities.invokeAndWait(() -> {});

      assertFalse(Lizzie.board.isPkBoard);
      assertFalse(EngineManager.hasActiveEngineGameTransaction());
      assertTrue(frame.inputRestored);
      assertTrue(toolbar.controlsEnabled);
      assertTrue(toolbar.updatedOnEventDispatchThread);
      assertTrue(engineMenu.isEnabled());
    } finally {
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Menu.engineMenu = previousEngineMenu;
      Lizzie.board = previousBoard;
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousEngine);
    }
  }

  private static void assertEqualsOneLeaseConflict(CountingLeaseEngineManager manager) {
    if (manager.leaseConflictCount != 1) {
      throw new AssertionError("expected one lease conflict, got " + manager.leaseConflictCount);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }

  private static final class CountingLeaseEngineManager extends EngineManager {
    private int leaseConflictCount;

    private CountingLeaseEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      leaseConflictCount++;
    }
  }

  private static final class RejectingLifecycleLeelaz extends Leelaz {
    private boolean stoppedPondering;

    private RejectingLifecycleLeelaz() throws Exception {
      super("");
    }

    @Override
    public void notPondering() {
      stoppedPondering = true;
    }

    @Override
    public synchronized boolean beginExclusiveGtpLifecycleTransition() {
      return false;
    }
  }

  private static final class SilentFrame extends LizzieFrame {
    @Override
    public void addInput(boolean shouldAdd) {}

    @Override
    public void setResult(String result) {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private boolean inputRestored;

    @Override
    public boolean isInputRoutingInitialized() {
      return true;
    }

    @Override
    public void addInput(boolean shouldAdd) {
      inputRestored = shouldAdd;
    }
  }

  private static final class TrackingToolbar extends BottomToolbar {
    private boolean controlsEnabled;
    private boolean updatedOnEventDispatchThread;

    @Override
    public void enableDisabelForEngineGame(boolean enable) {
      controlsEnabled = enable;
      updatedOnEventDispatchThread = SwingUtilities.isEventDispatchThread();
    }
  }
}
