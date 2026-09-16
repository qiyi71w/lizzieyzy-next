package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.search.FunctionCatalog;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Exercises production function-search input with real mouse and keyboard events. */
public final class FunctionSearchInputTest {
  private static final String TARGET = "settings.black-winrate";
  private static final String QUERY = "winrate";
  private static final String TARGET_ROW_PROPERTY = "lizzie.config.settingTargetId";
  private static final long UI_TIMEOUT_MILLIS = 5_000;
  private static final List<String> REQUIRED_RESULTS =
      List.of(
          "startup.locale",
          "toolbar.open-focus",
          "toolbar.query-arrows-enter",
          "toolbar.target-focus-unchanged",
          "shortcut.open-focus",
          "shortcut.query-arrows-enter",
          "shortcut.target-focus-unchanged",
          "cancel.focus-unchanged",
          "text.shortcut-owned",
          "modal.shortcut-refused",
          "board.unchanged");

  @Test
  void chineseInputChain() throws Exception {
    runLocale("zh_CN", "native", 1, "zh", "CN");
  }

  @Test
  void englishInputChain() throws Exception {
    runLocale("en_US", "custom", 2, "en", "US");
  }

  private static void runLocale(
      String locale, String presentation, int configValue, String language, String country)
      throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            FunctionSearchInputTest.class,
            "function-search-input-" + locale,
            List.of(
                "-Duser.language=" + language,
                "-Duser.country=" + country,
                "-D" + MenuPresentationMode.OVERRIDE_PROPERTY + "=" + presentation),
            List.of("probe", locale, presentation, Integer.toString(configValue)));
    Map<String, String> values = parseResult(result);
    assertEquals(locale, values.get("locale"), values.toString());
    assertEquals(presentation, values.get("presentation"), values.toString());
    assertEquals(TARGET, values.get("target"), values.toString());
    for (String key : REQUIRED_RESULTS) {
      assertEquals("true", values.get(key), "Missing or failed result " + key + ": " + values);
    }
    for (String path : List.of("toolbar", "shortcut")) {
      String settingBefore = required(values, path + ".setting.before");
      String settingAfter = required(values, path + ".setting.after");
      assertTrue(
          settingBefore.equals("true") || settingBefore.equals("false"),
          "Invalid setting evidence for " + path + ": " + settingBefore);
      assertEquals(settingBefore, settingAfter, "Setting changed during " + path);
      assertEquals(
          required(values, path + ".config.before"),
          required(values, path + ".config.after"),
          "Configuration changed during " + path);
      assertEquals(TARGET, required(values, path + ".selected.target"));
    }
    assertEquals("complete", values.get("phase"), values.toString());
  }

  /** Child-process entry point. It starts the production application in one requested locale. */
  public static void main(String[] args) throws Exception {
    if (args.length != 6 || !"probe".equals(args[0])) {
      throw new IllegalArgumentException(
          "expected probe, locale, presentation, language config, work directory, and result");
    }
    String localeName = args[1];
    String presentation = args[2];
    int configValue = Integer.parseInt(args[3]);
    Path work = Path.of(args[4]);
    Path resultPath = Path.of(args[5]);
    Evidence evidence = new Evidence(resultPath);
    int exitCode = 1;
    try {
      DesktopProbeProcess.phase(resultPath, "production-startup");
      runProbe(localeName, presentation, configValue, work, evidence);
      evidence.put("phase", "complete");
      DesktopProbeProcess.phase(resultPath, "input-chain-complete");
      exitCode = 0;
    } catch (Throwable failure) {
      evidence.put(
          "failure", failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
      failure.printStackTrace(System.err);
    } finally {
      closeProductionWindows();
      System.exit(exitCode);
    }
  }

  private static void runProbe(
      String localeName, String presentation, int configValue, Path work, Evidence evidence)
      throws Exception {
    Locale requestedLocale = requestedLocale(localeName);
    ResourceBundle expectedBundle =
        ResourceBundle.getBundle("l10n.DisplayStrings", requestedLocale);
    ResourceBundle englishBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    if (!requestedLocale.equals(expectedBundle.getLocale())) {
      throw new AssertionError(
          "requested bundle locale mismatch: "
              + requestedLocale
              + " != "
              + expectedBundle.getLocale());
    }

    Files.createDirectories(work);
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
            + "\"win-rate-always-black\":false,\"use-language\":"
            + configValue
            + "}}",
        StandardCharsets.UTF_8);
    System.setProperty("lizzie.work.dir", work.toAbsolutePath().toString());

    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), "real main window", 30_000);
    String title = expectedBundle.getString("FunctionSearch.title");
    runOnEdtAction(
        () -> {
          if (!presentation.equals(System.getProperty(MenuPresentationMode.ACTIVE_PROPERTY))) {
            throw new AssertionError(
                "requested menu presentation was not active: "
                    + System.getProperty(MenuPresentationMode.ACTIVE_PROPERTY));
          }
          if (Lizzie.config.useLanguage != configValue) {
            throw new AssertionError(
                "language config mismatch: " + Lizzie.config.useLanguage + " != " + configValue);
          }
          Locale effective = AppLocale.fromConfigValue(Lizzie.config.useLanguage).locale();
          if (!requestedLocale.equals(effective)) {
            throw new AssertionError(
                "effective locale mismatch: " + effective + " != " + requestedLocale);
          }
          if (!requestedLocale.equals(Lizzie.resourceBundle.getLocale())) {
            throw new AssertionError(
                "live bundle locale mismatch: "
                    + Lizzie.resourceBundle.getLocale()
                    + " != "
                    + requestedLocale);
          }
          if (!title.equals(Lizzie.resourceBundle.getString("FunctionSearch.title"))) {
            throw new AssertionError("live search title does not match independent locale oracle");
          }
          if (Locale.CHINA.equals(requestedLocale)
              && title.equals(englishBundle.getString("FunctionSearch.title"))) {
            throw new AssertionError("Chinese search title did not differ from English");
          }
        });
    AbstractButton toolbarEntry = findUniqueToolbarEntry(title);
    Robot robot = new Robot();
    robot.setAutoDelay(35);

    evidence.put("locale", localeName);
    evidence.put("presentation", presentation);
    evidence.put("target", TARGET);
    evidence.put("locale.requested", requestedLocale.toString());
    evidence.put("locale.effective", AppLocale.fromConfigValue(configValue).locale().toString());
    evidence.put("locale.live-bundle", Lizzie.resourceBundle.getLocale().toString());
    evidence.put("locale.observed-title", title);
    evidence.put("startup.locale", "true");

    focusMainPanel();
    Object boardNode = currentBoardNode();
    String boardConfig = currentConfig();

    DesktopProbeProcess.phase(evidence.path(), "toolbar-input");
    exerciseActivationPath("toolbar", robot, evidence, () -> click(robot, toolbarEntry));

    DesktopProbeProcess.phase(evidence.path(), "shortcut-input");
    exerciseActivationPath(
        "shortcut",
        robot,
        evidence,
        () -> {
          focusMainPanel();
          sendShortcut(robot);
        });

    DesktopProbeProcess.phase(evidence.path(), "input-ownership");
    exerciseTextOwnership(robot, evidence);
    exerciseModalOwnership(robot, evidence);
    DesktopProbeProcess.phase(evidence.path(), "cancellation");
    exerciseCancellation(robot, evidence);

    boolean boardUnchanged = boardNode == currentBoardNode() && boardConfig.equals(currentConfig());
    evidence.put("board.unchanged", Boolean.toString(boardUnchanged));
    if (!boardUnchanged) {
      throw new AssertionError("input chain changed the current board node or configuration");
    }
  }

  private static void exerciseActivationPath(
      String path, Robot robot, Evidence evidence, ThrowingRunnable openAction) throws Exception {
    focusMainPanel();
    Object beforeNode = currentBoardNode();
    boolean settingBefore = currentSetting();
    String configBefore = currentConfig();
    evidence.put(path + ".setting.before", Boolean.toString(settingBefore));
    evidence.put(path + ".config.before", configBefore);

    openAction.run();
    FunctionSearchDialog dialog = awaitSearch(path + " search");
    JTextField input = searchInput(dialog);
    await(input::isFocusOwner, path + " search input focus", UI_TIMEOUT_MILLIS);
    evidence.put(path + ".open-focus", "true");
    assertBoardAndConfigUnchanged(beforeNode, configBefore, path + " search opening");

    typeAndAwait(robot, input, QUERY, path + " query");
    JList<?> results = resultList(dialog);
    await(() -> targetIndex(results) >= 0, path + " target results", UI_TIMEOUT_MILLIS);
    List<String> resultIds = resultIds(results);
    int initialIndex = selectedIndex(results);
    if (initialIndex < 0 || initialIndex + 1 >= resultIds.size()) {
      throw new AssertionError(
          path + " search did not expose a selectable row after the initial selection");
    }
    press(robot, KeyEvent.VK_DOWN);
    await(
        () -> selectedIndex(results) == initialIndex + 1,
        path + " down selection",
        UI_TIMEOUT_MILLIS);
    press(robot, KeyEvent.VK_UP);
    await(() -> selectedIndex(results) == initialIndex, path + " up selection", UI_TIMEOUT_MILLIS);
    int targetIndex = targetIndex(results);
    int selectedIndex = selectedIndex(results);
    if (targetIndex < selectedIndex) {
      throw new AssertionError(
          "stable target precedes returned selection: " + targetIndex + " < " + selectedIndex);
    }
    for (int index = selectedIndex; index < targetIndex; index++) {
      press(robot, KeyEvent.VK_DOWN);
      int expectedIndex = index + 1;
      await(
          () -> selectedIndex(results) == expectedIndex,
          path + " target navigation step " + expectedIndex,
          UI_TIMEOUT_MILLIS);
    }
    await(
        () -> TARGET.equals(selectedTarget(results)),
        path + " target selection",
        UI_TIMEOUT_MILLIS);
    evidence.put(path + ".result.ids", String.join(",", resultIds));
    evidence.put(path + ".selected.target", selectedTarget(results));
    press(robot, KeyEvent.VK_ENTER);

    await(() -> !dialog.isShowing(), path + " search disposal", UI_TIMEOUT_MILLIS);
    ConfigDialog2 settings = awaitSettings(path + " settings target");
    JCheckBox control = blackWinrateControl(settings);
    JComponent row = targetRow(settings);
    await(
        () -> control.isShowing() && row.isShowing() && control.isFocusOwner(),
        path + " settings target visibility and focus",
        UI_TIMEOUT_MILLIS);
    boolean settingAfter = currentSetting();
    String configAfter = currentConfig();
    evidence.put(path + ".setting.after", Boolean.toString(settingAfter));
    evidence.put(path + ".config.after", configAfter);
    evidence.put(path + ".query-arrows-enter", "true");
    boolean unchanged =
        settingBefore == settingAfter
            && configBefore.equals(configAfter)
            && beforeNode == currentBoardNode()
            && control.isSelected() == settingBefore;
    evidence.put(path + ".target-focus-unchanged", Boolean.toString(unchanged));
    if (!unchanged) {
      throw new AssertionError(
          path + " target navigation changed settings, config, or board state");
    }
    runOnEdtAction(settings::dispose);
    await(() -> !settings.isShowing(), path + " settings cleanup", UI_TIMEOUT_MILLIS);
  }

  private static void exerciseCancellation(Robot robot, Evidence evidence) throws Exception {
    focusMainPanel();
    Object beforeNode = currentBoardNode();
    boolean settingBefore = currentSetting();
    String configBefore = currentConfig();
    sendShortcut(robot);
    FunctionSearchDialog dialog = awaitSearch("cancellation search");
    JTextField input = searchInput(dialog);
    await(input::isFocusOwner, "cancellation input focus", UI_TIMEOUT_MILLIS);
    press(robot, KeyEvent.VK_ESCAPE);
    await(() -> !dialog.isShowing(), "cancellation search disposal", UI_TIMEOUT_MILLIS);
    try {
      await(
          () -> Lizzie.frame.mainPanel.isFocusOwner(),
          "main panel focus restoration",
          UI_TIMEOUT_MILLIS);
    } catch (AssertionError failure) {
      evidence.put("cancel.focus.actual", focusDescription());
      throw failure;
    }
    boolean unchanged =
        beforeNode == currentBoardNode()
            && settingBefore == currentSetting()
            && configBefore.equals(currentConfig());
    evidence.put("cancel.focus-unchanged", Boolean.toString(unchanged));
    if (!unchanged) throw new AssertionError("Escape cancellation changed observable state");
  }

  private static void exerciseTextOwnership(Robot robot, Evidence evidence) throws Exception {
    JTextField text = LizzieFrame.toolbar.txtMoveNumber;
    String original = runOnEdt(text::getText);
    try {
      click(robot, text);
      await(text::isFocusOwner, "move-number text focus", UI_TIMEOUT_MILLIS);
      sendShortcut(robot);
      type(robot, "7");
      await(
          () -> runOnEdtUnchecked(text::getText).endsWith("7"),
          "move-number typed digit",
          UI_TIMEOUT_MILLIS);
      Thread.sleep(200);
      boolean owned =
          runOnEdt(() -> text.isFocusOwner() && findShowingSearchDialog() == null)
              && runOnEdt(text::getText).endsWith("7");
      evidence.put("text.shortcut-owned", Boolean.toString(owned));
      if (!owned) throw new AssertionError("search stole the shortcut from the text component");
    } finally {
      runOnEdtAction(() -> text.setText(original));
    }
  }

  private static void exerciseModalOwnership(Robot robot, Evidence evidence) throws Exception {
    CountDownLatch released = new CountDownLatch(1);
    java.awt.KeyEventDispatcher observer =
        event -> {
          if (event.getID() == KeyEvent.KEY_RELEASED && event.getKeyCode() == KeyEvent.VK_K) {
            released.countDown();
          }
          return false;
        };
    AtomicReference<JDialog> modalRef = new AtomicReference<>();
    AtomicReference<JButton> buttonRef = new AtomicReference<>();
    runOnEdtAction(
        () -> {
          JDialog modal =
              new JDialog(
                  Lizzie.frame,
                  "Function search modal boundary",
                  Dialog.ModalityType.APPLICATION_MODAL);
          JButton button = new JButton("Modal focus owner");
          modal.add(button);
          modal.pack();
          modal.setLocationRelativeTo(Lizzie.frame);
          modalRef.set(modal);
          buttonRef.set(button);
          KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(observer);
          SwingUtilities.invokeLater(() -> modal.setVisible(true));
        });
    JDialog modal = modalRef.get();
    JButton button = buttonRef.get();
    try {
      await(modal::isShowing, "fixture modal", UI_TIMEOUT_MILLIS);
      runOnEdtAction(button::requestFocusInWindow);
      await(button::isFocusOwner, "fixture modal focus", UI_TIMEOUT_MILLIS);
      sendShortcut(robot);
      if (!released.await(UI_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        throw new AssertionError("modal did not receive the shortcut key release");
      }
      Thread.sleep(200);
      boolean refused =
          runOnEdt(
              () ->
                  modal.isShowing() && button.isFocusOwner() && findShowingSearchDialog() == null);
      evidence.put("modal.shortcut-refused", Boolean.toString(refused));
      if (!refused) throw new AssertionError("search opened across an application-modal boundary");
    } finally {
      runOnEdtAction(
          () -> {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(observer);
            modal.dispose();
          });
      await(() -> !modal.isShowing(), "fixture modal cleanup", UI_TIMEOUT_MILLIS);
    }
  }

  private static AbstractButton findUniqueToolbarEntry(String title) throws Exception {
    List<AbstractButton> matches =
        runOnEdt(
            () -> {
              List<AbstractButton> found = new ArrayList<>();
              collectButtons(Lizzie.frame.topPanel.centerArea, title, found);
              return found;
            });
    if (matches.size() != 1) {
      throw new AssertionError(
          "expected one showing toolbar search entry, found " + matches.size());
    }
    return matches.get(0);
  }

  private static void collectButtons(Component root, String title, List<AbstractButton> matches) {
    if (root instanceof AbstractButton button
        && button.isShowing()
        && button.getAccessibleContext() != null
        && title.equals(button.getAccessibleContext().getAccessibleName())) {
      matches.add(button);
    }
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) collectButtons(child, title, matches);
    }
  }

  private static FunctionSearchDialog awaitSearch(String label) throws Exception {
    await(() -> findShowingSearchDialog() != null, label, UI_TIMEOUT_MILLIS);
    return runOnEdt(FunctionSearchInputTest::findShowingSearchDialog);
  }

  private static FunctionSearchDialog findShowingSearchDialog() {
    for (Window window : Window.getWindows()) {
      if (window instanceof FunctionSearchDialog dialog && dialog.isShowing()) return dialog;
    }
    return null;
  }

  private static ConfigDialog2 awaitSettings(String label) throws Exception {
    await(
        () -> Lizzie.frame.configDialog2 != null && Lizzie.frame.configDialog2.isShowing(),
        label,
        UI_TIMEOUT_MILLIS);
    return runOnEdt(() -> Lizzie.frame.configDialog2);
  }

  private static JTextField searchInput(FunctionSearchDialog dialog) throws Exception {
    String title = Lizzie.resourceBundle.getString("FunctionSearch.title");
    Component component = runOnEdt(() -> findAccessible(dialog, title, JTextField.class));
    if (!(component instanceof JTextField input)) {
      throw new AssertionError("search input is not attached");
    }
    return input;
  }

  private static JList<?> resultList(FunctionSearchDialog dialog) throws Exception {
    Component component = runOnEdt(() -> findType(dialog, JList.class));
    if (!(component instanceof JList<?> list)) {
      throw new AssertionError("search result list is not attached");
    }
    return list;
  }

  private static JCheckBox blackWinrateControl(ConfigDialog2 dialog) throws Exception {
    String title = Lizzie.resourceBundle.getString("Menu.alwaysShowBlackWinrate");
    Component component = runOnEdt(() -> findAccessible(dialog, title, JCheckBox.class));
    if (!(component instanceof JCheckBox control)) {
      throw new AssertionError("black-winrate control is not attached");
    }
    return control;
  }

  private static JComponent targetRow(ConfigDialog2 dialog) throws Exception {
    Component row = runOnEdt(() -> findTargetRow(dialog.getContentPane()));
    if (!(row instanceof JComponent target)) {
      throw new AssertionError("black-winrate target row is not attached");
    }
    return target;
  }

  private static Component findAccessible(Component root, String name, Class<?> type) {
    if (type.isInstance(root)
        && root.getAccessibleContext() != null
        && name.equals(root.getAccessibleContext().getAccessibleName())) return root;
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        Component found = findAccessible(child, name, type);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static Component findType(Component root, Class<?> type) {
    if (type.isInstance(root)) return root;
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        Component found = findType(child, type);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static Component findTargetRow(Component root) {
    if (root instanceof JComponent component
        && TARGET.equals(component.getClientProperty(TARGET_ROW_PROPERTY))) return component;
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        Component found = findTargetRow(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static int targetIndex(JList<?> results) {
    return runOnEdtUnchecked(
        () -> {
          for (int index = 0; index < results.getModel().getSize(); index++) {
            Object value = results.getModel().getElementAt(index);
            if (value instanceof FunctionCatalog.Entry entry && TARGET.equals(entry.id())) {
              return index;
            }
          }
          return -1;
        });
  }

  private static List<String> resultIds(JList<?> results) throws Exception {
    return runOnEdt(
        () -> {
          List<String> ids = new ArrayList<>();
          for (int index = 0; index < results.getModel().getSize(); index++) {
            Object value = results.getModel().getElementAt(index);
            if (!(value instanceof FunctionCatalog.Entry entry)) {
              throw new AssertionError("unexpected search result type: " + value);
            }
            ids.add(entry.id());
          }
          return ids;
        });
  }

  private static int selectedIndex(JList<?> results) {
    return runOnEdtUnchecked(results::getSelectedIndex);
  }

  private static String selectedTarget(JList<?> results) {
    return runOnEdtUnchecked(
        () -> {
          Object selected = results.getSelectedValue();
          return selected instanceof FunctionCatalog.Entry entry ? entry.id() : null;
        });
  }

  private static void focusMainPanel() throws Exception {
    runOnEdtAction(
        () -> {
          Lizzie.frame.toFront();
          Lizzie.frame.requestFocus();
        });
    await(Lizzie.frame::isFocused, "production main window focus", UI_TIMEOUT_MILLIS);
    try {
      // Native focus restoration after disposing a dialog may arrive after the first request.
      // This is test setup only; the behavior assertions below do not retry navigation or keys.
      await(
          () -> {
            if (!Lizzie.frame.mainPanel.isFocusOwner() && Lizzie.frame.isFocused()) {
              if (!Lizzie.frame.mainPanel.requestFocusInWindow()) {
                Lizzie.frame.mainPanel.requestFocus();
              }
            }
            return Lizzie.frame.mainPanel.isFocusOwner();
          },
          "production main-board focus",
          UI_TIMEOUT_MILLIS);
    } catch (AssertionError failure) {
      throw new AssertionError(failure.getMessage() + ": " + focusDescription(), failure);
    }
  }

  private static void click(Robot robot, Component component) throws Exception {
    Point center =
        runOnEdt(
            () -> {
              if (!component.isShowing()
                  || component.getWidth() <= 0
                  || component.getHeight() <= 0) {
                throw new AssertionError("component is not showing for Robot click");
              }
              Point location = component.getLocationOnScreen();
              return new Point(
                  location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
            });
    robot.mouseMove(center.x, center.y);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
  }

  private static void type(Robot robot, String text) {
    for (int offset = 0; offset < text.length(); offset++) {
      int key = KeyEvent.getExtendedKeyCodeForChar(text.charAt(offset));
      if (key == KeyEvent.VK_UNDEFINED) {
        throw new AssertionError("Robot cannot type character: " + text.charAt(offset));
      }
      press(robot, key);
    }
  }
  private static void typeAndAwait(
      Robot robot, JTextField input, String text, String label) throws Exception {
    for (int offset = 0; offset < text.length(); offset++) {
      String expected = text.substring(0, offset + 1);
      type(robot, text.substring(offset, offset + 1));
      await(
          () -> expected.equals(runOnEdtUnchecked(input::getText)),
          label + " character " + (offset + 1),
          UI_TIMEOUT_MILLIS);
    }
  }


  private static void press(Robot robot, int key) {
    robot.keyPress(key);
    robot.keyRelease(key);
  }

  private static void sendShortcut(Robot robot) {
    int modifiers = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    List<Integer> keys = modifierKeys(modifiers);
    for (int key : keys) robot.keyPress(key);
    press(robot, KeyEvent.VK_K);
    for (int index = keys.size() - 1; index >= 0; index--) robot.keyRelease(keys.get(index));
  }

  private static List<Integer> modifierKeys(int modifiers) {
    List<Integer> keys = new ArrayList<>();
    if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) keys.add(KeyEvent.VK_CONTROL);
    if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) keys.add(KeyEvent.VK_ALT);
    if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) keys.add(KeyEvent.VK_SHIFT);
    if ((modifiers & InputEvent.META_DOWN_MASK) != 0) keys.add(KeyEvent.VK_META);
    if (keys.isEmpty())
      throw new AssertionError("platform menu shortcut has no supported modifier");
    return keys;
  }

  private static void assertBoardAndConfigUnchanged(
      Object expectedNode, String expectedConfig, String phase) throws Exception {
    if (expectedNode != currentBoardNode() || !expectedConfig.equals(currentConfig())) {
      throw new AssertionError(phase + " changed board or configuration state");
    }
  }

  private static Object currentBoardNode() throws Exception {
    return runOnEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode());
  }

  private static boolean currentSetting() throws Exception {
    return runOnEdt(() -> Lizzie.config.winrateAlwaysBlack);
  }

  private static String currentConfig() throws Exception {
    return runOnEdt(() -> Lizzie.config.uiConfig.toString());
  }

  private static String focusDescription() throws Exception {
    return runOnEdt(
        () -> {
          KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
          Component owner = manager.getFocusOwner();
          Window window = manager.getFocusedWindow();
          return "owner="
              + (owner == null
                  ? "null"
                  : owner.getClass().getName()
                      + ",showing="
                      + owner.isShowing()
                      + ",focusable="
                      + owner.isFocusable())
              + ";window="
              + (window == null ? "null" : window.getClass().getName());
        });
  }

  private static Locale requestedLocale(String localeName) {
    return switch (localeName) {
      case "zh_CN" -> Locale.CHINA;
      case "en_US" -> Locale.US;
      default -> throw new IllegalArgumentException("unsupported probe locale: " + localeName);
    };
  }

  private static Map<String, String> parseResult(Path result) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : Files.readAllLines(result, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;
      int separator = line.indexOf('=');
      if (separator <= 0) throw new AssertionError("Malformed probe result line: " + line);
      String previous = values.put(line.substring(0, separator), line.substring(separator + 1));
      if (previous != null) throw new AssertionError("Duplicate probe result key: " + line);
    }
    return values;
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    assertNotNull(value, "Missing probe evidence " + key + ": " + values);
    return value;
  }

  private static <T> T runOnEdt(Callable<T> action) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) return action.call();
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            result.set(action.call());
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new AssertionError("EDT action failed", failure.get());
    return result.get();
  }

  private static <T> T runOnEdtUnchecked(Callable<T> action) {
    try {
      return runOnEdt(action);
    } catch (Exception error) {
      throw new AssertionError("EDT observation failed", error);
    }
  }

  private static void runOnEdtAction(ThrowingRunnable action) throws Exception {
    runOnEdt(
        () -> {
          action.run();
          return null;
        });
  }

  private static void await(BooleanSupplier condition, String label, long timeoutMillis)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (runOnEdt(condition::getAsBoolean)) return;
      Thread.sleep(40);
    }
    throw new AssertionError("Timed out waiting for " + label);
  }

  private static void closeProductionWindows() {
    try {
      runOnEdtAction(
          () -> {
            for (Window window : Window.getWindows()) {
              window.setVisible(false);
              window.dispose();
            }
          });
    } catch (Throwable ignored) {
      // Process termination remains the final cleanup boundary for a failed child probe.
    }
  }

  private static final class Evidence {
    private final Path path;
    private final Map<String, String> values = new LinkedHashMap<>();

    private Evidence(Path path) {
      this.path = path;
    }

    private Path path() {
      return path;
    }

    private synchronized void put(String key, String value) throws IOException {
      values.put(key, value == null ? "null" : value.replace('\n', ' ').replace('\r', ' '));
      StringBuilder text = new StringBuilder();
      values.forEach(
          (name, recorded) -> text.append(name).append('=').append(recorded).append('\n'));
      Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
