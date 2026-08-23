package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.Leelaz;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class LegacyEstimateConfigResidueTest {
  private static final List<String> DELETED_RUNTIME_FIELDS =
      List.of(
          "loadEstimateEngine",
          "estimateCommand",
          "estimateThreshold",
          "estimateArea",
          "useZenEstimate",
          "zenEstimateCommand");

  private static final List<String> DELETED_TYPES =
      List.of(
          "featurecat.lizzie.analysis.KataEstimate",
          "featurecat.lizzie.analysis.KataRawOwnershipParser",
          "featurecat.lizzie.analysis.OwnershipEstimate",
          "featurecat.lizzie.analysis.EstimateEngineSSHController",
          "featurecat.lizzie.gui.EstimateResults",
          "featurecat.lizzie.gui.SetEstimateParam");

  @Test
  void leftoverEstimateKeysAreNotRepairedOrRewritten() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-estimate-residue");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    String leftoverEstimate = "java -jar leftover/missing-estimate.jar";
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("autoload-default", true)
            .put("autoload-last", false)
            .put("autoload-empty", false)
            .put("default-engine", 0)
            .put("analysis-engine-command", "java -jar leftover/missing-analysis.jar")
            .put("estimate-command", leftoverEstimate)
            .put("load-estimate-engine", true)
            .put("use-zen-estimate", true)
            .put("estimate-threshold", 0.9)
            .put("estimate-area", true)
            .put("use-estimate-command", "ZenEstimate/ZenGTP.exe");
    JSONObject legacyEngine =
        new JSONObject()
            .put("name", "Legacy KataGo")
            .put("command", "java -jar leftover/missing-engine.jar")
            .put("isDefault", true);
    JSONObject leelaz =
        new JSONObject().put("engine-settings-list", new JSONArray().put(legacyEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    assertEquals(leftoverEstimate, ui.getString("estimate-command"));
    assertTrue(ui.getBoolean("load-estimate-engine"));
    assertTrue(ui.getBoolean("use-zen-estimate"));
    assertEquals(0.9, ui.getDouble("estimate-threshold"));
    assertTrue(ui.getBoolean("estimate-area"));
    assertEquals("ZenEstimate/ZenGTP.exe", ui.getString("use-estimate-command"));
    assertFalse(ui.getString("analysis-engine-command").contains("java -jar"));
  }

  @Test
  void leftoverEstimateKeysHaveNoRuntimeFieldsOrEstimateRequestPath() {
    for (String field : DELETED_RUNTIME_FIELDS) {
      assertThrows(NoSuchFieldException.class, () -> Config.class.getDeclaredField(field), field);
    }
    assertThrows(
        NoSuchMethodException.class,
        () -> Leelaz.class.getDeclaredMethod("requestPositionEstimate", java.util.function.Consumer.class));
    for (String type : DELETED_TYPES) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(type), type);
    }
  }

  private static boolean applyBundledKataGoDefaults(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("applyBundledKataGoDefaults");
    method.setAccessible(true);
    return (Boolean) method.invoke(config);
  }

  private static void withUserDir(Path userDir, ThrowingRunnable action) throws Exception {
    String previousUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", userDir.toString());
      action.run();
    } finally {
      if (previousUserDir == null) {
        System.clearProperty("user.dir");
      } else {
        System.setProperty("user.dir", previousUserDir);
      }
    }
  }

  private static void createBundledKataGoAssets(Path root) throws Exception {
    Files.writeString(root.resolve(".lizzie-portable"), "");
    Path katagoRoot = root.resolve("engines").resolve("katago");
    String[] platformDirs = {
      "macos-arm64", "macos-amd64", "linux-x64", "linux-x86", "windows-x64", "windows-x86"
    };
    for (String platformDir : platformDirs) {
      Path dir = Files.createDirectories(katagoRoot.resolve(platformDir));
      Files.write(dir.resolve("katago"), new byte[] {1});
      Files.write(dir.resolve("katago.exe"), new byte[] {1});
    }
    Path configs = Files.createDirectories(katagoRoot.resolve("configs"));
    Files.write(configs.resolve("gtp.cfg"), new byte[] {1});
    Files.write(configs.resolve("analysis.cfg"), new byte[] {1});
    Files.createDirectories(root.resolve("weights"));
    Files.write(root.resolve("weights").resolve("default.bin.gz"), new byte[] {1});
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
