package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import featurecat.lizzie.AppLocale;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WinrateGraphLegendCopyTest {
  private static final Pattern HAN_CHARACTER = Pattern.compile("[\\p{IsHan}]");

  @Test
  void englishWinrateAndScoreLeadLegendDoesNotUseHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    List<String> labels = WinrateGraph.lineLegendLabels(true, english);

    assertEquals(List.of("Winrate", "Score lead"), labels);
    labels.forEach(WinrateGraphLegendCopyTest::assertNoHan);
  }

  @Test
  void englishWinrateOnlyLegendDoesNotUseHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    List<String> labels = WinrateGraph.lineLegendLabels(false, english);

    assertEquals(List.of("Winrate"), labels);
    labels.forEach(WinrateGraphLegendCopyTest::assertNoHan);
  }

  @Test
  void simplifiedChineseKeepsCurrentWinrateAndScoreLeadLiterals() {
    ResourceBundle chinese = AppLocale.SIMPLIFIED_CHINESE.loadBundle();

    assertEquals(List.of("胜率", "目差"), WinrateGraph.lineLegendLabels(true, chinese));
    assertEquals(List.of("胜率"), WinrateGraph.lineLegendLabels(false, chinese));
  }

  private static void assertNoHan(String value) {
    assertFalse(HAN_CHARACTER.matcher(value).find(), "English legend contains Han: " + value);
  }
}
