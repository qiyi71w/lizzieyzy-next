package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchStyleTest {
  @TempDir Path tempDir;

  @Test
  void settingsNavigationHasOneClickableSurfaceAndKeyboardActions() throws Exception {
    Class<?> type = Class.forName("featurecat.lizzie.gui.ConfigDialog2$ModernTabComponent");
    java.lang.reflect.Constructor<?> constructor =
        type.getDeclaredConstructor(String.class, String.class, String.class);
    constructor.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            JButton button =
                (JButton) constructor.newInstance("Theme", "Fonts and colors", "theme");
            button.setSize(button.getPreferredSize());
            button.doLayout();
            for (java.awt.Component child : button.getComponents()) {
              if (child instanceof java.awt.Container) ((java.awt.Container) child).doLayout();
              assertSame(
                  button,
                  SwingUtilities.getDeepestComponentAt(
                      button,
                      child.getX() + child.getWidth() / 2,
                      child.getY() + child.getHeight() / 2));
            }
            assertTrue(button.isFocusable());
            assertEquals(
                javax.accessibility.AccessibleRole.PUSH_BUTTON,
                button.getAccessibleContext().getAccessibleRole());
            assertEquals("Theme", button.getAccessibleContext().getAccessibleName());
            java.util.concurrent.atomic.AtomicInteger clicks =
                new java.util.concurrent.atomic.AtomicInteger();
            button.addActionListener(e -> clicks.incrementAndGet());
            Object enter = button.getInputMap().get(javax.swing.KeyStroke.getKeyStroke("ENTER"));
            button
                .getActionMap()
                .get(enter)
                .actionPerformed(new java.awt.event.ActionEvent(button, 0, ""));
            for (String key : new String[] {"pressed SPACE", "released SPACE"}) {
              Object action = button.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(key));
              assertNotNull(action, key);
              button
                  .getActionMap()
                  .get(action)
                  .actionPerformed(new java.awt.event.ActionEvent(button, 0, ""));
            }
            assertEquals(2, clicks.get());
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        });
  }

  @Test
  void textAndSecondaryTextRemainReadableAcrossThemes() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.uiConfig = new JSONObject();
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        for (Color surface :
            new Color[] {
              AppleStyleSupport.workspaceSurface(), AppleStyleSupport.workspaceBackground()
            }) {
          assertTrue(contrast(AppleStyleSupport.dialogTextColor(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceMuted(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceSuccess(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceWarning(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceError(), surface) >= 4.5);
        }
        assertTrue(
            contrast(AppleStyleSupport.dialogTextColor(), AppleStyleSupport.workspaceSelection())
                >= 4.5);
        assertTrue(
            contrast(AppleStyleSupport.workspaceMuted(), AppleStyleSupport.workspaceSelection())
                >= 4.5);
      }
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void toolbarSurfaceRemainsReadableWithoutMorandiPalette() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.useMorandiColors = false;
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        BufferedImage image = new BufferedImage(300, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 40);
        AppleStyleSupport.paintToolbarSurface(g, 300, 40, false);
        g.dispose();
        Color surface = new Color(image.getRGB(150, 20));
        JButton button = new JButton("Last move");
        AppleStyleSupport.installButtonStyle(button);
        assertTrue(contrast(button.getForeground(), surface) >= 4.5);
      }
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void explicitThemesAndWallpaperAreNotOverwritten() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.uiConfig = new JSONObject().put("theme", "default");
      Lizzie.config = config;
      assertTrue(AppleStyleSupport.useNeutralWorkspaceBackground());
      config.uiConfig.put("theme", "my-theme");
      assertFalse(AppleStyleSupport.useNeutralWorkspaceBackground());
      config.uiConfig.put("theme", "default").put("background-image", "custom.png");
      assertFalse(AppleStyleSupport.useNeutralWorkspaceBackground());
      config.uiConfig.remove("background-image");
      config.uiConfig.put("custom-window-background-image", "my-wallpaper.png");
      assertFalse(AppleStyleSupport.useNeutralWorkspaceBackground());
      config.uiConfig.remove("custom-window-background-image");
      config.usePureBackground = true;
      assertFalse(AppleStyleSupport.useNeutralWorkspaceBackground());
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void disabledComboKeepsThemedSurfaceAndReadableLabel() throws Exception {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        SwingUtilities.invokeAndWait(
            () -> {
              AppleStyleSupport.applyUiDefaults();
              JComboBox<String> combo = new JComboBox<>(new String[] {"Transformer B11"});
              AppleStyleSupport.installComboBoxStyle(combo);
              combo.setEnabled(false);
              combo.setSize(300, 40);
              combo.doLayout();
              BufferedImage image = new BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB);
              Graphics2D g = image.createGraphics();
              combo.paint(g);
              g.dispose();
              Color background = new Color(image.getRGB(220, 20));
              assertEquals(combo.getBackground(), background);
              assertTrue(contrast(AppleStyleSupport.workspaceMuted(), background) >= 4.5);
            });
      }
    } finally {
      Lizzie.config = previous;
      AppleStyleSupport.applyUiDefaults();
    }
  }

  @Test
  void fontResolutionToleratesStartupBeforeUiConfiguration() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.uiConfig = null;
      config.uiFontName = Config.sysDefaultFontName;
      Lizzie.config = config;
      assertNotNull(AppleStyleSupport.workspaceFont(Font.PLAIN, 13));
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void readOnlySettingsTextRetainsWrappingAndNeverLooksEditable() throws Exception {
    java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    ConfigDialog2 dialog =
        (ConfigDialog2) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ConfigDialog2.class);
    java.lang.reflect.Method create =
        ConfigDialog2.class.getDeclaredMethod("createSettingText", String.class, boolean.class);
    java.lang.reflect.Method style =
        ConfigDialog2.class.getDeclaredMethod("modernizeComponentTree", java.awt.Component.class);
    create.setAccessible(true);
    style.setAccessible(true);
    JTextArea text = (JTextArea) create.invoke(dialog, "A long setting description", true);
    java.awt.Insets before = text.getInsets();
    style.invoke(dialog, text);
    assertEquals(before, text.getInsets());
    assertFalse(text.isOpaque());
    assertFalse(text.isEditable());
    assertTrue(text.getLineWrap());
    assertEquals(text.getText(), text.getAccessibleContext().getAccessibleName());
  }

  @Test
  void primaryActionsKeepLegibleTextAfterGlobalThemeRefresh() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        JButton button = new JButton("Save settings");
        AppleStyleSupport.preserveCustomButtonStyle(button);
        HumanSlTrainingStyle.stylePrimary(button);
        Dimension size = button.getPreferredSize();
        AppleStyleSupport.installButtonStyle(button);
        assertEquals(size, button.getPreferredSize());
        assertTrue(contrast(button.getForeground(), button.getBackground()) >= 4.5);
        button.setSize(size);
        BufferedImage image =
            new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        button.paint(graphics);
        graphics.dispose();
        assertEquals(button.getBackground().getRGB(), image.getRGB(size.width / 2, 5));
      }
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void suggestionTableRefreshKeepsDataAndFitsConfiguredFont() throws Exception {
    Config previous = Lizzie.config;
    int previousSize = Config.frameFontSize;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config = config;
      Config.frameFontSize = 22;
      java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      LizzieFrame frame =
          (LizzieFrame) ((sun.misc.Unsafe) field.get(null)).allocateInstance(LizzieFrame.class);
      frame.listTable =
          new JTable(new Object[][] {{"D4", "55.2%"}}, new Object[] {"Move", "Winrate"});
      frame.listScrollpane = new JScrollPane(frame.listTable);
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        frame.refreshSuggestionTableStyle();
        assertEquals("55.2%", frame.listTable.getValueAt(0, 1));
        assertEquals(
            AppleStyleSupport.workspaceSurface(),
            frame.listScrollpane.getViewport().getBackground());
        assertTrue(
            contrast(frame.listTable.getForeground(), frame.listTable.getBackground()) >= 4.5);
        assertTrue(
            frame.listTable.getRowHeight()
                >= frame.listTable.getFontMetrics(frame.listTable.getFont()).getHeight() + 8);
      }
    } finally {
      Lizzie.config = previous;
      Config.frameFontSize = previousSize;
    }
  }

  @Test
  void configuredFontAndLargerTextArePreserved() {
    Config previous = Lizzie.config;
    int previousSize = Config.frameFontSize;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.uiFontName = Font.MONOSPACED;
      Lizzie.config = config;
      Config.frameFontSize = 22;
      Font font = AppleStyleSupport.workspaceFont(Font.BOLD, 12);
      assertEquals(22, font.getSize());
      assertEquals(Font.MONOSPACED, font.getFamily());
    } finally {
      Lizzie.config = previous;
      Config.frameFontSize = previousSize;
    }
  }

  @Test
  void sixLanguageRowsWrapWithoutLosingControlOrOverlapping() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (String tag : new String[] {"zh-CN", "zh-TW", "en-US", "ja-JP", "ko", "th-TH"}) {
            ResourceBundle strings =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(tag));
            for (int size : new int[] {12, 18, 24}) {
              WorkbenchFormRow row = new WorkbenchFormRow();
              JPanel labels = new JPanel(new BorderLayout(0, 2));
              JTextArea text = new JTextArea(strings.getString("ConfigDialog2.modern.footerHint"));
              text.setLineWrap(true);
              text.setWrapStyleWord(true);
              text.setFont(new Font(Font.DIALOG, Font.PLAIN, size));
              labels.add(text);
              row.add(labels);
              JPanel controls = new JPanel();
              controls.setPreferredSize(new Dimension(240, 36));
              row.add(controls);
              for (int width : new int[] {360, 720, 1100}) {
                row.setSize(width, 400);
                row.setSize(width, row.getPreferredSize().height);
                row.doLayout();
                labels.doLayout();
                assertFalse(labels.getBounds().intersects(controls.getBounds()), tag);
                assertTrue(controls.getX() + controls.getWidth() <= width, tag);
                assertTrue(controls.getY() + controls.getHeight() <= row.getHeight(), tag);
                assertTrue(text.getHeight() >= text.getPreferredSize().height, tag);
                if (width >= 720) assertEquals(16, controls.getX() - labels.getWidth(), tag);
              }
            }
          }
        });
  }

  @Test
  void nestedControlGroupsWrapAndRemainInsideTheRow() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          WorkbenchFormRow row = new WorkbenchFormRow();
          JPanel labels = new JPanel(new BorderLayout());
          labels.add(new JTextArea("Image and texture settings"));
          row.add(labels);
          JPanel host = new JPanel(new BorderLayout());
          JPanel group = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 4));
          for (int i = 0; i < 4; i++) {
            JButton button = new JButton("Asset " + i);
            button.setPreferredSize(new Dimension(160, 38));
            group.add(button);
          }
          WorkbenchFormRow.prepareControls(group);
          host.add(group);
          row.add(host);
          for (int width : new int[] {760, 360, 1100, 480}) {
            row.setSize(width, 500);
            row.setSize(width, row.getPreferredSize().height);
            layoutTree(row);
            assertFalse(labels.getBounds().intersects(host.getBounds()));
            assertChildrenFit(row);
            if (width == 360) assertTrue(group.getHeight() >= 2 * 38 + 12);
          }
        });
  }

  private static void layoutTree(java.awt.Container root) {
    root.doLayout();
    for (java.awt.Component child : root.getComponents()) {
      if (child instanceof java.awt.Container) layoutTree((java.awt.Container) child);
    }
  }

  private static void assertChildrenFit(java.awt.Container root) {
    for (java.awt.Component child : root.getComponents()) {
      assertTrue(child.getX() >= 0 && child.getY() >= 0, child.toString());
      assertTrue(child.getX() + child.getWidth() <= root.getWidth(), child.toString());
      assertTrue(child.getY() + child.getHeight() <= root.getHeight(), child.toString());
      if (child instanceof JPanel) assertChildrenFit((JPanel) child);
    }
  }

  @Test
  void keyboardFocusIsVisibleAndDoesNotChangeButtonGeometry() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JButton button =
              new JButton("Analyze") {
                @Override
                public boolean hasFocus() {
                  return true;
                }
              };
          button.setPreferredSize(new Dimension(120, 36));
          for (double scale : new double[] {1, 1.5, 2}) {
            AppleStyleSupport.installButtonStyle(button);
            assertEquals(new Dimension(120, 36), button.getPreferredSize());
            button.setSize(button.getPreferredSize());
            BufferedImage image =
                new BufferedImage(
                    (int) (120 * scale), (int) (36 * scale), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.scale(scale, scale);
            button.paint(g);
            g.dispose();
            assertEquals(
                AppleStyleSupport.workspaceAccent().getRGB(),
                image.getRGB((int) (60 * scale), (int) (2 * scale)));
          }
        });
  }

  private static double contrast(Color a, Color b) {
    double first = luminance(a), second = luminance(b);
    return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
  }

  private static double luminance(Color color) {
    return .2126 * channel(color.getRed())
        + .7152 * channel(color.getGreen())
        + .0722 * channel(color.getBlue());
  }

  private static double channel(int value) {
    double normalized = value / 255.0;
    return normalized <= .04045 ? normalized / 12.92 : Math.pow((normalized + .055) / 1.055, 2.4);
  }
}
