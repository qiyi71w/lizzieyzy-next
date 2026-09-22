package featurecat.lizzie.analysis;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded static dependency scanner for Windows native PE binaries.
 *
 * <p>Traverses required imports recursively using {@link PeImportReader}, applying standard
 * Windows unpackaged native process search priority (authoritative KnownDLLs, application dir,
 * system dir, Windows dir, cwd, effective PATH). Delay imports are inspected separately and not
 * required for initial process loading.
 */
public final class PeDependencyScanner {

  public record Limits(int depth, int modules, long bytes, int findings) {
    public Limits {
      if (depth < 1 || modules < 1 || bytes < 1 || findings < 1) {
        throw new IllegalArgumentException("All scanner limits must be positive");
      }
    }

    public static Limits production() {
      return new Limits(16, 256, 32L * 1024 * 1024, 256);
    }
  }

  private static final Set<String> BASELINE_KNOWN_DLLS =
      Set.of(
          "kernel32.dll",
          "kernelbase.dll",
          "ntdll.dll",
          "user32.dll",
          "gdi32.dll",
          "gdi32full.dll",
          "advapi32.dll",
          "rpcrt4.dll",
          "ole32.dll",
          "oleaut32.dll",
          "shell32.dll",
          "shlwapi.dll",
          "combase.dll",
          "ws2_32.dll",
          "sechost.dll",
          "imm32.dll",
          "msvcrt.dll",
          "comdlg32.dll",
          "nsi.dll");

  @FunctionalInterface
  interface ApiSetResolver {
    String resolveHost(String apiSetDll);
  }

  record KnownDllsResult(Set<String> knownDlls, boolean authoritative, String scopeNote) {}

  private PeDependencyScanner() {}

  /**
   * Scans the static PE dependencies of the specified executable.
   *
   * @param executable path to the Windows PE executable
   * @param cwd working directory context for the target process
   * @param effectivePath effective PATH search directories (separated by ';' or ':')
   * @param systemRoot Windows SystemRoot directory (e.g. C:\Windows)
   * @param limits traversal, memory, and finding limits
   * @return collected dependency evidence snapshot
   */
  public static EngineStartupDiagnostics.Evidence scan(
      Path executable, Path cwd, String effectivePath, String systemRoot, Limits limits) {
    return scan(executable, cwd, effectivePath, systemRoot, limits, null, null, null);
  }

