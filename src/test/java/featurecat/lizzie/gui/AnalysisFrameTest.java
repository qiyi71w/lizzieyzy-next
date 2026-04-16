package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.swing.event.TableModelEvent;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

public class AnalysisFrameTest {
  private static final Unsafe UNSAFE = loadUnsafe();

  @Test
  void tableSnapshotSortsBySelectedColumnAndPreservesOrderForTies() {
    MoveData first = move("D4", 120, 51.2, 45.0, 10.0, 1.0, 2);
    MoveData second = move("Q16", 80, 51.2, 30.0, 20.0, 2.0, 0);
    MoveData third = move("C3", 60, 48.1, 60.0, 30.0, 3.0, 1);

    AnalysisFrame.TableSnapshot snapshot =
        AnalysisFrame.TableSnapshot.fromMoves(
            Arrays.asList(first, second, third), 3, 9, false, false, true, 6.5);

    assertEquals(3, snapshot.getRows().size());
    assertEquals("D4", snapshot.getRows().get(0).coordinate);
    assertEquals("Q16", snapshot.getRows().get(1).coordinate);
    assertEquals("C3", snapshot.getRows().get(2).coordinate);
  }

  @Test
  void tableSnapshotComputesTotalPlayouts() {
    AnalysisFrame.TableSnapshot snapshot =
        AnalysisFrame.TableSnapshot.fromMoves(
            Arrays.asList(
                move("D4", 120, 51.2, 45.0, 10.0, 1.0, 0),
                move("Q16", 80, 50.0, 44.0, 15.0, 2.0, 1),
                move("C3", 60, 48.1, 43.0, 20.0, 3.0, 2)),
            4,
            9,
            false,
            false,
            true,
            6.5);

    assertEquals(260, snapshot.getTotalPlayouts());
  }

  @Test
  void tableSnapshotEqualityDetectsDisplayChanges() {
    List<MoveData> rows = Arrays.asList(move("D4", 120, 51.2, 45.0, 10.0, 1.0, 0));

    AnalysisFrame.TableSnapshot first =
        AnalysisFrame.TableSnapshot.fromMoves(rows, 4, 9, false, false, true, 6.5);
    AnalysisFrame.TableSnapshot same =
        AnalysisFrame.TableSnapshot.fromMoves(rows, 4, 9, false, false, true, 6.5);
    AnalysisFrame.TableSnapshot changed =
        AnalysisFrame.TableSnapshot.fromMoves(rows, 4, 9, true, false, true, 6.5);

    assertEquals(first, same);
    assertNotEquals(first, changed);
  }

  @Test
  void tableModelPrependsMissingActualMoveFromNextNode() throws Exception {
    BoardData current = boardData(null, Stone.EMPTY, true, 50.0, 0, 0.0, 0.0);
    current.bestMoves =
        Arrays.asList(
            move("D4", 120, 55.0, 54.0, 12.0, 1.5, 0), move("C3", 80, 52.0, 51.0, 10.0, 0.5, 1));

    BoardData next = boardData("Q16", Stone.BLACK, false, 61.5, 40, 2.5, 0.6);
    next.bestMoves = Arrays.asList(move("Q16", 40, 38.5, 37.0, 8.0, -2.5, 0));

    try (TestEnvironment environment =
        installEnvironment(historyWithCurrentAndNext(current, next))) {
      AnalysisFrame.AnalysisTableModel model = newTableModel(1);
      AnalysisFrame.RowSnapshot actualMove = snapshotOf(model).getRows().get(0);

      assertEquals(3, snapshotOf(model).getRows().size());
      assertEquals("Q16", actualMove.coordinate);
      assertEquals(0, actualMove.playouts);
      assertEquals(-100, actualMove.order);
      assertTrue(actualMove.isNextMove);
      assertEquals(-10000.0, actualMove.lcb);
      assertEquals(-10000.0, actualMove.policy);
      assertEquals(38.5, actualMove.winrate);
      assertEquals(-2.5, actualMove.scoreMean);
      assertEquals(55.0, actualMove.bestWinrate);
      assertEquals(1.5, actualMove.bestScoreMean);
    }
  }

  @Test
  void tableModelPrependsUpgradedActualMoveWhenNextNodeHasMorePlayouts() throws Exception {
    BoardData current = boardData(null, Stone.EMPTY, true, 50.0, 0, 0.0, 0.0);
    current.bestMoves =
        Arrays.asList(
            move("D4", 120, 55.0, 54.0, 12.0, 1.5, 0), move("Q16", 80, 48.0, 47.0, 9.0, 0.5, 2));

    BoardData next = boardData("Q16", Stone.BLACK, false, 63.0, 240, 1.8, 0.7);
    next.bestMoves = Arrays.asList(move("Q16", 200, 37.0, 36.5, 9.0, -1.8, 0));

    try (TestEnvironment environment =
        installEnvironment(historyWithCurrentAndNext(current, next))) {
      AnalysisFrame.AnalysisTableModel model = newTableModel(1);
      AnalysisFrame.RowSnapshot actualMove = snapshotOf(model).getRows().get(0);

      assertEquals(3, snapshotOf(model).getRows().size());
      assertEquals("Q16", actualMove.coordinate);
      assertEquals(240, actualMove.playouts);
      assertEquals(2, actualMove.order);
      assertTrue(actualMove.isNextMove);
      assertEquals(-10000.0, actualMove.lcb);
      assertEquals(9.0, actualMove.policy);
      assertEquals(37.0, actualMove.winrate);
      assertEquals(-1.8, actualMove.scoreMean);
      assertEquals(0.7, actualMove.scoreStdev);
      assertEquals(55.0, actualMove.bestWinrate);
      assertEquals(1.5, actualMove.bestScoreMean);
    }
  }

