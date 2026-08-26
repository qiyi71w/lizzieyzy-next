package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.Stone;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardRendererMarkupPaintTest {
  @Test
  void lastFileAndLastRankOnNineteenAreOnBoard() {
    assertTrue(BoardRenderer.isMarkupOnBoard(18, 0, 19, 19));
    assertTrue(BoardRenderer.isMarkupOnBoard(0, 18, 19, 19));
    assertTrue(BoardRenderer.isMarkupOnBoard(18, 18, 19, 19));
    assertFalse(BoardRenderer.isMarkupOnBoard(19, 0, 19, 19));
    assertFalse(BoardRenderer.isMarkupOnBoard(0, 19, 19, 19));
  }

  @Test
  void remainingPointsAfterLastFileStillPaint() {
    assertEquals(List.of("sa", "dd"), BoardRenderer.remainingMarkupEntries("sa,dd", 19, 19));
    assertEquals(List.of("as", "pd"), BoardRenderer.remainingMarkupEntries("as,pd", 19, 19));
    assertEquals(List.of("ss:A", "dd:B"), BoardRenderer.remainingMarkupEntries("ss:A,dd:B", 19, 19));
  }

  @Test
  void lastFileAndLastRankOnThirteenAreOnBoard() {
    assertTrue(BoardRenderer.isMarkupOnBoard(12, 0, 13, 13));
    assertTrue(BoardRenderer.isMarkupOnBoard(0, 12, 13, 13));
    assertEquals(List.of("ma", "dd"), BoardRenderer.remainingMarkupEntries("ma,dd", 13, 13));
    assertEquals(List.of("dd"), BoardRenderer.remainingMarkupEntries("ss,dd", 13, 13));
  }

  @Test
  void emptyStarUsesHaloWhileEmptyPointStaysDarkAndBlackStoneStaysLight() {
    assertEquals(
        BoardRenderer.MarkupStroke.DARK, BoardRenderer.markupStroke(Stone.EMPTY, false));
    assertEquals(
        BoardRenderer.MarkupStroke.DARK_WITH_LIGHT_HALO,
        BoardRenderer.markupStroke(Stone.EMPTY, true));
    assertEquals(
        BoardRenderer.MarkupStroke.LIGHT, BoardRenderer.markupStroke(Stone.BLACK, false));
    assertEquals(
        BoardRenderer.MarkupStroke.LIGHT, BoardRenderer.markupStroke(Stone.BLACK, true));
    assertEquals(
        BoardRenderer.MarkupStroke.DARK, BoardRenderer.markupStroke(Stone.WHITE, false));
    assertEquals(
        BoardRenderer.MarkupStroke.DARK, BoardRenderer.markupStroke(Stone.WHITE, true));
  }

  @Test
  void starIntersectionsFollowDrawnMarkers() {
    assertTrue(BoardRenderer.isStarIntersection(3, 3, 19, 19, false));
    assertTrue(BoardRenderer.isStarIntersection(3, 9, 19, 19, false));
    assertFalse(BoardRenderer.isStarIntersection(4, 4, 19, 19, false));
    assertFalse(BoardRenderer.isStarIntersection(3, 9, 19, 19, true));
    assertTrue(BoardRenderer.isStarIntersection(3, 3, 19, 19, true));
    assertTrue(BoardRenderer.isStarIntersection(6, 6, 13, 13, false));
  }
}
