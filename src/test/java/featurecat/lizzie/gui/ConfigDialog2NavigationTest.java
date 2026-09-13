package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.search.FunctionCatalog;
import java.awt.Component;
import java.awt.Color;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.border.Border;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the navigation check against a real application startup in an isolated JVM. */
public final class ConfigDialog2NavigationTest {
  private static final String TARGET = "settings.black-winrate";
  private static final String THEME_TARGET = "config.theme.score-blunders";
  private static final Color HIGHLIGHT_COLOR = new Color(239, 219, 170);
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
    assertTrue(result.contains("theme.located=true"), result);
    assertTrue(result.contains("theme.visible=true"), result);
    assertTrue(result.contains("theme.focus=true"), result);
    assertTrue(result.contains("theme.highlight=true"), result);
    assertTrue(result.contains("first.located=true"), result);
    assertTrue(result.contains("first.focus=true"), result);
    assertTrue(result.contains("first.visible=true"), result);
    assertTrue(result.contains("rebound.focus=true"), result);
    assertTrue(result.contains("rebound.visible=true"), result);
    assertTrue(result.contains("value-preserved=true"), result);
    assertTrue(result.contains("catalog.count=80"), result);
    assertTrue(result.contains("catalog.visible=true"), result);
    assertTrue(result.contains("catalog.focus=true"), result);
    assertTrue(result.contains("catalog.title=true"), result);
    assertTrue(result.contains("highlight-cleared=true"), result);
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

