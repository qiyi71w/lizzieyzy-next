package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InFrameLayoutTest {

  @Test
  void defaultNormalLandscapeMatchesCurrentFormulaIncludingCommentSwap() {
    InFrameLayout layout = InFrameLayout.layout(allVisible(1600, 900, 4));

    assertEquals(new Rectangle(348, 0, 900, 900), layout.board);
    assertEquals(new Rectangle(0, 0, 330, 900), layout.leftColumn);
    assertEquals(new Rectangle(1266, 0, 334, 900), layout.rightColumn);
    assertEquals(new Rectangle(0, 502, 330, 314), layout.comment);
    assertEquals(new Rectangle(1266, 0, 334, 283), layout.variationGraph);
    assertEquals(new Rectangle(1266, 283, 334, 283), layout.candidateTable);
    assertEquals(Optional.of(new Rectangle(348, 0, 1, 900)), layout.boardLeftDivider);
    assertEquals(Optional.of(new Rectangle(1247, 0, 1, 900)), layout.boardRightDivider);
    assertEquals(Optional.of(new Rectangle(0, 502, 330, 1)), layout.commentTopDivider);
    assertEquals(Optional.of(new Rectangle(1266, 283, 334, 1)), layout.variationListDivider);
  }

  @Test
  void maximizedLandscapeMatchesCurrentFormulaIncludingCommentSwap() {
    InFrameLayout layout = InFrameLayout.layout(allVisible(1920, 938, 4));

    assertEquals(new Rectangle(488, 0, 938, 938), layout.board);
    assertEquals(new Rectangle(0, 0, 470, 938), layout.leftColumn);
    assertEquals(new Rectangle(1444, 0, 476, 938), layout.rightColumn);
    assertEquals(new Rectangle(0, 522, 470, 319), layout.comment);
    assertEquals(new Rectangle(1444, 0, 476, 234), layout.variationGraph);
    assertEquals(new Rectangle(1444, 234, 476, 234), layout.candidateTable);
  }

  @Test
  void hidingCommentKeepsSubBoardOnTheLeftAndDropsCommentDivider() {
    InFrameLayout layout =
        InFrameLayout.layout(
            new InFrameLayout.Request(
                1600, 900, 0, 0, 0, 0, 19, 19, 4, false, true, true, true, true, false));

    assertEquals(new Rectangle(348, 0, 900, 900), layout.board);
    assertEquals(new Rectangle(0, 0, 330, 900), layout.leftColumn);
    assertEquals(new Rectangle(1266, 0, 334, 900), layout.rightColumn);
    assertEquals(new Rectangle(), layout.comment);
    assertEquals(new Rectangle(1266, 0, 334, 450), layout.variationGraph);
    assertEquals(new Rectangle(1266, 450, 334, 450), layout.candidateTable);
    assertEquals(Optional.empty(), layout.commentTopDivider);
    assertEquals(Optional.of(new Rectangle(1266, 450, 334, 1)), layout.variationListDivider);
  }

  @Test
  void largerLeftoverShareWidensLeftAndNarrowsRightWithoutChangingBoardSize() {
    InFrameLayout baseline = InFrameLayout.layout(allVisible(1600, 900, 4));
    InFrameLayout layout = InFrameLayout.layout(allVisible(1600, 900, 4, 0.6));

    assertEquals(new Rectangle(420, 0, 900, 900), layout.board);
    assertEquals(new Rectangle(0, 0, 402, 900), layout.leftColumn);
    assertEquals(new Rectangle(1338, 0, 262, 900), layout.rightColumn);
    assertEquals(baseline.board.width, layout.board.width);
    assertEquals(baseline.board.height, layout.board.height);
    assertEquals(
        baseline.leftColumn.width + baseline.rightColumn.width,
        layout.leftColumn.width + layout.rightColumn.width);
    assertEquals(Optional.of(new Rectangle(420, 0, 1, 900)), layout.boardLeftDivider);
    assertEquals(Optional.of(new Rectangle(1319, 0, 1, 900)), layout.boardRightDivider);
  }

  @Test
  void smallerLeftoverShareWidensRightAndNarrowsLeftWithoutChangingBoardSize() {
    InFrameLayout baseline = InFrameLayout.layout(allVisible(1600, 900, 4));
    InFrameLayout layout = InFrameLayout.layout(allVisible(1600, 900, 4, 0.35));

    assertEquals(new Rectangle(245, 0, 900, 900), layout.board);
    assertEquals(new Rectangle(0, 0, 227, 900), layout.leftColumn);
    assertEquals(new Rectangle(1163, 0, 437, 900), layout.rightColumn);
    assertEquals(baseline.board.width, layout.board.width);
    assertEquals(baseline.board.height, layout.board.height);
    assertEquals(
        baseline.leftColumn.width + baseline.rightColumn.width,
        layout.leftColumn.width + layout.rightColumn.width);
  }

  @Test
  void emptyColumnsOmitTheMatchingBoardDivider() {
    InFrameLayout noLeft =
        InFrameLayout.layout(
            new InFrameLayout.Request(
                1600,
                900,
                0,
                0,
                0,
                0,
                19,
                19,
                4,
                Optional.empty(),
                false,
                false,
                true,
                true,
                true,
                false,
                false,
                false));
    InFrameLayout noRight =
        InFrameLayout.layout(
            new InFrameLayout.Request(
                1600,
                900,
                0,
                0,
                0,
                0,
                19,
                19,
                4,
                Optional.empty(),
                false,
                true,
                false,
                false,
                true,
                false,
                true,
                true));

    assertEquals(Optional.empty(), noLeft.boardLeftDivider);
    assertEquals(Optional.of(new Rectangle(1247, 0, 1, 900)), noLeft.boardRightDivider);
    assertEquals(Optional.of(new Rectangle(348, 0, 1, 900)), noRight.boardLeftDivider);
    assertEquals(Optional.empty(), noRight.boardRightDivider);
  }

  @Test
  void extremeLeftoverShareIsClampedSoColumnsStayNonNegative() {
    InFrameLayout zero = InFrameLayout.layout(allVisible(1600, 900, 4, 0.0));
    InFrameLayout one = InFrameLayout.layout(allVisible(1600, 900, 4, 1.0));

    assertEquals(new Rectangle(18, 0, 900, 900), zero.board);
    assertEquals(new Rectangle(0, 0, 0, 900), zero.leftColumn);
    assertEquals(new Rectangle(936, 0, 664, 900), zero.rightColumn);
    assertTrue(zero.leftColumn.width >= 0);
    assertTrue(zero.rightColumn.width >= 0);
    assertEquals(new Rectangle(682, 0, 900, 900), one.board);
    assertEquals(new Rectangle(0, 0, 664, 900), one.leftColumn);
    assertEquals(new Rectangle(1600, 0, 0, 900), one.rightColumn);
    assertTrue(one.leftColumn.width >= 0);
    assertTrue(one.rightColumn.width >= 0);
    assertEquals(900, zero.board.width);
    assertEquals(900, one.board.height);
  }

  @Test
  void largerCommentHeightShareRaisesCommentAndShortensTheRegionAbove() {
    InFrameLayout baseline = InFrameLayout.layout(allVisible(1600, 900, 4));
    InFrameLayout layout = InFrameLayout.layout(allVisible(1600, 900, 4, null, 0.5, null));

    assertEquals(new Rectangle(0, 408, 330, 408), layout.comment);
    assertEquals(new Rectangle(0, 0, 330, 112), layout.captured);
    assertEquals(new Rectangle(0, 112, 330, 90), layout.moveStatistics);
    assertEquals(new Rectangle(0, 202, 330, 206), layout.winrateGraph);
    assertEquals(baseline.board, layout.board);
    assertEquals(baseline.rightColumn, layout.rightColumn);
    assertEquals(baseline.variationGraph, layout.variationGraph);
    assertEquals(baseline.candidateTable, layout.candidateTable);
    assertTrue(layout.comment.height > baseline.comment.height);
    assertTrue(layout.comment.y < baseline.comment.y);
    assertEquals(Optional.of(new Rectangle(0, 408, 330, 1)), layout.commentTopDivider);
  }

  @Test
  void largerVariationGraphShareChangesOnlyThoseTwoRightColumnBlocks() {
    InFrameLayout baseline = InFrameLayout.layout(allVisible(1600, 900, 4));
    InFrameLayout layout = InFrameLayout.layout(allVisible(1600, 900, 4, null, null, 0.6));

    assertEquals(new Rectangle(1266, 0, 334, 340), layout.variationGraph);
    assertEquals(new Rectangle(1266, 340, 334, 226), layout.candidateTable);
    assertEquals(baseline.board, layout.board);
    assertEquals(baseline.leftColumn, layout.leftColumn);
    assertEquals(baseline.comment, layout.comment);
    assertEquals(baseline.subBoard, layout.subBoard);
    assertEquals(
        baseline.variationGraph.height + baseline.candidateTable.height,
        layout.variationGraph.height + layout.candidateTable.height);
    assertEquals(Optional.of(new Rectangle(1266, 340, 334, 1)), layout.variationListDivider);
    assertTrue(layout.variationListDivider.get().y != layout.subBoard.y);
  }

  @Test
  void hiddenCommentOrVariationListOmitsDividerAndKeepsShareForRestore() {
    InFrameLayout hiddenComment =
        InFrameLayout.layout(
            visible(1600, 900, 4, null, 0.5, null, false, true, true, true));
    InFrameLayout restoredComment = InFrameLayout.layout(allVisible(1600, 900, 4, null, 0.5, null));
    InFrameLayout hiddenVariation =
        InFrameLayout.layout(
            visible(1600, 900, 4, null, null, 0.6, true, true, false, true));
    InFrameLayout hiddenList =
        InFrameLayout.layout(
            visible(1600, 900, 4, null, null, 0.6, true, true, true, false));
    InFrameLayout listOnlyDefault =
        InFrameLayout.layout(
            visible(1600, 900, 4, null, null, null, true, true, false, true));

    assertEquals(Optional.empty(), hiddenComment.commentTopDivider);
    assertEquals(new Rectangle(), hiddenComment.comment);
    assertEquals(new Rectangle(0, 408, 330, 408), restoredComment.comment);
    assertEquals(Optional.empty(), hiddenVariation.variationListDivider);
    assertEquals(Optional.empty(), hiddenList.variationListDivider);
    assertEquals(listOnlyDefault.candidateTable, hiddenVariation.candidateTable);
  }

  @Test
  void extremeCommentAndListSharesAreClampedToMinimums() {
    InFrameLayout baseline = InFrameLayout.layout(allVisible(1600, 900, 4));
    InFrameLayout commentMin = InFrameLayout.layout(allVisible(1600, 900, 4, null, 0.0, null));
    InFrameLayout commentMax = InFrameLayout.layout(allVisible(1600, 900, 4, null, 1.0, null));
    InFrameLayout listMin = InFrameLayout.layout(allVisible(1600, 900, 4, null, null, 1.0));

    assertEquals(new Rectangle(0, 748, 330, 68), commentMin.comment);
    assertEquals(baseline.board, commentMin.board);
    assertEquals(baseline.rightColumn, commentMin.rightColumn);
    assertEquals(new Rectangle(0, 0, 330, 816), commentMax.comment);
    assertEquals(0, commentMax.winrateGraph.height);
    assertEquals(baseline.board, commentMax.board);
    assertEquals(36, listMin.candidateTable.height);
    assertEquals(530, listMin.variationGraph.height);
    assertEquals(baseline.board, listMin.board);
    assertEquals(baseline.leftColumn, listMin.leftColumn);
    assertEquals(baseline.comment, listMin.comment);
  }

  @Test
  void restoringClearsOverridesAndUsesFirstRunBoardProportion() {
    InFrameLayout restored =
        InFrameLayout.layout(allVisible(1600, 900, 7, 0.8, 0.5, 0.6).restored());

    assertEquals(new Rectangle(348, 0, 900, 900), restored.board);
    assertEquals(new Rectangle(0, 0, 330, 900), restored.leftColumn);
    assertEquals(new Rectangle(1266, 0, 334, 900), restored.rightColumn);
    assertEquals(new Rectangle(0, 502, 330, 314), restored.comment);
    assertEquals(new Rectangle(1266, 0, 334, 283), restored.variationGraph);
    assertEquals(new Rectangle(1266, 283, 334, 283), restored.candidateTable);
    assertEquals(Optional.of(new Rectangle(348, 0, 1, 900)), restored.boardLeftDivider);
    assertEquals(Optional.of(new Rectangle(1247, 0, 1, 900)), restored.boardRightDivider);
    assertEquals(Optional.of(new Rectangle(0, 502, 330, 1)), restored.commentTopDivider);
    assertEquals(Optional.of(new Rectangle(1266, 283, 334, 1)), restored.variationListDivider);
  }

  @Test
  void restoringKeepsPanelVisibility() {
    InFrameLayout restored =
        InFrameLayout.layout(
            visible(1600, 900, 7, 0.8, 0.5, 0.6, false, true, true, true).restored());

    assertEquals(new Rectangle(348, 0, 900, 900), restored.board);
    assertEquals(new Rectangle(0, 0, 330, 900), restored.leftColumn);
    assertEquals(new Rectangle(), restored.comment);
    assertEquals(Optional.empty(), restored.commentTopDivider);
    assertEquals(new Rectangle(1266, 0, 334, 450), restored.variationGraph);
    assertEquals(new Rectangle(1266, 450, 334, 450), restored.candidateTable);
  }

  @Test
  void assigningBoardProportionSyncsExistingLeftoverShareToThatTick() {
    assertEquals(0.75, InFrameLayout.leftoverShareAfterAssignedProportion(0.41, 6));
    assertNull(InFrameLayout.leftoverShareAfterAssignedProportion(null, 6));
  }


  private static InFrameLayout.Request allVisible(int width, int height, int proportion) {
    return allVisible(width, height, proportion, null);
  }

  private static InFrameLayout.Request allVisible(
      int width, int height, int proportion, Double leftoverLeftShare) {
    return allVisible(width, height, proportion, leftoverLeftShare, null, null);
  }

  private static InFrameLayout.Request allVisible(
      int width,
      int height,
      int proportion,
      Double leftoverLeftShare,
      Double commentHeightShare,
      Double variationGraphShare) {
    return visible(
        width,
        height,
        proportion,
        leftoverLeftShare,
        commentHeightShare,
        variationGraphShare,
        true,
        true,
        true,
        true);
  }

  private static InFrameLayout.Request visible(
      int width,
      int height,
      int proportion,
      Double leftoverLeftShare,
      Double commentHeightShare,
      Double variationGraphShare,
      boolean showComment,
      boolean showSubBoard,
      boolean showVariationGraph,
      boolean showListPane) {
    return new InFrameLayout.Request(
        width,
        height,
        0,
        0,
        0,
        0,
        19,
        19,
        proportion,
        leftoverLeftShare == null ? Optional.empty() : Optional.of(leftoverLeftShare),
        commentHeightShare == null ? Optional.empty() : Optional.of(commentHeightShare),
        variationGraphShare == null ? Optional.empty() : Optional.of(variationGraphShare),
        showComment,
        showSubBoard,
        showVariationGraph,
        showListPane,
        true,
        false,
        true,
        true);
  }
}
