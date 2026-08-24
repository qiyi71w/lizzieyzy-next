package featurecat.lizzie.update;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.JFontButton;
import featurecat.lizzie.gui.JFontLabel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** 检查更新页: version, 更新通道 + hint, Check. #298 can insert a source row above the footer. */
public final class CheckUpdateDialog extends JDialog {
  private static final Color PAGE_BACKGROUND = new Color(246, 247, 249);
  private static final Color MUTED_TEXT = new Color(90, 96, 104);

  private final JRadioButton stableButton =
      channelRadio(UpdateText.tr("WindowsUpdate.channel.stable", "正式", "Official"));
  private final JRadioButton betaButton =
      channelRadio(UpdateText.tr("WindowsUpdate.channel.beta", "测试", "Test"));
  private final JFontLabel channelHint = new JFontLabel(" ");

  public CheckUpdateDialog(Component parent) {
    super(
        parent == null ? null : SwingUtilities.getWindowAncestor(parent),
        UpdateText.tr("WindowsUpdate.page.title", "检查更新", "Check update"),
        ModalityType.APPLICATION_MODAL);
    buildUi(parent);
  }

  private void buildUi(Component parent) {
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(0, 16));
    root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
    root.setBackground(PAGE_BACKGROUND);
    setContentPane(root);

    root.add(buildHeader(), BorderLayout.NORTH);
    root.add(buildForm(), BorderLayout.CENTER);
    root.add(buildFooter(), BorderLayout.SOUTH);

    pack();
    setMinimumSize(new Dimension(Math.max(460, getWidth()), getHeight()));
    setLocationRelativeTo(parent == null ? Lizzie.frame : parent);
  }

  private JPanel buildHeader() {
    JPanel header = new JPanel(new BorderLayout(0, 4));
    header.setOpaque(false);

    JFontLabel versionLabel =
        new JFontLabel(
            UpdateText.tr("WindowsUpdate.currentVersion", "当前版本", "Current version"));
    versionLabel.setForeground(mutedText());

    JFontLabel versionValue = new JFontLabel(displayVersion());
    versionValue.setFont(versionValue.getFont().deriveFont(Font.BOLD, 16f));

    header.add(versionLabel, BorderLayout.NORTH);
    header.add(versionValue, BorderLayout.CENTER);
    return header;
  }

  private JPanel buildForm() {
    JPanel form = new JPanel(new GridBagLayout());
    form.setOpaque(false);

    JFontLabel channelLabel =
        new JFontLabel(UpdateText.tr("WindowsUpdate.page.channel", "更新通道", "Update channel"));
    channelLabel.setLabelFor(stableButton);

    UpdateChannel current = UpdateChannel.current();
    stableButton.setSelected(current == UpdateChannel.STABLE);
    betaButton.setSelected(current == UpdateChannel.BETA);
    ButtonGroup group = new ButtonGroup();
    group.add(stableButton);
    group.add(betaButton);
    stableButton.addActionListener(
        e -> {
          UpdateChannel.persist(UpdateChannel.STABLE);
          refreshChannelHint();
        });
    betaButton.addActionListener(
        e -> {
          UpdateChannel.persist(UpdateChannel.BETA);
          refreshChannelHint();
        });

    channelHint.setFont(channelHint.getFont().deriveFont(Font.PLAIN, 12f));
    channelHint.setForeground(mutedText());
    channelHint.setAlignmentY(Component.CENTER_ALIGNMENT);
    refreshChannelHint();

    JPanel channelRow = new JPanel();
    channelRow.setOpaque(false);
    channelRow.setLayout(new BoxLayout(channelRow, BoxLayout.X_AXIS));
    stableButton.setAlignmentY(Component.CENTER_ALIGNMENT);
    betaButton.setAlignmentY(Component.CENTER_ALIGNMENT);
    channelRow.add(stableButton);
    channelRow.add(Box.createHorizontalStrut(12));
    channelRow.add(betaButton);
    channelRow.add(Box.createHorizontalStrut(16));
    channelRow.add(channelHint);

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(0, 0, 8, 0);
    form.add(channelLabel, constraints);

    constraints.gridy = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1;
    constraints.insets = new Insets(0, 0, 0, 0);
    form.add(channelRow, constraints);
    return form;
  }

  private JPanel buildFooter() {
    JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    footer.setOpaque(false);
    JFontButton checkButton =
        new JFontButton(UpdateText.tr("WindowsUpdate.btnCheck", "检查更新", "Check update"));
    checkButton.addActionListener(
        e -> WindowsUpdateController.checkForUpdate(this, selectedChannel()));
    footer.add(checkButton);
    getRootPane().setDefaultButton(checkButton);
    return footer;
  }

  private void refreshChannelHint() {
    if (selectedChannel() == UpdateChannel.BETA) {
      channelHint.setText(
          UpdateText.tr(
              "WindowsUpdate.page.channelHint.beta",
              "测试版只从 GitHub 获取。",
              "Test builds come from GitHub only."));
      return;
    }
    channelHint.setText(
        UpdateText.tr(
            "WindowsUpdate.page.channelHint.stable",
            "正式通道跟随已发布版本。",
            "Official releases only."));
  }

  private UpdateChannel selectedChannel() {
    return betaButton.isSelected() ? UpdateChannel.BETA : UpdateChannel.STABLE;
  }

  private static JRadioButton channelRadio(String text) {
    JRadioButton button = new JRadioButton(text);
    button.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setFocusPainted(false);
    button.setBorderPainted(false);
    button.setBorder(BorderFactory.createEmptyBorder());
    button.setHorizontalAlignment(SwingConstants.LEFT);
    return button;
  }

  private static Color mutedText() {
    if (Lizzie.config != null && Lizzie.config.isAppleStyle) {
      return new Color(170, 176, 184);
    }
    return MUTED_TEXT;
  }

  private static String displayVersion() {
    return Lizzie.nextVersion == null || Lizzie.nextVersion.isBlank()
        ? "-"
        : Lizzie.nextVersion;
  }
}
