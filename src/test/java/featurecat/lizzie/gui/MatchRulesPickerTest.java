package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import java.nio.file.Files;
import javax.swing.JComboBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchRulesPickerTest {
  private Config previous;

  @BeforeEach
  void saveConfig() {
    previous = Lizzie.config;
  }

  @AfterEach
  void restoreConfig() {
    Lizzie.config = previous;
  }

  @Test
  void applyCustomSelectsNonPresetRulesWithoutStartupDefault() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-picker"));
    config.engineGameMatchRules = "";
    config.kataRules = KataGoRules.parse("japanese").orElseThrow().toGtpArgument();
    config.autoLoadKataRules = true;
    Lizzie.config = config;
    KataGoRules custom =
        KataGoRules.parse("chinese")
            .orElseThrow()
            .overlayEditor("AREA", "POSITIONAL", true, "NONE", "0", false);
    assertTrue(KataGoRules.officialNameOf(custom).isEmpty());

    MatchRulesPicker picker = new MatchRulesPicker();
    picker.applyCustom(custom);

    assertTrue(picker.selected().semanticallyEquals(custom));
    assertTrue(KataGoRules.officialNameOf(picker.selected()).isEmpty());
    assertTrue(picker.offersCustomChoice());
    assertTrue(
        KataGoRules.parse("japanese")
            .orElseThrow()
            .semanticallyEquals(KataGoRules.parse(config.kataRules).orElseThrow()));
  }

  @Test
  void officialPresetOptionsKeepDistinctLabelsAndMatchingRules() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-picker"));
    config.engineGameMatchRules = "";
    config.kataRules = KataGoRules.parse("japanese").orElseThrow().toGtpArgument();
    config.autoLoadKataRules = true;
    Lizzie.config = config;

    MatchRulesPicker picker = new MatchRulesPicker();
    String chinese = officialOptionLabel(picker, "chinese");
    String chineseOgs = officialOptionLabel(picker, "chinese-ogs");
    String aga = officialOptionLabel(picker, "aga");
    String newZealand = officialOptionLabel(picker, "new-zealand");

    assertNotEquals(chinese, chineseOgs);
    assertNotEquals(aga, newZealand);
  }

  private static String officialOptionLabel(MatchRulesPicker picker, String preset) {
    int index = KataGoRules.officialPresetNames().indexOf(preset);
    if (index < 0) {
      throw new AssertionError("missing official option for " + preset);
    }
    JComboBox<?> combo = picker.component();
    combo.setSelectedIndex(index);
    assertTrue(picker.selected().semanticallyEquals(KataGoRules.parse(preset).orElseThrow()));
    return String.valueOf(combo.getItemAt(index));
  }
}
