package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameBatchSpecFactory;
import featurecat.lizzie.enginegame.EngineGameParsedStart;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
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

class EngineManagerEngineGameOccupancyHandoffTest {
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
  private boolean previousEmpty;
  private int previousEngineNo;

  private OccupancyLeelaz black;
  private OccupancyLeelaz white;
  private CountingLeaseEngineManager manager;
  private SilentFrame frame;
  private SilentMenu menu;

  @BeforeEach
  void installOccupancyFixture() throws Exception {
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
    black.setEngineCommand("black-cmd");
    white.setEngineCommand("white-cmd");

    white.bindLiveRuntime();
    Lizzie.setPrimaryEngine(black);
    frame = allocate(SilentFrame.class);
    Lizzie.frame = frame;
    LizzieFrame.toolbar = allocate(SilentToolbar.class);
    menu = allocate(SilentMenu.class);
    LizzieFrame.menu = menu;
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
  }

  @AfterEach
  void restoreOccupancyFixture() throws Exception {
    if (black != null) {
      black.started = false;
      black.isLoaded = false;
    }
    if (white != null) {
      white.started = false;
      white.isLoaded = false;
    }
    EngineManager.resetEngineGameTransactionStateForTest();
    Lizzie.engineManager = previousManager;
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
  void endThenUnfinishedRestoreThenImmediateRestartDropsDelayedExclusivePrompt() throws Exception {
    EngineManager.EngineGameTransaction transaction =
        EngineManager.beginEngineGameTransaction(
            manager, EngineGamePlans.harness(0, 1, true), null, true);
    assertTrue(EngineManager.transitionEngineGameToDispatched(transaction));
    assertTrue(
        EngineManager.activateEngineGameTransaction(
            transaction,
            black,
            0,
            black.currentEngineIncarnation(),
            white.currentEngineIncarnation()));

    manager.stopEngineGame(0, true);
    assertFalse(EngineManager.hasActiveEngineGameTransaction());
    assertSame(black, Lizzie.leelaz);
    assertTrue(black.isUnfinishedForegroundRestoreOccupancyHeldForTest());

    long generationBefore = black.exclusiveOccupancyPromptGeneration();
    SwingUtilities.invokeAndWait(
        () -> {
          black.showExclusiveGtpConflictMessage();
          manager.startNewEngineGame(true);
        });

    assertEquals(0, manager.leaseConflictCount);
    assertTrue(
        black.exclusiveOccupancyPromptGeneration() > generationBefore,
        "immediate restart must hand off unfinished end-game restore occupancy");
    assertFalse(black.isUnfinishedForegroundRestoreOccupancyHeldForTest());
    assertFalse(EngineManager.hasPlayingEngineGameTransaction());
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(
        List.of(),
        black.displayedKeys,
        "a delayed exclusive-task prompt from end-game restore must not appear after occupancy already succeeded");
  }

  @Test
  void endThenUnfinishedRestoreThenWarmKataGoImmediateRestartActivatesRouteOwnedRestore()
      throws Exception {
    WarmKataGoOccupancyLeelaz blackEngine = new WarmKataGoOccupancyLeelaz(true);
    WarmKataGoOccupancyLeelaz whiteEngine = new WarmKataGoOccupancyLeelaz(false);
    CountingLeaseEngineManager restartManager =
        new CountingLeaseEngineManager(List.of(blackEngine, whiteEngine));
    restartManager.runWorkersInline = true;
    blackEngine.bindWarmKataGoRuntime();
    whiteEngine.bindWarmKataGoRuntime();
    blackEngine.setEngineCommand("black-cmd");
    whiteEngine.setEngineCommand("white-cmd");

    Lizzie.setPrimaryEngine(blackEngine);
    Lizzie.engineManager = restartManager;
    EngineManager.currentEngineNo = 0;
    try {
      EngineManager.EngineGameTransaction firstGame =
          EngineManager.beginEngineGameTransaction(
              restartManager, EngineGamePlans.harness(0, 1, true), null, true);
      assertTrue(EngineManager.transitionEngineGameToDispatched(firstGame));
      assertTrue(
          EngineManager.activateEngineGameTransaction(
              firstGame,
              blackEngine,
              0,
              blackEngine.currentEngineIncarnation(),
              whiteEngine.currentEngineIncarnation()));

      restartManager.stopEngineGame(0, true);
      assertFalse(EngineManager.hasActiveEngineGameTransaction());
      assertSame(blackEngine, Lizzie.leelaz);
      assertTrue(blackEngine.isUnfinishedForegroundRestoreOccupancyHeldForTest());

      long generationBefore = blackEngine.exclusiveOccupancyPromptGeneration();
      EngineManager.EngineGameTransaction[] secondGame = new EngineManager.EngineGameTransaction[1];
      SwingUtilities.invokeAndWait(
          () -> {
            blackEngine.showExclusiveGtpConflictMessage();
            assertTrue(
                blackEngine.beginExclusiveGtpLifecycleTransition(),
                "unfinished end-game restore occupancy must hand off to the next start");
            try {
              secondGame[0] =
                  EngineManager.beginEngineGameTransaction(
                      restartManager, EngineGamePlans.harness(0, 1, true), null, true);
            } finally {
              blackEngine.endExclusiveGtpLifecycleTransition();
            }
          });
      assertTrue(secondGame[0] != null, "second engine-game transaction must publish");

      EngineManager.PkEngineSynchronization blackSync =
          restartManager.startEngineForPkSynchronizationForTest(secondGame[0], 0, blackEngine);
      EngineManager.PkEngineSynchronization whiteSync =
          restartManager.startEngineForPkSynchronizationForTest(secondGame[0], 1, whiteEngine);

      assertTrue(
          EngineManager.isCurrentEngineGameTransaction(secondGame[0]),
          "warm KataGo PK start must keep the second transaction current");
      assertFalse(
          blackSync.hasFailed(),
          "black warm KataGo PK start must pass ordinary-command admission");
      assertFalse(
          whiteSync.hasFailed(),
          "white warm KataGo PK start must pass ordinary-command admission");
      assertTrue(EngineManager.transitionEngineGameToDispatched(secondGame[0]));
      assertTrue(
          restartManager.finishPkEngineSynchronizations(secondGame[0], blackSync, whiteSync));
      assertTrue(
          EngineManager.activateEngineGameTransaction(
              secondGame[0],
              blackEngine,
              0,
              blackEngine.currentEngineIncarnation(),
              whiteEngine.currentEngineIncarnation()));

      assertEquals(0, restartManager.leaseConflictCount);
      assertTrue(
          blackEngine.exclusiveOccupancyPromptGeneration() > generationBefore,
          "immediate restart must hand off unfinished end-game restore occupancy");
      assertFalse(blackEngine.isUnfinishedForegroundRestoreOccupancyHeldForTest());
      assertTrue(EngineManager.hasPlayingEngineGameTransaction());
      assertEquals(EngineManager.EngineGamePhase.ACTIVE, secondGame[0].phase());
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals(
          List.of(),
          blackEngine.displayedKeys,
          "a delayed exclusive-task prompt from end-game restore must not appear after occupancy already succeeded");
      assertEquals(List.of(), whiteEngine.displayedKeys);
      assertTrue(
          hasRouteOwnedRestoreCommand(blackEngine.transport),
          "black warm KataGo must execute the frozen restore route");
      assertTrue(
          hasRouteOwnedRestoreCommand(whiteEngine.transport),
          "white warm KataGo must execute the frozen restore route");
    } finally {
      blackEngine.started = false;
      blackEngine.isLoaded = false;
      whiteEngine.started = false;
      whiteEngine.isLoaded = false;
    }
  }

  @Test
  void ownEdtEngineModeReservationDoesNotRejectWarmForegroundStartEngineGame()
      throws Exception {
    WarmKataGoOccupancyLeelaz blackEngine = new WarmKataGoOccupancyLeelaz(true);
    WarmKataGoOccupancyLeelaz whiteEngine = new WarmKataGoOccupancyLeelaz(false);
    CountingLeaseEngineManager restartManager =
        new CountingLeaseEngineManager(List.of(blackEngine, whiteEngine));
    restartManager.runWorkersInline = true;
    blackEngine.bindWarmKataGoRuntime();
    whiteEngine.bindWarmKataGoRuntime();
    blackEngine.setEngineCommand("black-cmd");
    whiteEngine.setEngineCommand("white-cmd");

    Lizzie.setPrimaryEngine(whiteEngine);
    Lizzie.engineManager = restartManager;
    EngineManager.currentEngineNo = 1;
    try {
      boolean[] started = new boolean[1];
      SwingUtilities.invokeAndWait(
          () -> {
            Leelaz.EngineModeReservation reservation =
                whiteEngine.beginEngineModeReservation();
            assertTrue(
                reservation != null, "EDT must hold the current foreground reservation");
            try {
              started[0] = acceptStart();

            } finally {
              reservation.close();
            }
          });

      assertTrue(started[0], "the same dialog action must start the next engine game");
      assertTrue(EngineManager.hasPlayingEngineGameTransaction());
      EngineManager.EngineGameTransaction transaction =
          EngineManager.activeEngineGameTransactionForTest();
      assertTrue(transaction != null, "activated engine-game transaction must remain current");
      assertEquals(EngineManager.EngineGamePhase.ACTIVE, transaction.phase());
      assertEquals(0, restartManager.leaseConflictCount);
      assertTrue(
          hasRouteOwnedRestoreCommand(blackEngine.transport),
          "black warm KataGo must execute the frozen restore route");
      assertTrue(
          hasRouteOwnedRestoreCommand(whiteEngine.transport),
          "white warm KataGo must execute the frozen restore route");
    } finally {
      blackEngine.started = false;
      blackEngine.isLoaded = false;
      whiteEngine.started = false;
      whiteEngine.isLoaded = false;
    }
  }

  @Test
  void occupancyFailureRefusesStartEngineGameAndDoesNotEnterInGameUi() throws Exception {
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        black.beginExclusiveGtpLifecycleReservation(new Object());
    assertTrue(reservation != null);
    try {
      boolean started = acceptStart();


      assertFalse(started);
      assertFalse(EngineManager.hasActiveEngineGameTransaction());
      assertEquals(1, manager.leaseConflictCount);
      assertFalse(Lizzie.board.isPkBoard);
    } finally {
      reservation.close();
    }
  }

  @Test
  void pkOccupancyFailureDoesNotStartTheGame() throws Exception {
    Leelaz.ExclusiveGtpLifecycleReservation whiteReservation =
        white.beginExclusiveGtpLifecycleReservation(new Object());
    assertTrue(whiteReservation != null);
    try {
      EngineManager.PkEngineSynchronization whiteSync = manager.startEngineForPkSynchronization(1);
      assertTrue(whiteSync.hasFailed());
      assertTrue(
          manager.leaseConflictCount >= 1,
          "a live exclusive occupant must still show the exclusive-task dialog");

      EngineManager.EngineGameTransaction transaction =
          EngineManager.beginEngineGameTransaction(
              manager, EngineGamePlans.harness(0, 1, true), null, true);
      assertTrue(transaction != null);
      Lizzie.board.isPkBoard = true;

      assertTrue(manager.abortStartIfPkOccupancyRejected(transaction, whiteSync, whiteSync));

      assertFalse(EngineManager.hasActiveEngineGameTransaction());
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(
          Lizzie.board.isPkBoard,
          "a real occupancy failure must not leave the engine-game UI active");
    } finally {
      whiteReservation.close();
    }
  }

  private static boolean acceptStart() {
    Acceptance acceptance =
        Lizzie.engineGame.accept(
            genmoveSpec(),
            new StartObserver() {
              @Override
              public void playing() {}

              @Override
              public void startFailed(StartFailure failure) {}
            });
    return acceptance instanceof Acceptance.Accepted;
  }

  private static EngineGameBatchSpec genmoveSpec() {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(new EngineParticipantIdentity("black-cmd", ""))
            .second(new EngineParticipantIdentity("white-cmd", ""))
            .genmove(true)
            .timeLimitEnabled(true)
            .firstTimeSeconds(2)
            .secondTimeSeconds(2)
            .build());
  }

