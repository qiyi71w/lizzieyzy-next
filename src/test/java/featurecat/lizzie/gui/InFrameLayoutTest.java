package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  private static InFrameLayout.Request allVisible(int width, int height, int proportion) {
    return allVisible(width, height, proportion, null);
  }

  private static InFrameLayout.Request allVisible(
      int width, int height, int proportion, Double leftoverLeftShare) {
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
        true,
        true,
        true,
        true,
        true,
        false,
        true,
        true);
  }
}
