package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicButtonUI;

/** Shared visual tokens for the AI coaching setup, in-game bar and report. */
final class HumanSlTrainingStyle {
  private static final String BUTTON_STYLE_ROLE = "lizzie.humansl.buttonStyle";
  private static final String BUTTON_STYLE_PRIMARY = "primary";
  private static final String BUTTON_STYLE_SECONDARY = "secondary";
  private static final String BUTTON_STYLE_DANGER = "danger";

  static Color BACKGROUND() {
    return AppleStyleSupport.workspaceBackground();
  }

  static Color CARD() {
    return AppleStyleSupport.workspaceSurface();
  }

  static Color CARD_ALT() {
    return AppleStyleSupport.workspaceBackground();
  }

  static Color BORDER() {
    return AppleStyleSupport.workspaceBorder();
  }

  static Color TEXT() {
    return AppleStyleSupport.dialogTextColor();
  }

  static Color MUTED() {
    return AppleStyleSupport.workspaceMuted();
  }

  static final Color ACCENT = new Color(15, 118, 110);

  static Color ACCENT_DARK() {
    return AppleStyleSupport.workspaceAccent();
  }

  static Color ACCENT_SOFT() {
    return AppleStyleSupport.workspaceSelection();
  }

  static final Color WARNING = new Color(191, 75, 48);
  static final Color WARNING_SOFT = new Color(252, 238, 231);
  private static final Map<String, String> AVAILABLE_FONTS = availableFonts();

  private HumanSlTrainingStyle() {}

  static void stylePrimary(AbstractButton button) {
    button.putClientProperty(BUTTON_STYLE_ROLE, BUTTON_STYLE_PRIMARY);
    styleButton(button, ACCENT, Color.WHITE, ACCENT, 12);
  }

  static void styleSecondary(AbstractButton button) {
    button.putClientProperty(BUTTON_STYLE_ROLE, BUTTON_STYLE_SECONDARY);
    styleButton(button, CARD(), TEXT(), BORDER(), 12);
  }

  static void styleDanger(AbstractButton button) {
    button.putClientProperty(BUTTON_STYLE_ROLE, BUTTON_STYLE_DANGER);
    styleButton(button, WARNING, Color.WHITE, WARNING, 12);
  }

  static boolean restoreCustomButtonStyle(AbstractButton button) {
    Object role = button.getClientProperty(BUTTON_STYLE_ROLE);
    if (BUTTON_STYLE_PRIMARY.equals(role)) {
      stylePrimary(button);
      return true;
    }
    if (BUTTON_STYLE_SECONDARY.equals(role)) {
      styleSecondary(button);
      return true;
    }
    if (BUTTON_STYLE_DANGER.equals(role)) {
      styleDanger(button);
      return true;
    }
    return false;
  }

  static void copyCustomButtonStyle(AbstractButton source, AbstractButton target) {
    if (source == null || target == null) {
      return;
    }
    target.putClientProperty(BUTTON_STYLE_ROLE, source.getClientProperty(BUTTON_STYLE_ROLE));
  }

  private static void styleButton(
      AbstractButton button, Color fill, Color text, Color border, int radius) {
    button.setUI(new RoundedButtonUI(fill, border, radius));
    button.setContentAreaFilled(false);
    button.setOpaque(false);
    button.setFocusPainted(false);
    button.setBackground(fill);
    button.setForeground(text);
    button.setFont(fontForText(button.getText(), Font.BOLD, Math.max(12, Config.frameFontSize)));
    button.setBorder(
        BorderFactory.createCompoundBorder(
            new RoundedBorder(border, radius), BorderFactory.createEmptyBorder(7, 14, 7, 14)));
  }

  /** Uses a physical UI font so mixed CJK, Latin and digits cannot disappear in Swing. */
  static Font fontForText(String value, int style, int size) {
    String text = value == null ? "" : value;
    int resolvedSize = Math.max(10, size);
    LinkedHashMap<String, Boolean> candidates = new LinkedHashMap<>();
    addCandidate(candidates, Lizzie.config == null ? null : Lizzie.config.uiFontName);
    addCandidate(candidates, Lizzie.config == null ? null : Lizzie.config.fontName);
    if (containsThai(text)) {
      addCandidates(
          candidates, "Thonburi", "Leelawadee UI", "Noto Sans Thai", "Tahoma", "Arial Unicode MS");
    } else if (containsHangul(text)) {
      addCandidates(
          candidates,
          "Apple SD Gothic Neo",
          "Malgun Gothic",
          "Noto Sans CJK KR",
          "Noto Sans KR",
          "Arial Unicode MS");
    } else if (containsKana(text)) {
      addCandidates(
          candidates,
          "Hiragino Sans",
          "Yu Gothic UI",
          "Yu Gothic",
          "Meiryo",
          "Noto Sans CJK JP",
          "Arial Unicode MS");
    } else if (containsHan(text)) {
      addCandidates(
          candidates,
          "PingFang SC",
          "PingFang TC",
          "PingFang HK",
          "Microsoft YaHei UI",
          "Microsoft JhengHei UI",
          "Hiragino Sans GB",
          "Noto Sans CJK SC",
          "Source Han Sans SC",
          "WenQuanYi Zen Hei",
          "Arial Unicode MS");
    }
    addCandidates(
        candidates,
        "SF Pro Text",
        "Helvetica Neue",
        "Segoe UI",
        "Noto Sans",
        "DejaVu Sans",
        "Arial",
        "Arial Unicode MS");
    for (String requested : candidates.keySet()) {
      String available = AVAILABLE_FONTS.get(requested.toLowerCase(Locale.ROOT));
      if (available == null) {
        continue;
      }
      Font candidate = new Font(available, style, resolvedSize);
      if (candidate.canDisplayUpTo(text) < 0) {
        return candidate;
      }
    }
    return new Font(Font.DIALOG, style, resolvedSize);
  }

