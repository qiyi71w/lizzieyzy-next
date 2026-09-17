package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class KataGoAssetCatalogTest {
  private JSONObject sourceCatalogJson() throws IOException {
    JSONObject root;
    try (var input = getClass().getResourceAsStream("/katago-assets.json")) {
      root = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    root.put("origin", "project-source-build");
    root.put("engineReleaseRepository", "wimi321/lizzieyzy-next");
    root.put("engineReleaseTag", "next-2026-09-17.1");
    JSONObject assets = root.getJSONObject("assets");
    for (String id : assets.keySet()) {
      assets
          .getJSONObject(id)
          .put(
              "assetName",
              "katago-source-" + root.getString("katagoSourceCommit").substring(0, 12) + "-" + id + ".zip");
    }
    return root;
  }

  @Test
  void sourceEngineRepairUsesProjectAssetsWithoutChangingModelOrigins() throws IOException {
    KataGoAssetCatalog catalog = new KataGoAssetCatalog(sourceCatalogJson());
    assertTrue(
        catalog
            .assetDownloadUrl(catalog.asset("windows-tensorrt"))
            .startsWith("https://github.com/wimi321/lizzieyzy-next/releases/download/next-2026-09-17.1/"));
    assertTrue(catalog.modelDownloadUrl(catalog.model("b10-balanced")).contains("lightvector/KataGo"));
  }

  @Test
  void sourceDownloadsRejectForeignRepositoryAndMutableTags() throws IOException {
    for (String[] change :
        new String[][] {
          {"engineReleaseRepository", "evil/KataGo"},
          {"engineReleaseTag", "latest"},
          {"engineReleaseTag", "next-2026-09-17.1/../other"},
          {"katagoSourceCommit", "master"},
          {"origin", "unknown"}
        }) {
      JSONObject root = sourceCatalogJson();
      root.put(change[0], change[1]);
      assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(root));
    }
  }

  @Test
  void sourceAssetCannotSubstituteAnOldOrUnsafeFile() throws IOException {
    for (String name : new String[] {"../old.zip", "old-engine.zip", "folder\\engine.zip"}) {
      JSONObject root = sourceCatalogJson();
      root.getJSONObject("assets").getJSONObject("windows-cpu").put("assetName", name);
      assertThrows(IllegalStateException.class, () -> new KataGoAssetCatalog(root));
    }
  }

  @Test
  void pinsKataGo1181AndB11AsTheOnlyBundledDefault() {
    KataGoAssetCatalog catalog = KataGoAssetCatalog.get();
    KataGoAssetCatalog.Model model = catalog.defaultModel();

    assertEquals("1.18.1", catalog.katagoVersion());
    assertEquals("v1.18.1", catalog.katagoReleaseTag());
    assertEquals("kata1-tf3-b11c768-s11500M-d6163M.bin.gz", model.fileName());
    assertEquals(211_568_937L, model.sizeBytes());
    assertEquals(
        "73f6454eba62d2f6d099af8ce66d8c3fde6225e223c55817da0627590e98b0ae",
        model.sha256());
    assertTrue(model.bundled());
    assertFalse(catalog.model("b10-balanced").bundled());
    assertEquals("2026-09-07", model.publishedAt());
    assertEquals(
        "https://media.katagotraining.org/uploaded/networks/models/kata1/" + model.fileName(),
        catalog.modelDownloadUrl(model));
    assertEquals(
        "https://github.com/lightvector/KataGo/releases/download/v1.17.1/"
            + catalog.model("b10-balanced").fileName(),
        catalog.modelDownloadUrl(catalog.model("b10-balanced")));
  }

  @Test
  void keepsWindowsCuda128UnifiedAndLinuxCuda121() {
    KataGoAssetCatalog catalog = KataGoAssetCatalog.get();

    assertEquals("cuda12.8-cudnn9", catalog.asset("windows-nvidia").runtimeProfile());
    assertEquals(64, catalog.asset("windows-nvidia").executableSha256().length());
    assertEquals("cuda12.1-cudnn9", catalog.asset("linux-nvidia").runtimeProfile());
    assertTrue(catalog.asset("windows-nvidia").assetName().contains("cuda12.8"));
    assertTrue(catalog.asset("linux-nvidia").assetName().contains("cuda12.1"));
  }

  @Test
  void exposesExperimentalWindowsBackendsWithTrustedHashes() {
    KataGoAssetCatalog catalog = KataGoAssetCatalog.get();

    for (String id :
        new String[] {
          "windows-directml",
          "windows-openvino",
          "windows-rocm-gfx103x",
          "windows-rocm-gfx110x",
          "windows-rocm-gfx1151",
          "windows-rocm-gfx120x"
        }) {
      KataGoAssetCatalog.Asset asset = catalog.asset(id);
      assertEquals("experimental", asset.releaseTier());
      assertEquals(64, asset.sha256().length());
      assertTrue(catalog.assetDownloadUrl(asset).endsWith(asset.assetName()));
    }
  }
}
