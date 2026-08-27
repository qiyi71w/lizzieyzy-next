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
  void englishSingleLineWithScoreLeadUsesWinrateAndScoreLeadWithoutHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    List<String> labels = WinrateGraph.lineLegendLabels(false, true, english);

    assertEquals(List.of("Winrate", "Score lead"), labels);
    labels.forEach(WinrateGraphLegendCopyTest::assertNoHan);
  }

  @Test
  void englishTwoLineLegendUsesBlackAndWhiteWinrateWithoutHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    List<String> labels = WinrateGraph.lineLegendLabels(true, false, english);

    assertEquals(List.of("Black winrate", "White winrate"), labels);
    labels.forEach(WinrateGraphLegendCopyTest::assertNoHan);
  }

  @Test
  void simplifiedChineseKeepsCurrentWinrateAndScoreLeadLiterals() {
    ResourceBundle chinese = AppLocale.SIMPLIFIED_CHINESE.loadBundle();

    assertEquals(List.of("胜率", "目差"), WinrateGraph.lineLegendLabels(false, true, chinese));
    assertEquals(List.of("黑胜率", "白胜率"), WinrateGraph.lineLegendLabels(true, false, chinese));
  }

  private static void assertNoHan(String value) {
    assertFalse(HAN_CHARACTER.matcher(value).find(), "English legend contains Han: " + value);
  }
}
