package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;

public final class ConfigPersistenceAcceptanceTest {
  private static final String TARGET = "settings.black-winrate";
  private static final String TARGET_ROW_PROPERTY = "lizzie.config.settingTargetId";

  @Test
  public void savesSettingAcrossRestart() throws Exception {
    DesktopProbeProcess.requireDisplay();

    // Phase 1: Save phase in an isolated child JVM
    Path saveResult =
        DesktopProbeProcess.run(
            ConfigPersistenceAcceptanceTest.class, "config-save", List.of(), List.of("save"));

    String saveOutput = Files.readString(saveResult, StandardCharsets.UTF_8);
    assertTrue(saveOutput.contains("phase=save"), saveOutput);
    assertTrue(saveOutput.contains("located=true"), saveOutput);
    assertTrue(saveOutput.contains("initiallyUnchecked=true"), saveOutput);
    assertTrue(saveOutput.contains("checkedAfterClick=true"), saveOutput);
    assertTrue(saveOutput.contains("saveClicked=true"), saveOutput);
    assertTrue(saveOutput.contains("closed=true"), saveOutput);
    assertTrue(saveOutput.contains("configValue=true"), saveOutput);

    Path firstRunWorkDir = saveResult.getParent().resolve("work");
    assertTrue(Files.isDirectory(firstRunWorkDir), "First run work directory must exist");
    Path firstRunConfigFile = firstRunWorkDir.resolve("config.txt");
    assertTrue(Files.isRegularFile(firstRunConfigFile), "First run config.txt must exist");

    // Phase 2: Read phase in a fresh JVM pointing lizzie.work.dir at first run's profile
    Path readResult =
        DesktopProbeProcess.run(
            ConfigPersistenceAcceptanceTest.class,
            "config-read",
            List.of("-Dlizzie.work.dir=" + firstRunWorkDir.toAbsolutePath()),
            List.of("read", firstRunWorkDir.toAbsolutePath().toString()));

    String readOutput = Files.readString(readResult, StandardCharsets.UTF_8);
    assertTrue(readOutput.contains("phase=read"), readOutput);
    assertTrue(readOutput.contains("located=true"), readOutput);
    assertTrue(readOutput.contains("checkboxSelected=true"), readOutput);
    assertTrue(readOutput.contains("configValue=true"), readOutput);
    assertTrue(readOutput.contains("result=PASS"), readOutput);

    // Verify retained visual evidence
    assertTrue(
        Files.isRegularFile(saveResult.getParent().resolve("visible-windows.png")),
        "Save phase screenshot must be retained");
    assertTrue(
        Files.isRegularFile(readResult.getParent().resolve("visible-windows.png")),
        "Read phase screenshot must be retained");
  }

