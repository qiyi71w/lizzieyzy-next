package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PositionConfirmedRollbackTest {
  private static final int BOARD_SIZE = 19;
  private static final long OBSERVATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

  @Test
  void acceptedSnapshotRollbackRejectsOldAnalysisUntilUndoIsConfirmed() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      String play = awaitRawCommand(harness.transport, "play B Q16", 0);
      acknowledge(harness.engine, play);

      harness.engine.ponder();
      String initialAnalyze = awaitRawCommand(harness.transport, "kata-analyze", 0);
      acknowledge(harness.engine, initialAnalyze);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));

      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);
      assertNoAnalysis(harness.p0.getData());

      harness.engine.sendCommandWithResponseForTest("name", () -> {});
      String precedingQuery = awaitRawCommand(harness.transport, "name", 0);

      harness.acceptEmptySnapshot();

      assertSame(
          harness.p0,
          harness.board.getHistory().getCurrentHistoryNode(),
          "the accepted snapshot must navigate the real history back to P0");
      assertSame(
          harness.p1,
          harness.board.getHistory().getMainEnd(),
          "rollback must retain the legitimate P1 history node");
      assertEquals(
          0,
          payloadCount(harness.transport, "undo"),
          "the unanswered query must keep undo in the real command queue");
      assertEquals(
          1,
          payloadCount(harness.transport, "kata-analyze"),
          "queued rollback must not physically start analysis for P0");
      assertNotNull(
          harness.frame.scheduledResume,
          "ReadBoard should offer its delayed resume to the scheduler seam");
      assertEquals(1, harness.frame.scheduledResumeCount);

      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(harness.p0.getData());
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);

      acknowledge(harness.engine, precedingQuery);
      String undo = awaitRawCommand(harness.transport, "undo", 0);

      assertEquals(
          1,
          payloadCount(harness.transport, "kata-analyze"),
          "a written but unconfirmed undo must not physically start successor analysis");
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(harness.p0.getData());
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);

      acknowledge(harness.engine, undo);
      String confirmedAnalyze = awaitRawCommand(harness.transport, "kata-analyze", 1);
      acknowledge(harness.engine, confirmedAnalyze);
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));

      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertAnalysis(harness.p0.getData(), 4_875, 96.937);
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);
      assertEquals(
          2,
          payloadCount(harness.transport, "kata-analyze"),
          "confirmation should produce one physical successor analysis start");
      assertFalse(
          harness.frame.scheduledResumeRan,
          "the delayed scheduler action is deliberately recorded but not executed in this test");
    }
  }

  @Test
  void manualNavigationPassAndPlacementBindTheirIntendedTargets() throws Exception {
    BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    try (Harness harness = Harness.open()) {
      LizzieFrame.boardRenderer = allocate(SilentBoardRenderer.class);
      harness.frame.readBoard = null;
      harness.engine.canAddPlayer = true;
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));

      assertTrue(harness.board.previousMove(false));
      String undo = awaitRawCommand(harness.transport, "undo", 0);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(harness.p0.getData());
      assertEquals(1, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, undo);
      String backwardAnalysis = awaitRawCommand(harness.transport, "kata-analyze B", 0);
      acknowledge(harness.engine, backwardAnalysis);
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
      assertAnalysis(harness.p0.getData(), 4_875, 96.937);

      assertTrue(harness.board.nextMove(false));
      String replay = awaitRawCommand(harness.transport, "play B Q16", 1);
      assertSame(harness.p1, harness.board.getHistory().getCurrentHistoryNode());
      assertEquals(2, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, replay);
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze W", 1));
      harness.engine.parseAnalysisLineForTest(info(100, 0.6));
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);

      harness.board.pass();
      BoardHistoryNode pass = harness.board.getHistory().getCurrentHistoryNode();
      assertTrue(pass.getData().isPassNode());
      String passCommand = awaitRawCommand(harness.transport, "play W pass", 0);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(pass.getData());
      assertEquals(3, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, passCommand);
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze B", 1));
      harness.engine.parseAnalysisLineForTest(info(50, 0.8));
      assertAnalysis(pass.getData(), 50, 80);

      harness.board.place(3, 15, Stone.BLACK);
      BoardHistoryNode placed = harness.board.getHistory().getCurrentHistoryNode();
      String placement = awaitRawCommand(harness.transport, "play B D4", 0);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(placed.getData());
      assertEquals(4, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, placement);
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze W", 2));
      harness.engine.parseAnalysisLineForTest(info(25, 0.7));
      assertAnalysis(placed.getData(), 25, 70);
    } finally {
      LizzieFrame.boardRenderer = previousRenderer;
    }
  }

  @Test
  void secondaryPacketsCannotEnterPrimarySlotAfterLeavingDoubleMode() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      Leelaz secondary = new Leelaz("");
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.isKatago = true;
      Lizzie.leelaz2 = secondary;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      ExactSnapshotRestoreProtocolFixture.install(
          secondary, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      secondary.sendCommandNoLeelaz2("kata-analyze W 10");
      secondary.parseAnalysisLineForTest(info(40, 0.6));
      assertEquals(40, harness.p1.getData().getPlayouts2());

      Lizzie.config.extraMode = ExtraMode.Normal;
      secondary.parseAnalysisLineForTest(info(20_000, 0.8));

      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);
    }
  }

  @Test
  void savedSgfAnalysisSurvivesConfirmedHistoryRevisit() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      harness.engine.bestMovesEnginename = "KataGo";
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      harness.engine.parseAnalysisLineForTest(info(12_000, 0.615));
      String saved = SGFParser.saveToString(false);
      BoardHistoryList imported = SGFParser.parseSgf(saved, false);
      BoardHistoryNode importedP1 = imported.getMainEnd();
      assertAnalysis(importedP1.getData(), 12_000, 61.5);
      harness.board.setHistory(imported);
      harness.engine.sendCommand("clear_board");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "clear_board", 0));

      assertTrue(harness.board.nextMove(false));
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 1));
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 1));
      harness.engine.parseAnalysisLineForTest(info(50, 0.8));

      assertSame(importedP1, harness.board.getHistory().getCurrentHistoryNode());
      assertAnalysis(importedP1.getData(), 12_000, 61.5);
    }
  }

  @Test
  void navigationBetweenInfoIngressAndPublicationCannotWriteSuccessor() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      ((PublicationControlledLeelaz) harness.engine).beforePublication =
          () -> assertTrue(harness.board.previousMove(false));

      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));

      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertNoAnalysis(harness.p0.getData());
      assertEquals(1, payloadCount(harness.transport, "kata-analyze"));
    }
  }

  private static String info(int visits, double winrate) {
    return "info move C13 visits "
        + visits
        + " winrate "
        + winrate
        + " prior 0.7 lcb "
        + winrate
        + " scoreMean 1 scoreStdev 2 order 0 pv C13";
  }

  private static void assertNoAnalysis(BoardData data) {
    assertEquals(0, data.getPlayouts());
    assertTrue(data.bestMoves.isEmpty());
  }

  private static void assertAnalysis(BoardData data, int visits, double winrate) {
    assertEquals(visits, data.getPlayouts());
    assertEquals(winrate, data.winrate, 0.000_001);
    assertEquals(1, data.bestMoves.size());
    assertEquals("C13", data.bestMoves.get(0).coordinate);
    assertEquals(visits, data.bestMoves.get(0).playouts);
    assertEquals(winrate, data.bestMoves.get(0).winrate, 0.000_001);
  }

  private static String awaitRawCommand(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix, int occurrence)
      throws InterruptedException {
    long deadline = System.nanoTime() + OBSERVATION_TIMEOUT_NANOS;
    while (System.nanoTime() < deadline) {
      String raw = rawCommand(transport, prefix, occurrence);
      if (raw != null) {
        return raw;
      }
      Thread.sleep(5L);
    }
    throw new AssertionError(
        "Timed out waiting for physical command "
            + prefix
            + " occurrence "
            + occurrence
            + "; commands="
            + transport.rawCommands());
  }

  private static String rawCommand(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix, int occurrence) {
    List<String> commands = transport.commands();
    List<String> rawCommands = transport.rawCommands();
    int matched = 0;
    for (int index = 0; index < commands.size(); index++) {
      if (commands.get(index).startsWith(prefix)) {
        if (matched == occurrence) {
          return rawCommands.get(index);
        }
        matched++;
      }
    }
    return null;
  }

  private static int payloadCount(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix) {
    int count = 0;
    for (String command : transport.commands()) {
      if (command.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static void acknowledge(Leelaz engine, String rawCommand) {
    int split = rawCommand.indexOf(' ');
    String id =
        split > 0 && isDigits(rawCommand.substring(0, split)) ? rawCommand.substring(0, split) : "";
    engine.processCommandResponseLineForTest("=" + id);
  }

  private static boolean isDigits(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      if (!Character.isDigit(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static void setPendingSnapshot(ReadBoard readBoard, int[] snapshotCodes)
      throws Exception {
    ArrayList<Integer> counts = new ArrayList<>(snapshotCodes.length);
    for (int code : snapshotCodes) {
      counts.add(code);
    }
    setField(readBoard, "tempcount", counts);
  }

  private static void invokeSyncBoardStones(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("syncBoardStones", boolean.class);
    method.setAccessible(true);
    method.invoke(readBoard, false);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final Leelaz previousPrimary;
    private final Leelaz previousSecondary;
    private final LizzieFrame previousFrame;
    private final EngineManager previousManager;
    private final Menu previousMenu;
    private final BottomToolbar previousToolbar;
    private final boolean previousEngineEmpty;
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final boolean previousKeepForcing;
    private final String previousAllowCoords;
    private final String previousAvoidCoords;

    private final HeadlessBoard board;
    private final RecordingFrame frame;
    private final Leelaz engine;
    private final ReadBoard readBoard;
    private final ExactSnapshotRestoreProtocolFixture.Transport transport;
    private final BoardHistoryNode p0;
    private final BoardHistoryNode p1;

    private Harness(
        Config previousConfig,
        Board previousBoard,
        Leelaz previousPrimary,
        Leelaz previousSecondary,
        LizzieFrame previousFrame,
        EngineManager previousManager,
        Menu previousMenu,
        BottomToolbar previousToolbar,
        boolean previousEngineEmpty,
        int previousBoardWidth,
        int previousBoardHeight,
        boolean previousKeepForcing,
        String previousAllowCoords,
        String previousAvoidCoords,
        HeadlessBoard board,
        RecordingFrame frame,
        Leelaz engine,
        ReadBoard readBoard,
        ExactSnapshotRestoreProtocolFixture.Transport transport,
        BoardHistoryNode p0,
        BoardHistoryNode p1) {
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousPrimary = previousPrimary;
      this.previousSecondary = previousSecondary;
      this.previousFrame = previousFrame;
      this.previousManager = previousManager;
      this.previousMenu = previousMenu;
      this.previousToolbar = previousToolbar;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousKeepForcing = previousKeepForcing;
      this.previousAllowCoords = previousAllowCoords;
      this.previousAvoidCoords = previousAvoidCoords;
      this.board = board;
      this.frame = frame;
      this.engine = engine;
      this.readBoard = readBoard;
      this.transport = transport;
      this.p0 = p0;
      this.p1 = p1;
    }

    private static Harness open() throws Exception {
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      Leelaz previousPrimary = Lizzie.leelaz;
      Leelaz previousSecondary = Lizzie.leelaz2;
      LizzieFrame previousFrame = Lizzie.frame;
      EngineManager previousManager = Lizzie.engineManager;
      Menu previousMenu = LizzieFrame.menu;
      BottomToolbar previousToolbar = LizzieFrame.toolbar;
      boolean previousEngineEmpty = EngineManager.isEmpty;
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      boolean previousKeepForcing = LizzieFrame.isKeepForcing;
      String previousAllowCoords = LizzieFrame.allowcoords;
      String previousAvoidCoords = LizzieFrame.avoidcoords;

      EngineManager.resetEngineGameTransactionStateForTest();
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.enableLizzieCache = true;
      config.readBoardPonder = true;
      config.analyzeBlack = true;
      config.analyzeWhite = true;
      config.analyzeUpdateIntervalCentisec = 10;
      config.newMoveNumberInBranch = true;
      Lizzie.config = config;

      RecordingFrame frame = allocate(RecordingFrame.class);
      frame.priorityMoveCoords = new ArrayList<>();
      frame.isPlayingAgainstLeelaz = false;
      frame.isAnaPlayingAgainstLeelaz = false;
      frame.bothSync = false;
      frame.syncBoard = false;
      Lizzie.frame = frame;
      LizzieFrame.menu = allocate(SilentMenu.class);
      LizzieFrame.toolbar = allocate(BottomToolbar.class);
      LizzieFrame.isKeepForcing = false;
      LizzieFrame.allowcoords = "";
      LizzieFrame.avoidcoords = "";

      Leelaz engine = new PublicationControlledLeelaz();
      engine.isKatago = true;
      engine.started = true;
      engine.isLoaded = true;
      engine.requireResponseBeforeSend = true;
      engine.commandLists.addAll(List.of("name", "play", "undo", "stop", "kata-analyze"));
      setField(engine, "endGetCommandList", true);
      Lizzie.engineManager = new EngineManager(List.of(engine));
      EngineManager.isEmpty = false;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = null;

      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);

      HeadlessBoard board = new HeadlessBoard();
      Lizzie.board = board;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode p0 = history.getCurrentHistoryNode();
      history.place(15, 3, Stone.BLACK, false);
      BoardHistoryNode p1 = history.getCurrentHistoryNode();
      board.setHistory(history);

      ReadBoard readBoard = allocate(ReadBoard.class);
      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      readBoard.firstSync = false;
      frame.readBoard = readBoard;

      readBoard.parseLine("syncPlatform fox");
      readBoard.parseLine("roomToken rollback-regression");
      readBoard.parseLine("liveTitleMove 0");
      readBoard.parseLine("foxMoveNumber 0");

      return new Harness(
          previousConfig,
          previousBoard,
          previousPrimary,
          previousSecondary,
          previousFrame,
          previousManager,
          previousMenu,
          previousToolbar,
          previousEngineEmpty,
          previousBoardWidth,
          previousBoardHeight,
          previousKeepForcing,
          previousAllowCoords,
          previousAvoidCoords,
          board,
          frame,
          engine,
          readBoard,
          transport,
          p0,
          p1);
    }

    private void acceptEmptySnapshot() throws Exception {
      setPendingSnapshot(readBoard, new int[BOARD_SIZE * BOARD_SIZE]);
      invokeSyncBoardStones(readBoard);
    }

    @Override
    public void close() {
      engine.started = false;
      engine.isLoaded = false;
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.engineManager = previousManager;
      EngineManager.isEmpty = previousEngineEmpty;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.isKeepForcing = previousKeepForcing;
      LizzieFrame.allowcoords = previousAllowCoords;
      LizzieFrame.avoidcoords = previousAvoidCoords;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
    }
  }

  private static final class PublicationControlledLeelaz extends Leelaz {
    private Runnable beforePublication;

    private PublicationControlledLeelaz() throws java.io.IOException {
      super("");
    }

    @Override
    void beforeAnalysisDisplayPublicationForTest() {
      Runnable action = beforePublication;
      beforePublication = null;
      if (action != null) action.run();
    }
  }

  private static final class HeadlessBoard extends Board {
    @Override
    public void clearAfterMove() {
      Lizzie.leelaz.clearPonderLimit();
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    private int scheduledResumeCount;
    private volatile Runnable scheduledResume;
    private boolean scheduledResumeRan;

    @Override
    public BoardHistoryNode getDisplayNode() {
      return Lizzie.board.getHistory().getCurrentHistoryNode();
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      scheduledResumeCount++;
      scheduledResume =
          () -> {
            scheduledResumeRan = true;
            action.run();
          };
    }

    @Override
    public void onMainEnginePonder() {}

    @Override
    public void clearSelectImage() {}

    @Override
    public void clearKataEstimate() {}

    @Override
    public void clearTryPlay() {}

    @Override
    public void requestAnalysisRefresh() {}

    @Override
    public void requestAnalysisTitleUpdate() {}

    @Override
    public void refresh() {}

    @Override
    public void refreshAfterMove() {}

    @Override
    public void updateTitle() {}

    @Override
    public void resetTitle() {}

    @Override
    public void renderVarTree(int vw, int vh, boolean changeSize, boolean needGetEnd) {}
  }

  private static final class SilentBoardRenderer extends BoardRenderer {
    private SilentBoardRenderer() {
      super(false);
    }

    @Override
    public void removedrawmovestone() {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}

    @Override
    public void toggleDoubleMenuGameStatus() {}
  }
}
