package featurecat.lizzie.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.View;

/**
 * Acceleration-page layout restored from c53265b, without its older engine/setup implementation.
 * All state, permissions and actions continue to be owned by KataGoAutoSetupDialog.
 */
final class KataGoAccelerationLayout {
  private KataGoAccelerationLayout() {}

  static JPanel statusRow(JLabel title, JTextArea status) {
    return new StatusRow(title, status);
  }

  static JPanel primaryActions(JComponent repair, JComponent enable) {
    return new WrappingActions(repair, enable);
  }

  static JPanel maintenanceActions(
      JComponent nvidiaRuntime, JComponent switchBack, JComponent cleanCache) {
    return new WrappingActions(nvidiaRuntime, switchBack, cleanCache);
  }

  static JPanel experimentalActions(JComponent selector, JComponent install) {
    JPanel column = new JPanel(new BorderLayout(0, 8));
    column.setOpaque(false);
    column.add(selector, BorderLayout.NORTH);
    column.add(new WrappingActions(install), BorderLayout.SOUTH);
    return column;
  }

  static JPanel actionBlock(JLabel title, JComponent hint, JComponent actions) {
    JPanel block = new JPanel(new GridBagLayout());
    block.setOpaque(false);
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.weightx = 1;
    gbc.anchor = GridBagConstraints.NORTHWEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.insets = new Insets(0, 0, hint == null ? 8 : 4, 0);
    block.add(title, gbc);
    if (hint != null) {
      gbc.gridy++;
      gbc.insets = new Insets(0, 0, 8, 0);
      block.add(hint, gbc);
    }
    gbc.gridy++;
    gbc.insets = new Insets(0, 0, 0, 0);
    block.add(actions, gbc);
    return block;
  }

