package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineStartupDiagnostic;
import featurecat.lizzie.analysis.EngineStartupDiagnostics;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.logging.DiagnosticBundleExporter;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Container;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Actual application -> Leelaz -> failure window -> copy -> diagnostic export. */
public class EngineStartupDiagnosticsDesktopTest {
  @Test
  void failureSurvivesInWindowLogClipboardAndZipAtHighDpi() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            EngineStartupDiagnosticsDesktopTest.class,
            "startup-diagnostics",
            List.of("-Dsun.java2d.uiScale=1.5"),
            List.of("probe"));
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("passed", evidence.getString("result"));
    assertTrue(Files.isRegularFile(Path.of(evidence.getString("screenshot"))));
    assertTrue(Files.isRegularFile(Path.of(evidence.getString("zip"))));
    assertTrue(Files.isRegularFile(Path.of(evidence.getString("secondZip"))));
    assertEquals(
        System.getProperty("os.name").startsWith("Windows") ? "windows-native" : "java-process",
        evidence.getString("surface"));
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("engine-child")) {
      Thread.sleep(400);
      System.err.println("Controlled startup failure; token=fixture-secret");
      System.exit(17);
    }
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      runProbe(work, result);
      System.exit(0);
    } catch (Throwable error) {
      error.printStackTrace();
      Files.writeString(
          result,
          new JSONObject().put("result", "failed").put("error", error.toString()).toString(2));
      System.exit(1);
    }
  }

  private static void runProbe(Path work, Path result) throws Exception {
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}");
    System.setProperty("lizzie.work.dir", work.toString());
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
        });

    LoggingRuntime runtime = LoggingRuntime.current().orElseThrow();
    runtime.stopFullTrace();
    runtime.applySettings(runtime.settings().withDiagnosticsEnabled(false));
    if (Lizzie.config != null) {
      Lizzie.config.loggingSettings = runtime.settings();
    }
    assertFalse(runtime.fullTraceActive());
    assertFalse(runtime.settings().diagnosticsEnabled());

    boolean windows = System.getProperty("os.name").startsWith("Windows");
    String command;
    int expectedCode;
    if (windows) {
      Path fixture =
          WindowsStatusProcessFixture.create(work.resolve("native-status.exe"), 0xC0000135);
      command = quote(fixture.toString());
      expectedCode = -1073741515;
    } else {
      command =
          quote(Path.of(System.getProperty("java.home"), "bin", "java").toString())
              + " -cp "
              + quote(System.getProperty("java.class.path"))
              + " "
              + EngineStartupDiagnosticsDesktopTest.class.getName()
              + " engine-child";
      expectedCode = 17;
    }
    int index = Lizzie.engineManager.engineList.size();
    EngineData entry = new EngineData();
    entry.id = UUID.randomUUID().toString();
    entry.index = index;
    entry.commands = command;
    entry.name = "Startup diagnostic fixture";
    entry.width = entry.height = 19;
    entry.komi = 6.5f;
    Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
    Leelaz engine =
        new Leelaz(command) {
          @Override
          public String getEngineName(int ignored) {
            return "Startup diagnostic fixture";
          }
        };
    engine.savedEntryId = entry.id;
    engine.width = engine.height = engine.oriWidth = engine.oriHeight = 19;
    engine.komi = engine.orikomi = 6.5f;
    Lizzie.engineManager.engineList.add(engine);
    Thread launcher =
        new Thread(
            () -> {
              try {
                Lizzie.engineManager.switchEngineIfAvailable(index, true);
              } catch (Exception error) {
                error.printStackTrace();
              }
            },
            "startup-diagnostic-scenario");
    launcher.setDaemon(true);
    launcher.start();
    await(() -> !EngineStartupDiagnostics.getDefault().snapshot().failures().isEmpty());
    await(() -> failedWindow() != null);
    await(() -> !latest().toJson().getString("outcome").equals("collecting"));
    EngineStartupDiagnostic diagnostic = latest();
    assertEquals(expectedCode, diagnostic.toJson().getInt("exitCode"));
    if (windows) assertEquals("STATUS_DLL_NOT_FOUND", diagnostic.toJson().getString("statusName"));
    EngineFailedMessage dialog = failedWindow();
    assertNotNull(dialog);
    await(
        () -> {
          String text = componentTextOnEdt(dialog);
          return text.contains(diagnostic.attemptId())
              && text.contains(Integer.toString(expectedCode));
        });
    SwingUtilities.invokeAndWait(() -> button(dialog, "EngineFailedMessage.details").doClick());
    Path screenshot = result.getParent().resolve("startup-failure-details.png");
    ImageIO.write(new Robot().createScreenCapture(dialog.getBounds()), "png", screenshot.toFile());
    SwingUtilities.invokeAndWait(() -> button(dialog, "EngineFailedMessage.copyError").doClick());
    String copied =
        (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
    JSONObject copy = new JSONObject(copied);
    assertEquals(diagnostic.attemptId(), copy.getString("attemptId"));
    assertEquals(diagnostic.engineId(), copy.getString("engineId"));
    assertEquals(expectedCode, copy.getInt("exitCode"));
    assertFalse(copied.contains("fixture-secret"));
    Files.writeString(result.getParent().resolve("copied-error.json"), copied);
    SwingUtilities.invokeAndWait(
        () -> button(dialog, "EngineFailedMessage.exportDiagnostics").doClick());
    AtomicReference<DiagnosticsDialog> panel = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) {
            DiagnosticsDialog found = findPanel(window);
            if (found != null && window.isVisible()) panel.set(found);
          }
          assertNotNull(panel.get());
          assertEquals(
              copy.getLong("diagnosticRevision"),
              panel.get().currentRequest().startupFailures().failures().stream()
                  .filter(d -> d.attemptId().equals(diagnostic.attemptId()))
                  .findFirst()
                  .orElseThrow()
                  .revision());
          findButtonByText(
                  panel.get(), Lizzie.resourceBundle.getString("DiagnosticsDialog.exportDefault"))
              .doClick();
        });
    Path exports = DiagnosticBundleExporter.defaultOutputDirectory(work);
    await(
        () -> {
          Path p = zipIn(exports);
          if (p == null) return false;
          try (ZipFile testOpen = new ZipFile(p.toFile())) {
            return testOpen.getEntry("snapshots/engine-startup-failures.json") != null;
          } catch (Exception stillWriting) {
            return false;
          }
        });
    Path zip = zipIn(exports);
    JSONObject exported;
    try (ZipFile archive = new ZipFile(zip.toFile())) {
      JSONObject history =
          new JSONObject(
              new String(
                  archive
                      .getInputStream(archive.getEntry("snapshots/engine-startup-failures.json"))
                      .readAllBytes(),
                  StandardCharsets.UTF_8));
      exported = history.getJSONArray("failures").getJSONObject(0);
      assertEquals(copy.getString("attemptId"), exported.getString("attemptId"));
      assertEquals(copy.getLong("diagnosticRevision"), exported.getLong("diagnosticRevision"));
      assertEquals(copy.getString("engineId"), exported.getString("engineId"));
      assertEquals(expectedCode, exported.getInt("exitCode"));
    }
    assertFalse(LoggingRuntime.current().orElseThrow().fullTraceActive());
    Path appLog = work.resolve("logs/app.log");
    await(
        () -> {
          try {
            return Files.readString(appLog).contains(diagnostic.attemptId());
          } catch (Exception unavailable) {
            return false;
          }
        });
    String log = Files.readString(appLog);
    assertTrue(log.contains("WARN"));
    assertTrue(log.contains(diagnostic.engineId()));
    assertTrue(log.contains(Integer.toString(expectedCode)));
    assertFalse(log.contains("fixture-secret"));
    if (windows) {
      assertTrue(log.contains("0xC0000135"));
      assertTrue(log.contains("STATUS_DLL_NOT_FOUND"));
    }

    String warnLine =
        Files.readAllLines(appLog).stream()
            .filter(line -> line.contains("WARN") && line.contains(diagnostic.attemptId()))
            .filter(
                line ->
                    line.matches(
                        ".*\"diagnosticRevision\":"
                            + copy.getLong("diagnosticRevision")
                            + "[,}].*"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError("Missing WARN line for attempt " + diagnostic.attemptId()));
    int diagStart = warnLine.indexOf("diagnostic={");
    assertTrue(diagStart >= 0, "WARN line missing diagnostic core payload: " + warnLine);
    int diagEnd = warnLine.indexOf("} summary={", diagStart);
    assertTrue(diagEnd >= 0, "WARN line missing summary boundary: " + warnLine);
    JSONObject warnCore =
        new JSONObject(warnLine.substring(diagStart + "diagnostic=".length(), diagEnd + 1));
    assertEquals(copy.getString("attemptId"), warnCore.getString("attemptId"));
    assertEquals(copy.getString("engineId"), warnCore.getString("engineId"));
    assertEquals(copy.getLong("diagnosticRevision"), warnCore.getLong("diagnosticRevision"));
    assertEquals(copy.getInt("exitCode"), warnCore.getInt("exitCode"));
    assertEquals(copy.optString("exitHex", ""), warnCore.optString("exitHex", ""));
    assertEquals(copy.optString("statusName", ""), warnCore.optString("statusName", ""));
    assertEquals(exported.getString("attemptId"), warnCore.getString("attemptId"));
    assertEquals(exported.getLong("diagnosticRevision"), warnCore.getLong("diagnosticRevision"));
    assertEquals(exported.getString("engineId"), warnCore.getString("engineId"));
    assertEquals(exported.getInt("exitCode"), warnCore.getInt("exitCode"));
    assertEquals(exported.optString("statusName", ""), warnCore.optString("statusName", ""));

    Path javaExecutable =
        Path.of(System.getProperty("java.home"), "bin", "java" + (windows ? ".exe" : ""));
    Path peerWorkB = work.resolve("peer-b");
    Files.createDirectories(peerWorkB);
    String commandB =
        quote(javaExecutable.toString())
            + " -cp "
            + quote(System.getProperty("java.class.path"))
            + " "
            + ControlledGtpPeer.class.getName()
            + " "
            + quote(peerWorkB.toString());

    int indexB = 1;
    EngineData entryB = new EngineData();
    entryB.id = UUID.randomUUID().toString();
    entryB.index = indexB;
    entryB.commands = commandB;
    entryB.name = "Controlled GTP Peer B";
    entryB.width = entryB.height = 19;
    entryB.komi = 6.5f;

    Utils.saveEngineSettings(new ArrayList<>(List.of(entry, entryB)));
    Lizzie.engineManager.refreshEngineCatalog();

    boolean switchedToB = Lizzie.engineManager.switchEngineIfAvailable(indexB, true);
    assertTrue(switchedToB, "Engine switch to B must be accepted");
    await(() -> isEngineReadyOnEdt(indexB));

    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(
              Lizzie.engineManager.isEngineSwitchActive(indexB, true),
              "Engine switch to B must be active");
          assertNotNull(Lizzie.leelaz, "Lizzie.leelaz must be present");
          assertTrue(Lizzie.leelaz.isLoaded(), "Engine B must be loaded and ready");
          assertFalse(
              Lizzie.engineStartupStatus.snapshot().isActionable(),
              "Startup status must not be failed while B is active");
        });

    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(
              dialog.isShowing(), "Original failed dialog must remain showing while B is active");
          String dialogText = componentText(dialog);
          assertTrue(
              dialogText.contains(diagnostic.attemptId()),
              "Original dialog must remain bound to attempt " + diagnostic.attemptId());
          assertTrue(
              dialogText.contains(Integer.toString(expectedCode)),
              "Original dialog must retain engine A exit code");
        });

    Path peerWorkA = work.resolve("peer-a-retry");
    Files.createDirectories(peerWorkA);
    String commandASuccess =
        quote(javaExecutable.toString())
            + " -cp "
            + quote(System.getProperty("java.class.path"))
            + " "
            + ControlledGtpPeer.class.getName()
            + " "
            + quote(peerWorkA.toString());
    entry.commands = commandASuccess;
    Utils.saveEngineSettings(new ArrayList<>(List.of(entry, entryB)));
    Lizzie.engineManager.refreshEngineCatalog();

    boolean retriedA = Lizzie.engineManager.switchEngineIfAvailable(index, true);
    assertTrue(retriedA, "Retry switch to engine A must be accepted");
    await(() -> isEngineReadyOnEdt(index));

    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(
              Lizzie.engineManager.isEngineSwitchActive(index, true),
              "Engine switch to A must be active");
          assertNotNull(Lizzie.leelaz, "Lizzie.leelaz must be present");
          assertTrue(
              Lizzie.leelaz.isLoaded(),
              "Engine A must be loaded and ready after configuration repair");
          assertFalse(
              Lizzie.engineStartupStatus.snapshot().isActionable(),
              "Startup status must not be failed after successful retry");
        });

    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(
              dialog.isShowing(),
              "Original failed dialog must remain showing after successful retry");
          String dialogText = componentText(dialog);
          assertTrue(
              dialogText.contains(diagnostic.attemptId()),
              "Original dialog must remain bound to attempt " + diagnostic.attemptId());
          assertTrue(
              dialogText.contains(Integer.toString(expectedCode)),
              "Original dialog must retain engine A exit code");
        });

    SwingUtilities.invokeAndWait(
        () -> button(dialog, "EngineFailedMessage.exportDiagnostics").doClick());
    AtomicReference<DiagnosticsDialog> secondPanel = new AtomicReference<>();
    await(
        () -> {
          AtomicBoolean ready = new AtomicBoolean(false);
          try {
            SwingUtilities.invokeAndWait(
                () -> {
                  for (Window window : Window.getWindows()) {
                    DiagnosticsDialog found = findPanel(window);
                    if (found != null && window.isVisible()) {
                      secondPanel.set(found);
                      JButton exportBtn =
                          findButtonByText(
                              found,
                              Lizzie.resourceBundle.getString("DiagnosticsDialog.exportDefault"));
                      if (exportBtn != null && exportBtn.isEnabled()) {
                        ready.set(true);
                      }
                    }
                  }
                });
          } catch (Exception ignored) {
          }
          return ready.get();
        });
    SwingUtilities.invokeAndWait(
        () -> {
          JButton exportBtn =
              findButtonByText(
                  secondPanel.get(),
                  Lizzie.resourceBundle.getString("DiagnosticsDialog.exportDefault"));
          assertNotNull(exportBtn, "Export button must be present in diagnostics dialog");
          exportBtn.doClick();
        });

    await(
        () -> {
          Path p = zipOtherThan(exports, zip);
          if (p == null) return false;
          try (ZipFile testOpen = new ZipFile(p.toFile())) {
            return testOpen.getEntry("snapshots/engine-startup-failures.json") != null;
          } catch (Exception stillWriting) {
            return false;
          }
        });
    Path secondZip = zipOtherThan(exports, zip);
    try (ZipFile archive2 = new ZipFile(secondZip.toFile())) {
      JSONObject history2 =
          new JSONObject(
              new String(
                  archive2
                      .getInputStream(archive2.getEntry("snapshots/engine-startup-failures.json"))
                      .readAllBytes(),
                  StandardCharsets.UTF_8));
      JSONArray failures2 = history2.getJSONArray("failures");
      boolean foundOriginalAttempt = false;
      for (int i = 0; i < failures2.length(); i++) {
        JSONObject f = failures2.getJSONObject(i);
        if (diagnostic.attemptId().equals(f.optString("attemptId"))) {
          foundOriginalAttempt = true;
          assertEquals(copy.getLong("diagnosticRevision"), f.getLong("diagnosticRevision"));
          assertEquals(diagnostic.engineId(), f.getString("engineId"));
          assertEquals(expectedCode, f.getInt("exitCode"));
          break;
        }
      }
      assertTrue(
          foundOriginalAttempt,
          "Original attempt "
              + diagnostic.attemptId()
              + " must remain present in ZIP exported after retry");
    }

    Files.writeString(
        result,
        new JSONObject()
            .put("result", "passed")
            .put("surface", windows ? "windows-native" : "java-process")
            .put("attemptId", diagnostic.attemptId())
            .put("engineId", diagnostic.engineId())
            .put("revision", copy.getLong("diagnosticRevision"))
            .put("screenshot", screenshot.toString())
            .put("zip", zip.toString())
            .put("secondZip", secondZip.toString())
            .put("log", appLog.toString())
            .toString(2));
    if (Lizzie.engineManager != null && Lizzie.engineManager.engineList != null) {
      for (Leelaz eng : Lizzie.engineManager.engineList) {
        if (eng != null) eng.forceQuit();
      }
    }
    engine.forceQuit();
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) window.dispose();
        });
  }

  private static EngineStartupDiagnostic latest() {
    return EngineStartupDiagnostics.getDefault().snapshot().failures().get(0);
  }

  private static EngineFailedMessage failedWindow() {
    AtomicReference<EngineFailedMessage> found = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            for (Window window : Window.getWindows()) {
              if (window instanceof EngineFailedMessage failure && failure.isShowing()) {
                found.set(failure);
                break;
              }
            }
          });
    } catch (Exception ignored) {
    }
    return found.get();
  }

  private static String componentTextOnEdt(Container parent) {
    AtomicReference<String> text = new AtomicReference<>("");
    try {
      SwingUtilities.invokeAndWait(() -> text.set(componentText(parent)));
    } catch (Exception ignored) {
    }
    return text.get();
  }

  private static boolean isEngineReadyOnEdt(int index) {
    AtomicBoolean ready = new AtomicBoolean(false);
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            boolean active =
                Lizzie.engineManager != null
                    && Lizzie.engineManager.isEngineSwitchActive(index, true);
            boolean loaded = Lizzie.leelaz != null && Lizzie.leelaz.isLoaded();
            boolean statusOk =
                Lizzie.engineStartupStatus != null
                    && !Lizzie.engineStartupStatus.snapshot().isActionable();
            ready.set(active && loaded && statusOk);
          });
    } catch (Exception ignored) {
    }
    return ready.get();
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline)
        throw new AssertionError("Startup diagnostic scenario timed out");
      Thread.sleep(25);
    }
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private static Path zipIn(Path directory) {
    if (!Files.isDirectory(directory)) return null;
    try (var files = Files.list(directory)) {
      return files.filter(p -> p.toString().endsWith(".zip")).findFirst().orElse(null);
    } catch (Exception error) {
      return null;
    }
  }

  private static Path zipOtherThan(Path directory, Path previous) {
    if (!Files.isDirectory(directory)) return null;
    try (var files = Files.list(directory)) {
      return files
          .filter(p -> p.toString().endsWith(".zip") && !p.equals(previous))
          .findFirst()
          .orElse(null);
    } catch (Exception error) {
      return null;
    }
  }

  private static JButton button(Container parent, String name) {
    for (Component component : parent.getComponents()) {
      if (component instanceof JButton button && name.equals(button.getName())) return button;
      if (component instanceof Container child) {
        JButton found = button(child, name);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static JButton findButtonByText(Container parent, String text) {
    for (Component component : parent.getComponents()) {
      if (component instanceof JButton button && text.equals(button.getText())) return button;
      if (component instanceof Container child) {
        JButton found = findButtonByText(child, text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static DiagnosticsDialog findPanel(Container parent) {
    if (parent instanceof DiagnosticsDialog panel) return panel;
    for (Component component : parent.getComponents())
      if (component instanceof Container child) {
        DiagnosticsDialog found = findPanel(child);
        if (found != null) return found;
      }
    return null;
  }

  private static String componentText(Container parent) {
    StringBuilder text = new StringBuilder();
    for (Component component : parent.getComponents()) {
      if (component instanceof JTextArea area) text.append(area.getText());
      if (component instanceof Container child) text.append(componentText(child));
    }
    return text.toString();
  }
}