  @Test
  void refreshSnapshotSignalsStructureChangeWhenColumnCountSwitches() throws Exception {
    BoardData current = boardData(null, Stone.EMPTY, true, 50.0, 0, 0.0, 0.0);
    current.bestMoves = Arrays.asList(move("D4", 120, 55.0, 54.0, 12.0, 1.5, 0));
    BoardHistoryList history = historyWithCurrent(current);

    try (TestEnvironment environment = installEnvironment(history)) {
      AnalysisFrame.AnalysisTableModel model = newTableModel(1);
      List<TableModelEvent> events = new ArrayList<TableModelEvent>();

      model.addTableModelListener(events::add);
      assertEquals(7, model.getColumnCount());

      history.getStart().getData().isKataData = true;

      AnalysisFrame.AnalysisTableModel.RefreshResult result = model.refreshSnapshot();

      assertEquals(AnalysisFrame.AnalysisTableModel.RefreshResult.STRUCTURE_CHANGED, result);
      assertEquals(9, model.getColumnCount());
      assertTrue(
          events.stream().anyMatch(event -> event.getFirstRow() == TableModelEvent.HEADER_ROW));
    }
  }

  private static MoveData move(
      String coordinate,
      int playouts,
      double winrate,
      double lcb,
      double policy,
      double scoreMean,
      int order) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.playouts = playouts;
    move.winrate = winrate;
    move.lcb = lcb;
    move.policy = policy;
    move.scoreMean = scoreMean;
    move.order = order;
    move.scoreStdev = order;
    return move;
  }

  private static BoardData boardData(
      String coordinate,
      Stone lastMoveColor,
      boolean blackToPlay,
      double winrate,
      int playouts,
      double scoreMean,
      double scoreStdev) {
    BoardData data = BoardData.empty(19, 19);
    data.lastMove =
        coordinate == null
            ? Optional.empty()
            : Optional.of(Board.convertNameToCoordinates(coordinate));
    data.lastMoveColor = lastMoveColor;
    data.blackToPlay = blackToPlay;
    data.winrate = winrate;
    data.scoreMean = scoreMean;
    data.scoreStdev = scoreStdev;
    data.setPlayouts(playouts);
    return data;
  }

  private static BoardHistoryList historyWithCurrentAndNext(BoardData current, BoardData next) {
    BoardHistoryList history = historyWithCurrent(current);
    history.add(next);
    history.previous();
    return history;
  }

  private static BoardHistoryList historyWithCurrent(BoardData current) {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(19, 19));
    history.add(current);
    return history;
  }

  private static AnalysisFrame.AnalysisTableModel newTableModel(int index) throws Exception {
    AnalysisFrame frame = (AnalysisFrame) UNSAFE.allocateInstance(AnalysisFrame.class);
    frame.index = index;
    frame.sortnum = -1;
    return frame.new AnalysisTableModel();
  }

  private static AnalysisFrame.TableSnapshot snapshotOf(AnalysisFrame.AnalysisTableModel model)
      throws Exception {
    Field snapshotField = AnalysisFrame.AnalysisTableModel.class.getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    return (AnalysisFrame.TableSnapshot) snapshotField.get(model);
  }

  private static TestEnvironment installEnvironment(BoardHistoryList history) throws Exception {
    Config config = (Config) UNSAFE.allocateInstance(Config.class);
    config.anaFrameShowNext = true;
    config.showPreviousBestmovesInEngineGame = false;
    config.showKataGoScoreLeadWithKomi = false;

    Board board = (Board) UNSAFE.allocateInstance(Board.class);
    Field historyField = Board.class.getDeclaredField("history");
    historyField.setAccessible(true);
    historyField.set(board, history);
    LizzieFrame frame = (LizzieFrame) UNSAFE.allocateInstance(LizzieFrame.class);
    return new TestEnvironment(config, board, frame);
  }

  @SuppressWarnings("restriction")
  private static Unsafe loadUnsafe() {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return (Unsafe) field.get(null);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final boolean previousEngineGame;

    private TestEnvironment(Config config, Board board, LizzieFrame frame) {
      previousConfig = Lizzie.config;
      previousBoard = Lizzie.board;
      previousFrame = Lizzie.frame;
      previousLeelaz = Lizzie.leelaz;
      previousLeelaz2 = Lizzie.leelaz2;
      previousEngineGame = EngineManager.isEngineGame;
      Lizzie.config = config;
      Lizzie.board = board;
      Lizzie.frame = frame;
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      EngineManager.isEngineGame = false;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
      EngineManager.isEngineGame = previousEngineGame;
    }
  }
}
