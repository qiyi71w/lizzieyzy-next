package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.MeasuredKataGoTuning;
import featurecat.lizzie.util.NvidiaGpuDetector;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredFingerprint;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Opt-in NVIDIA desktop UI contract. Synthetic timings are NOT performance evidence. */
class MeasuredTuningDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"1", "1.5", "2"})
  void fileImportCancelConfirmAndRestoreUseTheProductionDialogs(String scale) throws Exception {
    assumeTrue(Boolean.getBoolean("lizzie.measured.desktop"));
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            MeasuredTuningDesktopTest.class,
            "measured-ui-only-" + scale,
            List.of("-Dsun.java2d.uiScale=" + scale),
            List.of("probe"),
            120);
    assertEquals(
        "passed; synthetic UI fixture, not performance evidence", Files.readString(result));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      await(() -> Lizzie.frame != null);
      edt(
          () -> {
            Lizzie.frame.setAlwaysOnTop(true);
            return null;
          });
      EngineData entry = new EngineData();
      Path engine = Files.writeString(work.resolve("katago.exe"), "UI-only, never executed");
      Path model = Files.writeString(work.resolve("ui-model.bin.gz"), "UI-only model identity");
      Path config = Files.writeString(work.resolve("gtp.cfg"), "numSearchThreads = 8\n");
      entry.name = "Synthetic UI acceptance only";
      entry.commands = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      entry = Utils.getEngineData().get(0);
      String id = entry.id;
      String command = entry.commands;
      Path report = createUiOnlyReport(work, entry);
      var controller = new MeasuredTuningDialog();
      AtomicBoolean busy = new AtomicBoolean();
      AtomicInteger changes = new AtomicInteger();
      Robot robot = new Robot();
      robot.setAutoDelay(80);

      importFile(controller, id, busy, changes, report, robot);
      clickOption(robot, "OptionPane.cancelButtonText");
      await(() -> !busy.get());
      assertFalse(MeasuredKataGoTuning.hasProfile(id));
      assertEquals(0, changes.get());

      importFile(controller, id, busy, changes, report, robot);
      Window confirm = edt(() -> SwingUtilities.getWindowAncestor(visible(JOptionPane.class)));
      robot.waitForIdle();
      robot.delay(400);
      ImageIO.write(
          robot.createScreenCapture(edt(confirm::getBounds)),
          "png",
          result.getParent().resolve("confirm-ui-only.png").toFile());
      clickOption(robot, "OptionPane.okButtonText");
      await(() -> changes.get() == 1);
      assertTrue(MeasuredKataGoTuning.hasProfile(id));
      clickOption(robot, "OptionPane.okButtonText");
      await(() -> !busy.get());

      SwingUtilities.invokeLater(
          () -> controller.restore(Lizzie.frame, id, busy::set, changes::incrementAndGet));
      clickOption(robot, "OptionPane.okButtonText");
      await(() -> changes.get() == 2);
      assertFalse(MeasuredKataGoTuning.hasProfile(id));
      assertEquals(command, Utils.getEngineData().get(0).commands);
      clickOption(robot, "OptionPane.okButtonText");
      await(() -> !busy.get());
      Files.writeString(result, "passed; synthetic UI fixture, not performance evidence");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      for (Window window : Window.getWindows()) {
        if (window.isShowing()) {
          System.err.println("Visible window: " + window + " bounds=" + window.getBounds());
          ImageIO.write(
              new Robot().createScreenCapture(edt(window::getBounds)),
              "png",
              result.getParent().resolve("failure-" + window.getName() + ".png").toFile());
        }
      }
      Files.writeString(result, "failed: " + failure);
      System.exit(1);
    }
  }

  private static Path createUiOnlyReport(Path work, EngineData entry) throws Exception {
    var hardware = NvidiaGpuDetector.detectBestGpu();
    assertTrue(hardware.detected && hardware.gpus.size() == 1, "Requires one local NVIDIA GPU");
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry);
    var command =
        KataGoRuntimeHelper.prepareBundledLaunchCommand(
            snapshot.sourceArguments, snapshot.enginePath);
    command = KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry);
    JSONObject fingerprint = KataGoMeasuredFingerprint.capture(snapshot, hardware.bestGpu, command);
    JSONArray runs = new JSONArray();
    for (int round = 1; round <= 3; round++) {
      for (int member = 0; member < 2; member++) {
        boolean baseline = (round + member) % 2 == 1;
        runs.put(
            new JSONObject()
                .put("profile", baseline ? "baseline" : "candidate")
                .put("round", round)
                .put("phase", "warm")
                .put("seconds", baseline ? 10 : 8)
                .put("responseSeconds", .05)
                .put("edtP95Seconds", .01)
                .put("maxMemoryMiB", 100)
                .put("maxEngineProcesses", 1)
                .put("observedRootVisits", 5000)
                .put("firstResultSeconds", .1));
      }
    }
    JSONObject report =
        new JSONObject()
            .put("schemaVersion", 1)
            .put("scene", "live")
            .put("measurementMode", "application")
            .put("fingerprint", fingerprint)
            .put("fixtureSha256", "d".repeat(64))
            .put("budget", 5000)
            .put("positions", 1)
            .put("metricScope", "totalGpu")
            .put(
                "baselineParameters",
                new JSONObject().put("numSearchThreads", 8).put("nnMaxBatchSize", 16))
            .put(
                "candidateParameters",
                new JSONObject().put("numSearchThreads", 16).put("nnMaxBatchSize", 32))
            .put("runs", runs);
    return Files.writeString(
        work.resolve("SYNTHETIC-UI-ONLY-not-a-benchmark.json"), report.toString(2));
  }

  private static void importFile(
      MeasuredTuningDialog controller,
      String id,
      AtomicBoolean busy,
      AtomicInteger changes,
      Path report,
      Robot robot)
      throws Exception {
    SwingUtilities.invokeLater(
        () -> controller.importReport(Lizzie.frame, id, busy::set, changes::incrementAndGet));
    await(() -> visible(JFileChooser.class) != null);
    JTextField filename =
        edt(
            () -> {
              List<JTextField> fields = new ArrayList<>();
              editableFields(visible(JFileChooser.class), fields);
              assertEquals(1, fields.size(), "Unambiguous visible filename field");
              return fields.get(0);
            });
    click(robot, filename);
    await(() -> edtUnchecked(filename::isFocusOwner));
    Toolkit.getDefaultToolkit()
        .getSystemClipboard()
        .setContents(new StringSelection(report.toString()), null);
    robot.keyPress(KeyEvent.VK_CONTROL);
    press(robot, KeyEvent.VK_A);
    press(robot, KeyEvent.VK_V);
    robot.keyRelease(KeyEvent.VK_CONTROL);
    press(robot, KeyEvent.VK_ENTER);
    await(() -> visible(JOptionPane.class) != null);
    assertFalse(MeasuredKataGoTuning.hasProfile(id), "Import must not apply before confirmation");
  }

  private static void clickOption(Robot robot, String key) throws Exception {
    await(() -> visible(JOptionPane.class) != null);
    JButton button = edt(() -> findButton(visible(JOptionPane.class), UIManager.getString(key)));
    assertNotNull(button, key);
    assertTrue(
        edt(
            () -> {
              Window window = SwingUtilities.getWindowAncestor(button);
              Point point = button.getLocationOnScreen();
              return window
                  .getBounds()
                  .contains(
                      new java.awt.Rectangle(
                          point.x, point.y, button.getWidth(), button.getHeight()));
            }),
        "Confirmation button must fit inside its native dialog");
    System.err.println("Click " + key + " at " + edt(button::getLocationOnScreen));
    click(robot, button);
  }

  private static JButton findButton(Container root, String text) {
    for (Component child : root.getComponents()) {
      if (child instanceof JButton button && text.equals(button.getText())) return button;
      if (child instanceof Container container) {
        JButton found = findButton(container, text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void editableFields(Container root, List<JTextField> fields) {
    for (Component child : root.getComponents()) {
      if (child instanceof JTextField field
          && field.isShowing()
          && field.isEnabled()
          && field.isEditable()) fields.add(field);
      if (child instanceof Container container) editableFields(container, fields);
    }
  }

  private static <T extends Component> T visible(Class<T> type) {
    return edtUnchecked(
        () -> {
          for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            T found = find(window, type);
            if (found != null) return found;
          }
          return null;
        });
  }

  private static <T extends Component> T find(Component component, Class<T> type) {
    if (type.isInstance(component) && component.isShowing()) return type.cast(component);
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        T found = find(child, type);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void click(Robot robot, Component component) throws Exception {
    Point point =
        edt(
            () -> {
              Point p = component.getLocationOnScreen();
              p.translate(component.getWidth() / 2, component.getHeight() / 2);
              return p;
            });
    robot.mouseMove(point.x, point.y);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    robot.delay(150);
  }

  private static void press(Robot robot, int key) {
    robot.keyPress(key);
    robot.keyRelease(key);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("UI condition timed out");
      Thread.sleep(50);
    }
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) return action.call();
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(5, TimeUnit.SECONDS);
  }

  private static <T> T edtUnchecked(Callable<T> action) {
    try {
      return edt(action);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static String quote(Path path) {
    return "\"" + path + "\"";
  }
}
