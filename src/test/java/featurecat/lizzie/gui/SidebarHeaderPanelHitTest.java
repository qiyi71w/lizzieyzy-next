package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.ResourceBundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SidebarHeaderPanelHitTest {
  private ResourceBundle previousBundle;

  @BeforeEach
  void useStableSimplifiedChineseLabels() {
    previousBundle = Lizzie.resourceBundle;
    Lizzie.resourceBundle = AppLocale.SIMPLIFIED_CHINESE.loadBundle();
  }

  @AfterEach
  void restoreApplicationBundle() {
    Lizzie.resourceBundle = previousBundle;
  }

  @Test
  void classicProblemTabUsesTheWholeVisibleLabel() {
    FontMetrics metrics = headerMetrics();

    assertEquals(0, SidebarHeaderPanel.primarySegmentIndexAt(new Point(18, 25), false, metrics));
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(58, 25), false, metrics),
        "clicking the first character of 问题手 should switch to the problem list.");
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(72, 25), false, metrics),
        "the old midpoint split cut through 问题手 and sent this click to 评论.");
    assertEquals(1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(90, 25), false, metrics));
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(58, 39), false, metrics),
        "the whole classic tab row should be clickable, not only the glyph pixels.");
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(102, 25), false, metrics),
        "keep a forgiving right side for 问题手 without covering the inline filters.");
  }

  @Test
  void classicSeparatorGapDoesNotSwitchTabs() {
    FontMetrics metrics = headerMetrics();

    assertEquals(-1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(45, 25), false, metrics));
  }

  @Test
  void appleSegmentedControlStillUsesFullHalves() {
    FontMetrics metrics = headerMetrics();

    assertEquals(0, SidebarHeaderPanel.primarySegmentIndexAt(new Point(20, 24), true, metrics));
    assertEquals(1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(96, 24), true, metrics));
    assertEquals(-1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(150, 24), true, metrics));
  }

  @Test
  void sideFilterHitTestingMatchesVisibleLabels() {
    FontMetrics metrics = headerMetrics();

    assertEquals(0, SidebarHeaderPanel.sideSegmentIndexAt(new Point(116, 25), false, metrics));
    assertEquals(1, SidebarHeaderPanel.sideSegmentIndexAt(new Point(160, 25), false, metrics));
    assertEquals(1, SidebarHeaderPanel.sideSegmentIndexAt(new Point(206, 25), false, metrics));
    assertEquals(0, SidebarHeaderPanel.sideSegmentIndexAt(new Point(156, 28), true, metrics));
    assertEquals(1, SidebarHeaderPanel.sideSegmentIndexAt(new Point(204, 28), true, metrics));
  }

  @Test
  void problemHeaderKeepsFiltersInlineWithoutTakingAnExtraRow() {
    assertEquals(48, SidebarHeaderPanel.preferredHeight(false, false));
    assertEquals(48, SidebarHeaderPanel.preferredHeight(true, false));
    assertEquals(56, SidebarHeaderPanel.preferredHeight(false, true));
    assertEquals(56, SidebarHeaderPanel.preferredHeight(true, true));
  }

  @Test
  void classicEnglishCommentsAndProblemsDoNotOverlap() {
    useEnglishLabels();
    FontMetrics metrics = headerMetrics();
    SidebarHeaderPanel.HeaderLayout layout =
        SidebarHeaderPanel.headerLayout(false, false, metrics, WIDE_SIDEBAR, "");

    assertFalse(
        layout.comments.intersects(layout.problems),
        "Comments and Problem moves must not share pixels.");
    assertEquals(
        0,
        SidebarHeaderPanel.primarySegmentIndexAt(center(layout.comments), false, metrics, WIDE_SIDEBAR));
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(center(layout.problems), false, metrics, WIDE_SIDEBAR));
  }

  @Test
  void classicEnglishProblemPageLabelsDoNotOverlap() {
    useEnglishLabels();
    FontMetrics metrics = headerMetrics();
    SidebarHeaderPanel.HeaderLayout layout =
        SidebarHeaderPanel.headerLayout(false, true, metrics, WIDE_SIDEBAR, "");

    assertNoOverlap(layout.comments, layout.problems, layout.black, layout.white);
    assertEquals(
        0,
        SidebarHeaderPanel.primarySegmentIndexAt(center(layout.comments), false, metrics, WIDE_SIDEBAR));
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(center(layout.problems), false, metrics, WIDE_SIDEBAR));
    assertEquals(
        0, SidebarHeaderPanel.sideSegmentIndexAt(center(layout.black), false, metrics, WIDE_SIDEBAR));
    assertEquals(
        1, SidebarHeaderPanel.sideSegmentIndexAt(center(layout.white), false, metrics, WIDE_SIDEBAR));
  }

  @Test
  void appleEnglishSegmentedControlFitsProblemMoves() {
    useEnglishLabels();
    FontMetrics metrics = headerMetrics();
    SidebarHeaderPanel.HeaderLayout layout =
        SidebarHeaderPanel.headerLayout(true, false, metrics, WIDE_SIDEBAR, "");
    int problemsWidth = metrics.stringWidth(Lizzie.resourceBundle.getString("SidebarHeader.problems"));

    assertTrue(
        layout.problems.width >= problemsWidth,
        "Apple half-width must fit Problem moves, not clip it into a 66px cell.");
    assertFalse(layout.comments.intersects(layout.problems));
    assertEquals(
        0,
        SidebarHeaderPanel.primarySegmentIndexAt(center(layout.comments), true, metrics, WIDE_SIDEBAR));
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(center(layout.problems), true, metrics, WIDE_SIDEBAR));
  }

  @Test
  void appleEnglishProblemPageLabelsDoNotOverlap() {
    useEnglishLabels();
    FontMetrics metrics = headerMetrics();
    SidebarHeaderPanel.HeaderLayout layout =
        SidebarHeaderPanel.headerLayout(true, true, metrics, WIDE_SIDEBAR, "");

    assertNoOverlap(layout.comments, layout.problems, layout.black, layout.white);
    assertEquals(
        0, SidebarHeaderPanel.sideSegmentIndexAt(center(layout.black), true, metrics, WIDE_SIDEBAR));
    assertEquals(
        1, SidebarHeaderPanel.sideSegmentIndexAt(center(layout.white), true, metrics, WIDE_SIDEBAR));
  }

  @Test
  void englishProblemPageWrapsFiltersOnlyWhenTheyDoNotFit() {
    useEnglishLabels();
    FontMetrics metrics = headerMetrics();

    SidebarHeaderPanel.HeaderLayout wide =
        SidebarHeaderPanel.headerLayout(false, true, metrics, WIDE_SIDEBAR, "");
    assertEquals(48, wide.height);
    assertFalse(wide.filtersWrapped);

    SidebarHeaderPanel.HeaderLayout narrow =
        SidebarHeaderPanel.headerLayout(false, true, metrics, NARROW_SIDEBAR, "");
    assertTrue(narrow.height > 48);
    assertTrue(narrow.filtersWrapped);
    assertTrue(narrow.black.y > narrow.comments.y);
    assertNoOverlap(narrow.comments, narrow.problems, narrow.black, narrow.white);
    assertEquals(
        0,
        SidebarHeaderPanel.sideSegmentIndexAt(
            center(narrow.black), false, metrics, NARROW_SIDEBAR));
    assertEquals(
        1,
        SidebarHeaderPanel.sideSegmentIndexAt(
            center(narrow.white), false, metrics, NARROW_SIDEBAR));
  }

  @Test
  void progressPillStaysVisibleWhenItDoesNotOverlapLabels() {
    useEnglishLabels();
    FontMetrics metrics = headerMetrics();
    String progress = SidebarHeaderPanel.progressLabelFor(snapshot(229, 229, false));
    SidebarHeaderPanel.HeaderLayout layout =
        SidebarHeaderPanel.headerLayout(false, true, metrics, WIDE_SIDEBAR, progress);

    assertFalse(layout.progress.isEmpty());
    assertFalse(layout.progress.intersects(layout.comments));
    assertFalse(layout.progress.intersects(layout.problems));
    assertFalse(layout.progress.intersects(layout.black));
    assertFalse(layout.progress.intersects(layout.white));
  }

  @Test
  void appleEnglishHitsFollowPaintedWrapWhenProgressIsVisible() {
    useEnglishLabels();
    FontMetrics metrics = headerMetrics();
    String progress = SidebarHeaderPanel.progressLabelFor(snapshot(229, 229, false));

    SidebarHeaderPanel.HeaderLayout wrapped =
        SidebarHeaderPanel.headerLayout(true, true, metrics, NARROW_SIDEBAR, progress);
    assertTrue(wrapped.filtersWrapped);
    assertTrue(wrapped.height > 56);
    assertEquals(
        0,
        SidebarHeaderPanel.sideSegmentIndexAt(
            center(wrapped.black), true, metrics, NARROW_SIDEBAR, progress));
    assertEquals(
        1,
        SidebarHeaderPanel.sideSegmentIndexAt(
            center(wrapped.white), true, metrics, NARROW_SIDEBAR, progress));
    assertEquals(
        wrapped.height,
        SidebarHeaderPanel.preferredHeight(true, true, metrics, NARROW_SIDEBAR, progress));

    SidebarHeaderPanel.HeaderLayout roomy =
        SidebarHeaderPanel.headerLayout(true, true, metrics, ROOMY_SIDEBAR, progress);
    assertFalse(roomy.filtersWrapped);
  }

  @Test
  void simplifiedChineseProblemPageStaysOneRowWithWideOrNarrowSidebar() {
    FontMetrics metrics = headerMetrics();
    assertEquals(
        48, SidebarHeaderPanel.headerLayout(false, true, metrics, NARROW_SIDEBAR, "").height);
    assertEquals(48, SidebarHeaderPanel.headerLayout(false, true, metrics, WIDE_SIDEBAR, "").height);
    assertEquals(
        56, SidebarHeaderPanel.headerLayout(true, true, metrics, WIDE_SIDEBAR, "").height);
  }

  @Test
  void progressLabelExplainsTheRightSideCounter() {
    assertEquals("", SidebarHeaderPanel.progressLabelFor(snapshot(0, 0, false)));
    assertEquals("评估中 228/229", SidebarHeaderPanel.progressLabelFor(snapshot(228, 229, true)));
    assertEquals("已评估 228/229", SidebarHeaderPanel.progressLabelFor(snapshot(228, 229, false)));
    assertEquals("评估完成", SidebarHeaderPanel.progressLabelFor(snapshot(229, 229, false)));
    assertEquals(
        "问题手评估进度：已评估 228/229。", SidebarHeaderPanel.progressTooltipFor(snapshot(228, 229, false)));
  }

  private static FontMetrics headerMetrics() {
    BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setFont(new Font("Dialog", Font.BOLD, 12));
      return graphics.getFontMetrics();
    } finally {
      graphics.dispose();
    }
  }

  private static final int WIDE_SIDEBAR = 500;
  private static final int NARROW_SIDEBAR = 200;
  private static final int ROOMY_SIDEBAR = 2000;

  private static void useEnglishLabels() {
    Lizzie.resourceBundle = AppLocale.ENGLISH.loadBundle();
  }

  private static Point center(Rectangle bounds) {
    return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  }

  private static void assertNoOverlap(Rectangle... bounds) {
    for (int i = 0; i < bounds.length; i++) {
      for (int j = i + 1; j < bounds.length; j++) {
        assertFalse(
            bounds[i].intersects(bounds[j]),
            "label rectangles intersect: " + bounds[i] + " vs " + bounds[j]);
      }
    }
  }

  private static ProblemListSnapshot snapshot(int analyzedMoves, int totalMoves, boolean running) {
    return new ProblemListSnapshot(
        ProblemListMetric.WINRATE_LOSS,
        Collections.emptyList(),
        Collections.emptyList(),
        analyzedMoves,
        totalMoves,
        running);
  }
}
