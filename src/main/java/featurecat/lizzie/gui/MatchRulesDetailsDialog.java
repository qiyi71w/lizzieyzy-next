package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.MatchRulesSnapshot;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ResourceBundle;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;

/** Read-only match-rules details from a frozen snapshot. Does not query engines. */
public final class MatchRulesDetailsDialog extends JDialog {
  static final int MIN_WIDTH = 360;
  static final int MIN_HEIGHT = 240;
  static final int MAX_WIDTH = 640;
  static final int MAX_HEIGHT = 480;

  private final Window ownerWindow;
  private final JFontTextArea textArea;
  private final JFontButton closeButton;

  public MatchRulesDetailsDialog(Window owner, MatchRulesSnapshot snapshot) {
    super(owner, ModalityType.DOCUMENT_MODAL);
    this.ownerWindow = owner;
    ResourceBundle bundle = Lizzie.resourceBundle;
    setTitle(bundle.getString("MatchRules.details.title"));
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    if (Lizzie.frame != null) {
      setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    }
    setResizable(true);

    textArea = new JFontTextArea(MatchRulesTexts.details(snapshot, bundle));
    textArea.setEditable(false);
    textArea.setLineWrap(true);
    textArea.setWrapStyleWord(true);
    textArea.setCaretPosition(0);
    JScrollPane scroll = new JScrollPane(textArea);
    scroll.setPreferredSize(preferredContentSize());
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

    closeButton = new JFontButton(bundle.getString("MatchRules.details.close"));
    AccessibilitySupport.button(
        closeButton,
        bundle.getString("MatchRules.details.close"),
        bundle.getString("MatchRules.details.close"));
    closeButton.addActionListener(event -> closeAndReturnFocus());

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    buttons.add(closeButton);

    JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(new EmptyBorder(12, 12, 12, 12));
    content.add(scroll, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);

    AccessibilitySupport.named(
        textArea,
        bundle.getString("MatchRules.details.title"),
        bundle.getString("MatchRules.details.title"));
    AccessibilitySupport.applyToTree(this);
    AccessibilitySupport.installEscapeAction(getRootPane(), this, this::closeAndReturnFocus);
    getRootPane().setDefaultButton(closeButton);
    pack();
    setLocationRelativeTo(owner);
  }

  static Dimension preferredContentSize() {
    int font = Config.frameFontSize > 0 ? Config.frameFontSize : 12;
    int width = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, font * 32));
    int height = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, font * 22));
    return new Dimension(width, height);
  }

  void closeAndReturnFocus() {
    setVisible(false);
    dispose();
    if (ownerWindow != null) {
      ownerWindow.toFront();
      ownerWindow.requestFocus();
    }
  }

  String detailsText() {
    return textArea.getText();
  }

  JFontButton closeButton() {
    return closeButton;
  }
}
