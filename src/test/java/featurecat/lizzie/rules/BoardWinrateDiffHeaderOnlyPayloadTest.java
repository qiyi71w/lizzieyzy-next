package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.lang.reflect.Field;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class BoardWinrateDiffHeaderOnlyPayloadTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void lastWinrateDiffUsesHeaderOnlyPayloadWhenPlayoutsAreZero() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(headerOnlyMove(0, 0, Stone.BLACK, true, 1, 68, 74));
      history.add(headerOnlyMove(1, 0, Stone.WHITE, true, 2, 41, 39));
      env.board.setHistory(history);

      BoardHistoryNode current = history.getCurrentHistoryNode();
      assertEquals(
          27.0,
          env.board.lastWinrateDiff(current),
          0.0001,
          "primary winrate diff should be computed from header-only payload.");
      assertEquals(
          35.0,
          env.board.lastWinrateDiff2(current),
          0.0001,
          "secondary winrate diff should be computed from header-only payload.");
    } finally {
      env.close();
    }
  }

  private static BoardData headerOnlyMove(
      int x,
      int y,
      Stone color,
      boolean blackToPlay,
      int moveNumber,
      double winrate,
      double winrate2) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    BoardData data =
        BoardData.move(
            stones,
            new int[] {x, y},
            color,
            blackToPlay,
            zobrist(stones),
            moveNumber,
            new int[BOARD_AREA],
            0,
            0,
            winrate,
            0);
    data.engineName = "MainEngine";
    data.analysisHeaderSlots = 3;
    data.winrate = winrate;
    data.setPlayouts(0);

    data.engineName2 = "SubEngine";
    data.analysisHeaderSlots2 = 3;
    data.winrate2 = winrate2;
    data.setPlayouts2(0);
    return data;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TrackingBoard extends Board {
    private TrackingBoard() {
      super();
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final TrackingBoard board;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        TrackingBoard board) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.board = board;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      TrackingBoard board = allocate(TrackingBoard.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.movelistwr = new ArrayList<>();
      board.isPkBoard = false;
      board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));

      Config config = allocate(Config.class);
      Lizzie.config = config;
      Lizzie.board = board;
      return new TestEnvironment(
          previousBoardWidth, previousBoardHeight, previousConfig, previousBoard, board);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
    }
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
