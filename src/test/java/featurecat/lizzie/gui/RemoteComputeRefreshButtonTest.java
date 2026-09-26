package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;

class RemoteComputeRefreshButtonTest {
  @Test
  void refreshIconHasTwoBalancedArrowsAtAllSupportedScales() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var button = new RemoteComputeDialog.CatalogRefreshButton();
          Icon icon = button.getIcon();
          assertNotNull(icon);
          assertEquals(20, icon.getIconWidth());
          assertEquals(20, icon.getIconHeight());
          for (double scale : new double[] {1, 1.5, 2}) {
            int size = (int) (20 * scale);
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graphics.scale(scale, scale);
            icon.paintIcon(button, graphics, 0, 0);
            graphics.dispose();
            int ink = 0;
            int symmetryError = 0;
            for (int y = 0; y < size; y++) {
              for (int x = 0; x < size; x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 128) {
                  ink++;
                }
                symmetryError +=
                    Math.abs(alpha - (image.getRGB(size - x - 1, size - y - 1) >>> 24));
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                  assertEquals(0, alpha, "Icon must not clip at scale " + scale);
                }
              }
            }
            assertTrue(ink > 60 * scale * scale, "Refresh arrows must remain visible");
            assertTrue(symmetryError < size * size * 3, "Arrows must be visually balanced");
            assertEquals(0, image.getRGB(size / 2, size / 2) >>> 24);
          }
        });
  }

  @Test
  void buttonKeepsFixedHitTargetAcrossLoadingAndDisabledStates() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var button = new RemoteComputeDialog.CatalogRefreshButton();
          Dimension size = new Dimension(44, 44);
          assertEquals(size, button.getPreferredSize());
          button.setRefreshing(true);
          button.setEnabled(false);
          assertEquals(size, button.getPreferredSize());
          button.setRefreshing(false);
          button.setEnabled(true);
          assertEquals(size, button.getPreferredSize());
        });
  }

  @Test
  void hoverPressedDisabledAndLoadingStatesAreDistinct() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var button = new RemoteComputeDialog.CatalogRefreshButton();
          BufferedImage idle = paint(button);
          button.getModel().setRollover(true);
          BufferedImage hover = paint(button);
          button.getModel().setArmed(true);
          button.getModel().setPressed(true);
          BufferedImage pressed = paint(button);
          assertNotEquals(idle.getRGB(8, 22), hover.getRGB(8, 22));
          assertNotEquals(hover.getRGB(8, 22), pressed.getRGB(8, 22));
          button.setEnabled(false);
          BufferedImage disabled = paint(button);
          button.setRefreshing(true);
          BufferedImage loading = paint(button);
          assertEquals(0, countPixels(disabled, new Color(43, 139, 90)));
          assertTrue(countPixels(loading, new Color(43, 139, 90)) > 30);
          button.setRefreshing(false);
        });
  }

  @Test
  void detachedButtonDoesNotKeepAnimationTimerAlive() throws Exception {
    var button = new RemoteComputeDialog.CatalogRefreshButton();
    Field field = button.getClass().getDeclaredField("animationTimer");
    field.setAccessible(true);
    Timer timer = (Timer) field.get(button);
    SwingUtilities.invokeAndWait(
        () -> {
          button.setRefreshing(true);
          assertFalse(timer.isRunning());
          button.addNotify();
          try {
            assertTrue(timer.isRunning());
          } finally {
            button.removeNotify();
          }
          assertFalse(timer.isRunning());
          button.addNotify();
          try {
            assertTrue(timer.isRunning());
            button.setRefreshing(false);
            assertFalse(timer.isRunning());
          } finally {
            button.removeNotify();
          }
        });
  }

  @Test
  void standardButtonActionsAndAccessibleNameArePreserved() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var button = new RemoteComputeDialog.CatalogRefreshButton();
          AccessibilitySupport.button(button, "Refresh available models", "Reload remote models");
          assertEquals(
              "Refresh available models", button.getAccessibleContext().getAccessibleName());
          assertEquals("Reload remote models", button.getToolTipText());
          assertTrue(button.isFocusable());
          assertNotNull(button.getActionMap().get("pressed"));
          assertNotNull(button.getActionMap().get("released"));
          AtomicInteger calls = new AtomicInteger();
          button.addActionListener(event -> calls.incrementAndGet());
          button.doClick(0);
          assertEquals(1, calls.get());
          button.setEnabled(false);
          button.doClick(0);
          assertEquals(1, calls.get());
        });
  }

  private static BufferedImage paint(RemoteComputeDialog.CatalogRefreshButton button) {
    button.setSize(button.getPreferredSize());
    BufferedImage image = new BufferedImage(44, 44, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    button.paint(graphics);
    graphics.dispose();
    return image;
  }

  private static int countPixels(BufferedImage image, Color color) {
    int count = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) == color.getRGB()) {
          count++;
        }
      }
    }
    return count;
  }
}
