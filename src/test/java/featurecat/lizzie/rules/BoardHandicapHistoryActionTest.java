package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoardHandicapHistoryActionTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void isFirstWhiteNodeWithHandicapIgnoresSnapshotMarkers() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode blackMove = root.add(new BoardHistoryNode(moveNode(0, 0, Stone.BLACK, 1)));
      BoardHistoryNode snapshot =
          blackMove.add(
              new BoardHistoryNode(
                  snapshotNode(Optional.of(new int[] {1, 1}), Stone.BLACK, false, 1)));
      BoardHistoryNode whiteMove =
          snapshot.add(new BoardHistoryNode(moveNode(2, 2, Stone.WHITE, 2)));

      assertFalse(
          board.isFirstWhiteNodeWithHandicap(whiteMove),
          "snapshot markers should not count as handicap stones.");
    } finally {
      env.close();
    }
  }

  @Test
  void isFirstWhiteNodeWithHandicapTreatsEarlierWhitePassAsARealWhiteAction() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode blackMove1 = root.add(new BoardHistoryNode(moveNode(0, 0, Stone.BLACK, 1)));
      BoardHistoryNode blackMove2 =
          blackMove1.add(new BoardHistoryNode(moveNode(1, 0, Stone.BLACK, 2)));
      BoardHistoryNode whitePass =
          blackMove2.add(new BoardHistoryNode(passNode(Stone.WHITE, true, 3)));
      BoardHistoryNode blackMove3 =
          whitePass.add(new BoardHistoryNode(moveNode(2, 0, Stone.BLACK, 4)));
      BoardHistoryNode whiteMove =
          blackMove3.add(new BoardHistoryNode(moveNode(2, 2, Stone.WHITE, 5)));

      assertFalse(
          board.isFirstWhiteNodeWithHandicap(whiteMove),
          "an earlier white pass should already end the initial handicap streak.");
    } finally {
      env.close();
    }
  }

  private static BoardData moveNode(int x, int y, Stone color, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        color == Stone.WHITE,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData passNode(Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        emptyStones(),
        color,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData snapshotNode(
      Optional<int[]> lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    lastMove.ifPresent(coords -> stones[Board.getIndex(coords[0], coords[1])] = lastMoveColor);
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
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

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;

    private TestEnvironment(int previousBoardWidth, int previousBoardHeight) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
    }

    private static TestEnvironment open() {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return new TestEnvironment(previousBoardWidth, previousBoardHeight);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
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
