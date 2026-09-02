package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class TrackingConfigMigrationTest {
  @Test
  void trackingPointAppearanceUsesTheAgreedDefaultsWhenSettingsAreAbsent() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("tracking-style"));

    config.loadTrackingPointAppearanceConfig(new JSONObject());

    assertTrue(config.showTrackingPointOutline);
    assertEquals(new Color(255, 156, 156), config.trackingPointInteriorColor);
    assertEquals(100, config.trackingPointInteriorOpacityPercent);
    assertEquals(92, config.trackingPointOutlineOpacityPercent);
    assertTrue(config.trackingPointTextAutoColor);
    assertEquals(Color.BLACK, config.trackingPointTextColor);
  }

  @Test
  void trackingPointAppearancePersistsEveryUserAdjustableValue() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("tracking-style"));
    config.uiConfig = new JSONObject();
    config.showTrackingPointOutline = false;
    config.trackingPointInteriorColor = new Color(10, 20, 30);
    config.trackingPointInteriorOpacityPercent = 37;
    config.trackingPointOutlineOpacityPercent = 64;
    config.trackingPointTextAutoColor = false;
    config.trackingPointTextColor = new Color(210, 220, 230);

    config.saveTrackingPointAppearanceConfig();

    assertFalse(config.uiConfig.getBoolean("show-tracking-point-outline"));
    assertEquals(
        "[10,20,30]", config.uiConfig.getJSONArray("tracking-point-interior-color").toString());
    assertEquals(37, config.uiConfig.getInt("tracking-point-interior-opacity"));
    assertEquals(64, config.uiConfig.getInt("tracking-point-outline-opacity"));
    assertFalse(config.uiConfig.getBoolean("tracking-point-text-auto-color"));
    assertEquals(
        "[210,220,230]", config.uiConfig.getJSONArray("tracking-point-text-color").toString());
  }

  @Test
  void trackingPointAppearanceLoadsEveryUserAdjustableValue() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("tracking-style"));
    JSONObject ui =
        new JSONObject()
            .put("show-tracking-point-outline", false)
            .put("tracking-point-interior-color", new JSONArray().put(10).put(20).put(30))
            .put("tracking-point-interior-opacity", 37)
            .put("tracking-point-outline-opacity", 64)
            .put("tracking-point-text-auto-color", false)
            .put("tracking-point-text-color", new JSONArray().put(210).put(220).put(230));

    config.loadTrackingPointAppearanceConfig(ui);

    assertFalse(config.showTrackingPointOutline);
    assertEquals(new Color(10, 20, 30), config.trackingPointInteriorColor);
    assertEquals(37, config.trackingPointInteriorOpacityPercent);
    assertEquals(64, config.trackingPointOutlineOpacityPercent);
    assertFalse(config.trackingPointTextAutoColor);
    assertEquals(new Color(210, 220, 230), config.trackingPointTextColor);
  }

  @Test
  void legacySecondProcessSettingsMigrateToSingleStreamVisitsOnly() {
    JSONObject ui =
        new JSONObject()
            .put("tracking-engine-preload", true)
            .put("tracking-engine-skip-warning", true)
            .put("tracking-engine-max-visits", 321);

    int visits = Config.migrateTrackingAnalysisConfig(ui);

    assertEquals(321, visits);
    assertEquals(321, ui.getInt("tracking-analysis-max-visits"));
    assertFalse(ui.has("tracking-engine-preload"));
    assertFalse(ui.has("tracking-engine-skip-warning"));
    assertFalse(ui.has("tracking-engine-max-visits"));
  }

  @Test
  void currentSingleStreamVisitsWinOverLegacyValue() {
    JSONObject ui =
        new JSONObject()
            .put("tracking-analysis-max-visits", 456)
            .put("tracking-engine-max-visits", 123);

    assertEquals(456, Config.migrateTrackingAnalysisConfig(ui));
    assertEquals(456, ui.getInt("tracking-analysis-max-visits"));
    assertFalse(ui.has("tracking-engine-max-visits"));
  }

  @Test
  void changingEitherTrackingParameterAtTheProductionSettingsEntryClearsTheOldContext()
      throws Exception {
    String dialog =
        Files.readString(Path.of("src/main/java/featurecat/lizzie/gui/ConfigDialog2.java"));

    assertTrue(dialog.contains("previousTrackingAnalysisMaxVisits"));
    assertTrue(dialog.contains("previousAnalyzeUpdateIntervalCentisec"));
    assertTrue(dialog.contains("Lizzie.config.analyzeUpdateIntervalCentisec"));
    assertTrue(dialog.contains("!= previousAnalyzeUpdateIntervalCentisec"));
    assertTrue(dialog.contains("Lizzie.frame.invalidateTrackingAnalysis()"));
    assertFalse(dialog.contains("Lizzie.frame.clearTrackingPoints()"));
  }

  @Test
  void modernGeneralSettingsExposeTheTrackingVisitLimit() throws Exception {
    String dialog =
        Files.readString(Path.of("src/main/java/featurecat/lizzie/gui/ConfigDialog2.java"));
    int engineSectionStart = dialog.indexOf("case MODERN_NAV_ENGINE:");
    int engineSectionEnd = dialog.indexOf("return content;", engineSectionStart);

    assertTrue(engineSectionStart >= 0);
    assertTrue(engineSectionEnd > engineSectionStart);
    assertTrue(
        dialog
            .substring(engineSectionStart, engineSectionEnd)
            .contains("txtTrackingAnalysisMaxVisits"),
        "综合设置的引擎与分析 section 应显示追踪选点计算量");
  }

  @Test
  void modernEngineSettingsExposeLiveAnalysisDurationAndVisitLimits() throws Exception {
    String dialog =
        Files.readString(Path.of("src/main/java/featurecat/lizzie/gui/ConfigDialog2.java"));
    int engineSectionStart = dialog.indexOf("case MODERN_NAV_ENGINE:");
    int engineSectionEnd = dialog.indexOf("return content;", engineSectionStart);

    assertTrue(engineSectionStart >= 0);
    assertTrue(engineSectionEnd > engineSectionStart);
    String engineSection = dialog.substring(engineSectionStart, engineSectionEnd);
    assertTrue(
        containsToggleInputRow(engineSection, "chkLimitTime", "txtMaxAnalyzeTime"),
        "综合设置的引擎与分析 section 应显示实况分析时长限制");
    assertTrue(
        containsToggleInputRow(engineSection, "chkLimitPlayouts", "txtLimitPlayouts"),
        "综合设置的引擎与分析 section 应显示实况分析计算量限制");
  }

  private static boolean containsToggleInputRow(String section, String toggle, String field) {
    int from = 0;
    while (true) {
      int call = section.indexOf("addToggleInputRow(", from);
      if (call < 0) {
        return false;
      }
      int open = section.indexOf('(', call);
      int depth = 0;
      int end = -1;
      for (int i = open; i < section.length(); i++) {
        char ch = section.charAt(i);
        if (ch == '(') {
          depth++;
        } else if (ch == ')') {
          depth--;
          if (depth == 0) {
            end = i;
            break;
          }
        }
      }
      if (end < 0) {
        return false;
      }
      String args = section.substring(open, end);
      if (args.contains(toggle) && args.contains(field)) {
        return true;
      }
      from = call + 1;
    }
  }
}
