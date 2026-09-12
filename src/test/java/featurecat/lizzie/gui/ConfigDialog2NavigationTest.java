package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the navigation check against a real application startup in an isolated JVM. */
public final class ConfigDialog2NavigationTest {
  private static final String TARGET = "settings.black-winrate";
  private static final String TARGET_ROW_PROPERTY = "lizzie.config.settingTargetId";

  @TempDir Path tempDir;

  @Test
  void blackWinrateRemainsReachableAcrossRebuildsAndRecreation() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Path work = Files.createDirectories(tempDir.resolve("work"));
    Path marker = tempDir.resolve("navigation-result.txt");
    String javaExecutable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classPath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path", ""));
    Process process =
        new ProcessBuilder(
                javaExecutable,
                "-cp",
                classPath,
                ConfigDialog2NavigationTest.class.getName(),
                "probe",
                work.toAbsolutePath().toString(),
                marker.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    String result = Files.readString(marker);
    assertTrue(result.contains("unknown=false"), result);
    assertTrue(result.contains("first.located=true"), result);
    assertTrue(result.contains("first.focus=true"), result);
    assertTrue(result.contains("first.visible=true"), result);
    assertTrue(result.contains("rebound.focus=true"), result);
    assertTrue(result.contains("rebound.visible=true"), result);
    assertTrue(result.contains("value-preserved=true"), result);
    assertTrue(result.contains("hidden-focus=false"), result);
    assertTrue(result.contains("recreated.focus=true"), result);
    assertTrue(result.contains("recreated.visible=true"), result);
  }

