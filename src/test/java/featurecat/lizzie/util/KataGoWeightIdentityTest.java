package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.EngineData;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoWeightIdentityTest {
  @TempDir Path root;

  @Test
  void samePathReplacementUsesCurrentHeaderAcrossConsumers() throws Exception {
    Path weight = Files.createDirectories(root.resolve("weights")).resolve("default.bin.gz");
    Path metadata = Files.createDirectories(root.resolve("engines/katago")).resolve("VERSION.txt");
    Files.writeString(
        metadata, "Model source: " + KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_FILE_NAME);
    EngineData entry = new EngineData();
    entry.name = "KataGo Bundled";
    entry.commands = "katago gtp -model \"" + weight + "\"";
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry);

    for (String model :
        List.of(
            KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL,
            "b10c512h8nbt3tflrs-fson-silu-rsnh",
            "my_custom_network",
            "kata1-tf3-b11c768-s12000M-d6200M")) {
      writeHeader(weight, model);
      String display = KataGoAutoSetupHelper.resolveWeightDisplayName(model);
      assertEquals(model, KataGoAutoSetupHelper.resolveActiveWeightModelName(snapshot));
      assertEquals(display, KataGoAutoSetupHelper.resolveActiveWeightDisplayName(snapshot));
      assertEquals(display, Leelaz.friendlyEngineName(entry.name, entry.commands));
      assertEquals(
          !model.equals("my_custom_network"), KataGoAutoSetupHelper.isTransformerWeight(weight));
      assertEquals("My engine", Leelaz.friendlyEngineName("My engine", entry.commands));
    }

    Files.writeString(weight, "not a gzip model");
    assertEquals("", KataGoAutoSetupHelper.resolveActiveWeightModelName(snapshot));
    assertEquals("default.bin.gz", KataGoAutoSetupHelper.resolveActiveWeightDisplayName(snapshot));
    assertEquals("default.bin.gz", Leelaz.friendlyEngineName(entry.name, entry.commands));
    assertFalse(KataGoAutoSetupHelper.isTransformerWeight(weight));
  }

  @Test
  void nativeFormatsExposeInternalNameWithoutDependingOnFilename() throws Exception {
    for (String suffix : List.of(".bin", ".bin.gz", ".txt", ".txt.gz", ".gz")) {
      Path weight = root.resolve("renamed" + suffix);
      writeHeader(weight, "zhizi_hzy_b28_muonfd2");
      assertEquals("zhizi_hzy_b28_muonfd2", KataGoAutoSetupHelper.resolveWeightDisplayName(weight));
      assertFalse(KataGoAutoSetupHelper.isTransformerWeight(weight));
    }
  }

  @Test
  void unknownCustomNameContainingAFamilyRetainsItsIdentity() throws Exception {
    Path weight = root.resolve("default.bin");
    String model = "custom-b28c512-experiment-v2";
    writeHeader(weight, model);
    assertEquals(model, KataGoAutoSetupHelper.resolveWeightDisplayName(weight));
    assertFalse(KataGoAutoSetupHelper.isTransformerWeight(weight));
  }

  @Test
  void unlistedTransformerVersionsRemainDistinguishable() throws Exception {
    Path weight = root.resolve("default.bin");
    String first = "kata1-tf3-b11c768-s12000M-d6200M";
    String second = "kata1-tf3-b11c768-s13000M-d6300M";
    writeHeader(weight, first);
    FileTime modified = Files.getLastModifiedTime(weight);
    long size = Files.size(weight);
    assertEquals(first, KataGoAutoSetupHelper.resolveWeightDisplayName(weight));
    writeHeader(weight, second);
    Files.setLastModifiedTime(weight, modified);
    assertEquals(size, Files.size(weight));
    assertEquals(second, KataGoAutoSetupHelper.resolveWeightDisplayName(weight));
    assertTrue(KataGoAutoSetupHelper.isTransformerWeight(weight));
  }

  @Test
  void unreadableOrUnsupportedHeadersNeverBorrowArchitectureFromFilename() throws Exception {
    Path weight = root.resolve(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL + ".bin");
    for (String contents :
        List.of(
            "",
            "not-a-model\n",
            "name\ninvalid\n22\n19\n",
            "name\n15\n0\n19\n",
            "x".repeat(5000))) {
      Files.writeString(weight, contents);
      assertEquals(
          weight.getFileName().toString(), KataGoAutoSetupHelper.resolveWeightDisplayName(weight));
      assertFalse(KataGoAutoSetupHelper.isTransformerWeight(weight));
    }
    Files.delete(weight);
    assertEquals(
        weight.getFileName().toString(), KataGoAutoSetupHelper.resolveWeightDisplayName(weight));
    Path onnx = root.resolve("custom.onnx");
    writeHeader(onnx, KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL);
    assertEquals("custom.onnx", KataGoAutoSetupHelper.resolveWeightDisplayName(onnx));
    assertFalse(KataGoAutoSetupHelper.isTransformerWeight(onnx));
  }

  private static void writeHeader(Path path, String name) throws Exception {
    try (OutputStream output =
        path.toString().endsWith(".gz")
            ? new GZIPOutputStream(Files.newOutputStream(path))
            : Files.newOutputStream(path)) {
      output.write((name + "\n15\n22\n19\n").getBytes(StandardCharsets.US_ASCII));
    }
  }
}
