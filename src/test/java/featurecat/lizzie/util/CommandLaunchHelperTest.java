package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.swingx.util.OS;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class CommandLaunchHelperTest {
  @Test
  void prepareInfersSharedExternalWorkingDirectory() throws Exception {
    Path root = Files.createTempDirectory("command-launch-shared-root");
    try {
      Path bundleDir = Files.createDirectories(root.resolve("bundle"));
      Path engineDir = Files.createDirectories(bundleDir.resolve("engine"));
      Path modelDir = Files.createDirectories(bundleDir.resolve("models"));
      Path configDir = Files.createDirectories(bundleDir.resolve("config"));

      List<String> command =
          Arrays.asList(
              engineDir.resolve("katago.exe").toString(),
              "-model",
              modelDir.resolve("kata1.bin.gz").toString(),
              "-config",
              configDir.resolve("gtp.cfg").toString());

      CommandLaunchHelper.LaunchSpec launchSpec = CommandLaunchHelper.prepare(command);
      assertPathEquals(bundleDir, launchSpec.getWorkingDirectory().toPath());
    } finally {
      deleteTree(root);
    }
  }

  @Test
  void prepareDoesNotUseFilesystemRootAsWorkingDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("command-launch-root-left");
    Path homeRoot =
        Files.createTempDirectory(Path.of(System.getProperty("user.home")), "command-launch-right");
    try {
      Path engineDir = Files.createDirectories(tempRoot.resolve("engine"));
      Path modelDir = Files.createDirectories(homeRoot.resolve("model"));
      Path configDir = Files.createDirectories(homeRoot.resolve("config"));

      List<String> command =
          Arrays.asList(
              engineDir.resolve("katago.exe").toString(),
              "-model",
              modelDir.resolve("kata1.bin.gz").toString(),
              "-config",
              configDir.resolve("gtp.cfg").toString());

      CommandLaunchHelper.LaunchSpec launchSpec = CommandLaunchHelper.prepare(command);
      assertPathEquals(engineDir, launchSpec.getWorkingDirectory().toPath());
      assertTrue(
          launchSpec.getWorkingDirectory().toPath().getParent() != null,
          "working directory should not be the filesystem root");
    } finally {
      deleteTree(tempRoot);
      deleteTree(homeRoot);
    }
  }

  @Test
  void configureProcessBuilderAppliesLaunchSpecWorkingDirectory() throws Exception {
    Path root = Files.createTempDirectory("command-launch-configure-root");
    try {
      Path engineDir = Files.createDirectories(root.resolve("engine"));
      List<String> command = Arrays.asList(engineDir.resolve("katago.exe").toString());
      CommandLaunchHelper.LaunchSpec launchSpec = CommandLaunchHelper.prepare(command);

      ProcessBuilder processBuilder = new ProcessBuilder("echo");
      processBuilder.directory(root.toFile());

      CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);

      assertPathEquals(engineDir, processBuilder.directory().toPath());
    } finally {
      deleteTree(root);
    }
  }

  @Test
  void buildWindowsPathEntriesPreservesOrderDedupesAndExpandsVariables() {
    Map<String, String> processEnvironment = new LinkedHashMap<String, String>();
    processEnvironment.put("TOOLS", "C:\\Toolkit");
    processEnvironment.put("SystemRoot", "C:\\Windows");
    processEnvironment.put("PATH", "c:\\shared\\bin;C:\\EnvPath");
    processEnvironment.put("Path", "%TOOLS%\\bin;C:\\Shared\\Bin");

    Map<String, String> systemEnvironment = new LinkedHashMap<String, String>();
    systemEnvironment.put("SystemRoot", "C:\\Windows");
    systemEnvironment.put("PATH", "C:\\System32;%SystemRoot%\\System32");
    systemEnvironment.put("Path", "c:\\envpath;C:\\UserTools");

    List<String> actual =
        CommandLaunchHelper.buildWindowsPathEntries(
            Arrays.asList("C:\\Engines\\KataGo\\katago.exe"),
            new File("C:\\Work\\Project"),
            processEnvironment,
            systemEnvironment,
            "C:\\MachinePath;%TOOLS%\\bin",
            "c:\\machinepath;C:\\UserPath");

    assertListEquals(
        Arrays.asList(
            "C:\\Engines\\KataGo",
            "C:\\Work\\Project",
            "c:\\shared\\bin",
            "C:\\EnvPath",
            "C:\\Toolkit\\bin",
            "C:\\System32",
            "C:\\Windows\\System32",
            "C:\\UserTools",
            "C:\\MachinePath",
            "C:\\UserPath"),
        actual);
  }

  @Test
  void buildWindowsPathEntriesIgnoresNullExecutableAndWorkingDirectoryEntries() {
    List<String> actual =
        CommandLaunchHelper.buildWindowsPathEntries(
            Collections.<String>emptyList(),
            null,
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            null,
            null);

    assertListEquals(Collections.<String>emptyList(), actual);
  }

  @Test
  void readWindowsRegistryPathTreatsMissingRegExecutableAsSoftFailure() throws Exception {
    AtomicReference<List<String>> command = new AtomicReference<List<String>>();

    assertEquals(
        "",
        CommandLaunchHelper.readWindowsRegistryPathForTest(
            "HKCU\\Environment",
            query -> {
              command.set(query.command());
              throw new IOException("missing reg executable");
            }));
    assertIterableEquals(
        Arrays.asList("reg", "query", "HKCU\\Environment", "/v", "Path"), command.get());
  }

  @Test
  void extractRegistryPathHandlesCaseAndWhitespaceVariations() throws Exception {
    String output =
        "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment\n"
            + "    path    REG_EXPAND_SZ    C:\\Windows\\System32;C:\\Tools\n";

    assertEquals(
        "C:\\Windows\\System32;C:\\Tools",
        invokePrivateStringMethod("extractRegistryPath", output));
  }

  @Test
  void windowsRegistryPathCacheReadsEachHiveOnce() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    CommandLaunchHelper.setWindowsRegistryPathReaderForTest(
        environmentKey -> {
          reads.incrementAndGet();
          return environmentKey + "-cached";
        });
    try {
      CommandLaunchHelper.resetWindowsRegistryPathCacheForTest();

      assertEquals(
          "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment-cached",
          CommandLaunchHelper.getWindowsRegistryPath(
              "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"));
      assertEquals(
          "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment-cached",
          CommandLaunchHelper.getWindowsRegistryPath(
              "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"));
      assertEquals(
          "HKCU\\Environment-cached",
          CommandLaunchHelper.getWindowsRegistryPath("HKCU\\Environment"));

      assertEquals(2, reads.get());
    } finally {
      CommandLaunchHelper.resetWindowsRegistryPathReaderForTest();
      CommandLaunchHelper.resetWindowsRegistryPathCacheForTest();
    }
  }

  @Test
  void configureProcessBuilderOnNonWindowsPreservesPathAndAppliesWorkingDirectory()
      throws Exception {
    Assumptions.assumeFalse(OS.isWindows(), "non-Windows scenario");

    Path root = Files.createTempDirectory("command-launch-non-windows-root");
    try {
      Path engineDir = Files.createDirectories(root.resolve("engine"));
      List<String> command = Arrays.asList(engineDir.resolve("katago").toString());
      CommandLaunchHelper.LaunchSpec launchSpec = CommandLaunchHelper.prepare(command);

      ProcessBuilder processBuilder = new ProcessBuilder("echo");
      processBuilder.directory(root.toFile());
      processBuilder.environment().put("PATH", "/usr/bin:/custom/bin");
      processBuilder.environment().put("Path", "/tmp/mixed-case-path");

      CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);

      assertPathEquals(engineDir, processBuilder.directory().toPath());
      assertEquals("/usr/bin:/custom/bin", processBuilder.environment().get("PATH"));
      assertEquals("/tmp/mixed-case-path", processBuilder.environment().get("Path"));
    } finally {
      deleteTree(root);
    }
  }

  @Test
  void classifyCommandRecognizesQuotedWindowsKataGoBenchmark() {
    List<String> tokens =
        Utils.splitCommand(
            "\"C:\\Program Files\\KataGo\\katago.exe\" benchmark -model \"D:\\Go Models\\net.bin.gz\"");
    assertEquals("C:\\Program Files\\KataGo\\katago.exe", tokens.get(0));
    assertEquals("benchmark", tokens.get(1));
    assertEquals("D:\\Go Models\\net.bin.gz", tokens.get(3));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK,
        CommandLaunchHelper.classifyCommand(tokens));
  }

  @Test
  void classifyCommandRecognizesSupportedKataGoBenchmarkNames() {
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK, classify("katago benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK, classify("katago.exe benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK,
        classify("katago-v1.16.0-opencl-windows-x64.exe benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK,
        classify("katago_cpu.exe benchmark -model net.bin.gz"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK, classify("katago_opencl benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK, classify("katago_cuda.exe benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.BENCHMARK,
        classify("/usr/local/bin/katago benchmark"));
  }

  @Test
  void classifyCommandKeepsGtpAndRejectsWrappersAndCaseVariants() {
    assertEquals(CommandLaunchHelper.EngineCommandPurpose.GTP, classify("katago gtp"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP,
        classify("katago.exe gtp -model net.bin.gz"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP, classify("leelaz.exe benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP, classify("wine katago.exe benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP, classify("cmd /c katago.exe benchmark"));
    assertEquals(CommandLaunchHelper.EngineCommandPurpose.GTP, classify("katago Benchmark"));
    assertEquals(CommandLaunchHelper.EngineCommandPurpose.GTP, classify("katago BENCHMARK"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP,
        classify("katago.exe -model C:\\benchmark\\model.bin.gz gtp"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP,
        classify("katago gtp -model benchmark.bin.gz -config C:\\benchmark\\gtp.cfg"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP,
        classify("katago gtp -override-config foo=benchmark"));
    assertEquals(
        CommandLaunchHelper.EngineCommandPurpose.GTP,
        classify("\"C:\\Program Files\\benchmark\\engine.exe\" benchmark"));
  }

  @Test
  void prepareLeavesOverrideConfigValueUnchangedWhenRelativeFileExists() throws Exception {
    Path root = Files.createTempDirectory("command-launch-override-config");
    try {
      Path engineDir = Files.createDirectories(root.resolve("engine"));
      Path executable = engineDir.resolve("katago.exe");
      Path model = engineDir.resolve("cmd-launch-model.bin.gz");
      Path config = engineDir.resolve("cmd-launch-gtp.cfg");
      String overrideValue = "logToFile=cmd-launch-log.txt";
      Files.createFile(model);
      Files.createFile(config);
      Files.createFile(engineDir.resolve(overrideValue));

      CommandLaunchHelper.LaunchSpec shortFlagSpec =
          CommandLaunchHelper.prepare(
              Arrays.asList(
                  executable.toString(),
                  "gtp",
                  "-model",
                  model.getFileName().toString(),
                  "-config",
                  config.getFileName().toString(),
                  "-override-config",
                  overrideValue));
      List<String> shortFlagParts = shortFlagSpec.getCommandParts();
      assertPathEquals(engineDir, shortFlagSpec.getWorkingDirectory().toPath());
      assertEquals(model.toAbsolutePath().normalize().toString(), shortFlagParts.get(3));
      assertEquals(config.toAbsolutePath().normalize().toString(), shortFlagParts.get(5));
      assertEquals(overrideValue, shortFlagParts.get(7));

      CommandLaunchHelper.LaunchSpec longFlagSpec =
          CommandLaunchHelper.prepare(
              Arrays.asList(
                  executable.toString(),
                  "gtp",
                  "-model",
                  model.getFileName().toString(),
                  "--override-config",
                  overrideValue));
      assertEquals(overrideValue, longFlagSpec.getCommandParts().get(5));
      assertEquals(
          model.toAbsolutePath().normalize().toString(), longFlagSpec.getCommandParts().get(3));
    } finally {
      deleteTree(root);
    }
  }

  private static CommandLaunchHelper.EngineCommandPurpose classify(String command) {
    return CommandLaunchHelper.classifyCommand(Utils.splitCommand(command));
  }

  private static void assertListEquals(List<String> expected, List<String> actual) {
    assertIterableEquals(expected, actual);
  }

  private static String invokePrivateStringMethod(String methodName, String argument)
      throws Exception {
    Method method = CommandLaunchHelper.class.getDeclaredMethod(methodName, String.class);
    method.setAccessible(true);
    return invokeString(method, argument);
  }

  private static String invokeString(Method method, String argument) throws Exception {
    try {
      return (String) method.invoke(null, argument);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }

  private static void assertPathEquals(Path expected, Path actual) {
    Path normalizedExpected = expected.toAbsolutePath().normalize();
    Path normalizedActual = actual.toAbsolutePath().normalize();
    assertEquals(normalizedExpected, normalizedActual);
  }

  private static void deleteTree(Path root) throws Exception {
    if (root == null || !Files.exists(root)) {
      return;
    }
    Files.walk(root)
        .sorted((left, right) -> right.compareTo(left))
        .forEach(CommandLaunchHelperTest::deletePath);
  }

  private static void deletePath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
