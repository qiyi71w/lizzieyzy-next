package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.search.FunctionCatalog;
import featurecat.lizzie.search.FunctionSearch;
import featurecat.lizzie.util.NetworkProxy;
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
  private javax.swing.JComponent highlightedTarget;
  private javax.swing.border.Border originalTargetBorder;
  private javax.swing.Timer highlightTimer;

  FunctionSearchController(LizzieFrame owner) {
    this.owner = owner;
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    owner.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            openQueued = false;
            clearHighlight();
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
      String reason = activate(selected, anchor);
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
    FunctionCatalog.Entry entry = FunctionCatalog.entry(id);
    if (entry == null) return "FunctionSearch.unavailable.targetMissing";
    if (entry.targetType() == FunctionCatalog.TargetType.CONTEXT) return null;
    return owner.functionEntryUnavailableReason(id);
  }

  String dependencyReason(String id) {
    return switch (id) {
      case "config.proxy.manual-address" ->
          NetworkProxy.MODE_MANUAL.equals(
                  Lizzie.config.uiConfig.optString(
                      NetworkProxy.KEY_PROXY_MODE, NetworkProxy.DEFAULT_MODE))
              ? null
              : "FunctionSearch.dependency.manualProxy";
      case "config.play.comment-panel" ->
          Lizzie.config.isCommentPanelAutoHiddenByMode()
              ? "FunctionSearch.dependency.commentPanel"
              : null;
      case "config.tracking.text-color" ->
          Lizzie.config.trackingPointTextAutoColor
              ? "FunctionSearch.dependency.trackingTextColor"
              : null;
      case "config.theme.background-image", "config.theme.board-image",
          "config.theme.black-stone-image", "config.theme.white-stone-image" ->
          "FunctionSearch.dependency.themeImage";
      default -> null;
    };
  }

  String activate(String id) {
    return activate(id, owner);
  }

  String activate(String id, Window sourceBoard) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Function navigation requires the EDT");
    }
    String reason = unavailableReason(id);
    clearHighlight();
    if (reason != null) return reason;
    FunctionCatalog.Entry entry = FunctionCatalog.entry(id);
    if (entry.targetType() == FunctionCatalog.TargetType.CONTEXT) {
      return navigateBoard(entry, sourceBoard);
    }
    if (FunctionCatalog.configSettingTarget(id) != null) {
      if (!owner.openConfigDialog2AtSetting(id))
        return "FunctionSearch.unavailable.targetMissing";
      return null;
    }
    switch (id) {
      case "weights.download" -> owner.openKataGoWeightDownload();
      case "engine.acceleration" -> owner.openKataGoAcceleration();
      case "game.komi" -> owner.editGameKomi();
      case "engine.rules" -> owner.setRulesAtEditor();
      case "game.ai-coach" -> owner.handleAiCoachToolbarAction();
      case "share.private-history" -> owner.openPrivateKifuSearch();
      case "share.public-history" -> owner.openPublicKifuSearch();
      default -> {
        if (entry.targetType() == FunctionCatalog.TargetType.SETTING
            || entry.targetType() == FunctionCatalog.TargetType.NAVIGATION) {
          if (LizzieFrame.menu.functionPath(id) != null) {
            if (!LizzieFrame.menu.locateFunction(id))
              return "FunctionSearch.unavailable.targetMissing";
          } else if (owner.toolbar.functionTarget(id) != null) {
            if (!owner.toolbar.locateFunction(id)) return "FunctionSearch.unavailable.toolbar";
            highlight(owner.toolbar.functionTarget(id));
          } else {
            if (!LizzieFrame.menu.locateTopFunction(id))
              return "FunctionSearch.unavailable.toolbar";
            highlight(LizzieFrame.menu.topFunctionTarget(id));
          }
        } else {
          java.util.List<javax.swing.JMenuItem> path = LizzieFrame.menu.refreshFunctionPath(id);
          javax.swing.JComponent control =
              path != null && !path.isEmpty()
                  ? path.get(path.size() - 1)
                  : owner.toolbar.functionTarget(id);
          if (control == null) control = LizzieFrame.menu.topFunctionTarget(id);
          if (!(control instanceof javax.swing.AbstractButton target))
            return "FunctionSearch.unavailable.targetMissing";
          reason = unavailableReason(id);
          if (reason != null) return reason;
          if (!target.isEnabled()) return "FunctionSearch.unavailable.context";
          target.doClick(0);
        }
      }
    }
    return null;
  }

  private void highlight(javax.swing.JComponent target) {
    clearHighlight();
    if (target == null || !target.isShowing()) return;
    highlightedTarget = target;
    originalTargetBorder = target.getBorder();
    target.setBorder(
        javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(new java.awt.Color(185, 156, 93), 2),
            originalTargetBorder));
    highlightTimer = new javax.swing.Timer(2400, event -> clearHighlight());
    highlightTimer.setRepeats(false);
    highlightTimer.start();
  }

  private void clearHighlight() {
    if (highlightTimer != null) highlightTimer.stop();
    highlightTimer = null;
    if (highlightedTarget != null) {
      highlightedTarget.setBorder(originalTargetBorder);
      highlightedTarget.repaint();
    }
    highlightedTarget = null;
    originalTargetBorder = null;
  }

  private String navigateBoard(FunctionCatalog.Entry entry, Window sourceBoard) {
    Window target = sourceBoard;
    if (target == owner && Lizzie.config.isFloatBoardMode()) target = owner.independentMainBoard;
    if (target == null
        || (target != owner && target != owner.independentMainBoard)
        || !target.isShowing()) return "FunctionSearch.unavailable.board";
    String boardName =
        Lizzie.resourceBundle.getString(
            target == owner ? "FunctionSearch.path.board" : "IndependentMainBoard.title");
    String path =
        entry.pathKeys().stream()
            .map(
                key ->
                    "FunctionSearch.path.board".equals(key)
                        ? boardName
                        : Lizzie.resourceBundle.getString(key))
            .collect(java.util.stream.Collectors.joining(" → "));
    javax.swing.JOptionPane.showMessageDialog(
        target,
        String.format(
            Lizzie.resourceBundle.getString("FunctionSearch.navigate.board"), boardName, path),
        Lizzie.resourceBundle.getString(entry.titleKey()),
        javax.swing.JOptionPane.INFORMATION_MESSAGE);
    if (target.isShowing()) {
      target.toFront();
      if (target == owner) owner.mainPanel.requestFocusInWindow();
      else target.requestFocus();
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
