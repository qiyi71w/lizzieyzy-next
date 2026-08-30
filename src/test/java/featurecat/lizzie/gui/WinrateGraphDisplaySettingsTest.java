package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WinrateGraphDisplaySettingsTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void persistRemovesLegacyWinrateGraphMode(int legacyMode) {
    Config config = ConfigTestHelper.createForTests(tempDir);
    config.persistedUi = new JSONObject();
    config.persistedUi.put("winrate-graph", new JSONArray().put(legacyMode));

    ConfigTestHelper.dropPersistedWinrateGraphMode(config);

    assertFalse(config.persistedUi.has("winrate-graph"));
  }

  @Test
  void winrateGraphHasNoPersistedPerspectiveMode() {
    assertThrows(NoSuchFieldException.class, () -> WinrateGraph.class.getDeclaredField("mode"));
  }
}