    TargetObservation themeObservation = showAndObserveThemeFirstLoad();
    if (!themeObservation.located
        || !themeObservation.visible
        || !themeObservation.focused
        || !themeObservation.highlighted) {
      throw new AssertionError(
          "initial theme navigation failed: located="
              + themeObservation.located
              + ", visible="
              + themeObservation.visible
              + ", focused="
              + themeObservation.focused
              + ", highlighted="
              + themeObservation.highlighted);
    }
    AtomicReference<ConfigDialog2> firstRef = new AtomicReference<>();
    AtomicReference<Boolean> unknown = new AtomicReference<>();
    runOnEdt(
        () -> {
          ConfigDialog2 dialog = new ConfigDialog2();
          verifySettingBindingsSurviveReordering(dialog);
          firstRef.set(dialog);
          unknown.set(dialog.locateSetting("settings.not-real"));
        });
    ConfigDialog2 first = firstRef.get();
    boolean initialValue = Lizzie.config.winrateAlwaysBlack;
    AtomicReference<Boolean> firstLocated = new AtomicReference<>();
    AtomicReference<Border> originalTargetBorder = new AtomicReference<>();
    ModalObservation firstObservation =
        showAndObserve(
            first,
            () -> {
              firstLocated.set(first.locateSetting(TARGET));
              originalTargetBorder.set(targetRow(first).getBorder());
            });
    JCheckBox firstControl = firstObservation.control;
    AtomicReference<Boolean> highlightClearedRef = new AtomicReference<>();
    runOnEdt(
        () ->
            highlightClearedRef.set(
                targetRow(first).getBorder() == originalTargetBorder.get()));
    boolean highlightCleared = Boolean.TRUE.equals(highlightClearedRef.get());
    CatalogObservation catalogObservation = showAndObserveAll(first);

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
            + "\ntheme.located="
            + themeObservation.located
            + "\ntheme.visible="
            + themeObservation.visible
            + "\ntheme.focus="
            + themeObservation.focused
            + "\ntheme.highlight="
            + themeObservation.highlighted
            + "\nfirst.located="
            + firstLocated.get()
            + "\nfirst.focus="
            + firstObservation.focused
            + "\nfirst.visible="
            + firstObservation.visible
            + "\ncatalog.count="
            + catalogObservation.count
            + "\ncatalog.visible="
            + catalogObservation.visible
            + "\ncatalog.focus="
            + catalogObservation.focused
            + "\ncatalog.title="
            + catalogObservation.title
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
            + "\nhighlight-cleared="
            + highlightCleared
            + "\nhidden-focus="
            + hiddenFocus);
  }

  private static TargetObservation showAndObserveThemeFirstLoad() throws Exception {
    AtomicReference<TargetObservation> observation = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnEdt(
        () -> {
          ConfigDialog2 dialog = new ConfigDialog2();
          boolean located = dialog.locateSetting(THEME_TARGET);
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
          javax.swing.Timer timer =
              new javax.swing.Timer(
                  50,
                  event -> {
                    try {
                      JComponent row = targetRow(dialog, THEME_TARGET);
                      Component focus =
                          KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                      boolean visible = fullyVisible(dialog, row);
                      boolean focused =
                          focus == row
                              || (focus != null && SwingUtilities.isDescendingFrom(focus, row));
                      boolean highlighted = rendersColor(row, HIGHLIGHT_COLOR);
                      if (located && visible && focused && highlighted
                          || System.nanoTime() >= deadline) {
                        observation.set(
                            new TargetObservation(located, visible, focused, highlighted));
                        ((javax.swing.Timer) event.getSource()).stop();
                        dialog.setVisible(false);
                      }
                    } catch (Throwable error) {
                      failure.set(error);
                      ((javax.swing.Timer) event.getSource()).stop();
                      dialog.setVisible(false);
                    }
                  });
          timer.setInitialDelay(50);
          timer.start();

          dialog.setVisible(true);
          dialog.dispose();
        });
    if (failure.get() != null) {
      throw new AssertionError("initial theme navigation observation failed", failure.get());
    }
    if (observation.get() == null) {
      throw new AssertionError("initial theme navigation observation did not run");
    }
    return observation.get();
  }

  private static CatalogObservation showAndObserveAll(ConfigDialog2 dialog) throws Exception {
    List<FunctionCatalog.ConfigSettingTarget> targets = FunctionCatalog.configSettingTargets();
    AtomicReference<CatalogObservation> observation = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnEdt(
        () -> {
          int[] next = {0};
          String[] pending = {null};
          boolean[] visible = {true};
          boolean[] focused = {true};
          boolean[] title = {true};
          javax.swing.Timer timer = new javax.swing.Timer(140, null);
          timer.addActionListener(
              event -> {
                try {
                  if (pending[0] != null) {
                    JComponent row = targetRow(dialog, pending[0]);
                    visible[0] &= fullyVisible(dialog, row);
                    Component focus =
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    boolean currentFocused =
                        focus == row || (focus != null && SwingUtilities.isDescendingFrom(focus, row));
                    if (!currentFocused) {
                      throw new AssertionError(
                          "setting target did not receive focus: "
                              + pending[0]
                              + ", owner="
                              + (focus == null ? "null" : focus.getClass().getName()));
                    }
                    focused[0] &= currentFocused;
                    String expectedTitle =
                        Lizzie.resourceBundle.getString(
                            FunctionCatalog.configSettingTarget(pending[0]).titleKey());
                    title[0] &= containsAccessibleName(row, expectedTitle);
                  }
                  if (next[0] == targets.size()) {
                    timer.stop();
                    observation.set(
                        new CatalogObservation(
                            targets.size(), visible[0], focused[0], title[0]));
                    dialog.setVisible(false);
                    return;
                  }
                  pending[0] = targets.get(next[0]++).id();
                  if (!dialog.locateSetting(pending[0])) {
                    throw new AssertionError("setting target was not attached: " + pending[0]);
                  }
                } catch (Throwable error) {
                  failure.set(error);
                  timer.stop();
                  dialog.setVisible(false);
                }
              });
          timer.setInitialDelay(0);
          timer.start();
          dialog.setVisible(true);
        });
    if (failure.get() != null) {
      throw new AssertionError("catalog navigation observation failed", failure.get());
    }
    if (observation.get() == null) {
      throw new AssertionError("catalog navigation observation did not run");
    }
    return observation.get();
  }

  private static boolean containsAccessibleName(Component root, String expected) {
    if (root.getAccessibleContext() != null
        && expected.equals(root.getAccessibleContext().getAccessibleName())) return true;
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        if (containsAccessibleName(child, expected)) return true;
      }
    }
    return false;
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
    return targetRow(dialog, TARGET);
  }

  private static JComponent targetRow(ConfigDialog2 dialog, String targetId) {
    Component row = findTargetRow(dialog.getContentPane(), targetId);
    if (!(row instanceof JComponent)) {
      throw new AssertionError(targetId + " row is not attached");
    }
    return (JComponent) row;
  }
  private static Component findTargetRow(Component root, String targetId) {
    if (root instanceof JComponent
        && targetId.equals(((JComponent) root).getClientProperty(TARGET_ROW_PROPERTY))) {
      return root;
    }
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        Component match = findTargetRow(child, targetId);
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

  private static boolean rendersColor(JComponent component, Color expected) {
    BufferedImage image =
        new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    try {
      component.getBorder().paintBorder(
          component, graphics, 0, 0, component.getWidth(), component.getHeight());
    } finally {
      graphics.dispose();
    }
    int expectedRgb = expected.getRGB();
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) == expectedRgb) return true;
      }
    }
    return false;
  }

  private static void verifySettingBindingsSurviveReordering(ConfigDialog2 dialog) {
    try {
      var create = ConfigDialog2.class.getDeclaredMethod("createDisplaySection", int.class);
      var validate = ConfigDialog2.class.getDeclaredMethod(
          "validateSettingRows", Component.class, FunctionCatalog.SettingSection.class);
      create.setAccessible(true);
      validate.setAccessible(true);
      Component section = (Component) create.invoke(dialog, 1);
      String firstId = "config.kifu.auto-analyze";
      String secondId = "config.kifu.jump-last";
      JComponent first = (JComponent) findTargetRow(section, firstId);
      JComponent second = (JComponent) findTargetRow(section, secondId);
      assertTrue(first != null && second != null, "settings must be bound when created");
      Container card = first.getParent();
      card.setComponentZOrder(second, card.getComponentZOrder(first));
      validate.invoke(dialog, section, FunctionCatalog.SettingSection.KIFU);
      assertTrue(findTargetRow(section, firstId) == first, "reordering must retain auto-analyze target");
      assertTrue(findTargetRow(section, secondId) == second, "reordering must retain jump-last target");

      for (String invalid : new String[] {null, "settings.not-real", firstId}) {
        second.putClientProperty(TARGET_ROW_PROPERTY, invalid);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> validate.invoke(dialog, section, FunctionCatalog.SettingSection.KIFU));
        assertTrue(failure.getCause() instanceof IllegalStateException);
      }
      second.putClientProperty(TARGET_ROW_PROPERTY, secondId);
      card.remove(second);
      var missing = org.junit.jupiter.api.Assertions.assertThrows(
          java.lang.reflect.InvocationTargetException.class,
          () -> validate.invoke(dialog, section, FunctionCatalog.SettingSection.KIFU));
      assertTrue(missing.getCause() instanceof IllegalStateException);
      card.add(second);
      validate.invoke(dialog, section, FunctionCatalog.SettingSection.KIFU);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void runOnEdt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static void flushEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static final class CatalogObservation {
    private final int count;
    private final boolean visible;
    private final boolean focused;
    private final boolean title;

    private CatalogObservation(int count, boolean visible, boolean focused, boolean title) {
      this.count = count;
      this.visible = visible;
      this.focused = focused;
      this.title = title;
    }
  }

  private static final class TargetObservation {
    private final boolean located;
    private final boolean visible;
    private final boolean focused;
    private final boolean highlighted;

    private TargetObservation(
        boolean located, boolean visible, boolean focused, boolean highlighted) {
      this.located = located;
      this.visible = visible;
      this.focused = focused;
      this.highlighted = highlighted;
    }
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
