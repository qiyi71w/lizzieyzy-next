package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoExperimentalBackendInstaller.Backend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class KataGoExperimentalBackendInstallerTest {
  @Test
  void everyBackendUsesAPinnedExperimentalCatalogAsset() {
    for (Backend backend : Backend.values()) {
      KataGoAssetCatalog.Asset asset = backend.asset();
      assertEquals("experimental", asset.releaseTier());
      assertTrue(asset.assetName().startsWith("katago-source-47aadc08518b-"));
      assertEquals(64, asset.sha256().length());
    }
  }

  @Test
  void archiveInstallPreservesBackendDirectoryTreeAndWritesAuditMetadata() throws Exception {
    Path root = Files.createTempDirectory("katago-experimental-install");
    Path archive = root.resolve("backend.zip");
    createArchive(
        archive,
        new FixtureEntry("bundle/katago.exe", "engine"),
        new FixtureEntry("bundle/rocblas/library/table.dat", "rocblas"),
        new FixtureEntry("bundle/hipblaslt/library/table.dat", "hipblaslt"));
    Path target = root.resolve("runtime").resolve("windows-x64-rocm-gfx103x");

    KataGoExperimentalBackendInstaller.installArchive(archive, Backend.ROCM_GFX103X, target);

    assertEquals("engine", Files.readString(target.resolve("katago.exe")));
    assertEquals(
        "rocblas", Files.readString(target.resolve("rocblas/library/table.dat")));
    assertEquals(
        "hipblaslt", Files.readString(target.resolve("hipblaslt/library/table.dat")));
    assertEquals(
        "rocm-gfx103x",
        Files.readString(target.resolve("lizzieyzy-next-engine-backend.txt")).trim());
    String manifest =
        Files.readString(target.resolve("lizzieyzy-next-katago-engine-manifest.txt"));
    assertTrue(manifest.contains("KataGo release: " + KataGoAssetCatalog.get().katagoReleaseTag()));
    assertTrue(manifest.contains("Asset SHA-256: " + Backend.ROCM_GFX103X.asset().sha256()));
  }

  @Test
  void archiveInstallRejectsPathTraversalWithoutWritingOutsideTarget() throws Exception {
    Path root = Files.createTempDirectory("katago-experimental-zip-slip");
    Path archive = root.resolve("unsafe.zip");
    createArchive(
        archive,
        new FixtureEntry("bundle/katago.exe", "engine"),
        new FixtureEntry("../outside.txt", "unsafe"));
    Path target = root.resolve("runtime").resolve("windows-x64-directml");

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                KataGoExperimentalBackendInstaller.installArchive(
                    archive, Backend.DIRECTML, target));

    assertTrue(failure.getMessage().contains("Unsafe path"));
    assertFalse(Files.exists(root.resolve("outside.txt")));
    assertFalse(Files.exists(target));
  }

  @Test
  void commandOverridesMatchOfficialOnnxProviderRequirements() throws Exception {
    Path root = Files.createTempDirectory("katago-experimental-overrides");
    Path directMl = engineWithMarker(root.resolve("directml"), "directml");
    Path openVinoGpu = engineWithMarker(root.resolve("openvino-gpu"), "openvino");
    Path openVinoNpu = engineWithMarker(root.resolve("openvino-npu"), "openvino-npu");
    Path rocm = engineWithMarker(root.resolve("rocm"), "rocm-gfx110x");

    assertEquals(
        " -override-config \"onnxProvider=directml\"",
        KataGoAutoSetupHelper.experimentalBackendOverrides(directMl, root));
    String gpuOverride = KataGoAutoSetupHelper.experimentalBackendOverrides(openVinoGpu, root);
    assertTrue(gpuOverride.contains("onnxProvider=openvino"));
    assertTrue(gpuOverride.contains("onnxOpenVINOCacheDir="));
    assertFalse(gpuOverride.contains("onnxOpenVINODeviceType=NPU"));
    String npuOverride = KataGoAutoSetupHelper.experimentalBackendOverrides(openVinoNpu, root);
    assertTrue(npuOverride.contains("onnxOpenVINODeviceType=NPU"));
    assertEquals("", KataGoAutoSetupHelper.experimentalBackendOverrides(rocm, root));
  }

  private static Path engineWithMarker(Path directory, String marker) throws IOException {
    Files.createDirectories(directory);
    Path engine = Files.writeString(directory.resolve("katago.exe"), "fixture");
    Files.writeString(directory.resolve("lizzieyzy-next-engine-backend.txt"), marker);
    return engine;
  }

  private static void createArchive(Path path, FixtureEntry... entries) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
      for (FixtureEntry entry : entries) {
        output.putNextEntry(new ZipEntry(entry.name));
        output.write(entry.content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
  }

  private record FixtureEntry(String name, String content) {}
}
