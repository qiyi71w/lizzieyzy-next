package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.enginegame.EngineGameMatchRulesSelection;
import featurecat.lizzie.enginegame.MatchRulesSnapshot;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-picker"));
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
  void openingAndAcceptingLegacyPresetKeepsItsRules() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-picker"));
    config.engineGameMatchRules = "chinese-ogs";
    Lizzie.config = config;

    MatchRulesPicker picker = new MatchRulesPicker();

    assertEquals(
        Lizzie.resourceBundle.getString("NewEngineGameDialog.matchRules.custom"),
        picker.component().getSelectedItem().toString());
    assertEquals("chinese-ogs", config.engineGameMatchRules);
    EngineGameMatchRulesSelection.persist(config, picker.selected());
    assertEquals("chinese-ogs", config.engineGameMatchRules);
    assertTrue(
        EngineGameMatchRulesSelection.stored(config)
            .orElseThrow()
            .semanticallyEquals(KataGoRules.parse("chinese-ogs").orElseThrow()));

    picker.component().setSelectedIndex(0);
    EngineGameMatchRulesSelection.persist(config, picker.selected());
    assertTrue(
        EngineGameMatchRulesSelection.stored(config)
            .orElseThrow()
            .semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
  }

  @Test
  void standardChoicesKeepDistinctNamesThroughSaveReopenAndMainDisplay() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-picker"));
    Lizzie.config = config;
    List<String> presets = List.of("chinese", "japanese", "aga", "new-zealand", "tromp-taylor");
    List<String> labelKeys =
        List.of(
            "LizzieFrame.currentRules.chinese",
            "MatchRules.option.japaneseKorean",
            "MatchRules.option.agaBga",
            "MatchRules.option.newZealand",
            "MatchRules.option.trompTaylor");
    MatchRulesPicker picker = new MatchRulesPicker();
    assertEquals(6, picker.component().getItemCount());
    Set<String> names = new HashSet<>();
    for (int i = 0; i < presets.size(); i++) {
      picker.component().setSelectedIndex(i);
      String label = picker.component().getSelectedItem().toString();
      assertEquals(Lizzie.resourceBundle.getString(labelKeys.get(i)), label);
      assertTrue(names.add(label), "Each rule family needs a distinct label");
      assertTrue(
          picker.selected().semanticallyEquals(KataGoRules.parse(presets.get(i)).orElseThrow()));
      EngineGameMatchRulesSelection.persist(config, picker.selected());
      MatchRulesPicker reopened = new MatchRulesPicker();
      assertEquals(label, reopened.component().getSelectedItem().toString());
      assertEquals(label, MatchRulesSnapshot.ruleName(reopened.selected(), Lizzie.resourceBundle));
    }
    assertEquals(
        Lizzie.resourceBundle.getString("NewEngineGameDialog.matchRules.custom"),
        String.valueOf(picker.component().getItemAt(5)));
  }

  @Test
  void officialAliasesAndJsonUseFamilyNamesWithoutRewritingSavedValues() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-picker"));
    Lizzie.config = config;
    for (String preset : List.of("japanese", "korean", "aga", "bga")) {
      String key =
          preset.equals("japanese") || preset.equals("korean")
              ? "MatchRules.option.japaneseKorean"
              : "MatchRules.option.agaBga";
      for (String saved :
          List.of(preset, KataGoRules.parse(preset).orElseThrow().toGtpArgument())) {
        config.engineGameMatchRules = saved;
        MatchRulesPicker picker = new MatchRulesPicker();
        String label = Lizzie.resourceBundle.getString(key);
        assertEquals(label, picker.component().getSelectedItem().toString());
        assertEquals(label, MatchRulesSnapshot.ruleName(picker.selected(), Lizzie.resourceBundle));
        EngineGameMatchRulesSelection.persist(config, picker.selected());
        assertEquals(saved, config.engineGameMatchRules);
      }
    }
  }

  @Test
  void customJsonKeepsUnknownFieldsUntilUserSelectsStandardRules() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-picker"));
    String saved =
        KataGoRules.parse("chinese")
            .orElseThrow()
            .toJson()
            .put("futureRule", "keep-me")
            .toString(2);
    config.engineGameMatchRules = saved;
    Lizzie.config = config;
    MatchRulesPicker picker = new MatchRulesPicker();
    assertEquals(
        Lizzie.resourceBundle.getString("NewEngineGameDialog.matchRules.custom"),
        picker.component().getSelectedItem().toString());
    EngineGameMatchRulesSelection.persist(config, picker.selected());
    assertEquals(saved, config.engineGameMatchRules);
    assertEquals(
        "keep-me", EngineGameMatchRulesSelection.stored(config).orElseThrow().string("futureRule"));
    picker.component().setSelectedIndex(0);
    EngineGameMatchRulesSelection.persist(config, picker.selected());
    assertTrue(
        EngineGameMatchRulesSelection.stored(config)
            .orElseThrow()
            .semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
  }
}
