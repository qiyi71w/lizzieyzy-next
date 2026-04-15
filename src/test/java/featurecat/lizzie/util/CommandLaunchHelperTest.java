package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  private static void assertListEquals(List<String> expected, List<String> actual) {
    assertIterableEquals(expected, actual);
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
