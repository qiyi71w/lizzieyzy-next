package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoWeightFileTest {
  private static final String MEDIA = "https://media.katagotraining.org/uploaded/networks/models/";

  @TempDir Path folder;

  @Test
  void onlyHttpsLinksToTheTrainingSiteWithPlainNamesAreTrusted() {
    assertTrue(KataGoWeightFile.isTrustedListing(MEDIA + "kata1/kata1-b28c512nbt-s1-d1.bin.gz"));
    assertTrue(KataGoWeightFile.isTrustedListing("https://katagotraining.org/m/g170e.txt.gz"));
    assertFalse(KataGoWeightFile.isTrustedListing("http://media.katagotraining.org/a.bin.gz"));
    assertFalse(KataGoWeightFile.isTrustedListing("https://example.com/a.bin.gz"));
    assertFalse(
        KataGoWeightFile.isTrustedListing("https://katagotraining.org.example.com/a.bin.gz"));
    assertFalse(KataGoWeightFile.isTrustedListing(MEDIA + "..%5C..%5Cevil.bin.gz"), "a backslash");
    assertFalse(KataGoWeightFile.isTrustedListing(MEDIA + "a\\..\\evil.bin.gz"), "a raw backslash");
    assertFalse(KataGoWeightFile.isTrustedListing(MEDIA + "kata1/installer.exe"));
    assertFalse(KataGoWeightFile.isTrustedListing("not a url"));
  }

  @Test
  void aNameWithAFolderIsRefusedBeforeAnythingIsWritten() throws Exception {
    assertEquals(folder.resolve("model.bin.gz"), KataGoWeightFile.inFolder(folder, "model.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "..\\evil.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "../evil.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "C:evil.bin.gz"));
    assertThrows(IOException.class, () -> KataGoWeightFile.inFolder(folder, "model.bin"));
  }

  @Test
  void aGzipWeightIsWholeOnlyWhenItReadsToTheEnd() throws Exception {
    byte[] raw = new byte[256 * 1024];
    new java.util.Random(5).nextBytes(raw);
    ByteArrayOutputStream gzip = new ByteArrayOutputStream();
    try (GZIPOutputStream output = new GZIPOutputStream(gzip)) {
      output.write(raw);
    }
    Path whole = Files.write(folder.resolve("whole.bin.gz"), gzip.toByteArray());
    Path cut =
        Files.write(
            folder.resolve("cut.bin.gz.part"),
            Arrays.copyOf(gzip.toByteArray(), gzip.size() - 100));

    assertTrue(KataGoWeightFile.isWhole(whole, null));
    assertFalse(KataGoWeightFile.isWhole(cut, null));
  }
}
