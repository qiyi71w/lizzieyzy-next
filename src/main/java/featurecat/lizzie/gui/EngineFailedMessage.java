package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
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

public class EngineFailedMessage extends JDialog {
  static final int MAX_DIALOG_WIDTH = 980;
  static final int SCREEN_MARGIN = 64;
  static final String REDACTED_VALUE = "<redacted>";
  private static final String SENSITIVE_KEY =
      "(?:password|passwd|token|api[-_]?key|secret)";
  private static final Pattern QUOTED_SENSITIVE_ASSIGNMENT_START =
      Pattern.compile(
          "(?i)([\\\"'])(\\s*" + SENSITIVE_KEY + "\\b\\s*[:=]\\s*)");
  private static final Pattern SENSITIVE_ASSIGNMENT_START =
      Pattern.compile(
          "(?i)\\b" + SENSITIVE_KEY + "\\b[\\\"']?\\s*[:=]\\s*");
  private static final Pattern SENSITIVE_FLAG_START =
      Pattern.compile(
          "(?i)(?<!\\S)(?:-{1,2}|/)"
              + SENSITIVE_KEY
              + "\\b(?:\\s*[:=]\\s*|\\s+)");

  private final TensorRtRepairContext repairContext;
  private final JButton tensorRtRepairButton;

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
    root.add(commandPanel, BorderLayout.CENTER);

    JPanel footer = new JPanel(new BorderLayout(8, 0));

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
      footer.add(btnRestart, BorderLayout.EAST);
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
      footer.add(diagnosticActions, BorderLayout.CENTER);
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
              setVisible(false);
              if (Lizzie.frame != null) {
                Lizzie.frame.openKataGoAutoSetup(repairContext);
              }
            }
          });
      footer.add(repairButton, restartContribute ? BorderLayout.WEST : BorderLayout.EAST);
    }
    this.repairContext = repairContext;
    this.tensorRtRepairButton = repairButton;

    root.add(footer, BorderLayout.SOUTH);
    setContentPane(root);
    int minimumWidth =
        Lizzie.config.isFrameFontSmall()
            ? 580
            : (Lizzie.config.isFrameFontMiddle() ? 660 : 730);
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
        area,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
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
    Rectangle available =
        usableBounds == null ? new Rectangle(0, 0, 1280, 800) : usableBounds;
    Rectangle proposed =
        dialogBounds == null
            ? new Rectangle(available.x, available.y, 1, 1)
            : dialogBounds;
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
