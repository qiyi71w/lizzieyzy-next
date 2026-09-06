package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.analysis.KataGoRules;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class EngineGameMatchRulesSelectionTest {
  @Test
  void prefillUsesChineseWhenNothingIsSavedAndStartupDefaultIsOff() throws Exception {
    Config config = emptyConfig();
    config.autoLoadKataRules = false;
    config.kataRules = KataGoRules.parse("japanese").orElseThrow().toGtpArgument();

    KataGoRules prefill = EngineGameMatchRulesSelection.prefill(config);

    assertTrue(prefill.semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
    assertTrue(EngineGameMatchRulesSelection.stored(config).isEmpty());
    assertFalse(EngineGameMatchRulesSelection.storedIsCorrupt(config));
  }

  @Test
  void prefillUsesEnabledStartupDefaultWhenNoMatchSelectionExists() throws Exception {
    Config config = emptyConfig();
    config.autoLoadKataRules = true;
    config.kataRules = KataGoRules.parse("japanese").orElseThrow().toGtpArgument();

    KataGoRules prefill = EngineGameMatchRulesSelection.prefill(config);

    assertTrue(prefill.semanticallyEquals(KataGoRules.parse("japanese").orElseThrow()));
  }

  @Test
  void persistRemembersMatchRulesWithoutChangingStartupDefault() throws Exception {
    Config config = emptyConfig();
    config.autoLoadKataRules = true;
    config.kataRules = KataGoRules.parse("chinese").orElseThrow().toGtpArgument();
    KataGoRules selected = KataGoRules.parse("tromp-taylor").orElseThrow();

    EngineGameMatchRulesSelection.persist(config, selected);

    assertTrue(
        EngineGameMatchRulesSelection.stored(config).orElseThrow().semanticallyEquals(selected));
    assertTrue(EngineGameMatchRulesSelection.prefill(config).semanticallyEquals(selected));
    assertEquals(KataGoRules.parse("chinese").orElseThrow().toGtpArgument(), config.kataRules);
    assertTrue(config.autoLoadKataRules);
  }

  @Test
  void persistRemembersNonPresetCustomWithoutChangingStartupDefault() throws Exception {
    Config config = emptyConfig();
    config.autoLoadKataRules = true;
    config.kataRules = KataGoRules.parse("chinese").orElseThrow().toGtpArgument();
    KataGoRules selected =
        KataGoRules.parse("chinese")
            .orElseThrow()
            .overlayEditor("AREA", "POSITIONAL", true, "NONE", "0", false);
    assertTrue(KataGoRules.officialNameOf(selected).isEmpty());

    EngineGameMatchRulesSelection.persist(config, selected);

    assertTrue(
        EngineGameMatchRulesSelection.stored(config).orElseThrow().semanticallyEquals(selected));
    assertEquals(KataGoRules.parse("chinese").orElseThrow().toGtpArgument(), config.kataRules);
    assertTrue(config.autoLoadKataRules);
  }

  @Test
  void corruptStoredSelectionIsNotSilentlyReplaced() throws Exception {
    Config config = emptyConfig();
    config.engineGameMatchRules = "not-a-rules-value";
    config.autoLoadKataRules = true;
    config.kataRules = KataGoRules.parse("japanese").orElseThrow().toGtpArgument();

    assertTrue(EngineGameMatchRulesSelection.storedIsCorrupt(config));
    assertTrue(EngineGameMatchRulesSelection.stored(config).isEmpty());
    assertTrue(
        EngineGameMatchRulesSelection.prefill(config)
            .semanticallyEquals(KataGoRules.parse("japanese").orElseThrow()));
  }

  private static Config emptyConfig() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules"));
    config.engineGameMatchRules = "";
    config.kataRules = "";
    config.autoLoadKataRules = false;
    return config;
  }
}