  private static Map<String, String> availableFonts() {
    Map<String, String> fonts = new LinkedHashMap<>();
    for (String family :
        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
      fonts.putIfAbsent(family.toLowerCase(Locale.ROOT), family);
    }
    return fonts;
  }

  private static void addCandidates(LinkedHashMap<String, Boolean> candidates, String... names) {
    if (names == null) {
      return;
    }
    for (String name : names) {
      addCandidate(candidates, name);
    }
  }

  private static void addCandidate(LinkedHashMap<String, Boolean> candidates, String name) {
    if (name != null && !name.trim().isEmpty()) {
      candidates.put(name.trim(), Boolean.TRUE);
    }
  }

  private static boolean containsThai(String value) {
    return value.codePoints().anyMatch(codePoint -> codePoint >= 0x0E00 && codePoint <= 0x0E7F);
  }

  private static boolean containsHangul(String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint ->
                (codePoint >= 0x1100 && codePoint <= 0x11FF)
                    || (codePoint >= 0xAC00 && codePoint <= 0xD7AF));
  }

  private static boolean containsKana(String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint ->
                (codePoint >= 0x3040 && codePoint <= 0x30FF)
                    || (codePoint >= 0x31F0 && codePoint <= 0x31FF));
  }

  private static boolean containsHan(String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint ->
                (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                    || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                    || (codePoint >= 0xF900 && codePoint <= 0xFAFF));
  }

  private static final class RoundedButtonUI extends BasicButtonUI {
    private final Color fill;
    private final Color outline;
    private final int radius;

    private RoundedButtonUI(Color fill, Color outline, int radius) {
      this.fill = fill;
      this.outline = outline;
      this.radius = radius;
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      ButtonModel model = button.getModel();
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      Color currentFill = fill;
      if (!button.isEnabled()) {
        currentFill = blend(fill, BACKGROUND(), 0.55f);
      } else if (model.isPressed() || model.isSelected()) {
        currentFill = blend(fill, Color.BLACK, 0.12f);
      } else if (model.isRollover()) {
        currentFill = blend(fill, Color.WHITE, 0.10f);
      }
      g2.setColor(currentFill);
      g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), radius, radius);
      g2.setColor(outline);
      g2.drawRoundRect(
          0,
          0,
          Math.max(0, component.getWidth() - 1),
          Math.max(0, component.getHeight() - 1),
          radius,
          radius);
      if (button.hasFocus()) {
        g2.setColor(button.getForeground());
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(3, 3, component.getWidth() - 7, component.getHeight() - 7, radius, radius);
      }
      g2.dispose();
      super.paint(graphics, component);
    }

    private static Color blend(Color base, Color overlay, float amount) {
      float clamped = Math.max(0f, Math.min(1f, amount));
      float keep = 1f - clamped;
      return new Color(
          Math.round(base.getRed() * keep + overlay.getRed() * clamped),
          Math.round(base.getGreen() * keep + overlay.getGreen() * clamped),
          Math.round(base.getBlue() * keep + overlay.getBlue() * clamped),
          base.getAlpha());
    }
  }

  static ImageIcon coachIcon(int size, boolean light) {
    URL resource = HumanSlTrainingStyle.class.getResource("/assets/ui/ai-coach/coach-target.png");
    if (resource == null) {
      return null;
    }
    Image image = new ImageIcon(resource).getImage();
    Image scaled =
        light
            ? AppleStyleSupport.brightenIcon(image, size)
            : image.getScaledInstance(size, size, Image.SCALE_SMOOTH);
    return new ImageIcon(scaled);
  }

  static class RoundedPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final Color fill;
    private final Color outline;
    private final int radius;

    RoundedPanel(Color fill, Color outline, int radius) {
      this.fill = fill;
      this.outline = outline;
      this.radius = radius;
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(fill);
      g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
      if (outline != null) {
        g2.setColor(outline);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
      }
      g2.dispose();
      super.paintComponent(graphics);
    }
  }

  static final class RoundedBorder extends AbstractBorder {
    private static final long serialVersionUID = 1L;
    private final Color color;
    private final int radius;

    RoundedBorder(Color color, int radius) {
      this.color = color;
      this.radius = radius;
    }

    @Override
    public void paintBorder(
        Component component, Graphics graphics, int x, int y, int width, int height) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(color);
      g2.setStroke(new BasicStroke(1f));
      g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
      g2.dispose();
    }

    @Override
    public java.awt.Insets getBorderInsets(Component component) {
      return new java.awt.Insets(1, 1, 1, 1);
    }
  }

  static void fixedHeight(JComponent component, int height) {
    Dimension preferred = component.getPreferredSize();
    component.setPreferredSize(new Dimension(preferred.width, height));
    component.setMinimumSize(new Dimension(1, height));
  }
}
