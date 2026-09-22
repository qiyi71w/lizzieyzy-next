package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeDependencyScannerTest {

  @TempDir Path tempDir;

  private byte[] buildPe(
      boolean isPe32Plus,
      int machine,
      long imageBase,
      List<String> normalImports,
      List<String> delayImports) {
    int fileSize = 2048;
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
      buf.putLong(optStart + 24, imageBase);
      buf.putInt(optStart + 108, 16); // NumberOfRvaAndSizes
      dataDirStart = optStart + 112;
    } else {
      buf.putInt(optStart + 28, (int) imageBase);
      buf.putInt(optStart + 92, 16); // NumberOfRvaAndSizes
      dataDirStart = optStart + 96;
    }

    // Normal import directory at RVA 0x1000
    int importRva = normalImports.isEmpty() ? 0 : 0x1000;
    int importSize = normalImports.isEmpty() ? 0 : (normalImports.size() + 1) * 20;
    buf.putInt(dataDirStart + 8, importRva);
    buf.putInt(dataDirStart + 12, importSize);

    // Delay import directory at RVA 0x1100
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
    buf.putInt(secStart + 16, 0x400); // SizeOfRawData (1024 bytes)
    buf.putInt(secStart + 20, 0x200); // PointerToRawData (offset 512)

    // 6. Section Data at file offset 0x200 (RVA 0x1000)
    int strOffset = 0x200 + 0x180; // strings start at offset 0x380 (RVA 0x1180)
    int strRva = 0x1180;

    // Normal Imports at offset 0x200
    if (!normalImports.isEmpty()) {
      int descOffset = 0x200;
      for (String imp : normalImports) {
        buf.putInt(descOffset + 12, strRva);
        buf.putInt(descOffset + 16, 0x1080);
        byte[] strBytes = imp.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < strBytes.length; i++) {
          buf.put(strOffset + i, strBytes[i]);
        }
        buf.put(strOffset + strBytes.length, (byte) 0);
        strOffset += strBytes.length + 1;
        strRva += strBytes.length + 1;
        descOffset += 20;
      }
    }

    // Delay Imports at offset 0x300 (RVA 0x1100)
    if (!delayImports.isEmpty()) {
      int descOffset = 0x300;
      for (String delay : delayImports) {
        buf.putInt(descOffset, 1); // rvaMode = true
        buf.putInt(descOffset + 4, strRva);
        byte[] strBytes = delay.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < strBytes.length; i++) {
          buf.put(strOffset + i, strBytes[i]);
        }
        buf.put(strOffset + strBytes.length, (byte) 0);
        strOffset += strBytes.length + 1;
        strRva += strBytes.length + 1;
        descOffset += 32;
      }
    }

    return buf.array();
  }

  @Test
  void nonPeFileReturnsNonNativePeOutcome() throws IOException {
    Path script = tempDir.resolve("run.bat");
    Files.writeString(script, "@echo off\r\necho Hello\r\n");

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            script, tempDir, "", "", PeDependencyScanner.Limits.production());

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("non-native-pe", finding.outcome());
    assertEquals("not-applicable", finding.completeness());
    assertEquals("pe-import-scan", finding.evidence());
    assertEquals("run.bat", finding.dll());
    assertNull(finding.importer());
    assertFalse(evidence.readError());
  }

  @Test
  void malformedPeFileReturnsInvalidImageOutcome() throws IOException {
    Path corruptPe = tempDir.resolve("corrupt.exe");
    byte[] bytes = new byte[128];
    bytes[0] = 0x4D;
    bytes[1] = 0x5A; // MZ
    bytes[0x3C] = 0x40; // e_lfanew
    bytes[0x40] = 'P';
    bytes[0x41] = 'E'; // PE
    // But file is truncated
    Files.write(corruptPe, bytes);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            corruptPe, tempDir, "", "", PeDependencyScanner.Limits.production());

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("invalid-image", finding.outcome());
    assertEquals("partial", finding.completeness());
    assertEquals("corrupt.exe", finding.dll());
    assertFalse(evidence.readError());
  }

  @Test
  void directMissingDllReportsCorrectChainAndImporter() throws IOException {
    byte[] pe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("missing-direct.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, pe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(exe, tempDir, "", "", PeDependencyScanner.Limits.production(),
            Set.of(), true, null);

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("not-found-in-checked-search-scope", finding.outcome());
    assertEquals("missing-direct.dll", finding.dll());
    assertEquals("engine.exe", finding.importer());
    assertEquals(List.of("engine.exe", "missing-direct.dll"), finding.chain());
    assertEquals("pe-import-scan", finding.evidence());
    assertEquals("complete", finding.completeness());
    assertNotNull(finding.checkedAt());
  }

  @Test
  void indirectMissingDllReportsFullChainAndDirectImporter() throws IOException {
    // level1.dll imports missing-level2.dll
    byte[] level1Pe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of("missing-level2.dll"),
            List.of());
    Path level1Path = tempDir.resolve("level1.dll");
    Files.write(level1Path, level1Pe);

    // engine.exe imports level1.dll
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("level1.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(exe, tempDir, "", "", PeDependencyScanner.Limits.production(),
            Set.of(), true, null);

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("not-found-in-checked-search-scope", finding.outcome());
    assertEquals("missing-level2.dll", finding.dll());
    assertEquals("level1.dll", finding.importer());
    assertEquals(List.of("engine.exe", "level1.dll", "missing-level2.dll"), finding.chain());
    assertEquals("complete", finding.completeness());
  }

  @Test
  void pathOnlyResolutionFindsDllInEffectivePath() throws IOException {
    Path pathDir = tempDir.resolve("custom_path");
    Files.createDirectories(pathDir);

    byte[] depPe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Path dep = pathDir.resolve("path_exclusive.dll");
    Files.write(dep, depPe);

    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("path_exclusive.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(exe, tempDir, pathDir.toString(), "",
            PeDependencyScanner.Limits.production(), Set.of(), true, null);

    assertTrue(evidence.findings().isEmpty());
    assertTrue(evidence.checkedScope().contains("modulesScanned=2"));
  }

  @Test
  void firstCandidateWithWrongArchitectureHaltsSearchAndReportsMismatch() throws IOException {
    Path cwdDir = tempDir.resolve("cwd");
    Path pathDir = tempDir.resolve("path");
    Files.createDirectories(cwdDir);
    Files.createDirectories(pathDir);

    // cwd has 32-bit x86 candidate (wrong architecture for x64 root)
    byte[] badCandidate =
        buildPe(
            false,
            PeImportReader.IMAGE_FILE_MACHINE_I386,
            0x10000000L,
            List.of(),
            List.of());
    Files.write(cwdDir.resolve("libfoo.dll"), badCandidate);

    // PATH has 64-bit AMD64 candidate
    byte[] goodCandidate =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Files.write(pathDir.resolve("libfoo.dll"), goodCandidate);

    // Root is 64-bit AMD64
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("libfoo.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            exe, cwdDir, pathDir.toString(), "", PeDependencyScanner.Limits.production());

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("architecture-mismatch", finding.outcome());
    assertEquals("libfoo.dll", finding.dll());
    assertEquals("engine.exe", finding.importer());
    assertEquals("partial", finding.completeness());
    assertTrue(finding.detail().contains("architecture mismatch"));
  }

  @Test
  void firstCandidateDamagedHaltsSearchAndReportsInvalidImage() throws IOException {
    Path exeDir = tempDir.resolve("app");
    Path sysDir = tempDir.resolve("system");
    Files.createDirectories(exeDir);
    Files.createDirectories(sysDir);

    // exeDir has damaged DLL
    Files.write(exeDir.resolve("damaged.dll"), new byte[] {0x4D, 0x5A, 0x00});

    // sysDir has valid DLL
    byte[] validPe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Files.write(sysDir.resolve("damaged.dll"), validPe);

    // Root exe imports damaged.dll
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("damaged.dll"),
            List.of());
    Path exe = exeDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            exe, exeDir, "", tempDir.toString(), PeDependencyScanner.Limits.production());

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("invalid-image", finding.outcome());
    assertEquals("damaged.dll", finding.dll());
    assertEquals("partial", finding.completeness());
  }

  @Test
  void knownDllResolvesFromSystemDirectoryAndExcludesEngineDirCopy() throws IOException {
    Path sysDir = tempDir.resolve("System32");
    Files.createDirectories(sysDir);

    // Real system KERNEL32.dll in System32
    byte[] sysKernel =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Files.write(sysDir.resolve("kernel32.dll"), sysKernel);

    // Bogus/fake kernel32.dll in app dir
    Path exeDir = tempDir.resolve("app");
    Files.createDirectories(exeDir);
    Files.write(exeDir.resolve("kernel32.dll"), new byte[] {0x4D, 0x5A, 0x00}); // Corrupt

    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("kernel32.dll"),
            List.of());
    Path exe = exeDir.resolve("engine.exe");
    Files.write(exe, exePe);

    // With authoritative custom KnownDLLs set containing kernel32.dll
    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            exe,
            exeDir,
            "",
            tempDir.toString(),
            PeDependencyScanner.Limits.production(),
            Set.of("kernel32.dll"),
            true,
            null);

    // Corrupt copy in exeDir was ignored; valid copy in sysDir was resolved!
    assertTrue(evidence.findings().isEmpty());
  }

  @Test
  void missingKnownDllDoesNotFallBackToEngineDir() throws IOException {
    Path sysDir = tempDir.resolve("System32");
    Files.createDirectories(sysDir);

    // System32 does NOT have kernel32.dll
    // Engine dir DOES have kernel32.dll
    Path exeDir = tempDir.resolve("app");
    Files.createDirectories(exeDir);
    byte[] dummyKernel =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Files.write(exeDir.resolve("kernel32.dll"), dummyKernel);

    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("kernel32.dll"),
            List.of());
    Path exe = exeDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            exe,
            exeDir,
            "",
            tempDir.toString(),
            PeDependencyScanner.Limits.production(),
            Set.of("kernel32.dll"),
            true,
            null);

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("not-found-in-checked-search-scope", finding.outcome());
    assertEquals("kernel32.dll", finding.dll());
    assertTrue(finding.detail().contains("engine-dir copies excluded"));
  }

  @Test
  void apiSetImportsAreMarkedUnresolvedPartialAndNeverReportPhysicalMissing() throws IOException {
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("api-ms-win-core-sysinfo-l1-1-0.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            exe, tempDir, "", "", PeDependencyScanner.Limits.production());

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("unresolved-api-set", finding.outcome());
    assertEquals("api-ms-win-core-sysinfo-l1-1-0.dll", finding.dll());
    assertEquals("partial", finding.completeness());
    assertTrue(evidence.checkedScope().contains("unresolved-mechanisms=api-set-contracts"));
  }

  @Test
  void delayImportMissingEmitsSeparateFindingWithoutMarkingRequiredScanPartial() throws IOException {
    // Required import: level1.dll (present)
    byte[] level1Pe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Files.write(tempDir.resolve("level1.dll"), level1Pe);

    // Delay import: missing_delay.dll (absent)
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("level1.dll"),
            List.of("missing_delay.dll"));
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    // Using authoritative KnownDLL view so required scan is complete
    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(
            exe,
            tempDir,
            "",
            "",
            PeDependencyScanner.Limits.production(),
            Set.of(),
            true,
            null);

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("not-found-in-checked-search-scope", finding.outcome());
    assertEquals("missing_delay.dll", finding.dll());
    assertEquals("engine.exe", finding.importer());
    assertEquals("complete", finding.completeness());
    assertTrue(finding.detail().startsWith("delay-import (not required at initial load)"));
    assertTrue(evidence.checkedScope().contains("delay findings separate from required traversal"));
  }

  @Test
  void cycleInRequiredImportsTerminatesStably() throws IOException {
    // cycle_a.dll imports cycle_b.dll
    byte[] aPe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of("cycle_b.dll"),
            List.of());
    Files.write(tempDir.resolve("cycle_a.dll"), aPe);

    // cycle_b.dll imports cycle_a.dll
    byte[] bPe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x190000000L,
            List.of("cycle_a.dll"),
            List.of());
    Files.write(tempDir.resolve("cycle_b.dll"), bPe);

    // engine.exe imports cycle_a.dll
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("cycle_a.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(exe, tempDir, "", "", PeDependencyScanner.Limits.production(),
            Set.of(), true, null);

    // Cycle terminates cleanly, no missing findings
    assertTrue(evidence.findings().isEmpty());
    assertTrue(evidence.checkedScope().contains("modulesScanned=3"));
  }

  @Test
  void sharedModuleDependencyIsScannedOnce() throws IOException {
    // leaf.dll has no imports
    byte[] leafPe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Files.write(tempDir.resolve("leaf.dll"), leafPe);

    // mod1.dll imports leaf.dll
    byte[] mod1Pe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x190000000L,
            List.of("leaf.dll"),
            List.of());
    Files.write(tempDir.resolve("mod1.dll"), mod1Pe);

    // mod2.dll imports leaf.dll
    byte[] mod2Pe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x1A0000000L,
            List.of("leaf.dll"),
            List.of());
    Files.write(tempDir.resolve("mod2.dll"), mod2Pe);

    // engine.exe imports both mod1.dll and mod2.dll
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("mod1.dll", "mod2.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(exe, tempDir, "", "", PeDependencyScanner.Limits.production(),
            Set.of(), true, null);

    assertTrue(evidence.findings().isEmpty());
    // Total modules: engine.exe, mod1.dll, mod2.dll, leaf.dll = 4
    assertTrue(evidence.checkedScope().contains("modulesScanned=4"));
    // Edges checked: engine->mod1, mod1->leaf, engine->mod2, mod2->leaf = 4
    assertTrue(evidence.checkedScope().contains("edgesChecked=4"));
  }

  @Test
  void depthLimitExceededReportsLimitExceededFinding() throws IOException {
    // deep.dll
    byte[] deepPe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of("missing.dll"),
            List.of());
    Files.write(tempDir.resolve("deep.dll"), deepPe);

    // engine.exe imports deep.dll
    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("deep.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    // Set depth limit to 1 (engine -> deep is depth 1; deep -> missing would be depth 2)
    PeDependencyScanner.Limits limits = new PeDependencyScanner.Limits(1, 256, 1024 * 1024, 256);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(exe, tempDir, "", "", limits);

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("limit-exceeded", finding.outcome());
    assertEquals("partial", finding.completeness());
    assertTrue(finding.detail().contains("depth limit exceeded"));
  }

  @Test
  void moduleCountLimitExceededStopsScanning() throws IOException {
    byte[] modPe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x180000000L,
            List.of(),
            List.of());
    Files.write(tempDir.resolve("m1.dll"), modPe);
    Files.write(tempDir.resolve("m2.dll"), modPe);

    byte[] exePe =
        buildPe(
            true,
            PeImportReader.IMAGE_FILE_MACHINE_AMD64,
            0x140000000L,
            List.of("m1.dll", "m2.dll"),
            List.of());
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, exePe);

    // Limits: module count = 2 (engine.exe + m1.dll fills limit of 2)
    PeDependencyScanner.Limits limits = new PeDependencyScanner.Limits(16, 2, 1024 * 1024, 256);

    EngineStartupDiagnostics.Evidence evidence =
        PeDependencyScanner.scan(exe, tempDir, "", "", limits);

    assertEquals(1, evidence.findings().size());
    EngineStartupDiagnostics.Finding finding = evidence.findings().get(0);
    assertEquals("limit-exceeded", finding.outcome());
    assertEquals("partial", finding.completeness());
    assertTrue(finding.detail().contains("Module count limit exceeded"));
  }

  @Test
  void delayImportCannotExceedModuleLimit() throws IOException {
    byte[] module = buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x180000000L, List.of(), List.of());
    Files.write(tempDir.resolve("late.dll"), module);
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of(), List.of("late.dll")));

    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(
        exe, tempDir, "", "", new PeDependencyScanner.Limits(16, 1, 1024 * 1024, 256));
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "limit-exceeded".equals(f.outcome()) && "late.dll".equals(f.dll())
            && f.detail().contains("delay imports")));
    assertTrue(evidence.checkedScope().contains("partial-reason=result-limit"));
  }

  @Test
  void delayReadBudgetExhaustionIsNotCorruptImage() throws IOException {
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of(), List.of("late.dll")));
    Files.write(tempDir.resolve("late.dll"), buildPe(true,
        PeImportReader.IMAGE_FILE_MACHINE_AMD64, 0x180000000L, List.of(), List.of()));
    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
        "", "", new PeDependencyScanner.Limits(16, 256, 600, 256), Set.of(), true, null);
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "late.dll".equals(f.dll()) && "limit-exceeded".equals(f.outcome())
            && "partial".equals(f.completeness())), evidence.findings().toString());
    assertFalse(evidence.findings().stream().anyMatch(f -> "invalid-image".equals(f.outcome())));
  }

  @Test
  void malformedDelayCandidateRemainsInvalidImage() throws IOException {
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of(), List.of("late.dll")));
    Files.write(tempDir.resolve("late.dll"), new byte[] {'M', 'Z'});
    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
        "", "", PeDependencyScanner.Limits.production(), Set.of(), true, null);
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "late.dll".equals(f.dll()) && "invalid-image".equals(f.outcome())
            && "partial".equals(f.completeness())));
  }

  @Test
  void nestedDelayTableReadLimitIsReported() throws IOException {
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of(), List.of("late.dll")));
    Files.write(tempDir.resolve("late.dll"), buildPe(true,
        PeImportReader.IMAGE_FILE_MACHINE_AMD64, 0x180000000L,
        List.of(), List.of("nested.dll")));
    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
        "", "", new PeDependencyScanner.Limits(16, 256, 940, 256), Set.of(), true, null);
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "late.dll".equals(f.dll()) && "limit-exceeded".equals(f.outcome())
            && "partial".equals(f.completeness())), evidence.findings().toString());
    assertFalse(evidence.findings().stream().anyMatch(f -> "invalid-image".equals(f.outcome())));
  }

  @Test
  void malformedNestedDelayTableReportsPartialImageError() throws IOException {
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of(), List.of("late.dll")));
    byte[] late = buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x180000000L, List.of(), List.of("nested.dll"));
    ByteBuffer.wrap(late).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x58 + 112 + 13 * 8, 0x7fff0000);
    Files.write(tempDir.resolve("late.dll"), late);
    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
        "", "", PeDependencyScanner.Limits.production(), Set.of(), true, null);
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "late.dll".equals(f.dll()) && "invalid-image".equals(f.outcome())
            && "partial".equals(f.completeness()) && f.detail().contains("delay-import")));
  }

  @Test
  void truncatedMzIsPartialCorruptPeRatherThanNonNative() throws IOException {
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, new byte[] {'M', 'Z'});
    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
        "", "", PeDependencyScanner.Limits.production(), Set.of(), true, null);
    assertEquals("invalid-image", evidence.findings().get(0).outcome());
    assertEquals("partial", evidence.findings().get(0).completeness());
  }

  @Test
  void malformedDelayTableKeepsKnownRequiredMissingEdge() throws IOException {
    byte[] pe = buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of("required-missing.dll"), List.of("late.dll"));
    ByteBuffer.wrap(pe).order(ByteOrder.LITTLE_ENDIAN).putInt(0x58 + 112 + 13 * 8, 0x7fff0000);
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, pe);
    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
        "", "", PeDependencyScanner.Limits.production(), Set.of(), true, null);
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "required-missing.dll".equals(f.dll()) && "engine.exe".equals(f.importer())
            && f.chain().equals(List.of("engine.exe", "required-missing.dll"))),
        evidence.findings().toString());
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "partial".equals(f.completeness()) && f.detail().contains("delay")));
  }

  @Test
  void delayTableReadLimitKeepsRequiredMissingEdge() throws IOException {
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of("required-missing.dll"), List.of("late.dll")));
    EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
        "", "", new PeDependencyScanner.Limits(16, 256, 600, 256), Set.of(), true, null);
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "required-missing.dll".equals(f.dll()) && "engine.exe".equals(f.importer())));
    assertTrue(evidence.findings().stream().anyMatch(f ->
        "limit-exceeded".equals(f.outcome()) && "partial".equals(f.completeness())));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void unreadableNativePeIsReadErrorRatherThanImageDamage() throws IOException {
    Path exe = tempDir.resolve("engine.exe");
    Files.write(exe, buildPe(true, PeImportReader.IMAGE_FILE_MACHINE_AMD64,
        0x140000000L, List.of(), List.of()));
    var permissions = Files.getPosixFilePermissions(exe);
    try {
      Files.setPosixFilePermissions(exe, Set.of());
      assertFalse(Files.isReadable(exe));
      EngineStartupDiagnostics.Evidence evidence = PeDependencyScanner.scan(exe, tempDir,
          "", "", PeDependencyScanner.Limits.production(), Set.of(), true, null);
      assertEquals("read-error", evidence.findings().get(0).outcome());
      assertEquals("partial", evidence.findings().get(0).completeness());
    } finally {
      Files.setPosixFilePermissions(exe, permissions);
    }
  }
}