  /** Child-process entry point. */
  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      throw new IllegalArgumentException("Expected at least mode, work directory, and result path");
    }
    String mode = args[0];
    int exitCode = 1;
    Path resultPath;
    if ("save".equals(mode)) {
      if (args.length != 3) {
        throw new IllegalArgumentException(
            "Expected save mode with work directory and result path");
      }
      Path work = Path.of(args[1]);
      resultPath = Path.of(args[2]);
      try {
        DesktopProbeProcess.phase(resultPath, "production-startup");
        runSaveProbe(work, resultPath);
        DesktopProbeProcess.phase(resultPath, "save-run-complete");
        exitCode = 0;
      } catch (Throwable failure) {
        captureVisibleWindows(resultPath.getParent().resolve("visible-windows.png"));
        Files.writeString(
            resultPath,
            "failure=" + failure.getClass().getName() + ": " + failure.getMessage(),
            StandardCharsets.UTF_8);
        failure.printStackTrace(System.err);
      } finally {
        System.exit(exitCode);
      }
    } else if ("read".equals(mode)) {
      if (args.length != 4) {
        throw new IllegalArgumentException(
            "Expected read mode with shared profile, harness work directory, and result path");
      }
      Path sharedProfile = Path.of(args[1]);
      Path harnessWork = Path.of(args[2]);
      resultPath = Path.of(args[3]);
      try {
        DesktopProbeProcess.phase(resultPath, "production-startup");
        runReadProbe(sharedProfile, harnessWork, resultPath);
        DesktopProbeProcess.phase(resultPath, "read-run-complete");
        exitCode = 0;
      } catch (Throwable failure) {
        captureVisibleWindows(resultPath.getParent().resolve("visible-windows.png"));
        Files.writeString(
            resultPath,
            "failure=" + failure.getClass().getName() + ": " + failure.getMessage(),
            StandardCharsets.UTF_8);
        failure.printStackTrace(System.err);
      } finally {
        System.exit(exitCode);
      }
    } else {
      throw new IllegalArgumentException("Unknown mode: " + mode);
    }
  }

  private static void runSaveProbe(Path work, Path resultPath) throws Exception {
    Files.createDirectories(work);
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2,"
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

    DesktopProbeProcess.phase(resultPath, "open-settings");
    AtomicReference<ConfigDialog2> dialogRef = new AtomicReference<>();
    AtomicReference<Boolean> locatedRef = new AtomicReference<>();
    AtomicReference<Boolean> initiallyUncheckedRef = new AtomicReference<>();
    AtomicReference<Boolean> checkedAfterClickRef = new AtomicReference<>();
    AtomicReference<Boolean> saveClickedRef = new AtomicReference<>();
    AtomicReference<Boolean> closedRef = new AtomicReference<>();
    AtomicReference<Throwable> failureRef = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            ConfigDialog2 dialog = new ConfigDialog2();
            dialogRef.set(dialog);

            boolean located = dialog.locateSetting(TARGET);
            locatedRef.set(located);
            if (!located) {
              throw new AssertionError("Failed to locate setting: " + TARGET);
            }

            Timer timer =
                new Timer(
                    350,
                    event -> {
                      try {
                        DesktopProbeProcess.phase(resultPath, "observe-control");
                        JComponent row = findTargetRow(dialog.getContentPane(), TARGET);
                        if (row == null) {
                          throw new AssertionError("Target row not found for " + TARGET);
                        }

                        JCheckBox checkBox = findDescendantCheckBox(row);
                        if (checkBox == null) {
                          throw new AssertionError("Descendant checkbox not found for " + TARGET);
                        }

                        captureVisibleWindows(
                            resultPath.getParent().resolve("visible-windows.png"));

                        boolean initiallyChecked = checkBox.isSelected();
                        initiallyUncheckedRef.set(!initiallyChecked);
                        if (initiallyChecked) {
                          throw new AssertionError(
                              "Expected checkbox to be initially unchecked for " + TARGET);
                        }

                        DesktopProbeProcess.phase(resultPath, "click-checkbox");
                        checkBox.doClick();
                        boolean checkedAfter = checkBox.isSelected();
                        checkedAfterClickRef.set(checkedAfter);
                        if (!checkedAfter) {
                          throw new AssertionError(
                              "Expected checkbox to be checked after click for " + TARGET);
                        }

                        DesktopProbeProcess.phase(resultPath, "click-save");
                        JButton saveButton = findSaveButton(dialog);
                        if (saveButton == null) {
                          throw new AssertionError("Save button with actionCommand OK not found");
                        }

                        saveButton.doClick();
                        saveClickedRef.set(true);

                        DesktopProbeProcess.phase(resultPath, "confirm-closed");
                        boolean closed = !dialog.isVisible();
                        closedRef.set(closed);
                        if (!closed) {
                          dialog.setVisible(false);
                          throw new AssertionError(
                              "ConfigDialog2 did not close after clicking Save");
                        }
                      } catch (Throwable t) {
                        failureRef.set(t);
                        dialog.setVisible(false);
                      }
                    });
            timer.setRepeats(false);
            timer.start();
            dialog.setVisible(true);
          } catch (Throwable t) {
            failureRef.set(t);
          }
        });

    if (dialogRef.get() != null) {
      SwingUtilities.invokeAndWait(() -> dialogRef.get().dispose());
    }

    if (failureRef.get() != null) {
      throw new AssertionError("Save phase failed", failureRef.get());
    }
    if (!Boolean.TRUE.equals(locatedRef.get())
        || !Boolean.TRUE.equals(initiallyUncheckedRef.get())
        || !Boolean.TRUE.equals(checkedAfterClickRef.get())
        || !Boolean.TRUE.equals(saveClickedRef.get())
        || !Boolean.TRUE.equals(closedRef.get())) {
      throw new AssertionError("Save phase did not complete expected sequence successfully");
    }

    if (!Lizzie.config.winrateAlwaysBlack) {
      throw new AssertionError("Production config winrateAlwaysBlack was not updated in memory");
    }

    DesktopProbeProcess.phase(resultPath, "save-complete");
    Files.writeString(
        resultPath,
        "phase=save\n"
            + "located="
            + locatedRef.get()
            + "\n"
            + "initiallyUnchecked="
            + initiallyUncheckedRef.get()
            + "\n"
            + "checkedAfterClick="
            + checkedAfterClickRef.get()
            + "\n"
            + "saveClicked="
            + saveClickedRef.get()
            + "\n"
            + "closed="
            + closedRef.get()
            + "\n"
            + "configValue="
            + Lizzie.config.winrateAlwaysBlack
            + "\n",
        StandardCharsets.UTF_8);
  }

  private static void runReadProbe(Path sharedProfile, Path harnessWork, Path resultPath)
      throws Exception {
    System.setProperty("lizzie.work.dir", sharedProfile.toAbsolutePath().toString());

    Lizzie.main(new String[0]);
    SwingUtilities.invokeAndWait(
        () -> {
          if (Lizzie.frame == null || !Lizzie.frame.isDisplayable()) {
            throw new AssertionError("production startup did not create a displayable frame");
          }
        });

    if (!Lizzie.config.winrateAlwaysBlack) {
      throw new AssertionError(
          "Production config winrateAlwaysBlack was not true upon startup: "
              + Lizzie.config.winrateAlwaysBlack);
    }

    DesktopProbeProcess.phase(resultPath, "open-settings");
    AtomicReference<ConfigDialog2> dialogRef = new AtomicReference<>();
    AtomicReference<Boolean> locatedRef = new AtomicReference<>();
    AtomicReference<Boolean> selectedRef = new AtomicReference<>();
    AtomicReference<Throwable> failureRef = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            ConfigDialog2 dialog = new ConfigDialog2();
            dialogRef.set(dialog);

            boolean located = dialog.locateSetting(TARGET);
            locatedRef.set(located);
            if (!located) {
              throw new AssertionError("Failed to locate setting: " + TARGET);
            }

            Timer timer =
                new Timer(
                    350,
                    event -> {
                      try {
                        DesktopProbeProcess.phase(resultPath, "observe-control");
                        JComponent row = findTargetRow(dialog.getContentPane(), TARGET);
                        if (row == null) {
                          throw new AssertionError("Target row not found for " + TARGET);
                        }

                        JCheckBox checkBox = findDescendantCheckBox(row);
                        if (checkBox == null) {
                          throw new AssertionError("Descendant checkbox not found for " + TARGET);
                        }

                        captureVisibleWindows(
                            resultPath.getParent().resolve("visible-windows.png"));
                        captureVisibleWindows(resultPath.getParent().resolve("window.png"));

                        boolean selected = checkBox.isSelected();
                        selectedRef.set(selected);
                        if (!selected) {
                          throw new AssertionError(
                              "Expected checkbox for " + TARGET + " to be selected after restart");
                        }
                      } catch (Throwable t) {
                        failureRef.set(t);
                      } finally {
                        dialog.setVisible(false);
                      }
                    });
            timer.setRepeats(false);
            timer.start();
            dialog.setVisible(true);
          } catch (Throwable t) {
            failureRef.set(t);
          }
        });

    if (dialogRef.get() != null) {
      SwingUtilities.invokeAndWait(() -> dialogRef.get().dispose());
    }

    if (failureRef.get() != null) {
      throw new AssertionError("Read phase failed", failureRef.get());
    }
    if (!Boolean.TRUE.equals(locatedRef.get())) {
      throw new AssertionError("Failed to locate setting in read phase");
    }
    if (!Boolean.TRUE.equals(selectedRef.get())) {
      throw new AssertionError("Checkbox was not selected in read phase");
    }

    DesktopProbeProcess.phase(resultPath, "read-complete");
    Files.writeString(
        resultPath,
        "phase=read\n"
            + "located="
            + locatedRef.get()
            + "\n"
            + "checkboxSelected="
            + selectedRef.get()
            + "\n"
            + "configValue="
            + Lizzie.config.winrateAlwaysBlack
            + "\n"
            + "result=PASS\n",
        StandardCharsets.UTF_8);
  }

  private static JComponent findTargetRow(Component root, String targetId) {
    if (root instanceof JComponent
        && targetId.equals(((JComponent) root).getClientProperty(TARGET_ROW_PROPERTY))) {
      return (JComponent) root;
    }
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        JComponent match = findTargetRow(child, targetId);
        if (match != null) {
          return match;
        }
      }
    }
    return null;
  }

  private static JCheckBox findDescendantCheckBox(Component root) {
    if (root instanceof JCheckBox) {
      return (JCheckBox) root;
    }
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        JCheckBox match = findDescendantCheckBox(child);
        if (match != null) {
          return match;
        }
      }
    }
    return null;
  }

  private static JButton findSaveButton(ConfigDialog2 dialog) {
    if (dialog.getRootPane() != null && dialog.getRootPane().getDefaultButton() != null) {
      JButton defaultBtn = dialog.getRootPane().getDefaultButton();
      if ("OK".equals(defaultBtn.getActionCommand())) {
        return defaultBtn;
      }
    }
    return findButtonByActionCommand(dialog.getContentPane(), "OK");
  }

  private static JButton findButtonByActionCommand(Container container, String command) {
    if (container instanceof JButton && command.equals(((JButton) container).getActionCommand())) {
      return (JButton) container;
    }
    for (Component child : container.getComponents()) {
      if (child instanceof JButton && command.equals(((JButton) child).getActionCommand())) {
        return (JButton) child;
      }
      if (child instanceof Container) {
        JButton found = findButtonByActionCommand((Container) child, command);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static void captureVisibleWindows(Path targetPath) {
    try {
      Rectangle screen =
          GraphicsEnvironment.getLocalGraphicsEnvironment()
              .getDefaultScreenDevice()
              .getDefaultConfiguration()
              .getBounds();
      BufferedImage image = new Robot().createScreenCapture(screen);
      Files.createDirectories(targetPath.getParent());
      ImageIO.write(image, "png", targetPath.toFile());
    } catch (Throwable t) {
      System.err.println("Failed to capture visible windows: " + t.getMessage());
    }
  }
}
