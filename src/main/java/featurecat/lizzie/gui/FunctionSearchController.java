package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.search.FunctionSearch;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashSet;
import java.util.Set;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Main-window owner for search, shortcut isolation, and the final navigation admission. */
final class FunctionSearchController implements KeyEventDispatcher {
  private final LizzieFrame owner;
  private final int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
  private final Set<Integer> swallowedKeys = new HashSet<>();
  private FunctionSearch index;
  private FunctionSearchDialog dialog;
  private boolean openQueued;

  FunctionSearchController(LizzieFrame owner) {
    this.owner = owner;
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    owner.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            openQueued = false;
            if (dialog != null) dialog.dispose();
            swallowedKeys.clear();
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(FunctionSearchController.this);
          }
        });
  }

  void open() {
    if (!owner.isDisplayable()) return;
    if (dialog != null && dialog.isShowing()) {
      dialog.toFront();
      return;
    }
    if (hasOtherModal()) return;
    KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    Component previousFocus = manager.getFocusOwner();
    Window anchor = manager.getFocusedWindow();
    if (anchor != owner && anchor != owner.independentMainBoard) anchor = owner;
    if (index == null) index = new FunctionSearch();
    FunctionSearchDialog current = new FunctionSearchDialog(owner, anchor, index, this);
    dialog = current;
    current.setVisible(true);
    // setVisible returns only after the application's modal block has ended.
    String selected = current.selectedTarget();
    if (dialog == current) dialog = null;
    if (selected != null && owner.isDisplayable()) {
      String reason = activate(selected);
      if (reason != null)
        javax.swing.JOptionPane.showMessageDialog(
            owner,
            Lizzie.resourceBundle.getString(reason),
            Lizzie.resourceBundle.getString("FunctionSearch.title"),
            javax.swing.JOptionPane.INFORMATION_MESSAGE);
    } else if (owner.isDisplayable()) {
      if (previousFocus != null && previousFocus.isShowing() && previousFocus.isFocusable()) {
        previousFocus.requestFocusInWindow();
      } else if (anchor != null && anchor.isShowing() && anchor != owner) {
        anchor.toFront();
        anchor.requestFocus();
      } else {
        owner.mainPanel.requestFocusInWindow();
      }
    }
  }

  String unavailableReason(String id) {
    if (!owner.isDisplayable()) return "FunctionSearch.unavailable.application";
    if (hasOtherModal()) return "FunctionSearch.unavailable.modal";
    return switch (id) {
      case "weights.download", "engine.acceleration", "settings.black-winrate" -> null;
      default -> "FunctionSearch.unavailable.targetMissing";
    };
  }

  String activate(String id) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Function navigation requires the EDT");
    }
    String reason = unavailableReason(id);
    if (reason != null) return reason;
    switch (id) {
      case "weights.download" -> owner.openKataGoWeightDownload();
      case "engine.acceleration" -> owner.openKataGoAcceleration();
      case "settings.black-winrate" -> {
        if (!owner.openConfigDialog2AtSetting(id))
          return "FunctionSearch.unavailable.targetMissing";
      }
      default -> throw new IllegalArgumentException(id);
    }
    return null;
  }

  private boolean hasOtherModal() {
    for (Window window : Window.getWindows()) {
      if (window instanceof Dialog other
          && other.isShowing()
          && other.isModal()
          && window != dialog) return true;
    }
    return false;
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    if (event.getID() == KeyEvent.KEY_RELEASED && swallowedKeys.remove(key)) {
      // Let an open search finish its own Enter handling, but never deliver this release
      // to the board or to the target opened after the modal loop exits.
      if (dialog != null && dialog.isShowing()) return false;
      event.consume();
      return true;
    }
    Component source = event.getComponent();
    Window window =
        source instanceof Window
            ? (Window) source
            : source == null ? null : SwingUtilities.getWindowAncestor(source);
    if (dialog != null && dialog.isShowing()) {
      if (window == dialog && event.getID() == KeyEvent.KEY_PRESSED) swallowedKeys.add(key);
      return false;
    }
    if (event.getID() == KeyEvent.KEY_PRESSED && swallowedKeys.contains(key)) {
      event.consume();
      return true;
    }
    if (event.getID() != KeyEvent.KEY_PRESSED
        || key != KeyEvent.VK_K
        || event.getModifiersEx() != shortcutMask
        || (window != owner && window != owner.independentMainBoard)
        || source instanceof JTextComponent
        || hasOtherModal()) return false;
    swallowedKeys.add(key);
    event.consume();
    if (!openQueued) {
      openQueued = true;
      SwingUtilities.invokeLater(
          () -> {
            if (!openQueued) return;
            openQueued = false;
            open();
          });
    }
    return true;
  }
}
