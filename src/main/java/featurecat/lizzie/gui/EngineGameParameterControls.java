package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameView;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The toolbar's view of the engine-game parameters: the "game parameters" button next to pause, and
 * the explanation on the ordinary breadth/aggressiveness controls, which a running engine game
 * locks because its participants take parameters only through the game panel.
 */
final class EngineGameParameterControls {
  private EngineGameParameterControls() {}

  /** The "game parameters" button; hidden until {@link #refreshEntry} sees an analysis game. */
  static JFontButton createEntry() {
    JFontButton button =
        new JFontButton(Lizzie.resourceBundle.getString("EngineGameParameters.entry"));
    button.setFocusable(false);
    button.setVisible(false);
    button.setMargin(new Insets(0, 0, 0, 0));
    button.addActionListener(
        event -> EngineGameParametersDialog.open(SwingUtilities.getWindowAncestor(button)));
    return button;
  }

  /**
   * Shows the button for an analysis-mode engine game and enables it while a game is running or
   * paused. Between games the snapshot carries no play mode, so the batch keeps its visibility.
   */
  static void refreshEntry(JComponent button, EngineGameSnapshot snapshot) {
    if (button == null) return;
    EngineGameView view = snapshot.view();
    if (view != null) button.setVisible(!view.genmove());
    else if (!snapshot.betweenGames()) button.setVisible(false);
    button.setEnabled(button.isVisible() && snapshot.playing());
  }

  /**
   * Points the toolbar's breadth and aggressiveness controls at the game panel while an engine game
   * locks them, and restores their own tooltips afterwards. Enabled state is the caller's.
   */
  static void explainToolbarLock(
      boolean locked,
      JComponent chkWrn,
      JComponent txtWrn,
      JComponent chkPda,
      JComponent txtPda,
      JComponent customPda) {
    String lockedTip =
        locked ? Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel") : null;
    String wrnTip = locked ? lockedTip : Lizzie.resourceBundle.getString("Menu.chkWRN.toolTopText");
    String pdaTip = locked ? lockedTip : Lizzie.resourceBundle.getString("Menu.chkPDA.toolTopText");
    chkWrn.setToolTipText(wrnTip);
    txtWrn.setToolTipText(wrnTip);
    chkPda.setToolTipText(pdaTip);
    txtPda.setToolTipText(pdaTip);
    customPda.setToolTipText(lockedTip);
  }

  /** Locks one of the advanced-parameter dialog's editors for the running engine game. */
  static void lockForEngineGame(JComponent control) {
    control.setEnabled(false);
    control.setToolTipText(Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel"));
  }
}
