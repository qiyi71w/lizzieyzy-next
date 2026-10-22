package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineGameOpeningParametersTest {
  @TempDir Path root;

  @Test
  void configWithoutPositionBreadthStartsBothPositionsAtZero() throws Exception {
    Config config = config();
    config.uiConfig.remove("engine-game-first-wrn");
    config.uiConfig.remove("engine-game-second-wrn");
    // The old engine-game "disable breadth" switch no longer decides the opening value.
    config.uiConfig.put("disable-wrn-in-game", false);
    config.uiConfig.put("txt-kata-engine-wrn", "0.8");

    assertEquals(EngineGameOpeningParameters.NEUTRAL, EngineGameOpeningParameters.stored(config));
  }

  @Test
  void savedOpeningBreadthBelongsToEachPosition() throws Exception {
    Config config = config();

    EngineGameOpeningParameters.persist(config, new EngineGameOpeningParameters(0.5, 1.25));

    EngineGameOpeningParameters stored = EngineGameOpeningParameters.stored(config);
    assertEquals(0.5, stored.wrn(true));
    assertEquals(1.25, stored.wrn(false));
  }

  @Test
  void corruptSavedValueFallsBackToZeroForThatPositionOnly() throws Exception {
    Config config = config();
    config.uiConfig.put("engine-game-first-wrn", 7.0);
    config.uiConfig.put("engine-game-second-wrn", 0.3);

    assertEquals(
        new EngineGameOpeningParameters(0, 0.3), EngineGameOpeningParameters.stored(config));
  }

  private Config config() throws Exception {
    java.nio.file.Files.createDirectories(root.resolve("save"));
    return ConfigTestHelper.createBootstrapped(root);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "-", ".", "-0.1", "2.01", "NaN", "Infinity", "1e9", "abc"})
  void partialOrOutOfRangeInputIsNotAValue(String text) {
    assertNull(parse(text));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "2", "0.25", " 1.5 "})
  void finiteBreadthWithinRangeIsAccepted(String text) {
    assertEquals(Double.parseDouble(text.trim()), parse(text));
  }

  private static Double parse(String text) {
    return EngineGameOpeningParameters.parseWrn(text);
  }
}
