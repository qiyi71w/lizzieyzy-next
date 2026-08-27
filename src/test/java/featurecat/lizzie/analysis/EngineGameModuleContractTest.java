package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameBatchSpecFactory;
import featurecat.lizzie.enginegame.EngineGameParsedStart;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.Rejection;
import featurecat.lizzie.enginegame.RunState;
import featurecat.lizzie.enginegame.StartFailure;
import featurecat.lizzie.enginegame.StartObserver;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineGameModuleContractTest {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("black-cmd", "");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("white-cmd", "");

  private EngineManager previousManager;
  private Leelaz previousPrimary;
  private Config previousConfig;
  private LizzieFrame previousFrame;
  private BottomToolbar previousToolbar;
  private Menu previousMenu;
  private JFontMenu previousEngineMenu;
  private Board previousBoard;
  private WinrateGraph previousWinrateGraph;
  private GtpConsolePane previousGtpConsole;
  private boolean previousEngineGame;
  private boolean previousPreEngineGame;
  private boolean previousEmpty;
  private int previousEngineNo;

  private OccupancyLeelaz black;
  private OccupancyLeelaz white;
  private CountingLeaseEngineManager manager;
  private RecordingObserver observer;

  @BeforeEach
  void installFixture() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
    previousManager = Lizzie.engineManager;
    previousPrimary = Lizzie.leelaz;
    previousConfig = Lizzie.config;
    previousFrame = Lizzie.frame;
    previousToolbar = LizzieFrame.toolbar;
    previousMenu = LizzieFrame.menu;
    previousEngineMenu = Menu.engineMenu;
    previousBoard = Lizzie.board;
    previousWinrateGraph = LizzieFrame.winrateGraph;
    previousGtpConsole = Lizzie.gtpConsole;
    previousEngineGame = EngineManager.isEngineGame;
    previousPreEngineGame = EngineManager.isPreEngineGame;
    previousEmpty = EngineManager.isEmpty;
    previousEngineNo = EngineManager.currentEngineNo;

    EngineManager.resetEngineGameTransactionStateForTest();
    Config config = allocate(Config.class);
    config.uiConfig = new JSONObject();
    config.newEngineGameHandicap = 0;
    config.newEngineGameKomi = 7.5;
    config.pkAdvanceTimeSettings = false;
    config.enginePkPonder = false;
    config.extraMode = ExtraMode.Normal;
    Lizzie.config = config;
    black = new OccupancyLeelaz();
    white = new OccupancyLeelaz();
    black.bindLiveRuntime();
    white.bindLiveRuntime();
    black.setEngineCommand("black-cmd");
    white.setEngineCommand("white-cmd");
    Lizzie.setPrimaryEngine(black);
    Lizzie.frame = allocate(SilentFrame.class);
    LizzieFrame.toolbar = allocate(SilentToolbar.class);
    LizzieFrame.menu = allocate(SilentMenu.class);
    Menu.engineMenu = new JFontMenu();
    LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
    Board board = allocate(SilentBoard.class);
    board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
    Lizzie.board = board;
    Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
    manager = new CountingLeaseEngineManager(List.of(black, white));
    Lizzie.engineManager = manager;

    EngineManager.isEmpty = false;
    EngineManager.currentEngineNo = 0;
    EngineManager.isEngineGame = false;
    EngineManager.isPreEngineGame = false;
    observer = new RecordingObserver();
  }

  @AfterEach
  void restoreFixture() throws Exception {
    EngineManager.resetEngineGameTransactionStateForTest();
    Lizzie.engineManager = previousManager;
    EngineManager.isEngineGame = previousEngineGame;
    EngineManager.isPreEngineGame = previousPreEngineGame;
    EngineManager.isEmpty = previousEmpty;
    EngineManager.currentEngineNo = previousEngineNo;
    Lizzie.setPrimaryEngine(previousPrimary);
    Lizzie.config = previousConfig;
    Lizzie.frame = previousFrame;
    LizzieFrame.toolbar = previousToolbar;
    LizzieFrame.menu = previousMenu;
    Menu.engineMenu = previousEngineMenu;
    Lizzie.board = previousBoard;
    LizzieFrame.winrateGraph = previousWinrateGraph;
    Lizzie.gtpConsole = previousGtpConsole;
    SwingUtilities.invokeAndWait(() -> {});
  }

  @Test
  void sameIdentityIsRejectedWithoutBatchOrObserver() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder().first(FIRST).second(FIRST).genmove(true).build());

    Acceptance acceptance = Lizzie.engineGame.accept(spec, observer);

    assertInstanceOf(Acceptance.Rejected.class, acceptance);
    assertInstanceOf(
        Rejection.InvalidParticipantCombination.class,
        ((Acceptance.Rejected) acceptance).reason());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.firstPlan());

    assertNull(Lizzie.engineGame.transaction());
    assertEquals(0, observer.playing);
    assertEquals(List.of(), observer.failures);
  }

  @Test
  void analysisWithoutLimitsIsRejectedWithoutRetainingObserver() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder().first(FIRST).second(SECOND).build());

    Acceptance acceptance = Lizzie.engineGame.accept(spec, observer);

    assertInstanceOf(Acceptance.Rejected.class, acceptance);
    assertInstanceOf(
        Rejection.InvalidAnalysisLimits.class, ((Acceptance.Rejected) acceptance).reason());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertEquals(0, observer.playing);
    assertEquals(List.of(), observer.failures);
  }

  @Test
  void occupancyFailureIsRejectedWithoutCreatingProductState() throws Exception {
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        black.beginExclusiveGtpLifecycleReservation(new Object());
    assertNotNull(reservation);
    try {
      Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(), observer);

      assertInstanceOf(Acceptance.Rejected.class, acceptance);
      assertInstanceOf(
          Rejection.OccupiedLifecycle.class, ((Acceptance.Rejected) acceptance).reason());
      assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
      assertNull(Lizzie.engineGame.firstPlan());

      assertNull(Lizzie.engineGame.transaction());
      assertEquals(1, manager.leaseConflictCount);
      assertEquals(0, observer.playing);
      assertEquals(List.of(), observer.failures);
    } finally {
      reservation.close();
    }
  }

  @Test
  void acceptCreatesBatchPlanTransactionAndPublishesStartingThenPlayingOnce() {
    Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(), observer);

    assertInstanceOf(Acceptance.Accepted.class, acceptance);
    assertNotNull(Lizzie.engineGame.firstPlan());
    assertNotNull(Lizzie.engineGame.transaction());
    assertEquals(FIRST, Lizzie.engineGame.firstPlan().black());
    assertEquals(SECOND, Lizzie.engineGame.firstPlan().white());
    assertEquals(0, Lizzie.engineGame.firstPlan().blackIndex());
    assertEquals(1, Lizzie.engineGame.firstPlan().whiteIndex());
    assertEquals(0, observer.playing);
    assertInstanceOf(GameActivity.Starting.class, activity(Lizzie.engineGame.current()));

    Lizzie.engineGame.onOwnerPlaying();
    assertEquals(1, observer.playing);
    assertEquals(List.of(), observer.failures);
    assertInstanceOf(GameActivity.Playing.class, activity(Lizzie.engineGame.current()));

    Lizzie.engineGame.onOwnerPlaying();
    assertEquals(1, observer.playing);
  }

  @Test
  void stopDuringStartingCancelsOncePublishesIdleAndLeavesOwnerRetired() {
    Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(), observer);
    assertInstanceOf(Acceptance.Accepted.class, acceptance);
    EngineGameSnapshot held = Lizzie.engineGame.current();
    assertInstanceOf(GameActivity.Starting.class, activity(held));

    Lizzie.engineGame.stop();

    assertEquals(0, observer.playing);
    assertEquals(1, observer.failures.size());
    assertInstanceOf(StartFailure.CancelledByUser.class, observer.failures.get(0));
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertInstanceOf(EngineGameSnapshot.BatchActive.class, held);
    Lizzie.engineGame.onOwnerPlaying();
    Lizzie.engineGame.onOwnerStartFailed(new IllegalStateException("late"));
    assertEquals(0, observer.playing);
    assertEquals(1, observer.failures.size());
  }

  @Test
  void publicSnapshotDoesNotParticipateInOwnerAdmission() {
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(), observer));
    EngineGameSnapshot starting = Lizzie.engineGame.current();
    Lizzie.engineGame.stop();
    assertInstanceOf(EngineGameSnapshot.BatchActive.class, starting);
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    EngineManager.resetEngineGameTransactionStateForTest();

    EngineGameInfo info = new EngineGameInfo();
    info.blackEngineIndex = 0;
    info.whiteEngineIndex = 1;
    info.firstEngineIndex = 0;
    info.secondEngineIndex = 1;
    info.isGenmove = true;
    assertNotNull(EngineManager.beginEngineGameTransaction(manager, info, null, true));
    assertInstanceOf(GameActivity.Starting.class, activity(starting));
  }



  @Test
  void pauseResumeAndReviseBatchLimitUpdateProductStateWithoutPlaceholders() {
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(), observer));
    Lizzie.engineGame.onOwnerPlaying();
    Lizzie.engineGame.pause();
    GameActivity.Playing paused = (GameActivity.Playing) activity(Lizzie.engineGame.current());
    assertEquals(RunState.PAUSED, paused.runState());
    assertTrue(LizzieFrame.toolbar.isPkStop);

    Lizzie.engineGame.resume();
    GameActivity.Playing running = (GameActivity.Playing) activity(Lizzie.engineGame.current());
    assertEquals(RunState.RUNNING, running.runState());
    assertFalse(LizzieFrame.toolbar.isPkStop);

    Lizzie.engineGame.reviseBatchLimit(8);
    EngineGameSnapshot.BatchActive active =
        (EngineGameSnapshot.BatchActive) Lizzie.engineGame.current();
    assertEquals(8, active.batch().batchLimit());
    assertEquals(8, EngineManager.engineGameInfo.batchNumber);
  }


  @Test
  void resolvedCatalogSlotsFollowIdentityAfterReorder() throws Exception {
    OccupancyLeelaz alpha = new OccupancyLeelaz();
    OccupancyLeelaz beta = new OccupancyLeelaz();
    alpha.bindLiveRuntime();
    beta.bindLiveRuntime();
    alpha.setEngineCommand("cmd-a");
    beta.setEngineCommand("cmd-b");
    CountingLeaseEngineManager reordered =
        new CountingLeaseEngineManager(List.of(beta, alpha));
    assertEquals(
        1,
        reordered.resolveEngineGameParticipant(new EngineParticipantIdentity("cmd-a", "")));
    assertEquals(
        0,
        reordered.resolveEngineGameParticipant(new EngineParticipantIdentity("cmd-b", "")));
  }

  private static GameActivity activity(EngineGameSnapshot snapshot) {
    return ((EngineGameSnapshot.BatchActive) snapshot).activity();
  }

  private static EngineGameBatchSpec genmoveSpec() {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(FIRST)
            .second(SECOND)
            .genmove(true)
            .timeLimitEnabled(true)
            .firstTimeSeconds(2)
            .secondTimeSeconds(2)
            .build());
  }

  private static final class RecordingObserver implements StartObserver {
    private int playing;
    private final List<StartFailure> failures = new ArrayList<>();

    @Override
    public void playing() {
      playing++;
    }

    @Override
    public void startFailed(StartFailure failure) {
      failures.add(failure);
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
    private boolean runWorkersInline;

    private CountingLeaseEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      leaseConflictCount++;
    }

    @Override
    protected void dispatchEngineGameUi(Runnable update) {
      update.run();
    }

    @Override
    protected Thread createEngineGameWorker(Runnable task, String name) {
      if (!runWorkersInline || isEngineGameDeadlineWatcher(name)) {
        return super.createEngineGameWorker(task, name);
      }
      return new Thread(task, name) {
        @Override
        public synchronized void start() {
          run();
        }
      };
    }

    private static boolean isEngineGameDeadlineWatcher(String name) {
      return name != null && name.startsWith("engine-game-deadline-");
    }
  }

  private static final class OccupancyLeelaz extends Leelaz {
    private OccupancyLeelaz() throws Exception {
      super("");
    }

    private void bindLiveRuntime() {
      installFreshCommandOutputForTest(new ByteArrayOutputStream());
      started = true;
      isLoaded = true;
      width = 19;
      height = 19;
    }

    @Override
    public void notPondering() {}

    @Override
    public void clearBestMoves() {}

    @Override
    public void nameCmd() {}

    @Override
    public void clearWithoutPonder() {}

    @Override
    public void sendCommand(String command) {}
  }

  private static final class SilentFrame extends LizzieFrame {
    @Override
    public boolean isDisplayable() {
      return true;
    }

    @Override
    public boolean isInputRoutingInitialized() {
      return true;
    }

    @Override
    public void addInput(boolean shouldAdd) {}

    @Override
    public void removeInput(boolean shouldRemove) {}

    @Override
    public void setResult(String result) {}

    @Override
    public void resetTitle() {}

    @Override
    public void restoreWRN(boolean isGenmove) {}

    @Override
    public void refresh() {}

    @Override
    public void setPlayers(String whiteName, String blackName) {}

    @Override
    public void updateTitle() {}

    @Override
    public void reSetLoc() {}

    @Override
    public void clearWRNforGame(boolean isGenmove) {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }

    @Override
    public void showUnsupportedWebSocketAdvancedClock() {}
  }

  private static final class SilentToolbar extends BottomToolbar {
    @Override
    public void enableDisabelForEngineGame(boolean enable) {}
  }

  private static final class SilentMenu extends Menu {
    @Override
    public void toggleDoubleMenuGameStatus() {}

    @Override
    public void updateMenuStatusForEngine() {}
  }


  private static final class SilentBoard extends Board {
    @Override
    public void clear(boolean isEngineGame) {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((java.awt.Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }
}
