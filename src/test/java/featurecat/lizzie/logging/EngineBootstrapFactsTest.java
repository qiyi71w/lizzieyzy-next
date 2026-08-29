package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineBootstrapFactsTest {
  @TempDir Path tempDir;

  @Test
  void userConfiguredCommandSanitizesPathsAndKeepsUnknownBackend() {
    String command =
        "\"C:\\\\Users\\\\Player\\\\secret\\\\katago.exe\" gtp "
            + "-model \"C:\\\\Users\\\\Player\\\\secret\\\\weights\\\\b18c384nbt.bin.gz\" "
            + "-config \"C:\\\\Users\\\\Player\\\\secret\\\\configs\\\\gtp.cfg\"";

    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals("unknown", facts.version());
    assertEquals("MAIN_BOARD", facts.purpose());
    assertEquals(EngineBootstrapFacts.SOURCE_USER_CONFIGURED, facts.source());
    assertEquals("unknown", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("b18c384nbt.bin.gz", facts.model());
    assertEquals("gtp.cfg", facts.config());
    String line = facts.formatLogLine("");
    assertTrue(line.startsWith("engine event=bootstrap"), line);
    assertFalse(line.contains("Player"), line);
    assertFalse(line.contains("Users"), line);
    assertFalse(line.contains("secret"), line);
    assertFalse(line.contains("C:"), line);
    assertFalse(line.contains("\\\\"), line);
  }

  @Test
  void bundledCommandRecordsSourceAndNvidiaBackendFromPath() throws Exception {
    Path engine =
        touch(
            tempDir
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia")
                .resolve("katago.exe"));
    Path model = touch(tempDir.resolve("weights").resolve("kata1.bin.gz"));
    Path config =
        touch(tempDir.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg"));
    String command = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);

    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals(EngineBootstrapFacts.SOURCE_BUNDLED, facts.source());
    assertEquals("nvidia", facts.backend());
    assertTrue(Config.isBundledKataGoCommand(command));
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("kata1.bin.gz", facts.model());
    assertEquals("gtp.cfg", facts.config());
    assertFalse(facts.formatLogLine("").contains(tempDir.toString()), facts.formatLogLine(""));
  }

  @Test
  void onnxProviderFromOverrideConfigSetsBackendWithoutInventingAValue() throws Exception {
    Path engine =
        touch(
            tempDir
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Files.writeString(
        engine.getParent().resolve("lizzieyzy-next-engine-backend.txt"),
        "directml\n",
        StandardCharsets.UTF_8);
    String command =
        quote(engine)
            + " gtp -model model.bin.gz -config gtp.cfg "
            + "-override-config \"onnxProvider=directml,onnxOpenVINOCacheDir=C:\\\\Users\\\\Player\\\\cache\"";

    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD");

    assertEquals("onnx", facts.backend());
    assertEquals("directml", facts.onnxProvider());
    assertEquals(EngineBootstrapFacts.SOURCE_BUNDLED, facts.source());
    String line = facts.formatLogLine("");
    assertFalse(line.contains("Player"), line);
    assertFalse(line.contains("cache"), line);
  }

  @Test
  void missingBackendAndProviderStayExplicitlyUnknown() {
    EngineBootstrapFacts facts =
        EngineBootstrapFacts.fromCommand("katago gtp -model model.bin.gz", "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals(EngineBootstrapFacts.SOURCE_USER_CONFIGURED, facts.source());
    assertEquals("unknown", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("model.bin.gz", facts.model());
    assertEquals("unknown", facts.config());
  }

  @Test
  void openClMarkerIsUsedWhenNvidiaBackendIsAbsent() throws Exception {
    Path engine =
        touch(
            tempDir
                .resolve("app")
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Files.writeString(
        engine.getParent().resolve("lizzieyzy-next-engine-backend.txt"),
        "opencl\n",
        StandardCharsets.UTF_8);

    EngineBootstrapFacts facts =
        EngineBootstrapFacts.fromCommand(quote(engine) + " gtp -model net.bin.gz", "MAIN_BOARD");

    assertEquals("opencl", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
  }

  @Test
  void emptyOrNullCommandDoesNotInventTypeOrPaths() {
    EngineBootstrapFacts empty = EngineBootstrapFacts.fromCommand("", "MAIN_BOARD");
    assertEquals("unknown", empty.engineType());
    assertEquals("unknown", empty.backend());
    assertEquals("unknown", empty.model());
    assertEquals(EngineBootstrapFacts.SOURCE_USER_CONFIGURED, empty.source());
    EngineBootstrapFacts missing = EngineBootstrapFacts.fromCommand(null, "MAIN_BOARD");
    assertEquals("unknown", missing.engineType());
    assertEquals("unknown", missing.backend());
  }

  private static Path touch(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    if (!Files.exists(path)) {
      Files.writeString(path, "", StandardCharsets.UTF_8);
    }
    return path;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath().normalize() + "\"";
  }
}
