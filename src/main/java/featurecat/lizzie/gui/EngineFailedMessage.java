package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineStartupDiagnostic;
import featurecat.lizzie.analysis.EngineStartupDiagnostics;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.ExportSanitizer;
import featurecat.lizzie.logging.ObservationText;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.json.JSONArray;
import org.json.JSONObject;

public class EngineFailedMessage extends JDialog {
  static final int MAX_DIALOG_WIDTH = 980;
  static final int SCREEN_MARGIN = 64;
  static final String REDACTED_VALUE = "<redacted>";
  private static final String SENSITIVE_KEY = "(?:password|passwd|token|api[-_]?key|secret)";
  private static final Pattern QUOTED_SENSITIVE_ASSIGNMENT_START =
      Pattern.compile("(?i)([\\\"'])(\\s*" + SENSITIVE_KEY + "\\b\\s*[:=]\\s*)");
  private static final Pattern SENSITIVE_ASSIGNMENT_START =
      Pattern.compile("(?i)\\b" + SENSITIVE_KEY + "\\b[\\\"']?\\s*[:=]\\s*");
  private static final Pattern SENSITIVE_FLAG_START =
      Pattern.compile("(?i)(?<!\\S)(?:-{1,2}|/)" + SENSITIVE_KEY + "\\b(?:\\s*[:=]\\s*|\\s+)");
  private static final int MAX_SUMMARY_FINDINGS = 16;
  private static final int MAX_SUMMARY_FIELD_BYTES = 1024;
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]+");

  private final TensorRtRepairContext repairContext;
  private final JButton tensorRtRepairButton;
  private boolean tensorRtRepairInvoked;
  private final String originalMessage;
  private final String originalCommand;
  private final JDialog diagnosticWindow;
  private final JTextArea summaryArea;
  private final JScrollPane detailsPane;
  private final JTextArea detailsArea;
  private final JButton btnDetails;
  private final JButton btnCopy;
  private final JButton btnExport;
  private final JLabel copyStatusLabel;

  private EngineStartupDiagnostics.Attempt boundAttempt;
  private EngineStartupDiagnostic displayedDiagnostic;
  private Timer refreshTimer;

  public static final class DiagnosticActionResult {
    public final boolean directedRepairOpened;

    public static DiagnosticActionResult none() {
      return new DiagnosticActionResult(false);
    }

    public static DiagnosticActionResult ofRepairChoice(boolean directedRepairOpened) {
      return new DiagnosticActionResult(directedRepairOpened);
    }

    public static DiagnosticActionResult of(EngineFailedMessage dialog) {
      return ofRepairChoice(dialog != null && dialog.tensorRtRepairInvoked());
    }

    DiagnosticActionResult(boolean directedRepairOpened) {
      this.directedRepairOpened = directedRepairOpened;
    }
  }

  public static boolean shouldOfferTensorRtRepair(TensorRtRepairContext context) {
    return context != null && context.repairable;
  }

  public static String tensorRtRepairActionLabel() {
    return Lizzie.resourceBundle.getString("EngineFailedMessage.openTensorRtRepair");
  }

  public static void runOnEventDispatchThreadAndWait(Runnable action) {
    if (action == null) {
      throw new IllegalArgumentException("The event-dispatch action must not be null.");
    }
    if (SwingUtilities.isEventDispatchThread()) {
      action.run();
      return;
    }
    try {
      SwingUtilities.invokeAndWait(action);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for the Swing event-dispatch thread.", interrupted);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IllegalStateException("Swing event-dispatch action failed.", cause);
    }
  }

  public static void showDialog(
      List<String> commands,
      String command,
      String message,
      boolean canUseCmdDignostic,
      boolean isGtpEngine,
      boolean restartContribute,
      boolean modal) {
    showDialog(
        commands,
        command,
        message,
        canUseCmdDignostic,
        isGtpEngine,
        restartContribute,
        modal,
        (TensorRtRepairContext) null,
        null);
  }

  public static void showDialog(
      List<String> commands,
      String command,
      String message,
      boolean canUseCmdDignostic,
      boolean isGtpEngine,
      boolean restartContribute,
      boolean modal,
      Consumer<EngineFailedMessage> onCreated) {
    showDialog(
        commands,
        command,
        message,
        canUseCmdDignostic,
        isGtpEngine,
        restartContribute,
        modal,
        null,
        onCreated);
  }

  public static void showDialog(
      List<String> commands,
      String command,
      String message,
      boolean canUseCmdDignostic,
      boolean isGtpEngine,
      boolean restartContribute,
      boolean modal,
      TensorRtRepairContext repairContext) {
    showDialog(
        commands,
        command,
        message,
        canUseCmdDignostic,
        isGtpEngine,
        restartContribute,
        modal,
        repairContext,
        null);
  }

  public static void showDialog(
      List<String> commands,
      String command,
      String message,
      boolean canUseCmdDignostic,
      boolean isGtpEngine,
      boolean restartContribute,
      boolean modal,
      TensorRtRepairContext repairContext,
      Consumer<EngineFailedMessage> onCreated) {
    runOnEventDispatchThreadAndWait(
        () -> {
          EngineFailedMessage dialog =
              new EngineFailedMessage(
                  commands,
                  command,
                  message,
                  canUseCmdDignostic,
                  isGtpEngine,
                  restartContribute,
                  repairContext);
          if (onCreated != null) {
            onCreated.accept(dialog);
          }
          dialog.setModal(modal);
          dialog.setVisible(true);
        });
  }

  public EngineFailedMessage(
      List<String> commands,
      String command,
      String message,
      boolean canUseCmdDignostic,
      boolean isGtpEngine,
      boolean restartContribute) {
    this(commands, command, message, canUseCmdDignostic, isGtpEngine, restartContribute, null);
  }

  public EngineFailedMessage(
      List<String> commands,
      String command,
      String message,
      boolean canUseCmdDignostic,
      boolean isGtpEngine,
      boolean restartContribute,
      TensorRtRepairContext repairContext) {
    // this.setModal(true);
    // setType(Type.POPUP);
    this.originalMessage = message;
    this.originalCommand = command;
    setTitle(Lizzie.resourceBundle.getString("Leelaz.engineFailed")); // "消息提醒");
    setAlwaysOnTop(true);
    try {
      this.setIconImage(ImageIO.read(getClass().getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    JPanel root = new JPanel(new BorderLayout(0, 10));
    root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    Font textFont = new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize);
    JScrollPane messagePane = createScrollableText(message, textFont);
    messagePane.setPreferredSize(new Dimension(1, 92));
    messagePane
        .getAccessibleContext()
        .setAccessibleName(Lizzie.resourceBundle.getString("Leelaz.engineFailed"));
    root.add(messagePane, BorderLayout.NORTH);

    JPanel commandPanel = new JPanel(new BorderLayout(8, 0));
    JLabel lblEngineCmd =
        new JFontLabel(Lizzie.resourceBundle.getString("EngineFailedMessage.engineCmd"));
    commandPanel.add(lblEngineCmd, BorderLayout.WEST);
    JScrollPane commandPane = createScrollableText(command, textFont);
    commandPane.setPreferredSize(new Dimension(1, 112));
    commandPane.getAccessibleContext().setAccessibleName(lblEngineCmd.getText());
    commandPanel.add(commandPane, BorderLayout.CENTER);

    JPanel diagnosticPanel = new JPanel(new BorderLayout(0, 8));
    diagnosticPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    summaryArea = new JTextArea();
    summaryArea.setName("EngineFailedMessage.diagnosticSummary");
    summaryArea.setEditable(false);
    summaryArea.setLineWrap(true);
    summaryArea.setWrapStyleWord(true);
    summaryArea.setFont(textFont);
    summaryArea.setOpaque(false);
    summaryArea.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
    summaryArea
        .getAccessibleContext()
        .setAccessibleName(
            Lizzie.resourceBundle.getString("EngineFailedMessage.diagnosticSummary"));
    JScrollPane summaryPane =
        new JScrollPane(
            summaryArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    summaryPane.setName("EngineFailedMessage.summaryPane");
    summaryPane.setPreferredSize(new Dimension(1, 86));
    summaryPane
        .getAccessibleContext()
        .setAccessibleName(
            Lizzie.resourceBundle.getString("EngineFailedMessage.diagnosticSummary"));
    diagnosticPanel.add(summaryPane, BorderLayout.CENTER);

    detailsPane = createScrollableText("", textFont);
    detailsPane.setName("EngineFailedMessage.detailsPane");
    detailsPane.setPreferredSize(new Dimension(1, 140));
    detailsPane
        .getAccessibleContext()
        .setAccessibleName(Lizzie.resourceBundle.getString("EngineFailedMessage.details"));
    detailsArea = (JTextArea) detailsPane.getViewport().getView();
    detailsArea.setName("EngineFailedMessage.detailsArea");
    diagnosticPanel.add(detailsPane, BorderLayout.SOUTH);

    root.add(commandPanel, BorderLayout.CENTER);
    diagnosticWindow = new JDialog(this, Lizzie.resourceBundle.getString("EngineFailedMessage.details"));
    diagnosticWindow.setName("EngineFailedMessage.diagnosticWindow");
    diagnosticWindow.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
    JPanel diagnosticContent = new JPanel(new BorderLayout(0, 8));
    diagnosticContent.add(diagnosticPanel, BorderLayout.CENTER);
    diagnosticWindow.setContentPane(diagnosticContent);

    JPanel footer = new JPanel(new BorderLayout(8, 0));
    JPanel legacyRow = new JPanel(new BorderLayout(8, 0));

    if (restartContribute) {
      JButton btnRestart =
          new JFontButton(Lizzie.resourceBundle.getString("EngineFailedMessage.btnRestart"));
      btnRestart.addActionListener(
          new ActionListener() {
            public void actionPerformed(ActionEvent e) {
              Lizzie.frame.startContributeEngine();
              setVisible(false);
            }
          });
      legacyRow.add(btnRestart, BorderLayout.EAST);
    }

    if (canUseCmdDignostic) {
      JButton btnRunInCmd =
          new JFontButton(Lizzie.resourceBundle.getString("EngineFailedMessage.btnRunInCmd"));
      btnRunInCmd.setForeground(Color.RED);
      btnRunInCmd.addActionListener(
          new ActionListener() {
            public void actionPerformed(ActionEvent e) {
              try {
                BufferedWriter bw =
                    new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream("dignostic.bat"), "UTF-8"));
                if (isGtpEngine) {
                  bw.write("CHCP 65001");
                  bw.newLine();
                  bw.write(
                      "@echo " + Lizzie.resourceBundle.getString("EngineFailedMessage.batTips"));
                  bw.newLine();
                }
                if (commands != null && !commands.isEmpty()) {
                  bw.write(buildDiagnosticCommand(commands, command));
                } else {
                  bw.write(buildDiagnosticCommand(null, command));
                }
                if (isGtpEngine) {
                  bw.write(" < test_commands.txt");
                  BufferedWriter bw2 = new BufferedWriter(new FileWriter("test_commands.txt"));
                  bw2.write("name");
                  bw2.newLine();
                  bw2.write("version");
                  bw2.newLine();
                  bw2.write("time_settings 0 2 1");
                  bw2.newLine();
                  bw2.write("genmove b");
                  bw2.newLine();
                  bw2.write("lz-genmove_analyze w 100");
                  bw2.newLine();
                  bw2.write("showboard");
                  bw2.newLine();
                  bw2.close();
                }
                bw.newLine();
                bw.write("pause");
                bw.newLine();
                bw.close();
                new ProcessBuilder("powershell", "/c", "start", "dignostic.bat").start();
              } catch (IOException s) {
                // TODO Auto-generated catch block
                s.printStackTrace();
              }
            }
          });
      btnRunInCmd.setFocusPainted(false);
      btnRunInCmd.setMargin(new Insets(0, 0, 0, 0));
      btnRunInCmd.setContentAreaFilled(false);

      JLabel lblClick =
          new JFontLabel(Lizzie.resourceBundle.getString("EngineFailedMessage.lblClick"));
      JLabel lblRunInCmd =
          new JFontLabel(Lizzie.resourceBundle.getString("EngineFailedMessage.lblRunInCmd"));
      JPanel diagnosticActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      diagnosticActions.add(lblClick);
      diagnosticActions.add(btnRunInCmd);
      diagnosticActions.add(lblRunInCmd);
      legacyRow.add(diagnosticActions, BorderLayout.CENTER);
    }

    JButton repairButton = null;
    if (shouldOfferTensorRtRepair(repairContext)) {
      repairButton = new JFontButton(tensorRtRepairActionLabel());
      repairButton
          .getAccessibleContext()
          .setAccessibleName(
              Lizzie.resourceBundle.getString(
                  "EngineFailedMessage.openTensorRtRepairAccessibleName"));
      repairButton
          .getAccessibleContext()
          .setAccessibleDescription(
              Lizzie.resourceBundle.getString(
                  "EngineFailedMessage.openTensorRtRepairAccessibleDescription"));
      repairButton.addActionListener(
          new ActionListener() {
            public void actionPerformed(ActionEvent e) {
              invokeTensorRtRepairAction();
            }
          });
      legacyRow.add(repairButton, restartContribute ? BorderLayout.WEST : BorderLayout.EAST);
    }
    this.repairContext = repairContext;
    this.tensorRtRepairButton = repairButton;

    footer.add(legacyRow, BorderLayout.CENTER);

    JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    copyStatusLabel = new JFontLabel("");
    copyStatusLabel.setName("EngineFailedMessage.copyStatus");

    btnDetails = new JFontButton(Lizzie.resourceBundle.getString("EngineFailedMessage.details"));
    btnDetails.setName("EngineFailedMessage.details");
    btnDetails
        .getAccessibleContext()
        .setAccessibleName(Lizzie.resourceBundle.getString("EngineFailedMessage.details"));
    btnDetails.addActionListener(e -> showDetails());

    btnCopy = new JFontButton(Lizzie.resourceBundle.getString("EngineFailedMessage.copyError"));
    btnCopy.setName("EngineFailedMessage.copyError");
    btnCopy
        .getAccessibleContext()
        .setAccessibleName(Lizzie.resourceBundle.getString("EngineFailedMessage.copyError"));
    btnCopy.addActionListener(e -> copyErrorAction());

    btnExport =
        new JFontButton(Lizzie.resourceBundle.getString("EngineFailedMessage.exportDiagnostics"));
    btnExport.setName("EngineFailedMessage.exportDiagnostics");
    btnExport
        .getAccessibleContext()
        .setAccessibleName(
            Lizzie.resourceBundle.getString("EngineFailedMessage.exportDiagnostics"));
    btnExport.addActionListener(e -> exportDiagnosticsAction());
    actionRow.add(copyStatusLabel);
    actionRow.add(btnCopy);
    actionRow.add(btnExport);

    diagnosticContent.add(actionRow, BorderLayout.SOUTH);
    footer.add(btnDetails, BorderLayout.EAST);
    root.add(footer, BorderLayout.SOUTH);
    setContentPane(root);
    int minimumWidth =
        (Lizzie.config != null && Lizzie.config.isFrameFontSmall())
            ? 580
            : ((Lizzie.config != null && Lizzie.config.isFrameFontMiddle()) ? 660 : 730);
    int preferredHeight = canUseCmdDignostic ? 360 : restartContribute ? 340 : 320;
    Rectangle usableScreenBounds = usableScreenBounds();
    Dimension dialogSize =
        calculateDialogSize(
            message,
            command,
            Config.frameFontSize,
            minimumWidth,
            preferredHeight,
            usableScreenBounds.getSize());
    setSize(dialogSize);
    setMinimumSize(
        new Dimension(Math.min(dialogSize.width, 480), Math.min(dialogSize.height, 260)));
    diagnosticWindow.setSize(
        Math.min(dialogSize.width, usableScreenBounds.width - SCREEN_MARGIN),
        Math.min(540, usableScreenBounds.height - SCREEN_MARGIN));
    diagnosticWindow.setMinimumSize(
        new Dimension(Math.min(diagnosticWindow.getWidth(), 480),
            Math.min(diagnosticWindow.getHeight(), 320)));
    setDisplayedDiagnostic(null);

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent e) {
            stopRefreshTimer();
          }

          @Override
          public void windowClosing(WindowEvent e) {
            stopRefreshTimer();
          }
        });

    JRootPane rp = this.getRootPane();
    KeyStroke stroke = KeyStroke.getKeyStroke(KeyEvent.VK_E, 0);
    InputMap inputMap = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    inputMap.put(stroke, KeyEvent.VK_E);
    rp.getActionMap()
        .put(
            KeyEvent.VK_E,
            new AbstractAction() {
              public void actionPerformed(ActionEvent e) {
                Lizzie.frame.toggleGtpConsole();
              }
            });

    setLocationRelativeTo(Lizzie.frame != null ? Lizzie.frame : null);
    setBounds(clampDialogBounds(getBounds(), usableScreenBounds));
  }

  public boolean offersTensorRtRepair() {
    return tensorRtRepairButton != null;
  }

  public TensorRtRepairContext repairContext() {
    return repairContext;
  }

  public JButton tensorRtRepairButton() {
    return tensorRtRepairButton;
  }

  public boolean tensorRtRepairInvoked() {
    return tensorRtRepairInvoked;
  }

  boolean recordTensorRtRepairInvoked() {
    if (tensorRtRepairButton == null) {
      return false;
    }
    tensorRtRepairInvoked = true;
    return true;
  }

  void invokeTensorRtRepairAction() {
    if (!recordTensorRtRepairInvoked()) {
      return;
    }
    setVisible(false);
    if (Lizzie.frame != null) {
      Lizzie.frame.openKataGoAutoSetup(repairContext);
    }
  }

  public void bindStartupDiagnostic(EngineStartupDiagnostics.Attempt attempt) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> bindStartupDiagnostic(attempt));
      return;
    }
    this.boundAttempt = attempt;
    stopRefreshTimer();
    if (attempt != null) {
      setDisplayedDiagnostic(attempt.snapshot());
      refreshTimer = new Timer(150, e -> refreshFromBoundAttempt());
      refreshTimer.setRepeats(true);
      refreshTimer.start();
    } else {
      setDisplayedDiagnostic(null);
    }
  }

  private void refreshFromBoundAttempt() {
    if (boundAttempt == null || !isVisible()) {
      stopRefreshTimer();
      return;
    }
    EngineStartupDiagnostic latest = boundAttempt.snapshot();
    if (latest != null
        && (displayedDiagnostic == null || latest.revision() != displayedDiagnostic.revision())) {
      setDisplayedDiagnostic(latest);
    }
  }

  private void setDisplayedDiagnostic(EngineStartupDiagnostic diagnostic) {
    this.displayedDiagnostic = diagnostic;
    if (diagnostic != null) {
      summaryArea.setText(formatSummaryText(diagnostic));
      detailsArea.setText(diagnostic.shareText());
    } else {
      summaryArea.setText("");
      detailsArea.setText(
          redactSensitiveText(
              (originalMessage == null ? "" : originalMessage)
                  + "\n\n"
                  + (originalCommand == null ? "" : originalCommand)));
    }
    detailsArea.setCaretPosition(0);
    summaryArea.setCaretPosition(0);
    revalidate();
    repaint();
  }

  private void stopRefreshTimer() {
    if (refreshTimer != null) {
      refreshTimer.stop();
      refreshTimer = null;
    }
  }

  @Override
  public void dispose() {
    stopRefreshTimer();
    super.dispose();
  }

  private void showDetails() {
    if (!diagnosticWindow.isVisible()) {
      diagnosticWindow.setLocationRelativeTo(this);
      diagnosticWindow.setBounds(
          clampDialogBounds(diagnosticWindow.getBounds(), usableScreenBounds()));
      diagnosticWindow.setVisible(true);
    }
    diagnosticWindow.toFront();
  }

  private void copyErrorAction() {
    EngineStartupDiagnostic toCopy = this.displayedDiagnostic;
    String copyText;
    if (toCopy != null) {
      copyText = toCopy.shareText();
    } else {
      copyText =
          redactSensitiveText(
              (originalMessage == null ? "" : originalMessage)
                  + "\n"
                  + (originalCommand == null ? "" : originalCommand));
    }
    try {
      Toolkit.getDefaultToolkit()
          .getSystemClipboard()
          .setContents(new StringSelection(copyText), null);
      copyStatusLabel.setText(Lizzie.resourceBundle.getString("EngineFailedMessage.copied"));
    } catch (Exception ex) {
      copyStatusLabel.setText(Lizzie.resourceBundle.getString("EngineFailedMessage.copyFailed"));
    }
  }

  private void exportDiagnosticsAction() {
    EngineStartupDiagnostic failure = this.displayedDiagnostic;
    LoggingRuntime.current()
        .ifPresent(runtime -> DiagnosticsDialog.open(diagnosticWindow, runtime, Lizzie.config, failure));
  }

  static String formatSummaryText(EngineStartupDiagnostic diagnostic) {
    if (diagnostic == null) {
      return "";
    }
    JSONObject json = diagnostic.toJson();
    String unavailable =
        Lizzie.resourceBundle.getString("EngineFailedMessage.diagnostic.unavailable");
    String decimal = json.isNull("exitCode") ? unavailable : String.valueOf(json.opt("exitCode"));
    String hex = json.isNull("exitHex") ? unavailable : json.optString("exitHex", unavailable);
    String status = localizeReason(json.optString("statusName", "unavailable"));
    String attemptId = diagnostic.attemptId();
    String engineId = diagnostic.engineId();
    String revision = String.valueOf(diagnostic.revision());
    String rawOutcome = json.optString("outcome", "");
    String outcome = localizeOutcome(rawOutcome);

    String summaryLine =
        MessageFormat.format(
            Lizzie.resourceBundle.getString("EngineFailedMessage.diagnosticSummary"),
            decimal,
            hex,
            status,
            attemptId,
            engineId,
            revision,
            outcome);
    String sourcesLine = formatSourcesText(json.optJSONObject("sources"));
    String findingsText = formatFindingsText(json.optJSONArray("findings"));
    StringBuilder rendered = new StringBuilder(summaryLine);
    if (!findingsText.isEmpty()) {
      rendered.append("\n\n").append(findingsText);
    }
    if (!sourcesLine.isEmpty()) {
      rendered.append("\n\n").append(sourcesLine);
    }
    return rendered.toString();
  }

  static String formatSourcesText(JSONObject sources) {
    if (sources == null || sources.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(Lizzie.resourceBundle.getString("EngineFailedMessage.sources")).append(": ");
    boolean first = true;
    for (String key : sources.keySet()) {
      if (!first) {
        sb.append("; ");
      }
      first = false;
      JSONObject src = sources.getJSONObject(key);
      String state = src.optString("collectionState", "");
      String localizedState = localizeState(state);
      String reason = src.optString("terminalReason", "");
      sb.append(key).append(": ").append(localizedState);
      if (reason != null && !reason.isEmpty() && !"null".equalsIgnoreCase(reason)) {
        sb.append(" (").append(localizeReason(reason)).append(")");
      }
    }
    return sb.toString();
  }
  static String formatFindingsText(JSONArray findings) {
    if (findings == null || findings.isEmpty()) {
      return "";
    }
    ExportSanitizer sanitizer = new ExportSanitizer();
    StringBuilder rendered =
        new StringBuilder(Lizzie.resourceBundle.getString("EngineFailedMessage.findings"));
    int displayed = Math.min(findings.length(), MAX_SUMMARY_FINDINGS);
    for (int index = 0; index < displayed; index++) {
      JSONObject finding = findings.optJSONObject(index);
      if (finding == null) {
        continue;
      }
      rendered.append("\n\n").append(index + 1).append(". ");
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.dll",
          safeDllName(finding.optString("dll"), sanitizer));
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.outcome",
          localizeFindingOutcome(safeFindingText(finding.optString("outcome"), sanitizer)));
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.importer",
          safeFindingText(finding.optString("importer"), sanitizer));
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.chain",
          safeChain(finding.optJSONArray("chain"), sanitizer));
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.source",
          localizeFindingEvidence(safeFindingText(finding.optString("evidence"), sanitizer)));
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.completeness",
          localizeFindingCompleteness(
              safeFindingText(finding.optString("completeness"), sanitizer)));
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.scope",
          safeFindingText(finding.optString("checkedScope"), sanitizer));
      appendFindingField(
          rendered,
          "EngineFailedMessage.finding.detail",
          safeFindingText(finding.optString("detail"), sanitizer));
    }
    if (findings.length() > displayed) {
      rendered
          .append('\n')
          .append(
              MessageFormat.format(
                  Lizzie.resourceBundle.getString("EngineFailedMessage.finding.more"),
                  findings.length() - displayed));
    }
    return rendered.toString();
  }

  private static void appendFindingField(StringBuilder rendered, String labelKey, String value) {
    if (value == null || value.isEmpty() || "null".equalsIgnoreCase(value)) {
      return;
    }
    if (rendered.charAt(rendered.length() - 1) != ' ') {
      rendered.append("\n   ");
    }
    rendered.append(Lizzie.resourceBundle.getString(labelKey)).append(": ").append(value);
  }

  private static String safeChain(JSONArray chain, ExportSanitizer sanitizer) {
    if (chain == null || chain.isEmpty()) {
      return "";
    }
    StringBuilder rendered = new StringBuilder();
    for (int index = 0; index < chain.length(); index++) {
      String item = safeFindingText(chain.optString(index), sanitizer);
      if (item.isEmpty() || "null".equalsIgnoreCase(item)) {
        continue;
      }
      if (!rendered.isEmpty()) {
        rendered.append(" -> ");
      }
      rendered.append(item);
    }
    return boundedSummaryField(rendered.toString());
  }

  private static String safeDllName(String dll, ExportSanitizer sanitizer) {
    if (dll == null || dll.isEmpty() || "null".equalsIgnoreCase(dll)) {
      return "";
    }
    int separator = Math.max(dll.lastIndexOf('/'), dll.lastIndexOf('\\'));
    String basename = separator < 0 ? dll : dll.substring(separator + 1);
    return safeFindingText(basename, sanitizer);
  }

  private static String safeFindingText(String value, ExportSanitizer sanitizer) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return boundedSummaryField(CONTROL_CHARACTERS.matcher(sanitizer.sanitizeText(value)).replaceAll(" "));
  }

  private static String boundedSummaryField(String value) {
    return ObservationText.boundedUtf8(value, MAX_SUMMARY_FIELD_BYTES, 1).trim();
  }

  private static String localizeFindingEvidence(String evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return "";
    }
    String key = "EngineFailedMessage.evidence." + evidence;
    return Lizzie.resourceBundle.containsKey(key) ? Lizzie.resourceBundle.getString(key) : evidence;
  }

  private static String localizeFindingOutcome(String outcome) {
    if (outcome == null || outcome.isEmpty()) {
      return "";
    }
    String key = "EngineFailedMessage.outcome." + outcome;
    return Lizzie.resourceBundle.containsKey(key) ? Lizzie.resourceBundle.getString(key) : outcome;
  }
  private static String localizeFindingCompleteness(String completeness) {
    if (completeness == null || completeness.isEmpty()) {
      return "";
    }
    return switch (completeness) {
      case "complete" -> Lizzie.resourceBundle.getString("EngineFailedMessage.complete");
      case "partial" -> Lizzie.resourceBundle.getString("EngineFailedMessage.partial");
      case "not-applicable" ->
          Lizzie.resourceBundle.getString("EngineFailedMessage.notApplicable");
      default -> completeness;
    };
  }

  private static String localizeOutcome(String outcome) {
    if (outcome == null || outcome.isEmpty()) {
      return "";
    }
    switch (outcome) {
      case "collecting":
        return Lizzie.resourceBundle.getString("EngineFailedMessage.collecting");
      case "partial":
        return Lizzie.resourceBundle.getString("EngineFailedMessage.partial");
      case "not-applicable":
        return Lizzie.resourceBundle.getString("EngineFailedMessage.notApplicable");
      case "no-specific-dll-identified":
        return Lizzie.resourceBundle.getString("EngineFailedMessage.noSpecificDll");
      default:
        return localizeReason(outcome);
    }
  }

  private static String localizeState(String state) {
    if (state == null || state.isEmpty()) {
      return "";
    }
    switch (state) {
      case "collecting":
        return Lizzie.resourceBundle.getString("EngineFailedMessage.collecting");
      case "partial":
        return Lizzie.resourceBundle.getString("EngineFailedMessage.partial");
      case "not-applicable":
        return Lizzie.resourceBundle.getString("EngineFailedMessage.notApplicable");
      default:
        return localizeReason(state);
    }
  }

  private static String localizeReason(String reason) {
    if (reason == null || reason.isEmpty()) {
      return "";
    }
    if ("not-applicable".equals(reason)) {
      return Lizzie.resourceBundle.getString("EngineFailedMessage.notApplicable");
    }
    String key = "EngineFailedMessage.diagnostic." + reason;
    return Lizzie.resourceBundle.containsKey(key) ? Lizzie.resourceBundle.getString(key) : reason;
  }

  static Dimension calculateDialogSize(
      String message,
      String command,
      int fontSize,
      int minimumWidth,
      int preferredHeight,
      Dimension usableScreen) {
    int usableWidth = Math.max(1, usableScreen == null ? 1280 : usableScreen.width);
    int usableHeight = Math.max(1, usableScreen == null ? 800 : usableScreen.height);
    int widthMargin = Math.min(SCREEN_MARGIN, Math.max(0, usableWidth / 4));
    int heightMargin = Math.min(SCREEN_MARGIN, Math.max(0, usableHeight / 4));
    int widthLimit = Math.max(1, usableWidth - widthMargin);
    int heightLimit = Math.max(1, usableHeight - heightMargin);
    int boundedMinimum = Math.min(Math.max(320, minimumWidth), widthLimit);
    int preferredWidth =
        Math.max(
            boundedMinimum,
            estimateTextWidth(
                (message == null ? "" : message) + "\n" + (command == null ? "" : command),
                fontSize));
    int width = Math.min(widthLimit, Math.min(MAX_DIALOG_WIDTH, preferredWidth));
    int height = Math.min(heightLimit, Math.max(260, preferredHeight));
    return new Dimension(width, height);
  }

  static JScrollPane createScrollableText(String text, Font font) {
    JTextArea area = new JTextArea(redactSensitiveText(text));
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    if (font != null) {
      area.setFont(font);
    }
    area.setCaretPosition(0);
    return new JScrollPane(
        area, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
  }

  static String redactSensitiveText(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return redactSensitiveRemainder(text);
  }

  private static String redactSensitiveRemainder(String text) {
    int firstStart = Integer.MAX_VALUE;
    int valueStart = -1;
    String closingQuote = "";

    Matcher quotedAssignment = QUOTED_SENSITIVE_ASSIGNMENT_START.matcher(text);
    if (quotedAssignment.find()) {
      firstStart = quotedAssignment.start();
      valueStart = quotedAssignment.end();
      closingQuote = quotedAssignment.group(1);
    }

    Matcher assignment = SENSITIVE_ASSIGNMENT_START.matcher(text);
    if (assignment.find() && assignment.start() < firstStart) {
      firstStart = assignment.start();
      valueStart = assignment.end();
      closingQuote = "";
    }

    Matcher flag = SENSITIVE_FLAG_START.matcher(text);
    if (flag.find() && flag.start() < firstStart) {
      valueStart = flag.end();
      closingQuote = "";
    }

    if (valueStart < 0) {
      return text;
    }
    // Sensitive values are user-controlled and may contain whitespace, separators, quotes, or
    // line breaks. Once a sensitive assignment starts, keeping any later text can retain a secret
    // fragment or quote-comma/newline injection. Preserve the useful command prefix and key, then
    // deliberately over-redact the entire remainder.
    return text.substring(0, valueStart) + REDACTED_VALUE + closingQuote;
  }

  static String buildDiagnosticCommand(List<String> commands, String fallbackCommand) {
    String commandLine =
        commands == null || commands.isEmpty()
            ? (fallbackCommand == null ? "" : fallbackCommand.trim())
            : buildCommandLine(commands);
    return redactSensitiveText(commandLine);
  }

  static Rectangle calculateUsableBounds(Rectangle screenBounds, Insets insets) {
    Rectangle bounds = screenBounds == null ? new Rectangle(0, 0, 1280, 800) : screenBounds;
    Insets safeInsets = insets == null ? new Insets(0, 0, 0, 0) : insets;
    return new Rectangle(
        bounds.x + safeInsets.left,
        bounds.y + safeInsets.top,
        Math.max(1, bounds.width - safeInsets.left - safeInsets.right),
        Math.max(1, bounds.height - safeInsets.top - safeInsets.bottom));
  }

  static Rectangle clampDialogBounds(Rectangle dialogBounds, Rectangle usableBounds) {
    Rectangle available = usableBounds == null ? new Rectangle(0, 0, 1280, 800) : usableBounds;
    Rectangle proposed =
        dialogBounds == null ? new Rectangle(available.x, available.y, 1, 1) : dialogBounds;
    int width = Math.min(Math.max(1, proposed.width), Math.max(1, available.width));
    int height = Math.min(Math.max(1, proposed.height), Math.max(1, available.height));
    int maximumX = available.x + available.width - width;
    int maximumY = available.y + available.height - height;
    int x = Math.max(available.x, Math.min(proposed.x, maximumX));
    int y = Math.max(available.y, Math.min(proposed.y, maximumY));
    return new Rectangle(x, y, width, height);
  }

  private static int estimateTextWidth(String text, int fontSize) {
    int longestLine = 0;
    for (String line : text.split("\\R", -1)) {
      longestLine = Math.max(longestLine, line.codePointCount(0, line.length()));
    }
    double averageGlyphWidth = Math.max(7.0, Math.max(1, fontSize) * 0.62);
    return 48 + (int) Math.ceil(Math.min(longestLine, 500) * averageGlyphWidth);
  }

  private Rectangle usableScreenBounds() {
    if (GraphicsEnvironment.isHeadless()) {
      return new Rectangle(0, 0, 1280, 800);
    }
    GraphicsConfiguration configuration =
        Lizzie.frame == null ? null : Lizzie.frame.getGraphicsConfiguration();
    if (configuration == null) {
      configuration = getGraphicsConfiguration();
    }
    if (configuration == null) {
      configuration =
          GraphicsEnvironment.getLocalGraphicsEnvironment()
              .getDefaultScreenDevice()
              .getDefaultConfiguration();
    }
    Rectangle bounds = configuration.getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
    return calculateUsableBounds(bounds, insets);
  }

  private static String buildCommandLine(List<String> commands) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < commands.size(); i++) {
      if (i > 0) {
        builder.append(' ');
      }
      builder.append(quoteForCmd(commands.get(i)));
    }
    return builder.toString();
  }

  private static String quoteForCmd(String token) {
    if (token == null) {
      return "\"\"";
    }
    String trimmed = token.trim();
    if (trimmed.isEmpty()) {
      return "\"\"";
    }
    if (trimmed.indexOf(' ') >= 0 || trimmed.indexOf('\t') >= 0 || trimmed.indexOf('"') >= 0) {
      return "\"" + trimmed.replace("\"", "\\\"") + "\"";
    }
    return trimmed;
  }
}
