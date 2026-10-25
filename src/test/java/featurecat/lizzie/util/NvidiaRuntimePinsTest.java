package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class NvidiaRuntimePinsTest {
  @Test
  void theBuildsPinnedPackagesPassAndAnythingElseFails() throws Exception {
    JSONArray pinned =
        new JSONObject(Files.readString(Path.of("scripts/katago_cuda_dependencies.json")))
            .getJSONArray("dependencies");
    int checked = 0;
    for (int index = 0; index < pinned.length(); index++) {
      JSONObject dependency = pinned.getJSONObject(index);
      String name = dependency.getString("name");
      if (!java.util.Set.of("cuda_cudart", "libcublas", "libnvjitlink", "cuda_nvrtc", "cudnn")
          .contains(name)) {
        continue;
      }
      checked++;
      String version = dependency.getString("version");
      String sha256 = dependency.getString("sha256");
      assertDoesNotThrow(() -> NvidiaRuntimePins.require(name, version, sha256.toUpperCase()));
      assertThrows(
          IOException.class, () -> NvidiaRuntimePins.require(name, version + ".1", sha256));
      assertThrows(
          IOException.class,
          () -> NvidiaRuntimePins.require(name, version, "0".repeat(64)),
          "a digest read from NVIDIA's JSON at install time is not trusted");
    }
    org.junit.jupiter.api.Assertions.assertEquals(5, checked);
    assertThrows(IOException.class, () -> NvidiaRuntimePins.require("libcufft", "1", "0"));
  }
}
