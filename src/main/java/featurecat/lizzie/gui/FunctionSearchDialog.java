package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.search.FunctionCatalog;
import featurecat.lizzie.search.FunctionCatalog.Entry;
import featurecat.lizzie.search.FunctionSearch;
import featurecat.lizzie.util.LocaleFontSupport;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/**
 * Approved 00-r2: a stationary centered input, warm paper and ink-green actions; results unfold
 * below with real paths and inline detail. Native editing owns IME.
 */
final class FunctionSearchDialog extends JDialog {
  private static final Color PAPER = new Color(0xFFFDF8);
  private static final Color INK = new Color(0x232724);
  private static final Color TEAL = new Color(0x0A655E);
  private static final Color MUTED = new Color(0x53615B);
  private static final Color LINE = new Color(0xCDBF9F);
  private static final Color SELECTED = new Color(0xE8F1EC);
  private final ResourceBundle bundle = Lizzie.resourceBundle;
  private final Locale effectiveLocale = bundle.getLocale();
  private final Font font =
      new Font(
          LocaleFontSupport.resolveLanguageFontName(Config.sysDefaultFontName, effectiveLocale),
          Font.PLAIN,
          Config.frameFontSize);
  private final FunctionSearch index;
  private final FunctionSearchController navigation;
  private final java.util.Map<String, Entry> entries =
      FunctionCatalog.entries().stream()
          .collect(java.util.stream.Collectors.toUnmodifiableMap(Entry::id, entry -> entry));
  private final List<String> categoryKeys =
      FunctionCatalog.entries().stream().map(Entry::categoryKey).distinct().sorted().toList();
  private final SearchInputSession inputSession = new SearchInputSession();
  private final DefaultListModel<Entry> model = new DefaultListModel<>();
  private final JTextField input;
  private final JList<Entry> results;
  private final JPanel expansion = new JPanel(new BorderLayout(0, 4));
  private final JComboBox<String> categories;
  private final JLabel count = new JLabel();
  private final JButton activate;
  private final JButton browse;
  private final int anchorY;
  private final int searchWidth;
  private final int availableBottom;
  private final Timer stateTimer;
  private final DocumentListener queryListener;
  private boolean closed;
  private String target;
  private String lastReason;

