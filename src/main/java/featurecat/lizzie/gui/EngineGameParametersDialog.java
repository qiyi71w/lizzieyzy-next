package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameOpeningParameters;
import featurecat.lizzie.enginegame.EngineGameParameterRequest;
import featurecat.lizzie.enginegame.EngineGameParameterState;
import featurecat.lizzie.enginegame.EngineGameTransaction;
import featurecat.lizzie.enginegame.EngineGameView;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.math.BigDecimal;
import java.text.MessageFormat;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * In-game "game parameters" panel: shows the confirmed breadth of the two engine-game participant
 * positions and sends edits only on Apply. Drafts belong to the game they were typed for and are
 * dropped when that game ends. Displays only what {@code Lizzie.engineGame} reports; EDT only.
 */
public final class EngineGameParametersDialog extends JDialog {
  private static EngineGameParametersDialog instance;

  private final PositionRow firstRow = new PositionRow(true);
  private final PositionRow secondRow = new PositionRow(false);
  private final JTextArea status = new JTextArea();
  private final JFontButton applyButton =
      new JFontButton(Lizzie.resourceBundle.getString("EngineGameParameters.apply"));

  /** The game the drafts were typed for, or null without a playing game. */
  private EngineGameTransaction draftGame;

  private EngineGameParametersDialog(Window owner) {
    super(owner);
    setTitle(Lizzie.resourceBundle.getString("EngineGameParameters.title"));
    setModal(false);
    setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
    if (owner != null) {
      setAlwaysOnTop(owner.isAlwaysOnTop());
    }

    JPanel rows = new JPanel(new GridBagLayout());
    rows.setBorder(new EmptyBorder(12, 12, 4, 12));
    firstRow.addTo(rows, 0);
    secondRow.addTo(rows, 1);

    status.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    status.setLineWrap(true);
    status.setWrapStyleWord(true);
    status.setEditable(false);
    status.setFocusable(false);
    status.setOpaque(false);
    status.setColumns(36);
    status.setBorder(new EmptyBorder(4, 12, 4, 12));

    applyButton.addActionListener(e -> apply());
    JFontButton closeButton =
        new JFontButton(Lizzie.resourceBundle.getString("EngineGameParameters.close"));
    closeButton.addActionListener(e -> setVisible(false));
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
    buttons.add(applyButton);
    buttons.add(closeButton);

    JPanel south = new JPanel(new BorderLayout());
    south.add(status, BorderLayout.CENTER);
    south.add(buttons, BorderLayout.SOUTH);
    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(rows, BorderLayout.CENTER);
    getContentPane().add(south, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(applyButton);
  }

  /** Shows the single panel instance, or brings it to front; drafts of a closed panel are gone. */
  public static void open(Window owner) {
    if (instance == null) {
      instance = new EngineGameParametersDialog(owner);
    }
    EngineGameParametersDialog dialog = instance;
    if (dialog.isVisible()) {
      dialog.refresh();
    } else {
      dialog.discardDrafts();
      dialog.refresh();
      dialog.pack();
      dialog.setLocationRelativeTo(owner);
      dialog.setVisible(true);
    }
    dialog.toFront();
  }

  /** Refreshes the panel after an engine-game transition; no-op while it is closed. */
  public static void refreshIfOpen() {
    if (instance != null && instance.isVisible()) {
      instance.refresh();
    }
  }

  /** Whether the panel is showing, so its inline failure text replaces a popup. */
  public static boolean isOpen() {
    return instance != null && instance.isVisible();
  }

  /** Breadth as people type it: {@code 0}, {@code 0.25}, never {@code 0.0}. */
  static String formatWrn(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  /** The playing game, or null; drafts and Apply are bound to it. */
  private static EngineGameTransaction playingGame(EngineGameParameterState state) {
    return state == null || Lizzie.engineGame.current().view() == null
        ? null
        : Lizzie.engineGame.transaction();
  }

  private void refresh() {
    EngineGameParameterState state = Lizzie.engineGame.parameterState();
    EngineGameTransaction game = playingGame(state);
    if (game != draftGame) {
      // Drafts never carry over to another game.
      discardDrafts();
      draftGame = game;
    }
    EngineGameView view = game == null ? null : Lizzie.engineGame.current().view();
    boolean playing = view != null;
    firstRow.show(view, playing ? state.position(true) : null);
    secondRow.show(view, playing ? state.position(false) : null);
    applyButton.setEnabled(playing);
    String message = "";
    if (!playing) {
      message = Lizzie.resourceBundle.getString("EngineGameParameters.notPlaying");
    } else if (state.failure() != null) {
      message =
          MessageFormat.format(
              Lizzie.resourceBundle.getString("EngineGameParameters.failed"), state.failure());
    }
    status.setText(message);
    status.setVisible(!message.isEmpty());
    if (isVisible()) {
      growToFit();
    }
  }

  /** Grows the window when longer names or a message no longer fit; never shrinks a user size. */
  private void growToFit() {
    Dimension preferred = getPreferredSize();
    if (preferred.width > getWidth() || preferred.height > getHeight()) {
      setSize(Math.max(preferred.width, getWidth()), Math.max(preferred.height, getHeight()));
    }
  }

  private void discardDrafts() {
    firstRow.discardDraft();
    secondRow.discardDraft();
  }

  private void apply() {
    EngineGameTransaction game = playingGame(Lizzie.engineGame.parameterState());
    if (game == null || game != draftGame) {
      // The drafts were typed for another game; reload instead of replaying them.
      refresh();
      showMessage("EngineGameParameters.notPlaying");
      return;
    }
    Double first = null;
    Double second = null;
    if (firstRow.dirty) {
      first = EngineGameOpeningParameters.parseWrn(firstRow.field.getText());
      if (first == null) {
        showMessage("EngineGameParameters.invalidWrn");
        return;
      }
    }
    if (secondRow.dirty) {
      second = EngineGameOpeningParameters.parseWrn(secondRow.field.getText());
      if (second == null) {
        showMessage("EngineGameParameters.invalidWrn");
        return;
      }
    }
    if (first == null && second == null) {
      return;
    }
    switch (Lizzie.engineGame.reviseParameters(EngineGameParameterRequest.wrn(first, second))) {
      case ACCEPTED, UNCHANGED -> {
        if (first != null) firstRow.dirty = false;
        if (second != null) secondRow.dirty = false;
        refresh();
      }
      case NOT_PLAYING -> {
        refresh();
        showMessage("EngineGameParameters.notPlaying");
      }
      case UNSUPPORTED -> showMessage("EngineGameParameters.rejected");
      case INVALID -> showMessage("EngineGameParameters.invalidWrn");
    }
  }

  private void showMessage(String key) {
    Utils.showMsg(Lizzie.resourceBundle.getString(key), this);
  }

  /** One participant position; bound to the position, its color follows the current game. */
  private static final class PositionRow implements DocumentListener {
    private final boolean firstPosition;
    private final JFontLabel position;
    private final JFontLabel engine = new JFontLabel("");
    private final JFontLabel color = new JFontLabel("");
    private final JFontLabel confirmed = new JFontLabel("");
    private final JFontLabel pending = new JFontLabel("");
    private final JFontTextField field = new JFontTextField();

    /** The user edited the field since the panel last loaded it. */
    private boolean dirty;

    private boolean loading;

    PositionRow(boolean firstPosition) {
      this.firstPosition = firstPosition;
      position =
          new JFontLabel(
              Lizzie.resourceBundle.getString(
                  firstPosition
                      ? "EngineGameParameters.position1"
                      : "EngineGameParameters.position2"));
      position.setFont(position.getFont().deriveFont(Font.BOLD));
      field.setColumns(6);
      field.getDocument().addDocumentListener(this);
    }

    void addTo(JPanel panel, int row) {
      JFontLabel[] labels = {position, engine, color, confirmed, pending};
      for (int column = 0; column < labels.length; column++) {
        panel.add(labels[column], constraints(column, row, column == 1 ? 1.0 : 0.0));
      }
      panel.add(field, constraints(labels.length, row, 0.0));
    }

    private static GridBagConstraints constraints(int x, int y, double weightx) {
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridx = x;
      constraints.gridy = y;
      constraints.weightx = weightx;
      constraints.anchor = GridBagConstraints.WEST;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.insets = new Insets(y == 0 ? 0 : 8, x == 0 ? 0 : 12, 0, 0);
      return constraints;
    }

    /** Shows the position in {@code view}; both null when no game is playing. */
    void show(EngineGameView view, EngineGameParameterState.Position state) {
      boolean black = view != null && firstPosition == view.firstIsBlack();
      engine.setText(view == null ? "" : (black ? view.black() : view.white()).name());
      color.setText(
          view == null
              ? ""
              : Lizzie.resourceBundle.getString(
                  black ? "EngineGameParameters.black" : "EngineGameParameters.white"));
      String wrnLabel = Lizzie.resourceBundle.getString("EngineGameParameters.wrn") + ": ";
      if (state == null || !state.supported()) {
        confirmed.setText(
            state == null
                ? ""
                : wrnLabel + Lizzie.resourceBundle.getString("EngineGameParameters.unsupported"));
        pending.setText("");
        field.setEnabled(false);
        dirty = false;
        load("");
        return;
      }
      confirmed.setText(
          wrnLabel
              + (state.wrn() == null
                  ? Lizzie.resourceBundle.getString("EngineGameParameters.unknown")
                  : formatWrn(state.wrn())));
      pending.setText(
          state.targetWrn() == null
              ? ""
              : MessageFormat.format(
                  Lizzie.resourceBundle.getString("EngineGameParameters.pending"),
                  formatWrn(state.targetWrn())));
      field.setEnabled(true);
      if (!dirty) {
        load(state.wrn() == null ? "" : formatWrn(state.wrn()));
      }
    }

    void discardDraft() {
      dirty = false;
      load("");
    }

    private void load(String text) {
      if (text.equals(field.getText())) {
        return;
      }
      loading = true;
      try {
        field.setText(text);
      } finally {
        loading = false;
      }
    }

    private void edited() {
      if (!loading) {
        dirty = true;
      }
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
      edited();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      edited();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      edited();
    }
  }
}