  static EngineStartupDiagnostics.Evidence scan(
      Path executable,
      Path cwd,
      String effectivePath,
      String systemRoot,
      Limits limits,
      Set<String> customKnownDlls,
      Boolean customKnownAuthoritative,
      ApiSetResolver customApiSetResolver) {

    Objects.requireNonNull(executable, "executable must not be null");
    Limits actualLimits = limits != null ? limits : Limits.production();
    Instant scanTime = Instant.now();

    Path absExecutable = executable.toAbsolutePath().normalize();
    String exeFileName =
        absExecutable.getFileName() != null
            ? absExecutable.getFileName().toString()
            : absExecutable.toString();

    // 1. Initial file check
    if (!Files.exists(absExecutable) || !Files.isRegularFile(absExecutable)) {
      String scope = buildInitialScope(scanTime, false, false, "target-file-unavailable");
      EngineStartupDiagnostics.Finding finding =
          new EngineStartupDiagnostics.Finding(
              "target-unavailable",
              exeFileName,
              null,
              List.of(exeFileName),
              "pe-import-scan",
              "not-applicable",
              scope,
              "Target executable file not found or not a regular file: " + absExecutable,
              scanTime);
      return new EngineStartupDiagnostics.Evidence(List.of(finding), scope, false);
    }

    PeImportReader.Budget budget = new PeImportReader.Budget(actualLimits.bytes());
    PeImportReader.Image rootImage;
    try {
      rootImage = PeImportReader.read(absExecutable, budget);
    } catch (ClosedByInterruptException e) {
      String scope = buildInitialScope(scanTime, false, false, "interrupted");
      EngineStartupDiagnostics.Finding finding =
          new EngineStartupDiagnostics.Finding(
              "timeout",
              exeFileName,
              null,
              List.of(exeFileName),
              "pe-import-scan",
              "partial",
              scope,
              "Scan interrupted/timeout during root executable read: " + e.getMessage(),
              scanTime);
      return new EngineStartupDiagnostics.Evidence(List.of(finding), scope, false);
    } catch (IOException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      if (Thread.currentThread().isInterrupted() || msg.contains("timeout")) {
        String scope = buildInitialScope(scanTime, false, false, "timeout");
        EngineStartupDiagnostics.Finding finding =
            new EngineStartupDiagnostics.Finding(
                "timeout",
                exeFileName,
                null,
                List.of(exeFileName),
                "pe-import-scan",
                "partial",
                scope,
                "Scan timed out reading root executable: " + msg,
                scanTime);
        return new EngineStartupDiagnostics.Evidence(List.of(finding), scope, false);
      }
      if (msg.contains("byte-limit")) {
        String scope = buildInitialScope(scanTime, false, false, "byte-limit-exceeded");
        EngineStartupDiagnostics.Finding finding =
            new EngineStartupDiagnostics.Finding(
                "limit-exceeded",
                exeFileName,
                null,
                List.of(exeFileName),
                "pe-import-scan",
                "partial",
                scope,
                "Read budget exceeded byte-limit (" + actualLimits.bytes() + "): " + msg,
                scanTime);
        return new EngineStartupDiagnostics.Evidence(List.of(finding), scope, false);
      }
      boolean nonPe = msg.contains("DOS signature") || msg.startsWith("File too small for DOS header");
      boolean unreadable = e instanceof java.nio.file.AccessDeniedException
          || msg.contains("Permission denied") || msg.contains("Access is denied");
      String outcome = nonPe ? "non-native-pe" : unreadable ? "read-error" : "invalid-image";
      String completeness = nonPe ? "not-applicable" : "partial";
      String scope = buildInitialScope(scanTime, false, false, outcome);
      EngineStartupDiagnostics.Finding finding =
          new EngineStartupDiagnostics.Finding(
              outcome,
              exeFileName,
              null,
              List.of(exeFileName),
              "pe-import-scan",
              completeness,
              scope,
              (nonPe ? "Not a native Windows PE binary: "
                  : unreadable ? "Cannot read PE image: " : "Invalid PE image: ") + msg,
              scanTime);
      return new EngineStartupDiagnostics.Evidence(List.of(finding), scope, false);
    }

    int targetMachine = rootImage.machine();

    // 2. Query KnownDLLs
    KnownDllsResult knownResult;
    if (customKnownDlls != null) {
      boolean auth = customKnownAuthoritative == null || customKnownAuthoritative;
      knownResult =
          new KnownDllsResult(
              customKnownDlls, auth, auth ? "custom-known-dlls" : "custom-known-dlls-unresolved");
    } else {
      knownResult = queryKnownDlls(targetMachine);
    }

    // 3. Resolve search directories
    Path exeDir =
        absExecutable.getParent() != null
            ? absExecutable.getParent()
            : Path.of("").toAbsolutePath();
    Path actualCwd =
        cwd != null ? cwd.toAbsolutePath().normalize() : Path.of("").toAbsolutePath();

    Path windowsDir = null;
    Path systemDir = null;
    if (systemRoot != null && !systemRoot.isBlank()) {
      windowsDir = Path.of(systemRoot).toAbsolutePath().normalize();
      if (targetMachine == PeImportReader.IMAGE_FILE_MACHINE_I386) {
        Path sysWow64 = windowsDir.resolve("SysWOW64");
        if (Files.exists(sysWow64) && Files.isDirectory(sysWow64)) {
          systemDir = sysWow64;
        } else {
          systemDir = windowsDir.resolve("System32");
        }
      } else {
        systemDir = windowsDir.resolve("System32");
      }
    }

    List<Path> pathDirs = parseEffectivePath(effectivePath, actualCwd);

    // Assemble ordered standard search directories:
    // 1. exeDir, 2. systemDir, 3. windowsDir, 4. cwd, 5. effective PATH dirs
    LinkedHashSet<Path> orderedDirs = new LinkedHashSet<>();
    orderedDirs.add(exeDir);
    if (systemDir != null) orderedDirs.add(systemDir);
    if (windowsDir != null) orderedDirs.add(windowsDir);
    orderedDirs.add(actualCwd);
    orderedDirs.addAll(pathDirs);
    List<Path> searchDirs = List.copyOf(orderedDirs);

    // 4. Execute traversal
    Traversal traversal =
        new Traversal(
            absExecutable,
            exeFileName,
            exeDir,
            systemDir,
            windowsDir,
            actualCwd,
            searchDirs,
            actualLimits,
            budget,
            targetMachine,
            knownResult,
            customApiSetResolver,
            scanTime);

    return traversal.run(rootImage);
  }

