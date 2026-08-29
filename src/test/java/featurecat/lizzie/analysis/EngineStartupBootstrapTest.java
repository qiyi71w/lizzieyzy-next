package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.logging.EngineBootstrapFacts;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineStartupBootstrapTest {
  @TempDir Path tempDir;

  @Test
  void bundledNvidiaBackendMatchesRuntimeHelper() throws Exception {
    Path engine =
        touch(
            tempDir
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia")
                .resolve("katago.exe"));
    String command = quote(engine) + " gtp -model kata1.bin.gz -config gtp.cfg";

    EngineBootstrapFacts facts = EngineStartupBootstrap.factsFor(command, "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals(EngineBootstrapFacts.SOURCE_BUNDLED, facts.source());
    assertEquals("MAIN_BOARD", facts.purpose());
    assertEquals(KataGoRuntimeHelper.resolveNvidiaBackend(engine), facts.backend());
    assertEquals("nvidia", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("kata1.bin.gz", facts.model());
    assertEquals("gtp.cfg", facts.config());
    assertTrue(Config.isBundledKataGoCommand(command));
  }

  @Test
  void userConfiguredMissingBackendAndProviderStayUnknown() {
    String command =
        "\"C:\\\\Users\\\\Player\\\\secret\\\\katago.exe\" gtp "
            + "-model \"C:\\\\Users\\\\Player\\\\secret\\\\net.bin.gz\"";

    EngineBootstrapFacts facts = EngineStartupBootstrap.factsFor(command, "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals(EngineBootstrapFacts.SOURCE_USER_CONFIGURED, facts.source());
    assertEquals("unknown", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("net.bin.gz", facts.model());
    assertNotEquals("Player", facts.model());
  }

  @Test
  void onnxMarkerFromRuntimeHelperSetsBackendAndProvider() throws Exception {
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

    EngineBootstrapFacts facts =
        EngineStartupBootstrap.factsFor(quote(engine) + " gtp -model m.bin.gz", "MAIN_BOARD");

    assertEquals("onnx", facts.backend());
    assertEquals("directml", facts.onnxProvider());
    assertEquals(EngineBootstrapFacts.SOURCE_BUNDLED, facts.source());
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
