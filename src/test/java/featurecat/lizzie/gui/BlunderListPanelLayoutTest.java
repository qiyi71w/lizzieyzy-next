package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlunderListPanelLayoutTest {
  private ResourceBundle previousBundle;

  @BeforeEach
  void useSimplifiedChineseEmptyStateCopy() {
    previousBundle = Lizzie.resourceBundle;
    Lizzie.resourceBundle = AppLocale.SIMPLIFIED_CHINESE.loadBundle();
  }

  @AfterEach
  void restoreApplicationBundle() {
    Lizzie.resourceBundle = previousBundle;
  }

  @Test
  void problemListTracksViewportWidthInsteadOfStaleComponentWidth() {
    BlunderListPanel panel = new BlunderListPanel();
    JScrollPane scrollPane = new JScrollPane(panel);
    scrollPane.setSize(240, 320);
    scrollPane.getViewport().setSize(new Dimension(240, 320));
    panel.setSize(900, 50);

    assertTrue(panel.getScrollableTracksViewportWidth());
    assertEquals(240, panel.getPreferredSize().width);
    assertEquals(240, panel.getPreferredScrollableViewportSize().width);
  }

  @Test
  void shortProblemListFillsViewportAndLongProblemListScrollsVertically() {
    BlunderListPanel panel = new BlunderListPanel();
    JScrollPane scrollPane = new JScrollPane(panel);
    scrollPane.setSize(240, 320);
    scrollPane.getViewport().setSize(new Dimension(240, 320));

    assertTrue(panel.getScrollableTracksViewportHeight());

    panel.updateSnapshot(snapshotWithBlackRows(10));

    assertFalse(panel.getScrollableTracksViewportHeight());
    assertEquals(670, panel.getPreferredSize().height);
  }

  @Test
  void scrollIncrementsMatchProblemCardRhythm() {
    BlunderListPanel panel = new BlunderListPanel();
    Rectangle visible = new Rectangle(0, 0, 240, 320);

    assertEquals(32, panel.getScrollableUnitIncrement(visible, SwingConstants.VERTICAL, 1));
    assertEquals(256, panel.getScrollableBlockIncrement(visible, SwingConstants.VERTICAL, 1));
  }

  @Test
  void emptyStateSubtitleStaysInsideCardAfterWrap() {
    assertEmptyStateFitsInsideCard(100, 86, false);
    assertEmptyStateFitsInsideCard(120, 86, false);
    assertEmptyStateFitsInsideCard(120, 200, false);
    assertEmptyStateFitsInsideCard(160, 280, false);
    assertEmptyStateFitsInsideCard(244, 320, false);
    assertEmptyStateFitsInsideCard(400, 320, false);
    assertEmptyStateFitsInsideCard(120, 86, true);
    assertEmptyStateFitsInsideCard(160, 280, true);
    assertEmptyStateFitsInsideCard(244, 320, true);
  }

  @Test
  void emptyStateFitsNarrowViewportWithoutScrolling() {
    BlunderListPanel panel = new BlunderListPanel();
    JScrollPane scrollPane = new JScrollPane(panel);
    scrollPane.setSize(120, 86);
    scrollPane.getViewport().setSize(new Dimension(120, 86));
    panel.updateSnapshot(emptySnapshot(false));

    assertTrue(
        panel.getScrollableTracksViewportHeight(),
        "empty state must fill the viewport instead of introducing an inner scrollbar");

    BufferedImage image = new BufferedImage(120, 86, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      BlunderListPanel.EmptyStateLayout layout =
          BlunderListPanel.fitEmptyState(120, 86, Font.SANS_SERIF, false, graphics);
      assertTrue(
          layout.fitsInPanel(120, 86),
          "wrapped empty-state text must stay inside the visible card without scrolling");
      panel.setSize(120, 86);
      panel.paint(graphics);
    } finally {
      graphics.dispose();
    }
  }

  private static void assertEmptyStateFitsInsideCard(
      int panelWidth, int panelHeight, boolean analysisRunning) {
    BlunderListPanel panel = new BlunderListPanel();
    panel.updateSnapshot(emptySnapshot(analysisRunning));
    panel.setSize(panelWidth, panelHeight);

    BufferedImage image = new BufferedImage(panelWidth, panelHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      BlunderListPanel.EmptyStateLayout layout =
          BlunderListPanel.fitEmptyState(
              panelWidth, panelHeight, Font.SANS_SERIF, analysisRunning, graphics);

      String expectedTitle =
          analysisRunning
              ? BlunderListPanel.EMPTY_STATE_ANALYZING_TITLE
              : BlunderListPanel.EMPTY_STATE_TITLE;
      assertEquals(expectedTitle, joinedText(layout.titleLines));
      assertEquals(BlunderListPanel.EMPTY_STATE_SUBTITLE, joinedText(layout.subtitleLines));
      assertTrue(layout.boxW <= BlunderListPanel.EMPTY_STATE_MAX_BOX_WIDTH);
      assertTrue(
          layout.fitsInPanel(panelWidth, panelHeight),
          "empty-state card and wrapped text must stay inside the panel");

      int innerWidth = Math.max(1, layout.boxW - layout.textInset * 2);
      int fullSubtitleWidth =
          graphics
              .getFontMetrics(layout.subtitleFont)
              .stringWidth(BlunderListPanel.EMPTY_STATE_SUBTITLE);
      if (fullSubtitleWidth > innerWidth) {
        assertTrue(
            layout.subtitleLines.size() > 1,
            "subtitle must wrap when it is wider than the card inner width");
        if (panelHeight > BlunderListPanel.EMPTY_STATE_MIN_BOX_HEIGHT + 40) {
          assertTrue(
              layout.boxH > BlunderListPanel.EMPTY_STATE_MIN_BOX_HEIGHT
                  || layout.boxY + layout.boxH <= panelHeight,
              "card height must grow after wrap when the viewport has room");
        }
      }

      for (BlunderListPanel.EmptyStateLine line : layout.titleLines) {
        assertLineInsideCard(layout, line, innerWidth);
      }
      for (BlunderListPanel.EmptyStateLine line : layout.subtitleLines) {
        assertLineInsideCard(layout, line, innerWidth);
      }

      panel.paint(graphics);
    } finally {
      graphics.dispose();
    }
  }

  private static void assertLineInsideCard(
      BlunderListPanel.EmptyStateLayout layout,
      BlunderListPanel.EmptyStateLine line,
      int innerWidth) {
    assertTrue(line.x >= layout.boxX, line.text);
    assertTrue(line.x + line.width <= layout.boxX + layout.boxW, line.text);
    assertTrue(line.baselineY - line.ascent >= layout.boxY, line.text);
    assertTrue(line.baselineY + line.descent <= layout.boxY + layout.boxH, line.text);
    if (line.text.codePointCount(0, line.text.length()) > 1) {
      assertTrue(line.width <= innerWidth, line.text + " width=" + line.width);
    }
  }

  private static String joinedText(List<BlunderListPanel.EmptyStateLine> lines) {
    StringBuilder text = new StringBuilder();
    for (BlunderListPanel.EmptyStateLine line : lines) {
      text.append(line.text);
    }
    return text.toString();
  }

  private static ProblemListSnapshot emptySnapshot(boolean analysisRunning) {
    return new ProblemListSnapshot(
        ProblemListMetric.WINRATE_LOSS,
        Collections.emptyList(),
        Collections.emptyList(),
        0,
        0,
        analysisRunning);
  }

  private static ProblemListSnapshot snapshotWithBlackRows(int rows) {
    List<ProblemMoveEntry> entries = new ArrayList<>();
    for (int i = 0; i < rows; i++) {
      entries.add(new ProblemMoveEntry(true, i + 1, "D4", 5.0 + i, 0.0, false, 1200, false, 3));
    }
    return new ProblemListSnapshot(
        ProblemListMetric.WINRATE_LOSS, entries, Collections.emptyList(), rows, rows, false);
  }
}
