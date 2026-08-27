package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

class LizzieFrameBottomToolbarLayoutTest {

  private static final int MAXIMIZED_WIDTH = 1920;
  private static final int MAXIMIZED_FRAME_HEIGHT = 1052;
  private static final int RESTORED_WIDTH = 1065;
  private static final int RESTORED_FRAME_HEIGHT = 700;
  private static final int INSET_TOP = 28;
  private static final int INSET_BOTTOM = 0;
  private static final int NATIVE_MENU_HEIGHT = 28;
  private static final int TOP_PANEL_HEIGHT = 32;

  @Test
  void shownContractStaysTwentySixAndHiddenStaysZero() {
    assertEquals(26, Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT);
    assertEquals(0, Menu.BOTTOM_TOOLBAR_HIDDEN_HEIGHT);
  }

  @Test
  void fallbackContentHeightReservesNativeMenuBar() {
    assertEquals(
        996,
        LizzieFrame.resolvedContentLength(
            0, MAXIMIZED_FRAME_HEIGHT, INSET_TOP, INSET_BOTTOM, NATIVE_MENU_HEIGHT));
  }

  @Test
  void laidOutContentPaneWinsOverFrameMinusInsets() {
    assertEquals(
        996,
        LizzieFrame.resolvedContentLength(996, MAXIMIZED_FRAME_HEIGHT, INSET_TOP, INSET_BOTTOM, 0));
  }

  @Test
  void parentPanelSizeIsPreferredOverALargerContentPane() {
    assertEquals(996, LizzieFrame.preferLaidOutLength(996, 1024));
    assertEquals(996, LizzieFrame.preferLaidOutLength(0, 996));
    assertEquals(0, LizzieFrame.preferLaidOutLength(0, 0));
  }

  @Test
  void nativeMenuReserveIsZeroForCustomStrip() {
    assertEquals(0, LizzieFrame.nativeMenuBarReserve(false, 28, 28));
  }

  @Test
  void nativeMenuReserveUsesRealHeightThenPreferred() {
    assertEquals(28, LizzieFrame.nativeMenuBarReserve(true, 28, 34));
    assertEquals(34, LizzieFrame.nativeMenuBarReserve(true, 0, 34));
    assertEquals(0, LizzieFrame.nativeMenuBarReserve(true, 0, 0));
  }

  @Test
  void shownToolbarFitsInsideMaximizedLinuxContentPane() {
    LizzieFrame.MainContentLayout layout =
        layoutFor(MAXIMIZED_WIDTH, maximizedContentHeight(), Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT);

    assertToolbarFullyInside(layout, maximizedContentHeight());
    assertEquals(Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT, layout.toolbar.height);
    assertEquals(maximizedContentHeight() - Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT, layout.toolbar.y);
    assertEquals(
        remainingBoardHeight(maximizedContentHeight(), Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT),
        layout.mainPanel.height);
  }

  @Test
  void hidingToolbarGivesTheBoardTheReservedBand() {
    int contentHeight = maximizedContentHeight();
    LizzieFrame.MainContentLayout shown =
        layoutFor(MAXIMIZED_WIDTH, contentHeight, Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT);
    LizzieFrame.MainContentLayout hidden =
        layoutFor(MAXIMIZED_WIDTH, contentHeight, Menu.BOTTOM_TOOLBAR_HIDDEN_HEIGHT);

    assertEquals(Menu.BOTTOM_TOOLBAR_HIDDEN_HEIGHT, hidden.toolbar.height);
    assertEquals(contentHeight, hidden.toolbar.y);
    assertEquals(
        shown.mainPanel.height + Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT, hidden.mainPanel.height);
    assertEquals(contentHeight, hidden.mainPanel.y + hidden.mainPanel.height);
    assertToolbarFullyInside(hidden, contentHeight);
  }

  @Test
  void restoredWindowUsesTheSameReserveAndRestoreMath() {
    int contentHeight =
        LizzieFrame.resolvedContentLength(
            0, RESTORED_FRAME_HEIGHT, INSET_TOP, 4, NATIVE_MENU_HEIGHT);
    LizzieFrame.MainContentLayout shown =
        layoutFor(RESTORED_WIDTH, contentHeight, Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT);
    LizzieFrame.MainContentLayout hidden =
        layoutFor(RESTORED_WIDTH, contentHeight, Menu.BOTTOM_TOOLBAR_HIDDEN_HEIGHT);

    assertEquals(640, contentHeight);
    assertToolbarFullyInside(shown, contentHeight);
    assertEquals(Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT, shown.toolbar.height);
    assertEquals(
        shown.mainPanel.height + Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT, hidden.mainPanel.height);
    assertEquals(
        contentHeight, hidden.mainPanel.y + hidden.mainPanel.height + hidden.toolbar.height);
  }

  @Test
  void customStripFallbackDoesNotReserveANativeMenuBar() {
    int contentHeight =
        LizzieFrame.resolvedContentLength(
            0, MAXIMIZED_FRAME_HEIGHT, INSET_TOP, INSET_BOTTOM, 0);
    LizzieFrame.MainContentLayout shown =
        layoutFor(MAXIMIZED_WIDTH, contentHeight, Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT);

    assertEquals(1024, contentHeight);
    assertEquals(0, LizzieFrame.nativeMenuBarReserve(false, 28, 28));
    assertToolbarFullyInside(shown, contentHeight);
    assertEquals(Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT, shown.toolbar.height);
  }

  @Test
  void oldFrameMinusInsetsMathPutsTheShownBarPastTheContentPane() {
    int oldContentHeight = MAXIMIZED_FRAME_HEIGHT - INSET_TOP - INSET_BOTTOM;
    int contentHeight = maximizedContentHeight();
    LizzieFrame.MainContentLayout oldLayout =
        LizzieFrame.layoutMainContent(
            MAXIMIZED_WIDTH, oldContentHeight, 0, TOP_PANEL_HEIGHT, true, 26, 0);

    assertTrue(
        oldLayout.toolbar.y + oldLayout.toolbar.height > contentHeight,
        "frame height minus window insets still places the bar below the content pane");
  }

  private static int maximizedContentHeight() {
    return LizzieFrame.resolvedContentLength(
        0, MAXIMIZED_FRAME_HEIGHT, INSET_TOP, INSET_BOTTOM, NATIVE_MENU_HEIGHT);
  }

  private static LizzieFrame.MainContentLayout layoutFor(
      int width, int contentHeight, int toolbarHeight) {
    return LizzieFrame.layoutMainContent(
        width, contentHeight, 0, TOP_PANEL_HEIGHT, true, toolbarHeight, 0);
  }

  private static int remainingBoardHeight(int contentHeight, int toolbarHeight) {
    return contentHeight - TOP_PANEL_HEIGHT - toolbarHeight;
  }

  private static void assertToolbarFullyInside(
      LizzieFrame.MainContentLayout layout, int contentHeight) {
    Rectangle bar = layout.toolbar;
    assertTrue(bar.y >= 0, "toolbar y is above the content pane");
    assertTrue(bar.y + bar.height <= contentHeight, "toolbar extends past the content pane");
    assertEquals(contentHeight - bar.height, bar.y);
  }
}
