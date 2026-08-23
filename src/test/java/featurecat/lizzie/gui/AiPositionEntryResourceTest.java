package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class AiPositionEntryResourceTest {
  private static final List<Locale> LOCALES =
      List.of(
          Locale.ROOT,
          Locale.US,
          Locale.SIMPLIFIED_CHINESE,
          Locale.TRADITIONAL_CHINESE,
          Locale.JAPAN,
          Locale.KOREA,
          Locale.forLanguageTag("zh-HK"),
          Locale.forLanguageTag("th-TH"));

  @Test
  void allLocalesProvideUnifiedEntryAndStatusText() {
    for (Locale locale : LOCALES) {
      ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      assertFalse(bundle.getString("BottomToolbar.kataEstimate").isBlank(), locale.toString());
      assertFalse(bundle.getString("Menu.aiPosition").isBlank(), locale.toString());
      assertFalse(bundle.getString("Menu.kataEstimate").isBlank(), locale.toString());
      assertFalse(bundle.getString("LizzieFrame.aiPositionWaiting").isBlank(), locale.toString());
      assertFalse(
          bundle.getString("LizzieFrame.aiPositionUnavailable").isBlank(), locale.toString());
      assertFalse(bundle.getString("LizzieFrame.commands.keySlash").isBlank(), locale.toString());
    }
  }

  @Test
  void simplifiedChineseUsesUnifiedAiPositionNames() {
    ResourceBundle bundle =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

    assertEquals("AI形势", bundle.getString("BottomToolbar.kataEstimate"));
    assertEquals("AI形势 (/ 或 .)", bundle.getString("Menu.aiPosition"));
    assertEquals("AI形势显示", bundle.getString("Menu.kataEstimate"));
    assertEquals("AI形势等待中", bundle.getString("LizzieFrame.aiPositionWaiting"));
    assertEquals("AI形势需要 KataGo", bundle.getString("LizzieFrame.aiPositionUnavailable"));
  }
}
