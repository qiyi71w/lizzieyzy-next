package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.ChangeListener;

public class WindowMenuStrip extends JPanel {
  private static final long SAME_MENU_REOPEN_SUPPRESS_MS = 250L;
  private final JMenuBar sourceMenuBar;
  private final List<MenuButton> menuButtons = new ArrayList<>();
  private final List<ActionButton> actionButtons = new ArrayList<>();
  private static final String POPUP_LISTENER_KEY = "lizzie.window-menu-strip.popup-listener";
  private JMenu recentlyHiddenMenu;
  private long recentlyHiddenAtMillis;

  public WindowMenuStrip(JMenuBar sourceMenuBar) {
    this.sourceMenuBar = sourceMenuBar;
    setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));
    setOpaque(false);
    setBorder(new EmptyBorder(2, 10, 2, 10));
    rebuild();
  }

  public void rebuild() {
    for (MenuButton button : menuButtons) {
      button.detach();
    }
    for (ActionButton button : actionButtons) {
      button.detach();
    }
    removeAll();
    menuButtons.clear();
    actionButtons.clear();
    if (sourceMenuBar == null) {
      return;
    }
    for (java.awt.Component component : sourceMenuBar.getComponents()) {
      if (!component.isVisible()) {
        continue;
      }
      if (component instanceof JMenu) {
        JMenu menu = (JMenu) component;
        String text = menu.getText();
        if (text == null || text.trim().isEmpty()) {
          continue;
        }
        MenuButton button = new MenuButton(menu);
        menuButtons.add(button);
        add(button);
        continue;
      }
      if (component instanceof AbstractButton) {
        AbstractButton sourceButton = (AbstractButton) component;
        String text = sourceButton.getText();
        if ((text == null || text.trim().isEmpty()) && sourceButton.getIcon() == null) {
          continue;
        }
        ActionButton button = new ActionButton(sourceButton);
        actionButtons.add(button);
        add(button);
        button.installStyle();
      }
    }
    revalidate();
    repaint();
  }

  public void refreshColors() {
    Color fg = AppleStyleSupport.dialogTextColor();
    for (MenuButton b : menuButtons) {
      b.setForeground(fg);
    }
    for (ActionButton b : actionButtons) {
      b.syncFromSource();
    }
  }

  @Override
  public Dimension getPreferredSize() {
    Dimension size = super.getPreferredSize();
    return new Dimension(size.width, Math.max(Config.menuHeight + 6, size.height));
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    AppleStyleSupport.paintToolbarSurface(g2, getWidth(), getHeight(), true);
    g2.dispose();
  }

  private void hideOtherPopups(JMenu except) {
    if (sourceMenuBar == null) {
      return;
    }
    for (int i = 0; i < sourceMenuBar.getMenuCount(); i++) {
      JMenu menu = sourceMenuBar.getMenu(i);
      if (menu != null && menu != except) {
        menu.setPopupMenuVisible(false);
        notifyMenuDeselected(menu);
      }
    }
  }

  private boolean hasVisiblePopup() {
    if (sourceMenuBar == null) {
      return false;
    }
    for (int i = 0; i < sourceMenuBar.getMenuCount(); i++) {
      JMenu menu = sourceMenuBar.getMenu(i);
      if (menu != null && menu.getPopupMenu() != null && menu.getPopupMenu().isVisible()) {
        return true;
      }
    }
    return false;
  }

  /** Opens the current source menu through its visible in-window proxy. */
  boolean showMenu(JMenu menu) {
    if (!isShowing()) return false;
    for (MenuButton button : menuButtons) {
      if (button.menu == menu && button.isShowing()) {
        openMenu(button, false);
        return true;
      }
    }
    return false;
  }

  private void openMenu(MenuButton button, boolean toggleIfVisible) {
    if (button == null || button.menu == null) {
      return;
    }
    JPopupMenu popup = button.menu.getPopupMenu();
    if (popup == null) {
      return;
    }
    boolean alreadyVisible = popup.isVisible();
    hideOtherPopups(button.menu);
    if (alreadyVisible && toggleIfVisible) {
      popup.setVisible(false);
      notifyMenuDeselected(button.menu);
      repaint();
      return;
    }

    notifyMenuSelected(button.menu);
    AppleStyleSupport.installPopupStyle(popup);
    popup.show(button, 0, button.getHeight() + 3);
    repaint();
  }

  private void closeMenu(JMenu menu) {
    if (menu == null || menu.getPopupMenu() == null) {
      return;
    }
    menu.getPopupMenu().setVisible(false);
    notifyMenuDeselected(menu);
    repaint();
  }

  private boolean shouldTreatPressAsCloseRequest(JMenu menu) {
    if (menu == null || menu.getPopupMenu() == null) {
      return false;
    }
    if (menu.getPopupMenu().isVisible()) {
      return true;
    }
    long elapsed = System.currentTimeMillis() - recentlyHiddenAtMillis;
    return recentlyHiddenMenu == menu
        && elapsed >= 0L
        && elapsed <= SAME_MENU_REOPEN_SUPPRESS_MS;
  }

  private void rememberPopupHidden(JMenu menu) {
    recentlyHiddenMenu = menu;
    recentlyHiddenAtMillis = System.currentTimeMillis();
  }

  private void ensurePopupListener(JMenu menu) {
    if (menu == null || menu.getPopupMenu() == null) {
      return;
    }
    JPopupMenu popup = menu.getPopupMenu();
    if (Boolean.TRUE.equals(popup.getClientProperty(POPUP_LISTENER_KEY))) {
      return;
    }
    popup.addPopupMenuListener(
        new PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            repaint();
          }

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            rememberPopupHidden(menu);
            notifyMenuDeselected(menu);
            repaint();
          }

          @Override
          public void popupMenuCanceled(PopupMenuEvent e) {
            rememberPopupHidden(menu);
            notifyMenuDeselected(menu);
            repaint();
          }
        });
    popup.putClientProperty(POPUP_LISTENER_KEY, Boolean.TRUE);
  }

  private void notifyMenuSelected(JMenu menu) {
    MenuEvent event = new MenuEvent(menu);
    for (MenuListener listener : menu.getMenuListeners()) {
      listener.menuSelected(event);
    }
  }

  private void notifyMenuDeselected(JMenu menu) {
    MenuEvent event = new MenuEvent(menu);
    for (MenuListener listener : menu.getMenuListeners()) {
      listener.menuDeselected(event);
    }
  }

  private final class MenuButton extends JButton {
    private final JMenu menu;
    private final PropertyChangeListener menuPropertyListener = this::menuPropertyChanged;
    private boolean suppressNextAction;

    private MenuButton(JMenu menu) {
      super(menu.getText());
      this.menu = menu;
      ensurePopupListener(menu);
      syncFromMenu();
      menu.addPropertyChangeListener(menuPropertyListener);
      setOpaque(false);
      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setRolloverEnabled(true);
      setMargin(new Insets(0, 0, 0, 0));
      setBorder(new EmptyBorder(5, 10, 5, 10));
      AccessibilitySupport.button(this, menu.getText(), menu.getText());
      addActionListener(
          e -> {
            if (suppressNextAction) {
              suppressNextAction = false;
              return;
            }
            openMenu(this, true);
          });
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              if (shouldTreatPressAsCloseRequest(menu)) {
                suppressNextAction = true;
                closeMenu(menu);
                e.consume();
              }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
              if (hasVisiblePopup() && !menu.getPopupMenu().isVisible()) {
                openMenu(MenuButton.this, false);
              } else {
                repaint();
              }
            }

            @Override
            public void mouseExited(MouseEvent e) {
              repaint();
            }
          });
    }

    private void menuPropertyChanged(PropertyChangeEvent event) {
      String property = event.getPropertyName();
      if ("visible".equals(property)) {
        SwingUtilities.invokeLater(WindowMenuStrip.this::rebuild);
        return;
      }
      if ("text".equals(property)
          || "enabled".equals(property)
          || "font".equals(property)
          || "foreground".equals(property)) {
        syncFromMenu();
      }
    }

    private void syncFromMenu() {
      setText(menu.getText());
      setEnabled(menu.isEnabled());
      Font menuFont = menu.getFont();
      if (menuFont != null) {
        setFont(menuFont.deriveFont(Font.BOLD, Math.max(Config.frameFontSize, 12)));
      }
      setForeground(AppleStyleSupport.dialogTextColor());
      if (getAccessibleContext() != null) {
        getAccessibleContext().setAccessibleName(menu.getText());
        getAccessibleContext().setAccessibleDescription(menu.getText());
      }
      revalidate();
      repaint();
    }

    private void detach() {
      menu.removePropertyChangeListener(menuPropertyListener);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      boolean active = menu.getPopupMenu().isVisible();
      boolean hover = getModel().isRollover();
      if (hover || active) {
        g2.setColor(new Color(255, 255, 255, active ? 40 : 24));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
      }
      if (active) {
        g2.setColor(new Color(120, 190, 255, 220));
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(8, getHeight() - 2, getWidth() - 8, getHeight() - 2);
      }
      g2.dispose();
      super.paintComponent(g);
    }
  }

  private final class ActionButton extends JButton {
    private final AbstractButton sourceButton;
    private final PropertyChangeListener sourcePropertyListener = this::sourcePropertyChanged;
    private final ChangeListener sourceChangeListener = event -> syncSelectionFromSource();

    private ActionButton(AbstractButton sourceButton) {
      this.sourceButton = sourceButton;
      syncFromSource();
      sourceButton.addPropertyChangeListener(sourcePropertyListener);
      sourceButton.addChangeListener(sourceChangeListener);
      addActionListener(event -> sourceButton.doClick(0));
    }

    private void installStyle() {
      AppleStyleSupport.copyButtonRole(sourceButton, this);
      AppleStyleSupport.installButtonStyle(this);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void sourcePropertyChanged(PropertyChangeEvent event) {
      String property = event.getPropertyName();
      if ("visible".equals(property)) {
        SwingUtilities.invokeLater(WindowMenuStrip.this::rebuild);
        return;
      }
      if ("text".equals(property)
          || "enabled".equals(property)
          || "font".equals(property)
          || "foreground".equals(property)
          || "background".equals(property)
          || "icon".equals(property)
          || "toolTipText".equals(property)
          || "name".equals(property)
          || "preferredSize".equals(property)
          || "margin".equals(property)
          || "iconTextGap".equals(property)
          || "horizontalAlignment".equals(property)
          || "lizzie.apple.button.customStyle".equals(property)
          || "lizzie.humansl.buttonStyle".equals(property)) {
        syncFromSource();
      }
    }

    private void syncFromSource() {
      setText(sourceButton.getText());
      setIcon(sourceButton.getIcon());
      setEnabled(sourceButton.isEnabled());
      setName(sourceButton.getName());
      setToolTipText(sourceButton.getToolTipText());
      setIconTextGap(sourceButton.getIconTextGap());
      setHorizontalAlignment(sourceButton.getHorizontalAlignment());
      if (sourceButton.getMargin() != null) {
        setMargin(sourceButton.getMargin());
      }
      if (sourceButton.getFont() != null) {
        setFont(sourceButton.getFont());
      }
      Dimension preferred = sourceButton.getPreferredSize();
      if (preferred != null) {
        setPreferredSize(new Dimension(preferred));
      }
      if (getAccessibleContext() != null) {
        String accessibleName = sourceButton.getAccessibleContext().getAccessibleName();
        String accessibleDescription =
            sourceButton.getAccessibleContext().getAccessibleDescription();
        getAccessibleContext()
            .setAccessibleName(
                accessibleName == null || accessibleName.trim().isEmpty()
                    ? sourceButton.getText()
                    : accessibleName);
        getAccessibleContext().setAccessibleDescription(accessibleDescription);
      }
      syncSelectionFromSource();
      installStyle();
      revalidate();
      repaint();
    }

    private void syncSelectionFromSource() {
      if (isSelected() != sourceButton.isSelected()) {
        setSelected(sourceButton.isSelected());
      }
      setEnabled(sourceButton.isEnabled());
      repaint();
    }

    private void detach() {
      sourceButton.removePropertyChangeListener(sourcePropertyListener);
      sourceButton.removeChangeListener(sourceChangeListener);
    }
  }
}
