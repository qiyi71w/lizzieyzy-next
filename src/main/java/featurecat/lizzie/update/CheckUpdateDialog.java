package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.AppleStyleSupport;
import featurecat.lizzie.gui.JFontButton;
import featurecat.lizzie.gui.JFontLabel;
import featurecat.lizzie.gui.JFontRadioButton;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** 检查更新页: version, 更新通道, reserved #298 row, then Check. Opening does not fetch. */
public final class CheckUpdateDialog extends JDialog {
  private final JFontRadioButton stableButton =
      new JFontRadioButton(
          UpdateText.tr("WindowsUpdate.channel.stable", "正式", "Official"));
  private final JFontRadioButton betaButton =
      new JFontRadioButton(UpdateText.tr("WindowsUpdate.channel.beta", "测试", "Test"));

  public CheckUpdateDialog(Component parent) {
    super(
        parent == null ? null : SwingUtilities.getWindowAncestor(parent),
        UpdateText.tr("WindowsUpdate.page.title", "检查更新", "Check update"),
        ModalityType.APPLICATION_MODAL);
    buildUi(parent);
  }

  private void buildUi(Component parent) {
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new GridBagLayout());
    root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
    AppleStyleSupport.applyPanelStyle(root);
    setContentPane(root);

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.gridwidth = 2;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(0, 0, 10, 0);
    root.add(
        new JFontLabel(
            UpdateText.tr("WindowsUpdate.currentVersion", "当前版本", "Current version")
                + ": "
                + displayVersion()),
        constraints);

    constraints.gridy = 1;
    constraints.gridwidth = 1;
    constraints.insets = new Insets(0, 0, 8, 12);
    root.add(
        new JFontLabel(UpdateText.tr("WindowsUpdate.page.channel", "更新通道", "Update channel")),
        constraints);

    UpdateChannel current = UpdateChannel.current();
    stableButton.setSelected(current == UpdateChannel.STABLE);
    betaButton.setSelected(current == UpdateChannel.BETA);
    ButtonGroup group = new ButtonGroup();
    group.add(stableButton);
    group.add(betaButton);
    stableButton.addActionListener(e -> UpdateChannel.persist(UpdateChannel.STABLE));
    betaButton.addActionListener(e -> UpdateChannel.persist(UpdateChannel.BETA));

    JPanel radios = new JPanel();
    radios.setOpaque(false);
    radios.add(stableButton);
    radios.add(betaButton);
    constraints.gridx = 1;
    constraints.insets = new Insets(0, 0, 8, 0);
    root.add(radios, constraints);

    JPanel sourceReservation = new JPanel();
    sourceReservation.setOpaque(false);
    sourceReservation.setPreferredSize(new Dimension(1, 36));
    sourceReservation.setMinimumSize(new Dimension(1, 36));
    constraints.gridx = 0;
    constraints.gridy = 2;
    constraints.gridwidth = 2;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.insets = new Insets(0, 0, 12, 0);
    root.add(sourceReservation, constraints);

    JFontButton checkButton =
        new JFontButton(UpdateText.tr("WindowsUpdate.btnCheck", "检查更新", "Check update"));
    checkButton.addActionListener(
        e -> WindowsUpdateController.checkForUpdate(this, selectedChannel()));
    constraints.gridy = 3;
    constraints.fill = GridBagConstraints.NONE;
    constraints.anchor = GridBagConstraints.EAST;
    constraints.insets = new Insets(0, 0, 0, 0);
    root.add(checkButton, constraints);

    pack();
    setMinimumSize(new Dimension(Math.max(360, getWidth()), getHeight()));
    setLocationRelativeTo(parent);
  }

  private UpdateChannel selectedChannel() {
    return betaButton.isSelected() ? UpdateChannel.BETA : UpdateChannel.STABLE;
  }

  private static String displayVersion() {
    return Lizzie.nextVersion == null || Lizzie.nextVersion.isBlank()
        ? "-"
        : Lizzie.nextVersion;
  }
}
