package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeImportReaderTest {

  @TempDir Path tempDir;

  record DelaySpec(String name, boolean rvaMode) {}

  private byte[] buildPe(
      boolean isPe32Plus,
      int machine,
      long imageBase,
      List<String> normalImports,
      List<DelaySpec> delayImports) {
    int fileSize = 1024;
    ByteBuffer buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);

    // 1. DOS Header (64 bytes)
    buf.putShort(0, (short) 0x5A4D); // 'MZ'
    buf.putInt(0x3C, 0x40); // e_lfanew = 0x40

    // 2. PE Signature (4 bytes at 0x40)
    buf.putInt(0x40, 0x00004550); // "PE\0\0"

    // 3. COFF Header (20 bytes at 0x44)
    int optSize = isPe32Plus ? 240 : 224;
    buf.putShort(0x44, (short) machine);
    buf.putShort(0x46, (short) 1); // numberOfSections = 1
    buf.putInt(0x48, 0); // timeDateStamp
    buf.putInt(0x4C, 0); // pointerToSymbolTable
    buf.putInt(0x50, 0); // numberOfSymbols
    buf.putShort(0x54, (short) optSize);
    buf.putShort(0x56, (short) 0x0022); // characteristics

    // 4. Optional Header (starts at 0x58)
    int optStart = 0x58;
    int magic = isPe32Plus ? 0x020B : 0x010B;
    buf.putShort(optStart, (short) magic);

    int dataDirStart;
    if (isPe32Plus) {
      buf.putLong(optStart + 24, imageBase); // ImageBase (8 bytes)
      buf.putInt(optStart + 108, 16); // NumberOfRvaAndSizes
      dataDirStart = optStart + 112;
    } else {
      buf.putInt(optStart + 28, (int) imageBase); // ImageBase (4 bytes)
      buf.putInt(optStart + 92, 16); // NumberOfRvaAndSizes
      dataDirStart = optStart + 96;
    }

    // Normal import directory at RVA 0x1000 (if non-empty)
    int importRva = normalImports.isEmpty() ? 0 : 0x1000;
    int importSize = normalImports.isEmpty() ? 0 : (normalImports.size() + 1) * 20;
    buf.putInt(dataDirStart + 8, importRva);
    buf.putInt(dataDirStart + 12, importSize);

    // Delay import directory at RVA 0x1100 (if non-empty)
    int delayRva = delayImports.isEmpty() ? 0 : 0x1100;
    int delaySize = delayImports.isEmpty() ? 0 : (delayImports.size() + 1) * 32;
    buf.putInt(dataDirStart + 13 * 8, delayRva);
    buf.putInt(dataDirStart + 13 * 8 + 4, delaySize);

    // 5. Section Header (40 bytes at optStart + optSize)
    int secStart = optStart + optSize;
    byte[] secName = ".rdata\0\0".getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < 8; i++) {
      buf.put(secStart + i, secName[i]);
    }
    buf.putInt(secStart + 8, 0x1000); // VirtualSize
    buf.putInt(secStart + 12, 0x1000); // VirtualAddress
    buf.putInt(secStart + 16, 0x200); // SizeOfRawData (512 bytes)
    buf.putInt(secStart + 20, 0x200); // PointerToRawData (file offset 512)

    // 6. Section Data at file offset 0x200 (RVA 0x1000)
    int strOffset = 0x200 + 0x140; // strings start at offset 0x340 (RVA 0x1140)
    int strRva = 0x1140;

    // Write Normal Imports at file offset 0x200 (RVA 0x1000)
    if (!normalImports.isEmpty()) {
      int descOffset = 0x200;
      for (String imp : normalImports) {
        buf.putInt(descOffset + 12, strRva); // nameRva
        buf.putInt(descOffset + 16, 0x1080); // firstThunk
        // write string
        byte[] strBytes = imp.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < strBytes.length; i++) {
          buf.put(strOffset + i, strBytes[i]);
        }
        buf.put(strOffset + strBytes.length, (byte) 0);
        strOffset += strBytes.length + 1;
        strRva += strBytes.length + 1;
        descOffset += 20;
      }
      // Null terminator descriptor is already 0
    }

    // Write Delay Imports at file offset 0x300 (RVA 0x1100)
    if (!delayImports.isEmpty()) {
      int descOffset = 0x300;
      for (DelaySpec delay : delayImports) {
        int grAttrs = delay.rvaMode() ? 1 : 0;
        buf.putInt(descOffset, grAttrs);
        if (delay.rvaMode()) {
          buf.putInt(descOffset + 4, strRva); // RVA mode
        } else {
          buf.putInt(descOffset + 4, (int) (imageBase + strRva)); // VA mode
        }
        byte[] strBytes = delay.name().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < strBytes.length; i++) {
          buf.put(strOffset + i, strBytes[i]);
        }
        buf.put(strOffset + strBytes.length, (byte) 0);
        strOffset += strBytes.length + 1;
        strRva += strBytes.length + 1;
        descOffset += 32;
      }
      // Null terminator descriptor is already 0
    }

    return buf.array();
  }

  @Test
  void pe32NormalImportsParsedCorrectly() throws IOException {
    byte[] peBytes =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x00400000L,
            List.of("KERNEL32.dll", "USER32.dll"),
            List.of());
    Path file = tempDir.resolve("test-pe32.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    PeImportReader.Image image = PeImportReader.read(file, budget);

    assertEquals(PeImportReader.IMAGE_FILE_MACHINE_I386, image.machine());
    assertEquals(List.of("KERNEL32.dll", "USER32.dll"), image.imports());
    assertTrue(image.delayImports().isEmpty());
    assertTrue(budget.bytesRead() > 0);
    assertTrue(budget.bytesRead() <= 64 * 1024);
  }

  @Test
  void pe32PlusDelayImportsRvaModeParsedCorrectly() throws IOException {
    byte[] peBytes =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x0000000140000000L,
            List.of("MSVCP140.dll"),
            List.of(new DelaySpec("VCRUNTIME140.dll", true)));
    Path file = tempDir.resolve("test-pe32plus.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    PeImportReader.Image image = PeImportReader.read(file, budget);

    assertEquals(PeImportReader.IMAGE_FILE_MACHINE_AMD64, image.machine());
    assertEquals(List.of("MSVCP140.dll"), image.imports());
    assertEquals(List.of("VCRUNTIME140.dll"), image.delayImports());
  }

  @Test
  void pe32DelayImportsVaModeParsedCorrectly() throws IOException {
    byte[] peBytes =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x00400000L,
            List.of(),
            List.of(new DelaySpec("SHELL32.dll", false)));
    Path file = tempDir.resolve("test-pe32-delay-va.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    PeImportReader.Image image = PeImportReader.read(file, budget);

    assertTrue(image.imports().isEmpty());
    assertEquals(List.of("SHELL32.dll"), image.delayImports());
  }

  @Test
  void peArm64MachineParsedCorrectly() throws IOException {
    byte[] peBytes =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_ARM64,
            0x0000000140000000L,
            List.of("ntdll.dll"),
            List.of());
    Path file = tempDir.resolve("test-arm64.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    PeImportReader.Image image = PeImportReader.read(file, budget);

    assertEquals(PeImportReader.IMAGE_FILE_MACHINE_ARM64, image.machine());
    assertEquals(List.of("ntdll.dll"), image.imports());
  }

  @Test
  void invalidDosSignatureThrowsConciseCause() throws IOException {
    byte[] peBytes =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x00400000L,
            List.of("KERNEL32.dll"),
            List.of());
    peBytes[0] = 'X';
    peBytes[1] = 'Y';
    Path file = tempDir.resolve("invalid-dos.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    IOException ex = assertThrows(IOException.class, () -> PeImportReader.read(file, budget));
    assertTrue(ex.getMessage().contains("Invalid DOS signature"), ex.getMessage());
  }

  @Test
  void invalidPeSignatureThrowsConciseCause() throws IOException {
    byte[] peBytes =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x00400000L,
            List.of("KERNEL32.dll"),
            List.of());
    peBytes[0x40] = 'N';
    peBytes[0x41] = 'O';
    Path file = tempDir.resolve("invalid-pe.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    IOException ex = assertThrows(IOException.class, () -> PeImportReader.read(file, budget));
    assertTrue(ex.getMessage().contains("Invalid PE signature"), ex.getMessage());
  }

  @Test
  void truncatedFileThrowsConciseCause() throws IOException {
    Path file = tempDir.resolve("truncated.exe");
    Files.write(file, new byte[32]);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    IOException ex = assertThrows(IOException.class, () -> PeImportReader.read(file, budget));
    assertTrue(ex.getMessage().contains("File too small for DOS header"), ex.getMessage());
  }

  @Test
  void invalidRvaThrowsConciseCause() throws IOException {
    byte[] peBytes =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x00400000L,
            List.of("KERNEL32.dll"),
            List.of());
    // Modify Import RVA in Optional Header (offset 0x58 + 96 + 8 = 0xC0) to point to 0x9000 (unmapped)
    ByteBuffer.wrap(peBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0xC0, 0x9000);
    Path file = tempDir.resolve("invalid-rva.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    IOException ex = assertThrows(IOException.class, () -> PeImportReader.read(file, budget));
    assertTrue(ex.getMessage().contains("Invalid RVA"), ex.getMessage());
  }

  @Test
  void importDirectoryCannotReadDescriptorsBeyondDeclaredRange() throws IOException {
    byte[] pe = buildPe(false, PeImportReader.IMAGE_FILE_MACHINE_I386, 0x00400000L,
        List.of("first.dll", "second.dll"), List.of());
    // Only the first descriptor is declared; reading a second one would invent evidence.
    ByteBuffer.wrap(pe).order(ByteOrder.LITTLE_ENDIAN).putInt(0xC4, 20);
    Path file = Files.write(tempDir.resolve("short-directory.exe"), pe);
    IOException error = assertThrows(IOException.class,
        () -> PeImportReader.read(file, new PeImportReader.Budget(64 * 1024)));
    assertTrue(error.getMessage().contains("terminating descriptor"));
  }

  @Test
  void readBudgetEnforcesByteLimit() throws IOException {
    byte[] peBytes =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x00400000L,
            List.of("KERNEL32.dll"),
            List.of());
    Path file = tempDir.resolve("budget-test.exe");
    Files.write(file, peBytes);

    // Limit to 70 bytes: DOS header (64) succeeds, but reading COFF header (24 -> 88) exceeds byte-limit
    PeImportReader.Budget budget = new PeImportReader.Budget(70);
    IOException ex = assertThrows(IOException.class, () -> PeImportReader.read(file, budget));
    assertTrue(ex.getMessage().contains("byte-limit"), ex.getMessage());
    assertEquals(64, budget.bytesRead());
  }

  @Test
  void threadInterruptionThrowsTimeout() throws IOException {
    byte[] peBytes =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x00400000L,
            List.of("KERNEL32.dll"),
            List.of());
    Path file = tempDir.resolve("interrupt-test.exe");
    Files.write(file, peBytes);

    PeImportReader.Budget budget = new PeImportReader.Budget(64 * 1024);
    Thread.currentThread().interrupt();
    try {
      IOException ex = assertThrows(IOException.class, () -> PeImportReader.read(file, budget));
      assertTrue(ex.getMessage().contains("timeout"), ex.getMessage());
    } finally {
      Thread.interrupted(); // clear interrupt status
    }
  }
}
