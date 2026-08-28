package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

  private static InFrameLayout.Request allVisible(int width, int height, int proportion) {
    return new InFrameLayout.Request(
        width, height, 0, 0, 0, 0, 19, 19, proportion, true, true, true, true, true, false);
  }
}
