package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsDialogLayoutTest {
  @TempDir Path tempDir;
  private final ResourceBundle originalBundle = Lizzie.resourceBundle;
  private JDialog window;
  private JFrame owner;

  @AfterEach
  void restore() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          if (window != null) window.dispose();
          if (owner != null) owner.dispose();
          for (String name : List.of("openDialog", "openPanel")) {
            try {
              var field = DiagnosticsDialog.class.getDeclaredField(name);
              field.setAccessible(true);
              field.set(null, null);
            } catch (ReflectiveOperationException e) {
              throw new AssertionError(e);
            }
          }
          Lizzie.resourceBundle = originalBundle;
        });
    LoggingRuntime.resetForTests();
  }

  @Test
  void englishButtonsRemainVisibleAfterEstimate() throws Exception {
    verifyStableLayout(Locale.US);
  }

  @Test
  void chineseButtonsRemainVisibleAfterEstimate() throws Exception {
    verifyStableLayout(Locale.SIMPLIFIED_CHINESE);
  }

  @Test
  void longMetadataRemainsAccessibleWithoutWideningThePage() throws Exception {
    tempDir = tempDir.resolve("long-directory-".repeat(12)).resolve("nested-directory-".repeat(12));
    verifyStableLayout(Locale.US);
    SwingUtilities.invokeAndWait(
        () -> {
          DiagnosticsDialog panel = (DiagnosticsDialog) window.getContentPane();
          Dimension size = window.getSize();
          List<String> values =
              List.of(
                  panel.hostSessionText(),
                  panel.helperProcessSessionText(),
                  panel.estimateText(),
                  panel.hostAppLogText(),
                  panel.hostCrashLogText(),
                  tempDir.resolve("logs").toAbsolutePath().toString());
          int exercised = 0;
          for (Component component : descendants(panel)) {
            if (component instanceof JLabel label
                && values.contains(label.getText())
                && (!label.getText().equals(panel.helperProcessSessionText())
                    || descendants(label.getParent()).stream()
                        .anyMatch(
                            c ->
                                c instanceof JLabel title
                                    && Lizzie.resourceBundle
                                        .getString("DiagnosticsDialog.helperProcessSession")
                                        .equals(title.getText())))) {
              String fullText =
                  label.getText()
                      + " process-session-".repeat(80)
                      + " (unknown content / before filtering)";
              label.setText(fullText);
              assertEquals(fullText, label.getToolTipText());
              exercised++;
            }
          }
          assertEquals(6, exercised);
          window.validate();
          assertEquals(size, window.getSize());
          assertButtonsVisible();
          assertWithinWorkArea();
        });
  }

  @Test
  void constrainedWorkAreaKeepsActionsReachableByScrolling() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    LoggingRuntime runtime = start();
    CountDownLatch estimated = new CountDownLatch(1);
    SwingUtilities.invokeAndWait(
        () -> {
          owner = new JFrame();
          owner.setBounds(-100, -100, 400, 300);
          owner.setVisible(true);
          window = DiagnosticsDialog.open(owner, runtime, null);
          observeEstimate((DiagnosticsDialog) window.getContentPane(), estimated);
          assertActionsReachable();
        });
    assertTrue(estimated.await(10, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> {
          window.validate();
          assertActionsReachable();
        });
  }

  private void assertActionsReachable() {
    assertWithinWorkArea();
    JScrollPane scroll = scrollPane();
    if (window.getGraphicsConfiguration().getBounds().width >= 1600) {
      assertFalse(
          scroll.getHorizontalScrollBar().isVisible(),
          "a height-limited window should reserve width for its vertical scrollbar");
    }
    for (JButton button : primaryButtons()) {
      Rectangle bounds =
          SwingUtilities.convertRectangle(
              button.getParent(), button.getBounds(), scroll.getViewport().getView());
      ((javax.swing.JComponent) scroll.getViewport().getView()).scrollRectToVisible(bounds);
      assertTrue(scroll.getViewport().getViewRect().contains(bounds), button.getText());
    }
  }

  @Test
  void manualSizeSurvivesEstimateRefreshAndReopen() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    LoggingRuntime runtime = start();
    CountDownLatch estimated = new CountDownLatch(1);
    Dimension[] resized = new Dimension[1];
    SwingUtilities.invokeAndWait(
        () -> {
          window = DiagnosticsDialog.open(null, runtime, null);
          DiagnosticsDialog panel = (DiagnosticsDialog) window.getContentPane();
          observeEstimate(panel, estimated);
          window.setSize(window.getWidth() + 40, window.getHeight() + 30);
          resized[0] = window.getSize();
          DiagnosticsDialog.notifyRuntimeChanged();
          window.setVisible(false);
          assertEquals(window, DiagnosticsDialog.open(null, runtime, null));
          assertEquals(resized[0], window.getSize());
        });
    assertTrue(estimated.await(10, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(() -> assertEquals(resized[0], window.getSize()));
  }

  private LoggingRuntime start() {
    LoggingRuntime.resetForTests();
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    return LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
  }

  private void assertWithinWorkArea() {
    var graphics =
        owner == null ? window.getGraphicsConfiguration() : owner.getGraphicsConfiguration();
    Rectangle area = graphics.getBounds();
    var insets = Toolkit.getDefaultToolkit().getScreenInsets(graphics);
    area =
        new Rectangle(
            area.x + insets.left,
            area.y + insets.top,
            area.width - insets.left - insets.right,
            area.height - insets.top - insets.bottom);
    assertTrue(area.contains(window.getBounds()), window.getBounds() + " outside " + area);
    assertTrue(window.getMinimumSize().width <= area.width);
    assertTrue(window.getMinimumSize().height <= area.height);
  }

  private void verifyStableLayout(Locale locale) throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    LoggingRuntime runtime = start();
    CountDownLatch estimated = new CountDownLatch(1);
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
          window = DiagnosticsDialog.open(null, runtime, null);
          DiagnosticsDialog panel = (DiagnosticsDialog) window.getContentPane();
          // Estimate publication is queued on this EDT; inspect the initial window before yielding.
          assertEquals("", panel.estimateText());
          assertButtonsVisible();
          observeEstimate(panel, estimated);
        });
    assertTrue(estimated.await(10, TimeUnit.SECONDS), "estimate was not published");
    SwingUtilities.invokeAndWait(
        () -> {
          window.validate();
          assertButtonsVisible();
        });
  }

  private static void observeEstimate(DiagnosticsDialog panel, CountDownLatch estimated) {
    for (Component component : descendants(panel)) {
      if (component instanceof JLabel label) {
        label.addPropertyChangeListener(
            "text",
            event -> {
              if (!panel.estimateText().isEmpty()) estimated.countDown();
            });
      }
    }
  }

  private JScrollPane scrollPane() {
    return (JScrollPane)
        descendants(window).stream()
            .filter(JScrollPane.class::isInstance)
            .findFirst()
            .orElseThrow();
  }

  private List<JButton> primaryButtons() {
    List<JButton> buttons = new ArrayList<>();
    for (String key : List.of("openLogs", "apply", "exportDefault")) {
      String text = Lizzie.resourceBundle.getString("DiagnosticsDialog." + key);
      buttons.add(
          (JButton)
              descendants(window).stream()
                  .filter(c -> c instanceof JButton b && text.equals(b.getText()))
                  .findFirst()
                  .orElseThrow());
    }
    return buttons;
  }

  private void assertButtonsVisible() {
    JScrollPane scroll = scrollPane();
    Rectangle visible = scroll.getViewport().getViewRect();
    for (JButton button : primaryButtons()) {
      Rectangle bounds =
          SwingUtilities.convertRectangle(
              button.getParent(), button.getBounds(), scroll.getViewport().getView());
      assertTrue(
          visible.contains(bounds), button.getText() + ": " + bounds + " outside " + visible);
    }
    assertFalse(scroll.getHorizontalScrollBar().isVisible(), "horizontal scrollbar");
    assertFalse(scroll.getVerticalScrollBar().isVisible(), "vertical scrollbar");
  }

  private static List<Component> descendants(Container parent) {
    List<Component> result = new ArrayList<>();
    for (Component child : parent.getComponents()) {
      result.add(child);
      if (child instanceof Container container) result.addAll(descendants(container));
    }
    return result;
  }
}
