package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SGFParserEmptyEngineSaveTest {
  @Test
  void ordinarySaveAllowsAMissingForegroundEngineAndWritesANonKataHeader() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      clearForegroundEngine();
      Lizzie.board.isKataBoard = false;

      String sgf = assertDoesNotThrow(() -> SGFParser.saveToString(false));

      assertFalse(
          sgf.contains("DZ[G]"),
          "a non-Kata board with no foreground engine must not write a Kata header");
      assertTrue(sgf.contains("AP[LizzieYzy Next"), "ordinary save still writes the app header");
    }
  }

  @Test
  void autosaveAllowsAMissingForegroundEngine(@TempDir Path tempDir) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      clearForegroundEngine();
      Path sgfFile = tempDir.resolve("auto.sgf");

      assertDoesNotThrow(() -> SGFParser.save(Lizzie.board, sgfFile.toString(), true));
      assertTrue(sgfFile.toFile().isFile(), "autosave should still write an SGF file");
    }
  }

  @Test
  void saveUsesTheBoardKataMarkerWhenTheForegroundEngineIsMissing() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      clearForegroundEngine();
      Lizzie.board.isKataBoard = true;

      String sgf = assertDoesNotThrow(() -> SGFParser.saveToString(false));

      assertTrue(
          sgf.contains("DZ[G]"),
          "an already-Kata board must keep its Kata header without a live engine");
    }
  }

  private static void clearForegroundEngine() {
    Lizzie.leelaz = null;
    EngineManager.isEmpty = true;
  }
}