  /**
   * Child-process entry point. It deliberately starts the production application, not a fixture
   * frame.
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !"probe".equals(args[0])) {
      throw new IllegalArgumentException("expected probe, work directory, and result path");
    }
    Path work = Path.of(args[1]);
    Path resultPath = Path.of(args[2]);
    int exitCode = 1;
    try {
      runProbe(work, resultPath);
      exitCode = 0;
    } catch (Throwable failure) {
      Files.writeString(
          resultPath,
          "failure=" + failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
      failure.printStackTrace(System.err);
    } finally {
      System.exit(exitCode);
    }
  }

  private static void runProbe(Path work, Path resultPath) throws Exception {
    Files.createDirectories(work);
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
            + "\"win-rate-always-black\":false}}",
        StandardCharsets.UTF_8);
    System.setProperty("lizzie.work.dir", work.toAbsolutePath().toString());

    Lizzie.main(new String[0]);
    SwingUtilities.invokeAndWait(
        () -> {
          if (Lizzie.frame == null || !Lizzie.frame.isDisplayable()) {
            throw new AssertionError("production startup did not create a displayable frame");
          }
        });

    AtomicReference<ConfigDialog2> firstRef = new AtomicReference<>();
    AtomicReference<Boolean> unknown = new AtomicReference<>();
    runOnEdt(
        () -> {
          ConfigDialog2 dialog = new ConfigDialog2();
          firstRef.set(dialog);
          unknown.set(dialog.locateSetting("settings.not-real"));
        });
    ConfigDialog2 first = firstRef.get();
    boolean initialValue = Lizzie.config.winrateAlwaysBlack;
    AtomicReference<Boolean> firstLocated = new AtomicReference<>();
    ModalObservation firstObservation =
        showAndObserve(first, () -> firstLocated.set(first.locateSetting(TARGET)));
    JCheckBox firstControl = firstObservation.control;

    runOnEdt(() -> first.switchTab(1));
    ModalObservation reboundObservation =
        showAndObserve(
            first,
            () -> {
              if (!first.locateSetting(TARGET)) {
                throw new AssertionError("setting was not found after tab rebuild");
              }
            });
    if (blackWinrateControl(first) != firstControl) {
      throw new AssertionError("tab rebuild replaced the existing setting control");
    }

    runOnEdt(
        () -> {
          if (!first.locateSetting(TARGET)) {
            throw new AssertionError("hidden dialog rejected a known setting target");
          }
          first.setVisible(false);
        });
    flushEdt();
    boolean hiddenFocus =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == firstControl;
    first.dispose();

    AtomicReference<ConfigDialog2> secondRef = new AtomicReference<>();
    runOnEdt(() -> secondRef.set(new ConfigDialog2()));
    ConfigDialog2 second = secondRef.get();
    ModalObservation recreatedObservation =
        showAndObserve(
            second,
            () -> {
              if (!second.locateSetting(TARGET)) {
                throw new AssertionError("recreated dialog rejected a known setting target");
              }
            });
    second.dispose();

    if (Boolean.TRUE.equals(unknown.get()) || !Boolean.TRUE.equals(firstLocated.get())) {
      throw new AssertionError("target lookup result was incorrect");
    }

    if (Lizzie.config.winrateAlwaysBlack != initialValue
        || firstObservation.value != initialValue
        || reboundObservation.value != initialValue
        || recreatedObservation.value != initialValue) {
      throw new AssertionError("navigation changed the setting value");
    }
    Files.writeString(
        resultPath,
        "unknown="
            + unknown.get()
            + "\nfirst.located="
            + firstLocated.get()
            + "\nfirst.focus="
            + firstObservation.focused
            + "\nfirst.visible="
            + firstObservation.visible
            + "\nrebound.focus="
            + reboundObservation.focused
            + "\nrebound.visible="
            + reboundObservation.visible
            + "\nrecreated.focus="
            + recreatedObservation.focused
            + "\nrecreated.visible="
            + recreatedObservation.visible
            + "\nvalue-preserved="
            + (Lizzie.config.winrateAlwaysBlack == initialValue
                && firstObservation.value == initialValue
                && reboundObservation.value == initialValue
                && recreatedObservation.value == initialValue)
            + "\nhidden-focus="
            + hiddenFocus);
  }

  private static ModalObservation showAndObserve(ConfigDialog2 dialog, Runnable beforeShow)
      throws Exception {
    AtomicReference<ModalObservation> observation = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnEdt(
        () -> {
          beforeShow.run();
          javax.swing.Timer timer =
              new javax.swing.Timer(
                  350,
                  event -> {
                    try {
                      JCheckBox control = blackWinrateControl(dialog);
                      JComponent row = targetRow(dialog);
                      observation.set(
                          new ModalObservation(
                              control,
                              fullyVisible(dialog, row),
                              KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                                  == control,
                              Lizzie.config.winrateAlwaysBlack));
                    } catch (Throwable error) {
                      failure.set(error);
                    } finally {
                      dialog.setVisible(false);
                    }
                  });
          timer.setRepeats(false);
          timer.start();
          dialog.setVisible(true);
        });
    if (failure.get() != null) {
      throw new AssertionError("navigation observation failed", failure.get());
    }
    if (observation.get() == null) {
      throw new AssertionError("navigation observation did not run");
    }
    return observation.get();
  }

  private static JCheckBox blackWinrateControl(ConfigDialog2 dialog) {
    String title = Lizzie.resourceBundle.getString("Menu.alwaysShowBlackWinrate");
    Component control = findAccessible(dialog.getContentPane(), title);
    if (!(control instanceof JCheckBox)) {
      throw new AssertionError("black-winrate control is not attached");
    }
    return (JCheckBox) control;
  }

  private static Component findAccessible(Component root, String name) {
    if (root.getAccessibleContext() != null
        && name.equals(root.getAccessibleContext().getAccessibleName())
        && root instanceof JCheckBox) {
      return root;
    }
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        Component match = findAccessible(child, name);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static JComponent targetRow(ConfigDialog2 dialog) {
    Component row = findTargetRow(dialog.getContentPane());
    if (!(row instanceof JComponent)) {
      throw new AssertionError("black-winrate row is not attached");
    }
    return (JComponent) row;
  }

  private static Component findTargetRow(Component root) {
    if (root instanceof JComponent
        && TARGET.equals(((JComponent) root).getClientProperty(TARGET_ROW_PROPERTY))) {
      return root;
    }
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        Component match = findTargetRow(child);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static boolean fullyVisible(ConfigDialog2 dialog, Component component) {
    if (!(dialog.tabbedPane.getSelectedComponent() instanceof JScrollPane)) return false;
    JScrollPane scrollPane = (JScrollPane) dialog.tabbedPane.getSelectedComponent();
    Component view = scrollPane.getViewport().getView();
    if (view == null) return false;
    Rectangle bounds =
        SwingUtilities.convertRectangle(
            component, new Rectangle(0, 0, component.getWidth(), component.getHeight()), view);
    return scrollPane.getViewport().getViewRect().contains(bounds);
  }

  private static void runOnEdt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static void flushEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static final class ModalObservation {
    private final JCheckBox control;
    private final boolean visible;
    private final boolean focused;
    private final boolean value;

    private ModalObservation(JCheckBox control, boolean visible, boolean focused, boolean value) {
      this.control = control;
      this.visible = visible;
      this.focused = focused;
      this.value = value;
    }
  }
}