  private static boolean hasRouteOwnedRestoreCommand(
      ExactSnapshotRestoreProtocolFixture.Transport transport) {
    for (String command : transport.commands()) {
      if (command.startsWith("loadsgf ")
          || command.startsWith("play ")
          || command.equals("clear_board")
          || command.startsWith("clear_board ")) {
        return true;
      }
    }
    return false;
  }

  private static void setLeelazField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
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
    public void changeEngIcoForEndPk() {
      Lizzie.leelaz.holdUnfinishedForegroundRestoreOccupancyForTest();
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
    private final List<String> displayedKeys = new ArrayList<>();

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

    @Override
    protected void displayExclusiveGtpConflictMessage(String key) {
      displayedKeys.add(key);
    }
  }

  private static final class WarmKataGoOccupancyLeelaz extends Leelaz {
    private final List<String> displayedKeys = new ArrayList<>();
    private final ExactSnapshotRestoreProtocolFixture.Transport transport;
    private final boolean gateRootReplayAfterKomi;
    private int komiCommandCount;

    private WarmKataGoOccupancyLeelaz(boolean gateRootReplayAfterKomi) throws Exception {
      super("");
      this.gateRootReplayAfterKomi = gateRootReplayAfterKomi;
      transport =
          ExactSnapshotRestoreProtocolFixture.install(this, this::respondToCommand);
    }

    private ExactSnapshotRestoreProtocolFixture.Response respondToCommand(String command)
        throws Exception {
      // Reproduce the tracking-release gate appearing between captured root replay commands.
      if (gateRootReplayAfterKomi && command.startsWith("komi ") && ++komiCommandCount == 2) {
        setLeelazField(this, "exclusiveGtpLifecycleQueueGate", true);
      }
      return ExactSnapshotRestoreProtocolFixture.Response.success();
    }

    private void bindWarmKataGoRuntime() {
      installFreshCommandOutputForTest(transport);
      started = true;
      isLoaded = true;
      isCheckingName = false;
      isKatago = true;
      firstLoad = false;
      width = 19;
      height = 19;
    }

    @Override
    protected void displayExclusiveGtpConflictMessage(String key) {
      displayedKeys.add(key);
    }
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
  }

  private static final class SilentToolbar extends BottomToolbar {
    @Override
    public void enableDisabelForEngineGame(boolean enable) {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}

    @Override
    public void toggleDoubleMenuGameStatus() {}

    @Override
    public void setBtnRankMark() {}

    @Override
    public void updateMenuStatusForEngine() {}
  }
}
