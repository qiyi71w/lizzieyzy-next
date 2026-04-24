package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoardHistoryListSyncSnapshotTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void syncUpdatesSnapshotBoardWhenMetadataMatchesButStonesDiffer() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList current = new BoardHistoryList(snapshotData(currentStones()));
      BoardHistoryList incoming = new BoardHistoryList(snapshotData(incomingStones()));

      int diffMoveNo = current.sync(incoming);

      assertEquals(7, diffMoveNo, "stone-only snapshot changes should still be detected.");
      assertArrayEquals(
          incoming.getStones(),
          current.getStones(),
          "sync should replace the current snapshot board when stones changed.");
    }
  }

  @Test
  void syncUpdatesSetupMetadataWhenSnapshotStonesMatch() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList current =
          new BoardHistoryList(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]"));
      BoardHistoryList incoming =
          new BoardHistoryList(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]AE[cc]LB[aa:X]"));

      current.getCurrentHistoryNode().addExtraStones(0, 0, true);
      incoming.getCurrentHistoryNode().addExtraStones(2, 2, false);
      incoming.getCurrentHistoryNode().setRemovedStone();

      int diffMoveNo = current.sync(incoming);
      BoardHistoryNode synced = current.getCurrentHistoryNode();
      String exportedNode = generateNode(synced);

      assertEquals(7, diffMoveNo, "setup metadata changes should mark the snapshot as different.");
      assertEquals(
          "AB[aa]AW[ba]AE[cc]LB[aa:X]",
          synced.getData().propertiesString(),
          "sync should replace the setup property map.");
      assertTrue(synced.hasRemovedStone(), "sync should keep the removed-stone marker.");
      assertEquals(1, synced.extraStones.size(), "sync should replace extra-stone metadata.");
      assertEquals(2, synced.extraStones.get(0).x);
      assertEquals(2, synced.extraStones.get(0).y);
      assertTrue(exportedNode.contains("AE[cc]"), "synced SGF export should keep removed stones.");
      assertTrue(exportedNode.contains("LB[aa:X]"), "synced SGF export should keep markup.");
    }
  }

  @Test
  void syncCloneKeepsSetupMetadataWhenAppendingSnapshotChild() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList current = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryList incoming = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      incoming.add(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]AE[cc]LB[ba:Y]"));
      incoming.getCurrentHistoryNode().addExtraStones(1, 0, false);
      incoming.getCurrentHistoryNode().setRemovedStone();

      current.sync(incoming);

      BoardHistoryNode cloned = current.getStart().next().orElseThrow();
      String exportedNode = generateNode(cloned);

      assertEquals(
          "AB[aa]AW[ba]AE[cc]LB[ba:Y]",
          cloned.getData().propertiesString(),
          "cloned snapshot child should keep setup properties.");
      assertTrue(
          cloned.hasRemovedStone(), "cloned snapshot child should keep removed-stone state.");
      assertNotNull(cloned.extraStones, "cloned snapshot child should keep extra stones.");
      assertEquals(1, cloned.extraStones.size(), "cloned snapshot child should keep extra stones.");
      assertTrue(exportedNode.contains("AE[cc]"), "cloned SGF export should keep removed stones.");
      assertTrue(exportedNode.contains("LB[ba:Y]"), "cloned SGF export should keep markup.");

      BoardHistoryList roundTrip = SGFParser.parseSgf("(;SZ[3]" + exportedNode + ")", false);
      BoardHistoryNode roundTripSnapshot = roundTrip.getStart().next().orElseThrow();
      assertEquals(
          "cc",
          roundTripSnapshot.getData().getProperty("AE"),
          "round-trip cloned snapshot export should keep removed-stone property map.");
    }
  }

  @Test
  void syncUpdatesVariationSnapshotMetadataWhenMainTrunkAndChildCountMatch() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList current = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode currentRoot = current.getCurrentHistoryNode();
      currentRoot.addOrGoto(moveData(0, 0, Stone.BLACK, false, 1), false);
      BoardHistoryNode currentVariation =
          currentRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]"), false);
      currentVariation.addExtraStones(0, 0, true);

      BoardHistoryList incoming = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode incomingRoot = incoming.getCurrentHistoryNode();
      incomingRoot.addOrGoto(moveData(0, 0, Stone.BLACK, false, 1), false);
      BoardHistoryNode incomingVariation =
          incomingRoot.addOrGoto(
              snapshotData(sharedSetupStones(), "AB[aa]AW[ba]AE[cc]LB[aa:X]"), false);
      incomingVariation.addExtraStones(2, 2, false);
      incomingVariation.setRemovedStone();

      current.sync(incoming);

      BoardHistoryNode syncedVariation = current.getStart().getVariation(1).orElseThrow();
      String exportedNode = generateNode(syncedVariation);

      assertTrue(syncedVariation.getData().isSnapshotNode(), "fixture branch should be SNAPSHOT.");
      assertEquals(
          "AB[aa]AW[ba]AE[cc]LB[aa:X]",
          syncedVariation.getData().propertiesString(),
          "variation snapshot sync should keep setup property updates.");
      assertTrue(
          syncedVariation.hasRemovedStone(),
          "variation snapshot sync should keep removed-stone metadata.");
      assertNotNull(
          syncedVariation.extraStones, "variation snapshot sync should keep extra-stone metadata.");
      assertEquals(
          1, syncedVariation.extraStones.size(), "variation snapshot sync should replace extras.");
      assertEquals(2, syncedVariation.extraStones.get(0).x);
      assertEquals(2, syncedVariation.extraStones.get(0).y);
      assertTrue(
          exportedNode.contains("AE[cc]"),
          "variation snapshot SGF export should keep AE metadata.");
      assertTrue(
          exportedNode.contains("LB[aa:X]"),
          "variation snapshot SGF export should keep markup metadata.");
    }
  }

  @Test
  void syncKeepsSnapshotSiblingVariationsDistinctWhenOnlySetupMetadataDiffers() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList source = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode sourceRoot = source.getCurrentHistoryNode();
      sourceRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]LB[aa:A]"), false);
      sourceRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]LB[aa:B]"), false);

      assertEquals(
          2,
          sourceRoot.numberOfChildren(),
          "source tree should keep both snapshot siblings with different setup metadata.");

      BoardHistoryList current = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      current.sync(source);

      BoardHistoryNode syncedRoot = current.getStart();
      assertEquals(
          2,
          syncedRoot.numberOfChildren(),
          "synced variation count should match source snapshot siblings.");
      assertEquals(
          "AB[aa]AW[ba]LB[aa:A]",
          syncedRoot.getVariation(0).orElseThrow().getData().propertiesString());
      assertEquals(
          "AB[aa]AW[ba]LB[aa:B]",
          syncedRoot.getVariation(1).orElseThrow().getData().propertiesString());
    }
  }

  @Test
  void syncKeepsSnapshotSiblingVariationsDistinctWhenOnlyRemovedStoneDiffers() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList source = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode sourceRoot = source.getCurrentHistoryNode();
      BoardHistoryNode removedSnapshot =
          sourceRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]"), false);
      removedSnapshot.setRemovedStone();
      sourceRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]"), false);

      assertEquals(
          2,
          sourceRoot.numberOfChildren(),
          "source tree should keep both snapshot siblings when removed-stone metadata differs.");

      BoardHistoryList current = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      current.sync(source);

      BoardHistoryNode syncedRoot = current.getStart();
      assertEquals(
          2,
          syncedRoot.numberOfChildren(),
          "synced tree should keep both snapshot siblings when removed-stone metadata differs.");
      assertTrue(
          syncedRoot.getVariation(0).orElseThrow().hasRemovedStone(),
          "first synced snapshot should keep removed-stone metadata.");
      assertFalse(
          syncedRoot.getVariation(1).orElseThrow().hasRemovedStone(),
          "second synced snapshot should keep non-removed metadata.");
    }
  }

  @Test
  void syncKeepsSnapshotSiblingVariationsDistinctWhenOnlyExtraStonesDiffer() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList source = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode sourceRoot = source.getCurrentHistoryNode();
      BoardHistoryNode withExtraStones =
          sourceRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]"), false);
      withExtraStones.addExtraStones(2, 2, false);
      sourceRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]"), false);

      assertEquals(
          2,
          sourceRoot.numberOfChildren(),
          "source tree should keep both snapshot siblings when extra-stone metadata differs.");

      BoardHistoryList current = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      current.sync(source);

      BoardHistoryNode syncedRoot = current.getStart();
      BoardHistoryNode syncedWithExtra = syncedRoot.getVariation(0).orElseThrow();
      BoardHistoryNode syncedWithoutExtra = syncedRoot.getVariation(1).orElseThrow();

      assertEquals(
          2,
          syncedRoot.numberOfChildren(),
          "synced tree should keep both snapshot siblings when extra-stone metadata differs.");
      assertNotNull(
          syncedWithExtra.extraStones,
          "snapshot with extra-stone metadata should keep its extra-stone list.");
      assertEquals(
          1,
          syncedWithExtra.extraStones.size(),
          "snapshot with extra-stone metadata should persist.");
      assertTrue(
          syncedWithoutExtra.extraStones == null || syncedWithoutExtra.extraStones.isEmpty(),
          "snapshot without extra-stone metadata should stay empty.");
    }
  }

  @Test
  void syncKeepsSourceSnapshotCommentWhenSourceHasPlayouts() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList current =
          new BoardHistoryList(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]", 1));
      BoardHistoryList source =
          new BoardHistoryList(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]", 12));

      current.getCurrentHistoryNode().getData().comment = "local-comment";
      source.getCurrentHistoryNode().getData().comment = "source-custom-comment";

      current.sync(source);

      assertEquals(
          "source-custom-comment",
          current.getCurrentHistoryNode().getData().comment,
          "snapshot comment ownership should follow source even when playouts > 0.");
    }
  }

  @Test
  void syncStaysStableAcrossRepeatedRunsAfterSnapshotMetadataReplacement() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList current = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode currentRoot = current.getCurrentHistoryNode();
      BoardHistoryNode currentMain =
          currentRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]LB[aa:old]"), false);
      currentMain.getData().comment = "local-main-comment";
      currentRoot.addOrGoto(snapshotData(currentStones(), "AB[aa]AW[ba]LB[ba:stale]"), false);

      BoardHistoryList source = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode sourceRoot = source.getCurrentHistoryNode();
      BoardHistoryNode sourceMain =
          sourceRoot.addOrGoto(
              snapshotData(sharedSetupStones(), "AB[aa]AW[ba]AE[cc]LB[aa:new]", 24), false);
      sourceMain.setRemovedStone();
      sourceMain.addExtraStones(1, 2, false);
      sourceMain.getData().comment = "source-stable-comment";

      int firstDiffMoveNo = current.sync(source);
      int secondDiffMoveNo = current.sync(source);

      BoardHistoryNode syncedRoot = current.getStart();
      BoardHistoryNode syncedMain = syncedRoot.getVariation(0).orElseThrow();

      assertTrue(firstDiffMoveNo >= 0, "first sync should detect source replacement.");
      assertEquals(-1, secondDiffMoveNo, "second sync should be stable and report no drift.");
      assertEquals(1, syncedRoot.numberOfChildren(), "stale local variations should be removed.");
      assertEquals(
          "source-stable-comment",
          syncedMain.getData().comment,
          "stable snapshot comment should stay source-owned after repeated sync.");
      assertTrue(
          syncedMain.hasRemovedStone(),
          "stable snapshot metadata should keep removed-stone ownership from source.");
      assertNotNull(syncedMain.extraStones, "stable snapshot metadata should keep extra stones.");
      assertEquals(1, syncedMain.extraStones.size());
      assertEquals(1, syncedMain.extraStones.get(0).x);
      assertEquals(2, syncedMain.extraStones.get(0).y);
    }
  }

  @Test
  void syncRemovesDeletedSnapshotVariationAndKeepsMainChildMetadata() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList current = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode currentRoot = current.getCurrentHistoryNode();
      BoardHistoryNode currentMain =
          currentRoot.addOrGoto(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]LB[aa:old]"), false);
      currentMain.addExtraStones(0, 0, true);

      BoardData staleVariationData = snapshotData(currentStones(), "AB[aa]AW[ba]LB[ba:stale]");
      staleVariationData.comment = "stale-variation";
      BoardHistoryNode staleVariation = currentRoot.addOrGoto(staleVariationData, false);
      staleVariation.addExtraStones(2, 2, false);
      staleVariation.setRemovedStone();

      BoardHistoryList incoming = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode incomingRoot = incoming.getCurrentHistoryNode();
      BoardHistoryNode incomingMain =
          incomingRoot.addOrGoto(
              snapshotData(sharedSetupStones(), "AB[aa]AW[ba]AE[cc]LB[aa:new]"), false);
      incomingMain.addExtraStones(1, 2, false);
      incomingMain.setRemovedStone();

      current.sync(incoming);

      BoardHistoryNode syncedRoot = current.getStart();
      BoardHistoryNode syncedMain = syncedRoot.getVariation(0).orElseThrow();

      assertEquals(
          1,
          syncedRoot.numberOfChildren(),
          "sync should prune local variations that were removed from source.");
      assertFalse(
          syncedRoot.getVariation(1).isPresent(),
          "source-deleted snapshot variation should not remain locally.");
      assertEquals(
          "AB[aa]AW[ba]AE[cc]LB[aa:new]",
          syncedMain.getData().propertiesString(),
          "remaining main child metadata should match source.");
      assertTrue(
          syncedMain.hasRemovedStone(),
          "remaining main child should keep removed-stone metadata from source.");
      assertNotNull(syncedMain.extraStones, "remaining main child should keep extra stones.");
      assertEquals(1, syncedMain.extraStones.size());
      assertEquals(1, syncedMain.extraStones.get(0).x);
      assertEquals(2, syncedMain.extraStones.get(0).y);
    }
  }

  @Test
  void compareDetectsSnapshotSetupMetadataDifferences() {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode current =
          new BoardHistoryNode(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]"));
      current.addExtraStones(0, 0, true);

      BoardHistoryNode incoming =
          new BoardHistoryNode(snapshotData(sharedSetupStones(), "AB[aa]AW[ba]AE[cc]LB[aa:X]"));
      incoming.addExtraStones(2, 2, false);
      incoming.setRemovedStone();

      assertFalse(
          current.compare(incoming),
          "compare should treat snapshot setup metadata as part of node identity.");
    }
  }

  private static BoardData snapshotData(Stone[] stones) {
    return snapshotData(stones, "");
  }

  private static BoardData snapshotData(Stone[] stones, String properties) {
    return snapshotData(stones, properties, 0);
  }

  private static BoardData snapshotData(Stone[] stones, String properties, int playouts) {
    BoardData data =
        BoardData.snapshot(
            stones,
            Optional.of(new int[] {2, 2}),
            Stone.BLACK,
            false,
            zobrist(stones),
            7,
            new int[BOARD_AREA],
            0,
            0,
            50,
            playouts);
    data.comment = "same-marker";
    data.addProperties(properties);
    return data;
  }

  private static BoardData moveData(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] sharedSetupStones() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    return stones;
  }

  private static Stone[] currentStones() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.WHITE;
    stones[Board.getIndex(2, 2)] = Stone.BLACK;
    return stones;
  }

  private static Stone[] incomingStones() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    stones[Board.getIndex(2, 2)] = Stone.BLACK;
    return stones;
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

  private static String generateNode(BoardHistoryNode node)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method method =
        SGFParser.class.getDeclaredMethod(
            "generateNode",
            Board.class,
            BoardHistoryNode.class,
            boolean.class,
            StringBuilder.class);
    method.setAccessible(true);
    StringBuilder builder = new StringBuilder();
    Object result = method.invoke(null, null, node, false, builder);
    return result.toString();
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;
    private final Config previousConfig;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz,
        Config previousConfig) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
      this.previousConfig = previousConfig;
    }

    private static TestEnvironment open() {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Config previousConfig = Lizzie.config;
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Lizzie.board = allocate(TrackingBoard.class);
      Lizzie.frame = allocate(TrackingFrame.class);
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      Lizzie.config = minimalConfig();
      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousBoard,
          previousFrame,
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
      Lizzie.leelaz = previousLeelaz;
      Lizzie.config = previousConfig;
    }
  }

  private static Config minimalConfig() {
    Config config = allocate(Config.class);
    config.appendWinrateToComment = false;
    config.showComment = true;
    return config;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) {
    try {
      return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
    } catch (InstantiationException ex) {
      throw new IllegalStateException("Failed to allocate " + type.getSimpleName(), ex);
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

  private static final class TrackingFrame extends LizzieFrame {
    @Override
    public void tryToResetByoTime() {}
  }

  private static final class TrackingBoard extends Board {
    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingLeelaz extends Leelaz {
    private TrackingLeelaz() throws Exception {
      super("");
    }

    @Override
    public void clearBestMoves() {}

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {}
  }
}