  FunctionSearchDialog(
      Window owner, Window anchor, FunctionSearch index, FunctionSearchController navigation) {
    super(
        owner,
        Lizzie.resourceBundle.getString("FunctionSearch.title"),
        ModalityType.APPLICATION_MODAL);
    this.index = index;
    this.navigation = navigation;
    setUndecorated(true);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    JPanel surface = new JPanel(new BorderLayout());
    surface.setBackground(PAPER);
    surface.setBorder(new SurfaceBorder());
    JPanel top = new JPanel(new BorderLayout(10, 0));
    top.setOpaque(false);
    top.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 10));
    top.add(new JLabel(new SearchIcon()), BorderLayout.WEST);
    input =
        new JTextField() {
          @Override
          protected void processInputMethodEvent(InputMethodEvent event) {
            if (event.getID() == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) {
              inputSession.textChanged(event);
            }
            super.processInputMethodEvent(event);
          }

          @Override
          protected void processKeyEvent(KeyEvent event) {
            if (!handleKey(event)) super.processKeyEvent(event);
          }

          @Override
          protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty() && !inputSession.isComposing()) {
              graphics.setColor(MUTED);
              graphics.setFont(getFont());
              FontMetrics metrics = graphics.getFontMetrics();
              graphics.drawString(
                  FunctionSearchDialog.this.copy("hint"),
                  4,
                  (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
            }
          }
        };
    input.setFont(font.deriveFont((float) font.getSize() + 2));
    input.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
    input.setBackground(PAPER);
    input.setForeground(INK);
    input.setCaretColor(TEAL);
    input.setSelectionColor(SELECTED);
    input.getAccessibleContext().setAccessibleName(copy("title"));
    input.setToolTipText(copy("hint"));
    top.add(input, BorderLayout.CENTER);
    top.add(button(copy("cancel"), this::dispose, false), BorderLayout.EAST);
    surface.add(top, BorderLayout.NORTH);
    expansion.setOpaque(false);
    expansion.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
    String[] categoryNames = new String[categoryKeys.size() + 1];
    categoryNames[0] = copy("all");
    for (int n = 0; n < categoryKeys.size(); n++)
      categoryNames[n + 1] = bundle.getString(categoryKeys.get(n));
    categories = new JComboBox<>(categoryNames);
    categories.setFont(font);
    categories.setForeground(INK);
    categories.setBackground(PAPER);
    categories.getAccessibleContext().setAccessibleName(copy("browse"));
    JPanel categoryLine = new JPanel(new BorderLayout(8, 0));
    categoryLine.setOpaque(false);
    categoryLine.add(categories, BorderLayout.WEST);
    count.setFont(font);
    count.setForeground(MUTED);
    categoryLine.add(count, BorderLayout.EAST);
    expansion.add(categoryLine, BorderLayout.NORTH);
    results =
        new JList<>(model) {
          @Override
          protected void processKeyEvent(KeyEvent event) {
            if (!handleKey(event)) super.processKeyEvent(event);
          }

          @Override
          protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (model.isEmpty()) {
              JTextArea empty = wrap(copy("empty"), Math.max(100, getWidth() - 24));
              empty.setSize(getWidth() - 24, empty.getPreferredSize().height);
              Graphics child = graphics.create(12, 12, getWidth() - 24, getHeight() - 24);
              empty.paint(child);
              child.dispose();
            }
          }
        };
    results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    results.setBackground(PAPER);
    results.setFont(font);
    results.getAccessibleContext().setAccessibleName(copy("browse"));
    results.setCellRenderer(new ResultRenderer());
    JScrollPane scroll = new JScrollPane(results);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(PAPER);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    expansion.add(scroll, BorderLayout.CENTER);
    activate = button(copy("activate"), this::activateSelection, true);
    JPanel footer = new JPanel(new BorderLayout(8, 2));
    footer.setOpaque(false);
    footer.add(activate, BorderLayout.EAST);
    Rectangle ownerBounds = anchor.getBounds();
    GraphicsConfiguration graphics = anchor.getGraphicsConfiguration();
    Rectangle workArea = graphics.getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(graphics);
    workArea =
        new Rectangle(
            workArea.x + insets.left,
            workArea.y + insets.top,
            workArea.width - insets.left - insets.right,
            workArea.height - insets.top - insets.bottom);
    searchWidth =
        Math.max(240, Math.min(720, Math.min(ownerBounds.width - 48, workArea.width - 32)));
    anchorY =
        Math.max(
            workArea.y + 16,
            Math.min(
                ownerBounds.y + (ownerBounds.height - top.getPreferredSize().height) / 2,
                workArea.y + workArea.height - Math.max(220, font.getSize() * 10)));
    availableBottom =
        Math.min(ownerBounds.y + ownerBounds.height, workArea.y + workArea.height) - 16;
    footer.add(
        wrap(copy("keyboard"), Math.max(100, searchWidth - activate.getPreferredSize().width - 44)),
        BorderLayout.CENTER);
    expansion.add(footer, BorderLayout.SOUTH);
    JPanel lower = new JPanel(new BorderLayout());
    lower.setOpaque(false);
    browse =
        button(
            copy("browse"),
            () -> {
              expansion.setVisible(true);
              refreshResults();
              resizeSearch();
            },
            false);
    lower.add(browse, BorderLayout.NORTH);
    lower.add(expansion, BorderLayout.CENTER);
    surface.add(lower, BorderLayout.CENTER);
    setContentPane(surface);
    categories.addActionListener(event -> refreshResults());
    results.addListSelectionListener(event -> refreshSelection());
    results.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            int row = results.locationToIndex(event.getPoint());
            if (event.getClickCount() == 2
                && row >= 0
                && results.getCellBounds(row, row).contains(event.getPoint())) activateSelection();
          }
        });
    queryListener =
        new DocumentListener() {
          public void insertUpdate(DocumentEvent event) {
            changed();
          }

          public void removeUpdate(DocumentEvent event) {
            changed();
          }

          public void changedUpdate(DocumentEvent event) {
            changed();
          }

          private void changed() {
            if (closed) return;
            expansion.setVisible(!input.getText().isBlank());
            refreshResults();
            // Text views receive this document event after application listeners. Layout
            // must wait until they have updated complex-script runs such as Thai.
            SwingUtilities.invokeLater(() -> {
              if (!closed) resizeSearch();
            });
          }
        };
    input.getDocument().addDocumentListener(queryListener);
    getRootPane()
        .registerKeyboardAction(
            event -> {
              if (!inputSession.isComposing()) dispose();
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    expansion.setVisible(false);
    refreshResults();
    resizeSearch();
    setLocation(
        Math.max(
            workArea.x + 16,
            Math.min(
                ownerBounds.x + (ownerBounds.width - searchWidth) / 2,
                workArea.x + workArea.width - searchWidth - 16)),
        anchorY);
    stateTimer =
        new Timer(
            250,
            event -> {
              Entry entry = results.getSelectedValue();
              String reason = entry == null ? null : navigation.unavailableReason(entry.id());
              if (!java.util.Objects.equals(reason, lastReason)) refreshSelection();
            });
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowOpened(WindowEvent event) {
            input.requestFocusInWindow();
            stateTimer.start();
          }
        });
  }

  String selectedTarget() {
    return target;
  }

  private String copy(String key) {
    return bundle.getString("FunctionSearch." + key);
  }

  private String title(Entry entry) {
    return bundle.getString(entry.titleKey());
  }

  private String path(Entry entry) {
    return String.join(" › ", entry.pathKeys().stream().map(bundle::getString).toList());
  }

  private boolean handleKey(KeyEvent event) {
    int key = event.getKeyCode();
    if (key == KeyEvent.VK_ENTER) {
      boolean composing = inputSession.isComposing();
      if (inputSession.enter(event)) activateSelection();
      // While composing, keep native candidate controls with the input method.
      if (composing) return false;
      event.consume();
      return true;
    }
    if (inputSession.isComposing()) return false;
    if (event.getID() == KeyEvent.KEY_PRESSED && event.getModifiersEx() == 0) {
      if (key == KeyEvent.VK_ESCAPE) {
        dispose();
        event.consume();
        return true;
      }
      if (key == KeyEvent.VK_UP || key == KeyEvent.VK_DOWN) {
        expansion.setVisible(true);
        if (model.isEmpty()) refreshResults();
        resizeSearch();
        if (!model.isEmpty()) {
          int next =
              Math.max(
                  0,
                  Math.min(
                      model.size() - 1,
                      results.getSelectedIndex() + (key == KeyEvent.VK_DOWN ? 1 : -1)));
          results.setSelectedIndex(next);
          results.ensureIndexIsVisible(next);
        }
        event.consume();
        return true;
      }
    }
    return false;
  }

  private void refreshResults() {
    Entry previous = results.getSelectedValue();
    model.clear();
    if (input.getText().isBlank() && !expansion.isVisible()) {
      refreshSelection();
      return;
    }
    String category =
        categories.getSelectedIndex() == 0
            ? null
            : categoryKeys.get(categories.getSelectedIndex() - 1);
    List<FunctionSearch.Match> matches = index.search(input.getText(), effectiveLocale);
    int total = 0;
    boolean browsing = input.getText().isBlank();
    List<Entry> visible = new ArrayList<>(browsing ? matches.size() : Math.min(50, matches.size()));
    for (FunctionSearch.Match match : matches) {
      Entry entry = entries.get(match.id());
      if (category != null && !category.equals(entry.categoryKey())) continue;
      total++;
      if (browsing || visible.size() < 50) visible.add(entry);
    }
    model.addAll(visible);
    count.setText(String.format(effectiveLocale, copy("results"), total));
    if (previous != null && model.contains(previous)) results.setSelectedValue(previous, true);
    else if (!model.isEmpty()) results.setSelectedIndex(0);
    results
        .getAccessibleContext()
        .setAccessibleDescription(model.isEmpty() ? copy("empty") : count.getText());
    refreshSelection();
  }

  private void refreshSelection() {
    Entry entry = results.getSelectedValue();
    lastReason = entry == null ? null : navigation.unavailableReason(entry.id());
    activate.setEnabled(entry != null && lastReason == null);
    results.setCellRenderer(new ResultRenderer());
    results.revalidate();
    results.repaint();
  }

  private void activateSelection() {
    if (closed || inputSession.isComposing()) return;
    Entry entry = results.getSelectedValue();
    if (entry == null) return;
    String reason = navigation.unavailableReason(entry.id());
    if (reason != null) {
      refreshSelection();
      return;
    }
    target = entry.id();
    dispose();
  }

  private void resizeSearch() {
    browse.setVisible(!expansion.isVisible());
    int height =
        expansion.isVisible()
            ? Math.max(180, availableBottom - anchorY)
            : getContentPane().getPreferredSize().height;
    setSize(searchWidth, height);
    results.setFixedCellWidth(Math.max(120, searchWidth - 46));
    validate();
  }

  @Override
  public void dispose() {
    closed = true;
    if (stateTimer != null) stateTimer.stop();
    if (input != null && queryListener != null)
      input.getDocument().removeDocumentListener(queryListener);
    super.dispose();
  }

  private JButton button(String label, Runnable action, boolean primary) {
    JButton button = new JFontButton(label);
    AppleStyleSupport.preserveCustomButtonStyle(button);
    button.setFont(font);
    button.setForeground(primary ? Color.WHITE : INK);
    button.setUI(
        new BasicButtonUI() {
          @Override
          public void paint(Graphics graphics, JComponent component) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill =
                !button.isEnabled()
                    ? new Color(0xE7E4DB)
                    : primary
                        ? (button.getModel().isPressed() ? new Color(0x084D47) : TEAL)
                        : PAPER;
            if (button.isEnabled() && button.getModel().isRollover())
              fill = primary ? new Color(0x12766D) : SELECTED;
            g.setColor(fill);
            g.fillRoundRect(3, 3, component.getWidth() - 7, component.getHeight() - 7, 8, 8);
            g.setColor(button.hasFocus() ? TEAL : LINE);
            g.setStroke(new BasicStroke(button.hasFocus() ? 2 : 1));
            g.drawRoundRect(1, 1, component.getWidth() - 3, component.getHeight() - 3, 10, 10);
            g.dispose();
            super.paint(graphics, component);
          }

          @Override
          protected void paintText(
              Graphics g, AbstractButton component, Rectangle bounds, String text) {
            g.setColor(component.isEnabled() ? component.getForeground() : MUTED);
            javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(
                g, text, -1, bounds.x, bounds.y + g.getFontMetrics().getAscent());
          }
        });
    button.setContentAreaFilled(false);
    button.setOpaque(false);
    button.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
    button.addActionListener(event -> action.run());
    return button;
  }

  private JTextArea wrap(String text, int width) {
    JTextArea area = new JTextArea(text);
    area.setFont(font);
    area.setForeground(MUTED);
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setBorder(null);
    area.setSize(width, Short.MAX_VALUE);
    area.setPreferredSize(new Dimension(width, area.getPreferredSize().height));
    return area;
  }

  private final class ResultRenderer extends JPanel implements ListCellRenderer<Entry> {
    @Override
    public Component getListCellRendererComponent(
        JList<? extends Entry> list, Entry entry, int row, boolean selected, boolean focus) {
      selected = row == list.getSelectedIndex();
      removeAll();
      setLayout(new BorderLayout(0, 4));
      setBackground(selected ? SELECTED : PAPER);
      setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(focus ? TEAL : selected ? LINE : PAPER),
              BorderFactory.createEmptyBorder(8, 10, 8, 10)));
      int width = Math.max(100, list.getFixedCellWidth() - 30);
      String reason = navigation.unavailableReason(entry.id());
      JTextPane heading = new JTextPane();
      heading.setEditable(false);
      heading.setFocusable(false);
      heading.setOpaque(false);
      heading.setBorder(null);
      heading.setMargin(new Insets(0, 0, 0, 0));
      heading.setFont(font);
      SimpleAttributeSet style = new SimpleAttributeSet();
      StyleConstants.setFontFamily(style, font.getFamily());
      StyleConstants.setFontSize(style, font.getSize());
      StyleConstants.setBold(style, true);
      StyleConstants.setForeground(style, reason == null ? INK : MUTED);
      try {
        heading.getStyledDocument().insertString(0, title(entry), style);
        StyleConstants.setBold(style, false);
        StyleConstants.setForeground(style, MUTED);
        heading
            .getStyledDocument()
            .insertString(heading.getDocument().getLength(), "  " + path(entry), style);
      } catch (javax.swing.text.BadLocationException impossible) {
        throw new AssertionError(impossible);
      }
      heading.setSize(width, Short.MAX_VALUE);
      heading.setPreferredSize(new Dimension(width, heading.getPreferredSize().height));
      add(heading, BorderLayout.NORTH);
      if (selected) {
        String detail =
            copy(
                    switch (entry.targetType()) {
                      case SETTING -> "type.setting";
                      case ACTION -> "type.action";
                      case NAVIGATION, CONTEXT -> "type.navigation";
                      case WINDOW -> "type.window";
                    })
                + (entry.shortcut().isEmpty() ? "" : " · " + entry.shortcut())
                + "\n"
                + bundle.getString(entry.descriptionKey())
                + (reason == null ? "" : "\n" + bundle.getString(reason));
        add(wrap(detail, width), BorderLayout.CENTER);
      }
      getAccessibleContext().setAccessibleName(title(entry) + " " + path(entry));
      getAccessibleContext()
          .setAccessibleDescription(
              reason == null ? bundle.getString(entry.descriptionKey()) : bundle.getString(reason));
      return this;
    }
  }

  private static final class SurfaceBorder extends AbstractBorder {
    @Override
    public Insets getBorderInsets(Component component) {
      return new Insets(2, 2, 2, 2);
    }

    @Override
    public void paintBorder(Component component, Graphics g, int x, int y, int width, int height) {
      g.setColor(LINE);
      g.drawRoundRect(x, y, width - 1, height - 1, 12, 12);
    }
  }

  static final class SearchIcon implements Icon {
    @Override
    public int getIconWidth() {
      return 16;
    }

    @Override
    public int getIconHeight() {
      return 16;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(TEAL);
      g.setStroke(new BasicStroke(1.5f));
      g.drawOval(x + 1, y + 1, 9, 9);
      g.drawLine(x + 9, y + 9, x + 14, y + 14);
      g.dispose();
    }
  }
}
