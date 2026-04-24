package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoardEditReplayLinkedListTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void getMainMoveLinkedListBetweenSkipsStaticSnapshotRootMoveDuringRestore() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      BoardHistoryNode root = new BoardHistoryNode(staticSnapshotRoot());
      BoardHistoryNode current =
          root.add(new BoardHistoryNode(regularMoveNode(0, 0, Stone.WHITE, 51)));

      MoveLinkedList replayHead = board.getMainMoveLinkedListBetween(root, current);

      assertEquals(
          List.of("WHITE:A3"),
          replaySequence(replayHead),
          "restore replay should contain only real history moves after the snapshot root.");
    } finally {
      env.close();
    }
  }

  @Test
  void getMainMoveLinkedListBetweenKeepsPassAndSkipsSnapshots() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode snapshot =
          root.add(
              new BoardHistoryNode(
                  BoardData.snapshot(
                      stones(placement(2, 2, Stone.BLACK)),
                      Optional.of(new int[] {2, 2}),
                      Stone.BLACK,
                      true,
                      zobrist(stones(placement(2, 2, Stone.BLACK))),
                      1,
                      new int[BOARD_AREA],
                      0,
                      0,
                      50,
                      0)));
      snapshot.addExtraStones(1, 2, true);
      snapshot.addExtraStones(2, 1, false);
      BoardHistoryNode pass =
          snapshot.add(
              new BoardHistoryNode(
                  BoardData.pass(
                      stones(placement(2, 2, Stone.BLACK)),
                      Stone.WHITE,
                      false,
                      zobrist(stones(placement(2, 2, Stone.BLACK))),
                      2,
                      new int[BOARD_AREA],
                      0,
                      0,
                      50,
                      0)));
      BoardHistoryNode current =
          pass.add(
              new BoardHistoryNode(
                  regularMoveNode(
                      0,
                      0,
                      Stone.BLACK,
                      3,
                      placement(2, 2, Stone.BLACK),
                      placement(0, 0, Stone.BLACK))));

      MoveLinkedList replayHead = board.getMainMoveLinkedListBetween(root, current);

      assertEquals(
          List.of("WHITE:pass", "BLACK:A3"),
          replaySequence(replayHead),
          "restore replay should keep the real pass and skip the snapshot marker.");
    } finally {
      env.close();
    }
  }

  @Test
  void getMoveLinkedListAfterSkipsSetupExtraStonesAndKeepsRealActions() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode snapshot =
          root.add(new BoardHistoryNode(snapshotNode(Optional.of(new int[] {2, 2}), Stone.BLACK)));
      snapshot.addExtraStones(1, 2, true);
      snapshot.addExtraStones(2, 1, false);
      BoardHistoryNode pass = snapshot.add(new BoardHistoryNode(passNode(Stone.WHITE, false, 2)));
      pass.add(new BoardHistoryNode(regularMoveNode(0, 0, Stone.BLACK, 3)));

      MoveLinkedList replayHead = board.getMoveLinkedListAfter(snapshot);

      assertEquals(
          List.of("BLACK:A3", "WHITE:pass"),
          replaySequence(replayHead),
          "linked-list replay should keep only real actions after the snapshot boundary.");
    } finally {
      env.close();
    }
  }

  private static List<String> replaySequence(MoveLinkedList head) {
    MoveLinkedList tail = head;
    while (!tail.variations.isEmpty()) {
      tail = tail.variations.get(0);
    }

    List<String> moves = new ArrayList<>();
    while (tail.previous.isPresent()) {
      if (!tail.needSkip) {
        String move = tail.isPass ? "pass" : Board.convertCoordinatesToName(tail.x, tail.y);
        moves.add((tail.isBlack ? Stone.BLACK : Stone.WHITE).name() + ":" + move);
      }
      tail = tail.previous.get();
    }
    return moves;
  }

  private static BoardData staticSnapshotRoot() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(2, 2)] = Stone.BLACK;
    return snapshotNode(Optional.of(new int[] {2, 2}), Stone.BLACK);
  }

  private static BoardData snapshotNode(Optional<int[]> lastMove, Stone lastMoveColor) {
    Stone[] stones = emptyStones();
    lastMove.ifPresent(coords -> stones[Board.getIndex(coords[0], coords[1])] = lastMoveColor);
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        true,
        zobrist(stones),
        1,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData passNode(Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        stones(placement(2, 2, Stone.BLACK)),
        lastMoveColor,
        blackToPlay,
        zobrist(stones(placement(2, 2, Stone.BLACK))),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData regularMoveNode(int x, int y, Stone color, int moveNumber) {
    return regularMoveNode(
        x, y, color, moveNumber, placement(2, 2, Stone.BLACK), placement(x, y, color));
  }

  private static BoardData regularMoveNode(
      int x, int y, Stone color, int moveNumber, Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[Board.getIndex(placement.x, placement.y)] = placement.color;
    }
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        !color.isBlack(),
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

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[Board.getIndex(placement.x, placement.y)] = placement.color;
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

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone color;

    private Placement(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
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
