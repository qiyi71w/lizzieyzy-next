package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.HumanSlGameController;
import featurecat.lizzie.gui.Input;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Focused behavior tests for the root starting-position setup seam (tickets 01 and 02 of issue
 * 217).
 *
 * <p>The seam mutates the root SNAPSHOT in place: placement, replacement, erase, clear-all and
 * side-to-play changes never create {@code MOVE}/{@code PASS} nodes or variations, never apply
 * capture/ko/suicide rules, and round-trip through SGF as root {@code AB}/{@code AW}/{@code PL}.
 */
class BoardRootSetupSeamTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void emptyGameSetupPlacementStaysRootOnlySnapshot() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertTrue(
          Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK),
          "black setup placement on an empty root should succeed.");
      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE),
          "white setup placement on an empty root should succeed.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertTrue(root.getData().isSnapshotNode(), "setup edits should keep the root a snapshot.");
      assertEquals(0, root.numberOfChildren(), "setup placement must not create children.");
      assertEquals(0, root.getData().moveNumber, "setup placement must not consume move numbers.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void emptyGameSetupStonesDoNotBecomeMovesOrFakeSgfMoves() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);

      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("AB[aa]"), "setup black stone should export as root AB[aa].");
      assertTrue(sgf.contains("AW[bb]"), "setup white stone should export as root AW[bb].");
      assertTrue(sgf.contains("PL[B]"), "empty/new setup should default to Black to play.");
      assertFalse(
          sgf.contains(";B[") || sgf.contains(";W["),
          "setup stones must not be saved as ordinary B[]/W[] moves.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode root = roundTrip.getStart();
      assertTrue(root.getData().isSnapshotNode(), "saved setup should reopen as a root snapshot.");
      assertEquals(0, root.numberOfChildren(), "saved setup should reopen root-only.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void blackToolReplacesWhiteSetupStoneInPlace() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertTrue(Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE));
      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.BLACK),
          "a black setup tool should replace an existing white setup stone in place.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(0, root.numberOfChildren(), "replacement must not create nodes.");
      assertTrue(root.getData().isSnapshotNode(), "replacement must keep the root a snapshot.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupEraseRemovesOnlySelectedPoint() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);
      assertTrue(
          Lizzie.board.setupEraseStone(0, 0), "erase tool should remove the selected setup point.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(Stone.EMPTY, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(0, root.numberOfChildren(), "erase must not create nodes.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupPlacementIgnoresCaptureAndSuicide() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      // Surround the center point with black setup stones on a 3x3 board.
      int[][] ring = {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {1, 2}, {2, 2}};
      for (int[] point : ring) {
        assertTrue(Lizzie.board.setupPlaceStone(point[0], point[1], Stone.BLACK));
      }
      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE),
          "setup placement must allow a surrounded stone without capture/suicide logic.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(
          Stone.WHITE,
          root.getData().stones[Board.getIndex(1, 1)],
          "the surrounded white stone should remain part of the starting position.");
      assertEquals(
          Stone.BLACK,
          root.getData().stones[Board.getIndex(0, 0)],
          "setup placement must not capture the surrounding black stones.");
      assertEquals(0, root.numberOfChildren(), "setup placement must stay root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupClearAllPreservesGameInfoAndSideToPlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);
      assertTrue(
          Lizzie.board.setupSetSideToPlay(false), "side-to-play should be switchable to White.");
      Lizzie.board.getHistory().getGameInfo().setKomi(6.5);

      assertTrue(Lizzie.board.setupClearAll(), "clear-all should reset the starting position.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      for (int index = 0; index < BOARD_AREA; index++) {
        assertEquals(Stone.EMPTY, root.getData().stones[index], "clear-all should empty the root.");
      }
      assertFalse(root.getData().blackToPlay, "clear-all must preserve the chosen side-to-play.");
      assertEquals(
          6.5,
          Lizzie.board.getHistory().getGameInfo().getKomi(),
          "clear-all must preserve game metadata such as komi.");
      assertEquals(0, root.numberOfChildren(), "clear-all must keep the tree root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void emptyGameDefaultsToBlackToPlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertTrue(
          Lizzie.board.getHistory().getData().blackToPlay,
          "empty/new setup should default to Black to play.");
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("PL[B]"), "default side-to-play should save as PL[B].");
    } finally {
      env.close();
    }
  }

  @Test
  void setupSideToPlayRoundTripsAsPl() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      assertTrue(Lizzie.board.setupSetSideToPlay(false));

      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("PL[W]"), "white side-to-play should save as PL[W].");

      BoardHistoryList roundTrip = SGFParser.parseSgf(sgf, false);
      assertFalse(
          roundTrip.getStart().getData().blackToPlay, "reopened SGF should restore White to play.");
      assertEquals(0, roundTrip.getStart().numberOfChildren(), "round-trip must stay root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void rootSetupRoundTripIsStableAcrossRepeatedSaves() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);
      Lizzie.board.setupSetSideToPlay(false);

      String firstSave = SGFParser.saveToString(false);
      BoardHistoryList parsed = SGFParser.parseSgf(firstSave, false);
      Lizzie.board.setHistory(parsed);
      String secondSave = SGFParser.saveToString(false);

      assertTrue(
          secondSave.contains("AB[aa]") && secondSave.contains("AW[bb]"),
          "repeated saves must keep the root setup stones.");
      assertTrue(secondSave.contains("PL[W]"), "repeated saves must keep the side-to-play.");
      assertFalse(
          secondSave.contains(";B[") || secondSave.contains(";W["),
          "repeated saves must not invent ordinary moves.");
      assertEquals(
          SGFParser.parseSgf(firstSave, false).getStart().numberOfChildren(),
          SGFParser.parseSgf(secondSave, false).getStart().numberOfChildren(),
          "repeated save cycles must not incrementally generate setup child nodes.");

      BoardHistoryList secondParse = SGFParser.parseSgf(secondSave, false);
      BoardHistoryNode root = secondParse.getStart();
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
      assertFalse(root.getData().blackToPlay, "side-to-play must survive repeated saves.");
      assertEquals(0, root.numberOfChildren(), "repeated saves must stay root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupSeamRefusesWhenRootHasRealHistory() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa])", false);
      Lizzie.board.setHistory(history);

      assertFalse(
          Lizzie.board.setupPlaceStone(1, 1, Stone.BLACK),
          "setup edits must be refused when the root already has real moves.");
      assertEquals(
          1,
          history.getStart().numberOfChildren(),
          "the refused edit must not disturb the existing tree.");
      assertEquals(
          Stone.EMPTY,
          history.getStart().getData().stones[Board.getIndex(1, 1)],
          "the refused edit must not mutate the root.");
    } finally {
      env.close();
    }
  }

  @Test
  void existingRootSetupSgfIsEditableInPlace() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]AB[aa]AW[bb]PL[W])", false);
      Lizzie.board.setHistory(history);

      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.BLACK),
          "a black setup tool should replace an existing root setup white stone.");
      assertTrue(
          Lizzie.board.setupEraseStone(0, 0),
          "the erase tool should remove an existing root setup stone.");
      assertTrue(
          Lizzie.board.setupSetSideToPlay(true),
          "side-to-play should be changeable on an existing root setup SGF.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(Stone.EMPTY, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(0, root.numberOfChildren(), "edits must not create child nodes.");

      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("AB[bb]"), "edited black stone should export as AB[bb].");
      assertTrue(sgf.contains("PL[B]"), "edited side-to-play should export as PL[B].");
      assertFalse(
          sgf.contains("AW[") || sgf.contains(";B[") || sgf.contains(";W["),
          "edited setup must not drift into AW properties or ordinary moves.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode roundTripRoot = roundTrip.getStart();
      assertEquals(Stone.BLACK, roundTripRoot.getData().stones[Board.getIndex(1, 1)]);
      assertTrue(roundTripRoot.getData().blackToPlay, "reopened SGF should restore Black to play.");
      assertEquals(0, roundTripRoot.numberOfChildren(), "reopened SGF must stay root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupModeRoutesMainBoardLeftClickThroughSeam() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = allocate(FakeBoardRenderer.class);
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        Lizzie.frame.onClicked(0, 0);

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.BLACK,
            root.getData().stones[Board.getIndex(1, 1)],
            "setup-mode main board click should place the selected black tool stone.");
        assertEquals(0, root.numberOfChildren(), "setup-mode click must not create move nodes.");

        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_WHITE;
        Lizzie.frame.onClicked(0, 0);
        assertEquals(
            Stone.WHITE,
            root.getData().stones[Board.getIndex(1, 1)],
            "a white tool click should replace the black setup stone in place.");
        assertEquals(0, root.numberOfChildren(), "replacement must stay root-only.");
      } finally {
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  @Test
  void setupModeRoutesMainBoardRightClickThroughSeam() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = allocate(FakeBoardRenderer.class);
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        assertTrue(
            Lizzie.frame.onClickedRight(0, 0),
            "setup-mode right click should be consumed by the setup seam.");

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.WHITE,
            root.getData().stones[Board.getIndex(1, 1)],
            "right click with the black tool should place the opposite white setup stone.");
        assertEquals(0, root.numberOfChildren(), "setup-mode right click must stay root-only.");
      } finally {
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  @Test
  void setupModeRoutingPrecedesActiveHumanSlGame() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = allocate(FakeBoardRenderer.class);
      Lizzie.frame.humanSlGame = allocate(HumanSlGameController.class);
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        Lizzie.frame.onClicked(0, 0);

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.BLACK,
            root.getData().stones[Board.getIndex(1, 1)],
            "setup-mode clicks must not be routed into an active HumanSL game.");
        assertEquals(0, root.numberOfChildren(), "setup-mode click must stay root-only.");
      } finally {
        Lizzie.frame.humanSlGame = null;
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  @Test
  void setupModeInputMousePressedRoutesThroughSeam() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = allocate(FakeBoardRenderer.class);
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        Input.tempDrag = true;
        Input input = new Input();
        javax.swing.JComponent source = new javax.swing.JPanel();
        MouseEvent press =
            new MouseEvent(
                source,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                10,
                10,
                1,
                false,
                MouseEvent.BUTTON1);
        input.mousePressed(press);
        assertFalse(
            Input.tempDrag,
            "setup-mode interception must cancel a pending drag so it cannot fire after exit.");
        Input.tempDrag = false;

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.BLACK,
            root.getData().stones[Board.getIndex(1, 1)],
            "setup-mode mouse press should route through the setup seam.");
        assertEquals(0, root.numberOfChildren(), "setup-mode mouse press must stay root-only.");
      } finally {
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Menu previousMenu;
    private final WinrateGraph previousWinrateGraph;
    private final Leelaz previousLeelaz;
    private final Config previousConfig;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Board previousBoard,
        LizzieFrame previousFrame,
        Menu previousMenu,
        WinrateGraph previousWinrateGraph,
        Leelaz previousLeelaz,
        Config previousConfig) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousMenu = previousMenu;
      this.previousWinrateGraph = previousWinrateGraph;
      this.previousLeelaz = previousLeelaz;
      this.previousConfig = previousConfig;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Menu previousMenu = LizzieFrame.menu;
      WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Config previousConfig = Lizzie.config;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      TrackingBoard board = allocate(TrackingBoard.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.movelistwr = new ArrayList<>();
      board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.board = board;
      Lizzie.frame = allocate(TrackingFrame.class);
      LizzieFrame.menu = allocate(Menu.class);
      LizzieFrame.menu.txtKomi = new javax.swing.JTextField();
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      Config config = allocate(Config.class);
      config.newMoveNumberInBranch = false;
      config.playSound = false;
      config.initialMaxScoreLead = 10;
      Lizzie.config = config;
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousBoard,
          previousFrame,
          previousMenu,
          previousWinrateGraph,
          previousLeelaz,
          previousConfig);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.winrateGraph = previousWinrateGraph;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.config = previousConfig;
    }
  }

  private static final class FakeBoardRenderer extends BoardRenderer {
    private FakeBoardRenderer() {
      super(false);
    }

    @Override
    public Optional<int[]> convertScreenToCoordinates(int x, int y) {
      return Optional.of(new int[] {1, 1});
    }
  }

  private static final class TrackingBoard extends Board {
    private TrackingBoard() {
      super();
    }

    @Override
    public void clear(boolean isEngineGame) {
      if (startStonelist == null) {
        startStonelist = new ArrayList<>();
      } else {
        startStonelist.clear();
      }
      if (movelistwr == null) {
        movelistwr = new ArrayList<>();
      } else {
        movelistwr.clear();
      }
      hasStartStone = false;
      setHistory(new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)));
    }

    @Override
    public void clearforedit() {
      clear(false);
    }

    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private TrackingFrame() {
      super();
    }

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void refreshProblemListSnapshot() {}

    @Override
    public void refresh() {}

    @Override
    public void setPlayers(String whitePlayer, String blackPlayer) {}

    @Override
    public void resetTitle() {}

    @Override
    public void tryToResetByoTime() {}
  }

  private static final class TrackingLeelaz extends Leelaz {
    private TrackingLeelaz() throws IOException {
      super("");
    }

    @Override
    public void clear() {}

    @Override
    public void clearBestMoves() {}

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {}

    @Override
    public void playMove(Stone color, String move) {}

    @Override
    public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {}

    @Override
    public void loadSgf(Path sgfFile) {}

    @Override
    public void sendCommand(String command) {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    if (Leelaz.class.isAssignableFrom(type)) {
      java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    }
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
