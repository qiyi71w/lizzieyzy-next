package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeelazDisplayNameTest {
  @TempDir Path temp;

  @Test
  void bundledDefaultWeightUsesInternalModelName() throws Exception {
    Path root = Files.createTempDirectory("leelaz-display-default");
    Path weightsDir = Files.createDirectories(root.resolve("weights"));
    Path enginesDir = Files.createDirectories(root.resolve("engines").resolve("katago"));
    Path weightPath = Files.createFile(weightsDir.resolve("default.bin.gz"));
    writeHeader(weightPath, "kata1-zhizi-b28c512nbt-muonfd2");
    Files.write(
        enginesDir.resolve("VERSION.txt"),
        ("KataGo version: v1.16.5\nModel source: kata1-zhizi-b28c512nbt-muonfd2.bin.gz\n")
            .getBytes(StandardCharsets.UTF_8));

    String command =
        "\"/tmp/katago\" gtp -model \""
            + weightPath.toAbsolutePath()
            + "\" -config \"/tmp/gtp.cfg\"";

    assertEquals("zhizi 28B muonfd2", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  @Test
  void bundledTransformerDefaultUsesFriendlyLocalizedName() throws Exception {
    Path root = Files.createTempDirectory("leelaz-display-transformer-default");
    Path weightsDir = Files.createDirectories(root.resolve("weights"));
    Path enginesDir = Files.createDirectories(root.resolve("engines").resolve("katago"));
    Path weightPath = Files.createFile(weightsDir.resolve("default.bin.gz"));
    writeHeader(weightPath, "b10c512h8nbt3tflrs-fson-silu-rsnh");
    Files.writeString(
        enginesDir.resolve("VERSION.txt"),
        "KataGo release: v1.17.0\n"
            + "Model source: b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz\n"
            + "Model architecture: transformer\n");
    String command =
        "\"/tmp/katago\" gtp -model \""
            + weightPath.toAbsolutePath()
            + "\" -config \"/tmp/gtp.cfg\"";
    java.util.ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle = AppLocale.SIMPLIFIED_CHINESE.loadBundle();

      assertEquals("Transformer 10B 均衡版", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void downloadedOfficialWeightHidesInternalTrainingHashes() throws Exception {
    String command = commandFor("kata1-b28c512nbt-s12763923712-d5805955894", "-model ");

    assertEquals("28B", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  @Test
  void tensorRtBackendNameDoesNotReplaceWeightDisplayName() throws Exception {
    String command = commandFor("kata1-zhizi-b28c512nbt-muonfd2", "-model ");

    assertEquals("zhizi 28B muonfd2", Leelaz.friendlyEngineName("KataGo TensorRT", command));
  }

  @Test
  void remoteComputeNameUsesSavedArgsInsteadOfStaleEngineName() {
    java.util.ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle = AppLocale.SIMPLIFIED_CHINESE.loadBundle();
      assertEquals(
          "智子云算力 VIP 包月 · Transformer 10B · 均衡版 · TensorRT",
          Leelaz.friendlyEngineName("智子云算力 28B TensorRT", RemoteComputeConfig.COMMAND_ZHIZI));
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void weightDisplayHandlesMultipleSpacesAfterModelFlag() throws Exception {
    String command = commandFor("kata1-b28c512nbt-s12763923712-d5805955894", "-model  ");

    assertEquals("28B", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  @Test
  void weightDisplayHandlesEqualsStyleWeightsFlag() throws Exception {
    String command = commandFor("kata1-zhizi-b28c512nbt-muonfd2", "--weights=");

    assertEquals("zhizi 28B muonfd2", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  private String commandFor(String model, String flag) throws Exception {
    Path weight = temp.resolve("model.bin.gz");
    writeHeader(weight, model);
    return "katago gtp " + flag + "\"" + weight + "\"";
  }

  private static void writeHeader(Path weight, String model) throws Exception {
    try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(weight))) {
      output.write((model + "\n15\n22\n19\n").getBytes(StandardCharsets.US_ASCII));
    }
  }
}