  private static List<Path> parseEffectivePath(String effectivePath, Path cwd) {
    if (effectivePath == null || effectivePath.isBlank()) {
      return List.of();
    }
    List<Path> dirs = new ArrayList<>();
    String[] parts =
        effectivePath.contains(";")
            ? effectivePath.split(";")
            : effectivePath.split(File.pathSeparator);
    for (String part : parts) {
      String trimmed = part.trim();
      if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
      }
      if (!trimmed.isEmpty()) {
        try {
          Path p = Path.of(trimmed);
          if (!p.isAbsolute()) {
            p = cwd.resolve(p);
          }
          dirs.add(p.toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
      }
    }
    return Collections.unmodifiableList(dirs);
  }

  private static KnownDllsResult queryKnownDlls(int targetMachine) {
    if (!Platform.isWindows()) {
      return new KnownDllsResult(
          BASELINE_KNOWN_DLLS, false, "non-windows-host-known-dlls-unresolved-view");
    }
    try {
      Set<String> dlls = new HashSet<>();
      if (targetMachine == PeImportReader.IMAGE_FILE_MACHINE_I386) {
        boolean read32 = false;
        try {
          if (Advapi32Util.registryKeyExists(
              WinReg.HKEY_LOCAL_MACHINE,
              "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\KnownDLLs32")) {
            var values =
                Advapi32Util.registryGetValues(
                    WinReg.HKEY_LOCAL_MACHINE,
                    "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\KnownDLLs32");
            extractDllNames(values, dlls);
            read32 = true;
          }
        } catch (Throwable ignored) {
        }
        if (!read32) {
          return new KnownDllsResult(
              Collections.unmodifiableSet(dlls), false,
              "target-x86-KnownDLLs32-registry-view-unavailable");
        }
      } else {
        int sam = WinNT.KEY_READ | WinNT.KEY_WOW64_64KEY;
        var values =
            Advapi32Util.registryGetValues(
                WinReg.HKEY_LOCAL_MACHINE,
                "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\KnownDLLs",
                sam);
        extractDllNames(values, dlls);
      }
      return new KnownDllsResult(
          Collections.unmodifiableSet(dlls), true, "authoritative-known-dlls-registry-view");
    } catch (Throwable t) {
      return new KnownDllsResult(
          BASELINE_KNOWN_DLLS, false, "known-dlls-registry-view-query-failed: " + t.getMessage());
    }
  }

  private static void extractDllNames(Map<String, Object> values, Set<String> out) {
    if (values == null) return;
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      String key = entry.getKey();
      if ("DllDirectory".equalsIgnoreCase(key) || "DllDirectory32".equalsIgnoreCase(key)) {
        continue;
      }
      Object val = entry.getValue();
      if (val instanceof String s && !s.isBlank()) {
        String name = s.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".dll")) name += ".dll";
        out.add(name);
      } else if (key != null && !key.isBlank()) {
        String name = key.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".dll")) name += ".dll";
        out.add(name);
      }
    }
  }

  private static boolean isApiSet(String dllName) {
    if (dllName == null) return false;
    String lower = dllName.toLowerCase(Locale.ROOT);
    return lower.startsWith("api-ms-win-") || lower.startsWith("ext-ms-");
  }

  private static String buildInitialScope(
      Instant scanTime, boolean authoritativeKnown, boolean hasApiSet, String status) {
    StringBuilder scope = new StringBuilder();
    scope.append("pe-import-scan; search-order=KnownDLLs->exeDir->system->windows->cwd->effectivePATH");
    scope.append("; scannedAt=").append(scanTime);
    scope.append("; dynamic-LoadLibrary-excluded");
    scope.append("; post-failure-scan-time-only");
    scope.append("; delay findings separate from required traversal");
    scope.append(
        "; limitations: SxS-not-evaluated, package-dependencies-excluded,"
            + " already-loaded-modules-excluded, changed-search-policy-excluded,"
            + " wow64-redirection-limitations");
    scope.append("; status=").append(status);
    return scope.toString();
  }

  private static final class Traversal {
    final Path executable;
    final String exeName;
    final Path exeDir;
    final Path systemDir;
    final Path windowsDir;
    final Path cwd;
    final List<Path> searchDirs;
    final Limits limits;
    final PeImportReader.Budget budget;
    final int targetMachine;
    final KnownDllsResult knownResult;
    final ApiSetResolver apiSetResolver;
    final Instant scanTime;

    final List<EngineStartupDiagnostics.Finding> findings = new ArrayList<>();
    final Map<Path, PeImportReader.Image> visitedModules = new HashMap<>();
    final Set<Path> activeAncestorStack = new HashSet<>();
    final Set<Path> cleanModules = new HashSet<>();

    int modulesScannedCount = 0;
    int edgesCount = 0;
    boolean partialOverall = false;
    boolean limitExceeded = false;
    boolean interrupted = false;
    boolean hasUnresolvedApiSets = false;
    boolean lookupFailed;

    Traversal(
        Path executable,
        String exeName,
        Path exeDir,
        Path systemDir,
        Path windowsDir,
        Path cwd,
        List<Path> searchDirs,
        Limits limits,
        PeImportReader.Budget budget,
        int targetMachine,
        KnownDllsResult knownResult,
        ApiSetResolver apiSetResolver,
        Instant scanTime) {
      this.executable = executable;
      this.exeName = exeName;
      this.exeDir = exeDir;
      this.systemDir = systemDir;
      this.windowsDir = windowsDir;
      this.cwd = cwd;
      this.searchDirs = searchDirs;
      this.limits = limits;
      this.budget = budget;
      this.targetMachine = targetMachine;
      this.knownResult = knownResult;
      this.apiSetResolver = apiSetResolver;
      this.scanTime = scanTime;
      if (!knownResult.authoritative()) {
        this.partialOverall = true;
      }
    }

    EngineStartupDiagnostics.Evidence run(PeImportReader.Image rootImage) {
      visitedModules.put(executable, rootImage);
      activeAncestorStack.add(executable);
      modulesScannedCount = 1;

      // 1. Traverse required dependencies recursively
      for (String requiredImport : rootImage.imports()) {
        traverseRequired(requiredImport, exeName, List.of(exeName), 1);
        if (limitExceeded || interrupted || lookupFailed) break;
      }
      if (!knownResult.authoritative() && !rootImage.imports().isEmpty() && findings.isEmpty())
        recordFinding("known-dll-view-unresolved", null, exeName, List.of(exeName), "partial",
            "Target KnownDLL registry view unavailable: " + knownResult.scopeNote());

      if (rootImage.delayError() != null && !limitExceeded && !interrupted)
        handleCandidateReadError(rootImage.delayError(), executable, exeName, exeName,
            List.of(exeName), true);
      // 2. Separately inspect root delay imports
      checkDelayImports(rootImage.delayImports(), exeName, List.of(exeName));

      String checkedScope = buildFinalScope();
      return new EngineStartupDiagnostics.Evidence(findings, checkedScope, false);
    }

    private void traverseRequired(
        String dllName, String importer, List<String> currentChain, int currentDepth) {
      if (limitExceeded || interrupted || lookupFailed) return;

      if (Thread.currentThread().isInterrupted()) {
        recordFinding(
            "timeout",
            dllName,
            importer,
            append(currentChain, dllName),
            "partial",
            "Scan interrupted/timeout at " + dllName);
        interrupted = true;
        partialOverall = true;
        return;
      }

      if (currentDepth > limits.depth()) {
        recordFinding(
            "limit-exceeded",
            dllName,
            importer,
            append(currentChain, dllName),
            "partial",
            "Traversal depth limit exceeded (" + limits.depth() + ") at " + dllName);
        partialOverall = true;
        limitExceeded = true;
        return;
      }

      if (findings.size() >= limits.findings()) {
        partialOverall = true;
        limitExceeded = true;
        return;
      }

      if (edgesCount >= (long) limits.modules() * 16) {
        recordFinding("limit-exceeded", dllName, importer, append(currentChain, dllName),
            "partial", "Dependency reference limit reached");
        partialOverall = true;
        limitExceeded = true;
        return;
      }
      edgesCount++;
      List<String> nextChain = append(currentChain, dllName);

      // Check API Set
      if (isApiSet(dllName)) {
        String host = apiSetResolver != null ? apiSetResolver.resolveHost(dllName) : null;
        if (host == null) {
          hasUnresolvedApiSets = true;
          partialOverall = true;
          recordFinding(
              "unresolved-api-set",
              dllName,
              importer,
              nextChain,
              "partial",
              "API Set contract (" + dllName + ") unresolved; reliable host mapping unavailable");
          return;
        }
        dllName = host;
      }

      String lowerDll = dllName.toLowerCase(Locale.ROOT);
      boolean isKnown = knownResult.knownDlls().contains(lowerDll);

      if (isKnown) {
        // KnownDLLs: search ONLY system directory, never exeDir or PATH.
        if (systemDir == null) {
          recordFinding(
              "not-found-in-checked-search-scope",
              dllName,
              importer,
              nextChain,
              "partial",
              "KnownDLL " + dllName + " cannot be resolved: system directory unavailable");
          partialOverall = true;
          return;
        }

        Path candidate = lookup(systemDir, dllName, importer, nextChain);
        if (lookupFailed) return;
        if (candidate == null) {
          // Exclude any engine directory same-name file from acting as a KnownDLL hit
          recordFinding(
              "not-found-in-checked-search-scope",
              dllName,
              importer,
              nextChain,
              knownResult.authoritative() ? "complete" : "partial",
              "KnownDLL "
                  + dllName
                  + " not found in target system directory "
                  + systemDir
                  + " (engine-dir copies excluded)");
          if (!knownResult.authoritative()) {
            partialOverall = true;
          }
          return;
        }

        // Candidate found in system directory
        inspectAndRecurse(candidate, dllName, importer, currentChain, currentDepth);
        return;
      }

      // Normal DLL search priority: exeDir, systemDir, windowsDir, cwd, effective PATH
      Path candidate = null;
      for (Path dir : searchDirs) {
        candidate = lookup(dir, dllName, importer, nextChain);
        if (candidate != null) {
          break; // First existing candidate halts search!
        }
      }
      if (lookupFailed) return;

      if (candidate == null) {
        recordFinding(
            "not-found-in-checked-search-scope",
            dllName,
            importer,
            nextChain,
            partialOverall ? "partial" : "complete",
            "DLL not found in checked search scope: " + dllName);
        return;
      }

      inspectAndRecurse(candidate, dllName, importer, currentChain, currentDepth);
    }

    private void inspectAndRecurse(
        Path candidate,
        String dllName,
        String importer,
        List<String> currentChain,
        int currentDepth) {
      Path normalized = candidate.toAbsolutePath().normalize();
      List<String> nextChain = append(currentChain, dllName);

      // Check cycle on active ancestor stack
      if (activeAncestorStack.contains(normalized)) {
        // Finite cycle termination: do not recurse into active ancestors
        return;
      }

      // Memoized module check
      PeImportReader.Image candidateImage = visitedModules.get(normalized);
      if (candidateImage == null) {
        if (modulesScannedCount >= limits.modules()) {
          recordFinding(
              "limit-exceeded",
              dllName,
              importer,
              nextChain,
              "partial",
              "Module count limit exceeded (" + limits.modules() + ") at " + dllName);
          partialOverall = true;
          limitExceeded = true;
          return;
        }

        try {
          candidateImage = PeImportReader.read(normalized, budget);
          modulesScannedCount++;
          visitedModules.put(normalized, candidateImage);
        } catch (ClosedByInterruptException e) {
          recordFinding(
              "timeout",
              dllName,
              importer,
              nextChain,
              "partial",
              "Scan interrupted/timeout reading candidate " + normalized + ": " + e.getMessage());
          interrupted = true;
          partialOverall = true;
          return;
        } catch (IOException e) {
          handleCandidateReadError(e, normalized, dllName, importer, nextChain, false);
          return;
        }
      }

      // Architecture match check
      if (candidateImage.machine() != targetMachine) {
        recordFinding(
            "architecture-mismatch",
            dllName,
            importer,
            nextChain,
            "partial",
            "First candidate at "
                + normalized
                + " has architecture mismatch: expected 0x"
                + Integer.toHexString(targetMachine)
                + " but found 0x"
                + Integer.toHexString(candidateImage.machine()));
        partialOverall = true;
        return;
      }

      // If module is already known clean, we don't need to re-recurse its full clean subtree
      if (cleanModules.contains(normalized)) {
        return;
      }

      int findingsBeforeRecurse = findings.size();

      // Recurse into required imports of candidate
      activeAncestorStack.add(normalized);
      String candidateFileName =
          normalized.getFileName() != null ? normalized.getFileName().toString() : dllName;
      try {
        for (String subImport : candidateImage.imports()) {
          traverseRequired(subImport, candidateFileName, nextChain, currentDepth + 1);
          if (limitExceeded || interrupted) break;
        }
      } finally {
        activeAncestorStack.remove(normalized);
      }

      if (candidateImage.delayError() != null && !limitExceeded && !interrupted)
        handleCandidateReadError(candidateImage.delayError(), normalized, dllName, candidateFileName,
            nextChain, true);
      // Separately inspect candidate's delay imports
      checkDelayImports(candidateImage.delayImports(), candidateFileName, nextChain);

      if (findings.size() == findingsBeforeRecurse && !limitExceeded && !interrupted) {
        cleanModules.add(normalized);
      }
    }

    private void checkDelayImports(
        List<String> delayImports, String importer, List<String> currentChain) {
      if (delayImports == null || delayImports.isEmpty()) return;

      for (String delayDll : delayImports) {
        if (limitExceeded || interrupted) break;

        if (edgesCount >= (long) limits.modules() * 16) {
          recordFinding("limit-exceeded", delayDll, importer, append(currentChain, delayDll),
              "partial", "Dependency reference limit reached in delay imports");
          partialOverall = true;
          limitExceeded = true;
          return;
        }
        edgesCount++;
        if (Thread.currentThread().isInterrupted()) {
          interrupted = true;
          return;
        }

        List<String> chain = append(currentChain, delayDll);

        if (isApiSet(delayDll)) {
          String host = apiSetResolver != null ? apiSetResolver.resolveHost(delayDll) : null;
          if (host == null) {
            hasUnresolvedApiSets = true;
            recordFinding(
                "unresolved-api-set",
                delayDll,
                importer,
                chain,
                "partial",
                "delay-import (not required at initial load); API Set contract ("
                    + delayDll
                    + ") unresolved");
            continue;
          }
          delayDll = host;
        }

        String lowerDll = delayDll.toLowerCase(Locale.ROOT);
        boolean isKnown = knownResult.knownDlls().contains(lowerDll);

        Path candidate = null;
        if (isKnown) {
          if (systemDir != null) {
            candidate = lookup(systemDir, delayDll, importer, chain);
          }
        } else {
          for (Path dir : searchDirs) {
            candidate = lookup(dir, delayDll, importer, chain);
            if (candidate != null) break;
          }
        }
        if (lookupFailed) return;

        if (candidate == null) {
          // Delay import missing: report finding with completeness "complete" within delay check,
          // but do NOT mark required scan partial solely for absent delay DLL.
          recordFinding(
              "not-found-in-checked-search-scope",
              delayDll,
              importer,
              chain,
              "complete",
              "delay-import (not required at initial load): " + delayDll);
          continue;
        }

        Path normalized = candidate.toAbsolutePath().normalize();
        PeImportReader.Image img = visitedModules.get(normalized);
        if (img == null) {
          if (modulesScannedCount >= limits.modules()) {
            recordFinding("limit-exceeded", delayDll, importer, chain, "partial",
                "Module count limit exceeded (" + limits.modules() + ") in delay imports");
            partialOverall = true;
            limitExceeded = true;
            return;
          }
          try {
            img = PeImportReader.read(normalized, budget);
            modulesScannedCount++;
            visitedModules.put(normalized, img);
          } catch (IOException e) {
            handleCandidateReadError(e, normalized, delayDll, importer, chain, true);
            if (limitExceeded || interrupted) return;
            continue;
          }
          if (img.delayError() != null) {
            handleCandidateReadError(img.delayError(), normalized, delayDll, importer, chain, true);
            if (limitExceeded || interrupted) return;
          }
        }

        if (img.machine() != targetMachine) {
          recordFinding(
              "architecture-mismatch",
              delayDll,
              importer,
              chain,
              "partial",
              "delay-import (not required at initial load); first candidate at "
                  + normalized
                  + " has architecture mismatch: expected 0x"
                  + Integer.toHexString(targetMachine)
                  + " but found 0x"
                  + Integer.toHexString(img.machine()));
        }
      }
    }

    private void handleCandidateReadError(
        IOException e, Path candidate, String dllName, String importer, List<String> chain,
        boolean delayImport) {
      String prefix = delayImport ? "delay-import (not required at initial load); " : "";
      String msg = e.getMessage() == null ? "" : e.getMessage();
      if (msg.contains("byte-limit")) {
        recordFinding(
            "limit-exceeded",
            dllName,
            importer,
            chain,
            "partial",
            prefix + "Read budget exceeded byte-limit (" + limits.bytes() + "): " + msg);
        partialOverall = true;
        limitExceeded = true;
      } else if (msg.contains("timeout") || Thread.currentThread().isInterrupted()) {
        recordFinding(
            "timeout",
            dllName,
            importer,
            chain,
            "partial",
            prefix + "Scan interrupted/timeout reading candidate " + candidate + ": " + msg);
        partialOverall = true;
        interrupted = true;
      } else if (!(e instanceof java.nio.file.AccessDeniedException)
          && (msg.contains("DOS signature") || msg.contains("PE signature")
              || msg.contains("Invalid") || msg.contains("Truncated")
              || msg.contains("truncated") || msg.contains("header")
              || msg.contains("RVA") || msg.contains("directory"))) {
        recordFinding(
            "invalid-image",
            dllName,
            importer,
            chain,
            "partial",
            prefix + "First candidate image at " + candidate + " is invalid/damaged: " + msg);
        partialOverall = true;
      } else {
        recordFinding(
            "read-error",
            dllName,
            importer,
            chain,
            "partial",
            prefix + "Read error for candidate " + candidate + ": " + msg);
        partialOverall = true;
      }
    }

    private void recordFinding(
        String outcome,
        String dll,
        String importer,
        List<String> chain,
        String completeness,
        String detail) {
      if (findings.size() >= limits.findings()) {
        partialOverall = true;
        limitExceeded = true;
        return;
      }
      findings.add(
          new EngineStartupDiagnostics.Finding(
              outcome,
              dll,
              importer,
              chain,
              "pe-import-scan",
              completeness,
              buildCurrentScope(),
              detail,
              Instant.now()));
    }

    private String buildCurrentScope() {
      return buildScopeText(false);
    }

    private String buildFinalScope() {
      return buildScopeText(true);
    }

    private String buildScopeText(boolean finalSummary) {
      StringBuilder sb = new StringBuilder();
      sb.append("pe-import-scan; search-order=KnownDLLs->exeDir->system->windows->cwd->effectivePATH");
      sb.append("; scannedAt=").append(scanTime);
      sb.append("; dynamic-LoadLibrary-excluded");
      sb.append("; post-failure-scan-time-only");
      sb.append("; delay findings separate from required traversal");
      sb.append(
          "; limitations: SxS-not-evaluated, package-dependencies-excluded,"
              + " already-loaded-modules-excluded, changed-search-policy-excluded,"
              + " wow64-redirection-limitations");
      if (limitExceeded) sb.append("; partial-reason=result-limit");
      sb.append("; known-dlls-view=")
          .append(knownResult.authoritative() ? "authoritative" : "unresolved");
      if (hasUnresolvedApiSets) {
        sb.append("; unresolved-mechanisms=api-set-contracts");
      }
      if (finalSummary) {
        sb.append("; modulesScanned=").append(modulesScannedCount);
        sb.append("; edgesChecked=").append(edgesCount);
        sb.append("; bytesRead=").append(budget.bytesRead());
      }
      return sb.toString();
    }

    private Path lookup(Path dir, String dll, String importer, List<String> chain) {
      try {
        return resolveInDir(dir, dll);
      } catch (IOException failure) {
        recordFinding("read-error", dll, importer, chain, "partial",
            "Cannot inspect search directory " + dir + ": " + failure.getMessage());
        partialOverall = true;
        lookupFailed = true;
        return null;
      }
    }

    private static Path resolveInDir(Path dir, String fileName) throws IOException {
      if (dir == null || Files.notExists(dir)) return null;
      Path direct = dir.resolve(fileName);
      if (Platform.isWindows() && Files.notExists(direct)) return null;
      if (Files.isRegularFile(direct)) return direct;
      int entries = 0;
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
        for (Path entry : stream) {
          if (++entries > 4096) throw new IOException("directory-limit");
          if (Thread.currentThread().isInterrupted()) throw new IOException("timeout");
          if (entry.getFileName().toString().equalsIgnoreCase(fileName)
              && Files.isRegularFile(entry)) return entry;
        }
      } catch (java.nio.file.DirectoryIteratorException failure) {
        throw new IOException("Directory listing failed", failure);
      }
      return null;
    }

    private static List<String> append(List<String> chain, String next) {
      List<String> list = new ArrayList<>(chain.size() + 1);
      list.addAll(chain);
      list.add(next);
      return Collections.unmodifiableList(list);
    }
  }
}
