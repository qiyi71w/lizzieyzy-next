package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.LocaleFontSupport;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Robot;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native layouts only; no measured tuning is applied and no engine is started. */
class PerformanceActionsDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"1", "1.5", "2"})
  void performanceActionsFitAtEachScale(String scale) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            PerformanceActionsDesktopTest.class,
            "performance-actions-" + scale,
            List.of("-Dsun.java2d.uiScale=" + scale),
            List.of("probe"),
            120);
    assertEquals("passed", Files.readString(result));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      long deadline = System.nanoTime() + 15_000_000_000L;
      while (Lizzie.frame == null && System.nanoTime() < deadline) Thread.sleep(50);
      assertNotNull(Lizzie.frame);
      for (Locale locale :
          List.of(
              Locale.SIMPLIFIED_CHINESE,
              Locale.TRADITIONAL_CHINESE,
              Locale.US,
              Locale.JAPAN,
              Locale.KOREA,
              Locale.forLanguageTag("th-TH"))) {
        KataGoAutoSetupDialog[] dialog = new KataGoAutoSetupDialog[1];
        SwingUtilities.invokeAndWait(
            () -> {
              Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
              Config.sysDefaultFontName =
                  LocaleFontSupport.resolveDefaultFontName(
                      Lizzie.resourceBundle.getString("Lizzie.defaultFontName"), locale);
              Lizzie.setUIFont(
                  new javax.swing.plaf.FontUIResource(
                      Config.sysDefaultFontName, java.awt.Font.PLAIN, 12));
              dialog[0] = new KataGoAutoSetupDialog(Lizzie.frame);
              ((JList<?>) field(dialog[0], "sectionNav")).setSelectedIndex(2);
              dialog[0].setVisible(true);
            });
        Thread.sleep(700);
        new Robot().waitForIdle();
        Rectangle[] bounds = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> bounds[0] = dialog[0].getBounds());
        ImageIO.write(
            new Robot().createScreenCapture(bounds[0]),
            "png",
            result.getParent().resolve("performance-" + locale + ".png").toFile());
        SwingUtilities.invokeAndWait(
            () -> {
              for (String name :
                  List.of(
                      "btnOptimizePerformance",
                      "btnImportMeasuredTuning",
                      "btnRestoreMeasuredTuning")) {
                AbstractButton button = (AbstractButton) field(dialog[0], name);
                assertEquals(
                    -1,
                    button.getFont().canDisplayUpTo(button.getText()),
                    locale + " " + name + " missing glyph");
                Insets insets = button.getInsets();
                Rectangle view =
                    new Rectangle(
                        insets.left,
                        insets.top,
                        button.getWidth() - insets.left - insets.right,
                        button.getHeight() - insets.top - insets.bottom);
                String displayed =
                    SwingUtilities.layoutCompoundLabel(
                        button,
                        button.getFontMetrics(button.getFont()),
                        button.getText(),
                        button.getIcon(),
                        button.getVerticalAlignment(),
                        button.getHorizontalAlignment(),
                        button.getVerticalTextPosition(),
                        button.getHorizontalTextPosition(),
                        view,
                        new Rectangle(),
                        new Rectangle(),
                        button.getIconTextGap());
                assertEquals(
                    button.getText(),
                    displayed,
                    locale
                        + " "
                        + name
                        + " actual="
                        + button.getSize()
                        + " preferred="
                        + button.getPreferredSize());
                assertTrue(button.getHeight() >= 32, locale + " " + name + " touch target");
                var cards = (javax.swing.JComponent) field(dialog[0], "detailCards");
                Rectangle local =
                    SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), cards);
                assertTrue(
                    local.x >= 0 && local.x + local.width <= cards.getWidth(),
                    locale
                        + " "
                        + name
                        + " horizontal overflow: "
                        + local
                        + " / "
                        + cards.getSize());
              }
              dialog[0].dispose();
            });
      }
      Files.writeString(result, "passed");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, "failed: " + failure);
      System.exit(1);
    }
  }

  private static Object field(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