  static JTextArea statusChip(Font font, Color background, Color border) {
    WrappingText area = new WrappingText(font, true);
    area.setOpaque(true);
    area.setBackground(background);
    area.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border),
            BorderFactory.createEmptyBorder(6, 8, 8, 8)));
    return area;
  }

  static JTextArea hint(String message, Font font, Color foreground) {
    WrappingText area = new WrappingText(font, false);
    area.setForeground(foreground);
    area.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
    setStatusText(area, message);
    return area;
  }

  static void setStatusText(JTextArea area, String value) {
    String plain = value == null ? "" : value;
    if (!plain.equals(area.getText())) {
      area.setText(plain);
      area.setCaretPosition(0);
    }
    area.getAccessibleContext().setAccessibleName(plain);
    area.revalidate();
    area.repaint();
  }

  /** Width actually visible through the ancestor chain, not a stale oversized child width. */
  private static int visibleWidth(JComponent component, int fallback) {
    int width = component.getWidth() > 0 ? component.getWidth() : fallback;
    int offset = component.getX();
    for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
      if (parent.getWidth() > 0) {
        Insets insets = parent.getInsets();
        int right = parent.getWidth() - insets.right;
        if (parent instanceof JViewport) {
          Rectangle view = ((JViewport) parent).getViewRect();
          right = view.x + view.width;
        }
        int available = right - Math.max(offset, insets.left);
        if (available > 0) {
          width = Math.min(width, available);
        }
      }
      if (parent instanceof JViewport) {
        break;
      }
      offset += parent.getX();
    }
    return Math.max(1, width);
  }

  private static int textHeightForWidth(JTextArea area, int width) {
    Insets insets = area.getInsets();
    Object caretWidth = area.getClientProperty("caretWidth");
    if (!(caretWidth instanceof Number) || ((Number) caretWidth).intValue() < 0) {
      caretWidth = UIManager.get("Caret.width");
    }
    int caretMargin = caretWidth instanceof Number ? ((Number) caretWidth).intValue() : 1;
    if (caretMargin < 0) {
      caretMargin = 1;
    }
    // BasicTextUI also reserves this margin when laying out even a read-only text area.
    int innerWidth = Math.max(1, width - insets.left - insets.right - caretMargin);
    // Use Swing's wrapped view, including word wrapping, newlines and long tokens.
    View view = area.getUI().getRootView(area);
    view.setSize(innerWidth, Integer.MAX_VALUE);
    int height = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS));
    return insets.top + insets.bottom
        + Math.max(area.getFontMetrics(area.getFont()).getHeight(), height) + 4;
  }

  /** Preserve aligned status columns, but stack them before the value becomes unreadably narrow. */
  private static final class StatusRow extends JPanel {
    private static final int COLUMN_GAP = 10;
    private static final int MINIMUM_VALUE_WIDTH = 160;
    private final JLabel title;
    private final JTextArea status;
    private boolean relayoutPending;
    private int lastWidth = -1;

    StatusRow(JLabel title, JTextArea status) {
      super(null);
      this.title = title;
      this.status = status;
      setOpaque(false);
      add(title);
      add(status);
    }

    private int titleColumnWidth() {
      int width = title.getPreferredSize().width;
      if (getParent() != null) {
        for (Component sibling : getParent().getComponents()) {
          if (sibling instanceof StatusRow) {
            width = Math.max(width, ((StatusRow) sibling).title.getPreferredSize().width);
          }
        }
      }
      return width;
    }

    private Dimension arrange(boolean apply) {
      int width = visibleWidth(this, 740);
      int titleWidth = titleColumnWidth();
      boolean stacked = width < titleWidth + COLUMN_GAP + MINIMUM_VALUE_WIDTH;
      int valueWidth = stacked ? width : width - titleWidth - COLUMN_GAP;
      int titleHeight = title.getPreferredSize().height;
      int valueHeight = textHeightForWidth(status, valueWidth);
      if (apply) {
        title.setBounds(0, 0, Math.min(width, titleWidth), titleHeight);
        status.setBounds(stacked ? 0 : titleWidth + COLUMN_GAP,
            stacked ? titleHeight + 4 : 0, valueWidth, valueHeight);
      }
      return new Dimension(width,
          stacked ? titleHeight + 4 + valueHeight : Math.max(titleHeight, valueHeight));
    }

    @Override
    public Dimension getPreferredSize() {
      return arrange(false);
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(Math.min(80, getPreferredSize().width), getPreferredSize().height);
    }

    @Override
    public void doLayout() {
      arrange(true);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
      super.setBounds(x, y, width, height);
      if (width <= 0 || getParent() == null) {
        return;
      }
      boolean widthChanged = lastWidth != width;
      lastWidth = width;
      if (!relayoutPending && (widthChanged || height < getPreferredSize().height)) {
        relayoutPending = true;
        SwingUtilities.invokeLater(() -> {
          relayoutPending = false;
          if (getParent() != null) {
            getParent().invalidate();
            getParent().revalidate();
          }
        });
      }
    }
  }

  /** Plain text so long paths and translated messages wrap without HTML ellipses. */
  static final class WrappingText extends JTextArea {
    private final boolean compact;
    private boolean relayoutPending;
    private int lastWidth = -1;

    private WrappingText(Font font, boolean compact) {
      this.compact = compact;
      setFont(font);
      setEditable(false);
      setFocusable(false);
      setOpaque(false);
      setLineWrap(true);
      setWrapStyleWord(true);
      setHighlighter(null);
      setMargin(new Insets(0, 0, 0, 0));
    }

    int heightForWidth(int width) {
      return textHeightForWidth(this, width);
    }

    private int naturalWidth() {
      FontMetrics metrics = getFontMetrics(getFont());
      int width = 0;
      for (String line : getText().split("\\R", -1)) {
        width = Math.max(width, metrics.stringWidth(line));
      }
      Insets insets = getInsets();
      return Math.max(24, width + insets.left + insets.right);
    }

    @Override
    public Dimension getPreferredSize() {
      int available = visibleWidth(this, compact ? 560 : 640);
      int width = compact ? Math.min(naturalWidth(), available) : available;
      return new Dimension(width, heightForWidth(width));
    }

    @Override
    public Dimension getMinimumSize() {
      Dimension preferred = getPreferredSize();
      return new Dimension(Math.min(compact ? 160 : 80, preferred.width), preferred.height);
    }

    @Override
    public Dimension getMaximumSize() {
      return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
      super.setBounds(x, y, width, height);
      if (width <= 0 || getParent() == null || getUI() == null) {
        return;
      }
      boolean widthChanged = lastWidth != width;
      lastWidth = width;
      if (!relayoutPending && (widthChanged || height < heightForWidth(width))) {
        relayoutPending = true;
        SwingUtilities.invokeLater(
            () -> {
              relayoutPending = false;
              if (getParent() != null) {
                getParent().invalidate();
                getParent().revalidate();
              }
            });
      }
    }
  }

  /** Left-aligned, width-aware actions; never the shared dialog's fixed two-column grid. */
  static final class WrappingActions extends JPanel {
    private static final int GAP = 8;
    private boolean relayoutPending;
    private int lastWidth = -1;

    WrappingActions(JComponent... actions) {
      super(null);
      setOpaque(false);
      for (JComponent action : actions) {
        add(action);
      }
    }

    private int naturalWidth() {
      int width = 0;
      for (Component action : getComponents()) {
        if (action.isVisible()) {
          width += (width == 0 ? 0 : GAP) + action.getPreferredSize().width;
        }
      }
      Insets insets = getInsets();
      return Math.max(1, width + insets.left + insets.right);
    }

    private Dimension arrange(boolean apply) {
      Insets insets = getInsets();
      int width = visibleWidth(this, naturalWidth());
      int inner = Math.max(1, width - insets.left - insets.right);
      int x = 0;
      int y = 0;
      int rowHeight = 0;
      int usedWidth = 0;
      for (Component action : getComponents()) {
        if (!action.isVisible()) {
          continue;
        }
        Dimension preferred = action.getPreferredSize();
        int actionWidth = Math.min(inner, preferred.width);
        if (x > 0 && x + GAP + actionWidth > inner) {
          y += rowHeight + GAP;
          x = 0;
          rowHeight = 0;
        }
        if (x > 0) {
          x += GAP;
        }
        if (apply) {
          action.setBounds(insets.left + x, insets.top + y, actionWidth, preferred.height);
        }
        x += actionWidth;
        usedWidth = Math.max(usedWidth, x);
        rowHeight = Math.max(rowHeight, preferred.height);
      }
      return new Dimension(
          Math.min(width, insets.left + usedWidth + insets.right),
          insets.top + y + rowHeight + insets.bottom);
    }

    @Override
    public Dimension getPreferredSize() {
      return arrange(false);
    }

    @Override
    public Dimension getMinimumSize() {
      Dimension preferred = getPreferredSize();
      return new Dimension(Math.min(80, preferred.width), preferred.height);
    }

    @Override
    public void doLayout() {
      arrange(true);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
      super.setBounds(x, y, width, height);
      if (width <= 0 || getParent() == null) {
        return;
      }
      boolean widthChanged = lastWidth != width;
      lastWidth = width;
      if (!relayoutPending && (widthChanged || height < getPreferredSize().height)) {
        relayoutPending = true;
        SwingUtilities.invokeLater(
            () -> {
              relayoutPending = false;
              if (getParent() != null) {
                getParent().invalidate();
                getParent().revalidate();
              }
            });
      }
    }
  }
}
