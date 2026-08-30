package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.junit.jupiter.api.Test;

class PanelBackgroundStyleTest {

  @Test
  void sidebarPanelFillUsesConfiguredCommentBackground() {
    Color configured = new Color(12, 34, 56, 123);

    Color fill = SidebarPanel.resolveCommentPanelFillColor(configured, false);

    assertEquals(configured, fill);
  }

  @Test
  void winrateGraphBackgroundIsSemiTransparentAndLiftedFromBlack() {
    Color background = WinrateGraph.resolveGraphBackgroundColor(new Color(0, 0, 0, 200), false);

    assertTrue(background.getAlpha() < 200, "winrate graph background should be translucent.");
    assertTrue(background.getRed() > 0, "winrate graph background should not stay pure black.");
    assertTrue(background.getGreen() > 0, "winrate graph background should not stay pure black.");
    assertTrue(background.getBlue() > 0, "winrate graph background should not stay pure black.");
  }

  @Test
  void winrateGridLinesAreMoreVisibleThanTheOldLowAlphaLines() {
    assertTrue(
        WinrateGraph.resolveGridLineColor().getAlpha() > 30,
        "grid lines should be clearer than the old alpha=30 dashed lines.");
  }

  @Test
  void winrateGraphFillStaysBelowCurveAndGridHierarchyOnDarkAndLightBackgrounds() {
    Color curve = new Color(100, 180, 255);
    Color grid = WinrateGraph.resolveGridLineColor();
    Color darkBackground = new Color(20, 24, 30);
    Color lightBackground = new Color(236, 232, 224);
    Color above = WinrateGraph.resolveAboveBaselineFillColor(curve);
    Color belowDark = WinrateGraph.resolveBelowBaselineFillColor(darkBackground);
    Color belowLight = WinrateGraph.resolveBelowBaselineFillColor(lightBackground);

    assertEquals(52, above.getAlpha());
    assertEquals(42, belowDark.getAlpha());
    assertEquals(42, belowLight.getAlpha());
    assertTrue(above.getAlpha() < curve.getAlpha());
    assertTrue(belowDark.getAlpha() < curve.getAlpha());
    assertTrue(belowDark.getAlpha() < grid.getAlpha());
    assertTrue(belowLight.getAlpha() < grid.getAlpha());
    assertTrue(
        luminanceContrast(belowDark, darkBackground)
            > luminanceContrast(belowDark, lightBackground));
    assertTrue(
        luminanceContrast(belowLight, lightBackground)
            > luminanceContrast(belowLight, darkBackground));
  }

  private static double luminanceContrast(Color foreground, Color background) {
    return Math.abs(relativeLuminance(foreground) - relativeLuminance(background));
  }

  private static double relativeLuminance(Color color) {
    return (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()) / 255.0;
  }
}
