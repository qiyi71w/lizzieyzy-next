package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.search.FunctionCatalog;
import featurecat.lizzie.search.FunctionSearch;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises function-search navigation against a real production startup in an isolated JVM. */
public final class FunctionSearchNavigationTest {
  private static final String SETTING_TARGET = "menu.showMaxValueReverse";
  private static final String HIDDEN_TOP_TARGET = "toolbar.time-limit";
  private static final String MOVE_NUMBER_TARGET = "menu.showAllMoveNumberInBranch";
  private static final String MODAL_REASON = "FunctionSearch.unavailable.modal";
  private static final long CHILD_TIMEOUT_SECONDS = 90;
  private static final long CHILD_CLEANUP_TIMEOUT_SECONDS = 5;

  @TempDir Path tempDir;

  @Test
  void navigationPreservesRealStateAcrossNativeAndCustomMenus() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());

    for (String presentation : List.of("native", "custom")) {
      Path work = Files.createDirectories(tempDir.resolve("work-" + presentation));
      Path resultPath = tempDir.resolve("result-" + presentation + ".txt");
      Process child = null;
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Thread outputReader = null;
      try {
        String javaExecutable =
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        String classPath =
            System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path", ""));
        child =
            new ProcessBuilder(
                    javaExecutable,
                    "-D" + MenuPresentationMode.OVERRIDE_PROPERTY + "=" + presentation,
                    "-cp",
                    classPath,
                    FunctionSearchNavigationTest.class.getName(),
                    "probe",
                    presentation,
                    work.toAbsolutePath().toString(),
                    resultPath.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        Process childProcess = child;
        outputReader =
            new Thread(
                () -> {
                  try {
                    childProcess.getInputStream().transferTo(output);
                  } catch (IOException ignored) {
                    // The parent owns process cleanup; a forced child exit may close the stream.
                  }
                },
                "function-search-navigation-output-" + presentation);
        outputReader.setDaemon(true);
        outputReader.start();

        if (!child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          child.destroyForcibly();
          child.waitFor(CHILD_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          fail("isolated " + presentation + " navigation probe timed out");
        }
        outputReader.join(TimeUnit.SECONDS.toMillis(CHILD_CLEANUP_TIMEOUT_SECONDS));
        String childOutput = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), childOutput);
      } finally {
        if (child != null && child.isAlive()) {
          child.destroyForcibly();
          child.waitFor(CHILD_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (outputReader != null) {
          outputReader.join(TimeUnit.SECONDS.toMillis(CHILD_CLEANUP_TIMEOUT_SECONDS));
        }
      }
    }
  }

  /** Child-process entry point. It starts the real application rather than a fixture frame. */
  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !"probe".equals(args[0])) {
      throw new IllegalArgumentException(
          "expected probe, presentation, work directory, and result");
    }
    String presentation = args[1];
    Path work = Path.of(args[2]);
    Path resultPath = Path.of(args[3]);
    int exitCode = 1;
    try {
      runProbe(presentation, work, resultPath);
      exitCode = 0;
    } catch (Throwable failure) {
      Files.writeString(
          resultPath,
          "failure=" + failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()),
          StandardCharsets.UTF_8);
      failure.printStackTrace(System.err);
    } finally {
      closeProductionWindows();
      System.exit(exitCode);
    }
  }

  private static void runProbe(String presentation, Path work, Path resultPath) throws Exception {
    Files.createDirectories(work);
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
            + "\"win-rate-always-black\":false}}",
        StandardCharsets.UTF_8);
    System.setProperty("lizzie.work.dir", work.toAbsolutePath().toString());

    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), "real main window", 30_000);
    runOnEdtAction(
        () -> {
          if (Lizzie.frame == null || !Lizzie.frame.isDisplayable()) {
            throw new AssertionError("production startup did not create a displayable frame");
          }
          if (!presentation.equals(System.getProperty(MenuPresentationMode.ACTIVE_PROPERTY))) {
            throw new AssertionError(
                "requested menu presentation was not active: "
                    + System.getProperty(MenuPresentationMode.ACTIVE_PROPERTY));
          }
        });
    closeVisibleDialogs();
    FunctionSearchController controller = new FunctionSearchController(Lizzie.frame);

    StringBuilder evidence = new StringBuilder();
    evidence.append("presentation=").append(presentation).append('\n');
    checkSettingNavigation(controller, evidence);
    checkHiddenTopNavigation(controller, evidence);
    checkBusyOwnerStateGates(controller, evidence);
    checkNormalizedWhitespaceBrowse(controller, evidence);
    checkDynamicActionRefresh(controller, evidence);
    checkActionPaintingIsReadOnly(controller, evidence);
    checkAvailabilityIsReadOnly(controller, evidence);
    checkOwnerStateGate(controller, evidence);
    checkActivationTimeModal(controller, evidence);
    checkMainBoardContext(controller, evidence);
    checkIndependentBoardContext(controller, evidence);
    checkDestructiveCancellation(controller, evidence);
    checkKomiNavigation(controller, evidence);
    Files.writeString(resultPath, evidence.toString(), StandardCharsets.UTF_8);
  }

  private static void checkSettingNavigation(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    boolean expected =
        runOnEdt(
            () -> {
              Lizzie.config.showSuggestionMaxRed = false;
              LizzieFrame.menu.refreshFunctionPath(SETTING_TARGET);
              Lizzie.config.showSuggestionMaxRed = true;
              return true;
            });
    String expectedConfig = runOnEdt(() -> Lizzie.config.uiConfig.toString());
    String reason = runOnEdt(() -> controller.activate(SETTING_TARGET));
    List<JMenuItem> path = runOnEdt(() -> LizzieFrame.menu.functionPath(SETTING_TARGET));
    if (path == null
        || path.isEmpty()
        || !(path.get(path.size() - 1) instanceof JCheckBoxMenuItem)) {
      throw new AssertionError("setting target did not resolve to a checkbox menu item");
    }
    JCheckBoxMenuItem control = (JCheckBoxMenuItem) path.get(path.size() - 1);
    await(control::isShowing, "setting menu target visibility", 4_000);
    boolean selected = runOnEdt(control::isSelected);
    boolean visible = runOnEdt(control::isShowing);
    boolean unchanged =
        runOnEdt(
            () ->
                Lizzie.config.showSuggestionMaxRed == expected
                    && expectedConfig.equals(Lizzie.config.uiConfig.toString()));
    evidence
        .append("setting.reason=")
        .append(reason)
        .append('\n')
        .append("setting.visible=")
        .append(visible)
        .append('\n')
        .append("setting.selected=")
        .append(selected)
        .append('\n')
        .append("setting.unchanged=")
        .append(unchanged)
        .append('\n');
    if (reason != null || !visible || selected != expected || !unchanged) {
      throw new AssertionError(
          "setting navigation changed or failed: reason="
              + reason
              + ", visible="
              + visible
              + ", selected="
              + selected
              + ", expected="
              + expected
              + ", unchanged="
              + unchanged);
    }
  }

  private static void checkKomiNavigation(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    closeVisibleDialogs();
    double beforeKomi = runOnEdt(() -> Lizzie.board.getHistory().getGameInfo().getKomi());
    String beforeConfig = runOnEdt(() -> Lizzie.config.uiConfig.toString());
    SwingUtilities.invokeLater(() -> controller.activate("game.komi"));
    JDialog shown = awaitDialog(GameInfoDialog.class, "komi field navigation", 4_000);
    if (!(shown instanceof GameInfoDialog)) {
      throw new AssertionError("game.komi opened " + shown.getClass().getName());
    }
    String komiName = Lizzie.resourceBundle.getString("GameInfoDialog.komi");
    Component target = runOnEdt(() -> findAccessibleTextField(shown, komiName));
    if (!(target instanceof JTextField field)) {
      throw new AssertionError("game.komi did not expose the komi text field");
    }
    await(field::isFocusOwner, "komi field focus", 4_000);
    boolean unchanged =
        runOnEdt(
            () ->
                Double.parseDouble(field.getText()) == beforeKomi
                    && Lizzie.board.getHistory().getGameInfo().getKomi() == beforeKomi
                    && beforeConfig.equals(Lizzie.config.uiConfig.toString()));
    evidence
        .append("komi.focus=")
        .append(runOnEdt(field::isFocusOwner))
        .append('\n')
        .append("komi.unchanged=")
        .append(unchanged)
        .append('\n');
    if (!unchanged) throw new AssertionError("komi navigation changed game or configuration state");
    runOnEdtAction(shown::dispose);
    await(() -> !shown.isShowing(), "komi dialog cleanup", 4_000);
  }

  private static Component findAccessibleTextField(Component root, String name) {
    if (root instanceof JTextField
        && root.getAccessibleContext() != null
        && name.equals(root.getAccessibleContext().getAccessibleName())) return root;
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        Component match = findAccessibleTextField(child, name);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static void checkHiddenTopNavigation(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    clearMenuSelection();
    runOnEdtAction(
        () -> {
          Lizzie.config.showTopToolBar = false;
          Lizzie.frame.reSetLoc();
        });
    flushEdt();
    String reason = runOnEdt(() -> controller.activate(HIDDEN_TOP_TARGET));
    Thread.sleep(300);
    flushEdt();
    JComponent target = runOnEdt(() -> LizzieFrame.menu.topFunctionTarget(HIDDEN_TOP_TARGET));
    boolean showing = runOnEdt(target::isShowing);
    evidence
        .append("hidden-top.reason=")
        .append(reason)
        .append('\n')
        .append("hidden-top.showing=")
        .append(showing)
        .append('\n');
    if (reason != null || !showing) {
      throw new AssertionError(
          "hidden top toolbar target was not presented: reason=" + reason + ", showing=" + showing);
    }
  }

  private static void checkOwnerStateGate(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    int previous = runOnEdt(() -> Lizzie.config.allowMoveNumber);
    try {
      runOnEdtAction(() -> Lizzie.config.allowMoveNumber = -1);
      List<JMenuItem> path =
          runOnEdt(() -> LizzieFrame.menu.refreshFunctionPath(MOVE_NUMBER_TARGET));
      if (path == null || path.isEmpty()) {
        throw new AssertionError("move-number target did not resolve");
      }
      boolean enabled = runOnEdt(() -> path.get(path.size() - 1).isEnabled());
      String reason = runOnEdt(() -> controller.unavailableReason(MOVE_NUMBER_TARGET));
      evidence
          .append("state-gate.enabled=")
          .append(enabled)
          .append('\n')
          .append("state-gate.reason=")
          .append(reason)
          .append('\n');
      if (enabled || reason == null) {
        throw new AssertionError(
            "disabled owner state was not reported: enabled=" + enabled + ", reason=" + reason);
      }
      assertEquals(reason, runOnEdt(() -> controller.activate(MOVE_NUMBER_TARGET)));
      runOnEdtAction(() -> Lizzie.config.allowMoveNumber = 5);
      assertEquals(null, runOnEdt(() -> controller.activate(MOVE_NUMBER_TARGET)));
      assertTrue(runOnEdt(() -> path.get(path.size() - 1).isEnabled()));
      assertTrue(runOnEdt(() -> path.get(path.size() - 1).isShowing()));
      clearMenuSelection();
    } finally {
      runOnEdtAction(() -> Lizzie.config.allowMoveNumber = previous);
    }
  }

  private static void checkNormalizedWhitespaceBrowse(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    FunctionSearchDialog dialog =
        runOnEdt(
            () ->
                new FunctionSearchDialog(
                    Lizzie.frame, Lizzie.frame, new FunctionSearch(), controller));
    try {
      JTextField input =
          (JTextField)
              findAccessibleTextField(
                  dialog, Lizzie.resourceBundle.getString("FunctionSearch.title"));
      JList<?> results = findResultList(dialog);
      JButton browse = findButton(dialog, Lizzie.resourceBundle.getString("FunctionSearch.browse"));
      if (input == null || results == null || browse == null) {
        throw new AssertionError("function search controls were not constructed");
      }
      for (String query : List.of("\u00a0", "\u0085")) {
        runOnEdtAction(() -> input.setText(query));
        flushEdt();
        runOnEdtAction(
            () -> {
              if (browse.isVisible()) browse.doClick(0);
            });
        int visibleRows = runOnEdt(() -> results.getModel().getSize());
        int expectedRows = FunctionCatalog.entries().size();
        evidence
            .append("normalized-empty.U+")
            .append(String.format("%04X", query.codePointAt(0)))
            .append(".rows=")
            .append(visibleRows)
            .append('/')
            .append(expectedRows)
            .append('\n');
        if (visibleRows != expectedRows) {
          throw new AssertionError(
              "normalized-empty query was capped: U+"
                  + String.format("%04X", query.codePointAt(0))
                  + " rows="
                  + visibleRows
                  + "/"
                  + expectedRows);
        }
      }
    } finally {
      runOnEdtAction(dialog::dispose);
    }
  }

  private static void checkBusyOwnerStateGates(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    List<JMenuItem> webBoardPath =
        runOnEdt(() -> LizzieFrame.menu.refreshFunctionPath("menu.webBoardToggle"));
    if (webBoardPath == null || webBoardPath.isEmpty()) {
      throw new AssertionError("Web Board toggle did not resolve");
    }
    JMenuItem webBoardToggle = webBoardPath.get(webBoardPath.size() - 1);
    boolean previousEnabled = runOnEdt(webBoardToggle::isEnabled);
    try {
      runOnEdtAction(() -> webBoardToggle.setEnabled(false));
      String busyReason = runOnEdt(() -> controller.unavailableReason("menu.webBoardToggle"));
      evidence.append("web-board.busy.reason=").append(busyReason).append('\n');
      if (busyReason == null
          || !busyReason.equals(runOnEdt(() -> controller.activate("menu.webBoardToggle")))) {
        throw new AssertionError("Web Board busy owner gate was not projected");
      }
    } finally {
      runOnEdtAction(() -> webBoardToggle.setEnabled(previousEnabled));
    }

    boolean previousHumanGame = runOnEdt(() -> Lizzie.frame.isPlayingAgainstLeelaz);
    try {
      runOnEdtAction(() -> Lizzie.frame.isPlayingAgainstLeelaz = true);
      String coachReason = runOnEdt(() -> controller.unavailableReason("game.ai-coach"));
      evidence.append("ai-coach.game.reason=").append(coachReason).append('\n');
      if (coachReason == null
          || !coachReason.equals(runOnEdt(() -> controller.activate("game.ai-coach")))) {
        throw new AssertionError("AI Coach game-state owner gate was not projected");
      }
    } finally {
      runOnEdtAction(() -> Lizzie.frame.isPlayingAgainstLeelaz = previousHumanGame);
    }
  }

  private static void checkDynamicActionRefresh(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.current()
            .orElseThrow(() -> new AssertionError("logging runtime was not initialized"));
    List<JMenuItem> path =
        runOnEdt(() -> LizzieFrame.menu.refreshFunctionPath("menu.stopFullTrace"));
    if (path == null || path.isEmpty()) {
      throw new AssertionError("Stop Full Logs action did not resolve");
    }
    JMenuItem stop = path.get(path.size() - 1);
    try {
      runtime.stopFullTrace();
      runOnEdtAction(() -> LizzieFrame.menu.refreshFunctionPath("menu.stopFullTrace"));
      if (runOnEdt(stop::isEnabled)) {
        throw new AssertionError("Stop Full Logs action stayed enabled while trace was inactive");
      }
      runtime.startFullTrace(java.util.Set.of());
      if (!runtime.fullTraceActive()) {
        throw new AssertionError("Full Logs did not start for dynamic action check");
      }
      String reason = runOnEdt(() -> controller.unavailableReason("menu.stopFullTrace"));
      evidence.append("stop-full-trace.refreshed.reason=").append(reason).append('\n');
      if (reason != null) {
        throw new AssertionError("live Stop Full Logs action remained unavailable: " + reason);
      }
      assertEquals(null, runOnEdt(() -> controller.activate("menu.stopFullTrace")));
      if (runtime.fullTraceActive()) {
        throw new AssertionError("Stop Full Logs action did not stop the live trace");
      }
    } finally {
      runtime.stopFullTrace();
    }
  }

  private static void checkActionPaintingIsReadOnly(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    LoggingRuntime runtime = LoggingRuntime.current().orElseThrow();
    runtime.stopFullTrace();
    SwingUtilities.invokeLater(controller::open);
    JDialog dialog = awaitDialog(FunctionSearchDialog.class, "action search", 4_000);
    try {
      JTextField input =
          (JTextField)
              findAccessibleTextField(
                  dialog, Lizzie.resourceBundle.getString("FunctionSearch.title"));
      JList<?> results = findResultList(dialog);
      JButton activate =
          findButton(dialog, Lizzie.resourceBundle.getString("FunctionSearch.activate"));
      runOnEdtAction(
          () -> {
            input.setText(
                Lizzie.resourceBundle.getString(
                    FunctionCatalog.entry("menu.stopFullTrace").titleKey()));
            for (int i = 0; i < results.getModel().getSize(); i++) {
              if (((FunctionCatalog.Entry) results.getModel().getElementAt(i))
                  .id()
                  .equals("menu.stopFullTrace")) {
                results.setSelectedIndex(i);
                results.ensureIndexIsVisible(i);
                return;
              }
            }
            throw new AssertionError("Stop Full Logs result missing");
          });
      assertTrue(runOnEdt(() -> !activate.isEnabled()), "inactive trace must disable action");
      runtime.startFullTrace(java.util.Set.of());
      await(activate::isEnabled, "live trace action availability", 4_000);
      runOnEdtAction(
          () -> {
            List<JMenuItem> path = LizzieFrame.menu.functionPath("menu.stopFullTrace");
            javax.swing.JMenu menu = (javax.swing.JMenu) path.get(0);
            java.util.concurrent.atomic.AtomicInteger refreshes =
                new java.util.concurrent.atomic.AtomicInteger();
            javax.swing.event.MenuListener listener =
                new javax.swing.event.MenuListener() {
                  public void menuSelected(javax.swing.event.MenuEvent event) {
                    refreshes.incrementAndGet();
                  }

                  public void menuDeselected(javax.swing.event.MenuEvent event) {}

                  public void menuCanceled(javax.swing.event.MenuEvent event) {}
                };
            menu.addMenuListener(listener);
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(
                    results.getWidth(),
                    results.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            try {
              results.paint(graphics);
              assertEquals(
                  0, refreshes.get(), "painting search results must not refresh business menus");
            } finally {
              graphics.dispose();
              menu.removeMenuListener(listener);
            }
          });
      runtime.stopFullTrace();
      await(() -> !activate.isEnabled(), "stopped trace action availability", 4_000);
      evidence.append("action-paint.read-only=true\n");
      evidence.append("action-availability.live-transitions=true\n");
    } finally {
      runOnEdtAction(dialog::dispose);
      runtime.stopFullTrace();
    }
  }

  private static void checkAvailabilityIsReadOnly(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    String key = "txt-move-rank-mark-last-move";
    boolean hadValue = runOnEdt(() -> Lizzie.config.uiConfig.has(key));
    Object previous = runOnEdt(() -> Lizzie.config.uiConfig.opt(key));
    try {
      runOnEdtAction(() -> Lizzie.config.uiConfig.remove(key));
      String reason = runOnEdt(() -> controller.unavailableReason("menu.moveRankMenu"));
      boolean mutated = runOnEdt(() -> Lizzie.config.uiConfig.has(key));
      evidence
          .append("setting-availability.reason=")
          .append(reason)
          .append('\n')
          .append("setting-availability.mutated=")
          .append(mutated)
          .append('\n');
      if (mutated) {
        throw new AssertionError("setting availability mutated UI configuration");
      }
    } finally {
      runOnEdtAction(
          () -> {
            if (hadValue) Lizzie.config.uiConfig.put(key, previous);
            else Lizzie.config.uiConfig.remove(key);
          });
    }
  }

  private static void checkActivationTimeModal(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    closeVisibleDialogs();
    JDialog blocker =
        runOnEdt(
            () -> {
              JDialog dialog = new JDialog(Lizzie.frame, "Navigation blocker", true);
              dialog.setSize(220, 100);
              dialog.setLocationRelativeTo(Lizzie.frame);
              return dialog;
            });
    SwingUtilities.invokeLater(() -> blocker.setVisible(true));
    await(blocker::isShowing, "activation-time modal blocker", 4_000);
    String reason = runOnEdt(() -> controller.activate("files.open"));
    evidence.append("modal.reason=").append(reason).append('\n');
    if (!MODAL_REASON.equals(reason)) {
      throw new AssertionError("activation did not recheck a newly shown modal: " + reason);
    }
    runOnEdtAction(blocker::dispose);
    await(() -> !blocker.isShowing(), "modal blocker cleanup", 4_000);
  }

  private static void checkMainBoardContext(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    closeVisibleDialogs();
    Object beforeNode = runOnEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode());
    String beforeConfig = runOnEdt(() -> Lizzie.config.uiConfig.toString());
    runOnEdtAction(() -> Lizzie.frame.RightClickMenu.setCoords(new int[] {4, 4}));
    SwingUtilities.invokeLater(() -> controller.activate("board.insert-black", Lizzie.frame));
    JDialog prompt = awaitOptionPaneDialog(Lizzie.frame, "main board context prompt", 4_000);
    boolean correctOwner = runOnEdt(() -> prompt.getOwner() == Lizzie.frame);
    dismissMessage(prompt);
    await(() -> !prompt.isShowing(), "main board context prompt cleanup", 4_000);
    boolean sameBoard =
        beforeNode == runOnEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode())
            && beforeConfig.equals(runOnEdt(() -> Lizzie.config.uiConfig.toString()));
    evidence
        .append("main-context.owner=")
        .append(correctOwner)
        .append('\n')
        .append("main-context.same-board=")
        .append(sameBoard)
        .append('\n');
    if (!correctOwner || !sameBoard) {
      throw new AssertionError("main board context navigation changed state or used another owner");
    }
  }

  private static void checkIndependentBoardContext(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    closeVisibleDialogs();
    runOnEdtAction(Lizzie.frame::openIndependentMainBoard);
    await(
        () ->
            Lizzie.frame.independentMainBoard != null
                && Lizzie.frame.independentMainBoard.isShowing(),
        "independent board",
        4_000);
    runOnEdtAction(() -> Lizzie.board.place(3, 3));
    Object beforeNode = runOnEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode());
    String beforeConfig = runOnEdt(() -> Lizzie.config.uiConfig.toString());
    runOnEdtAction(() -> Lizzie.frame.RightClickMenu2.setCoords(new int[] {3, 3}));
    Window independent = runOnEdt(() -> Lizzie.frame.independentMainBoard);
    SwingUtilities.invokeLater(
        () -> controller.activate("board.delete-stone", Lizzie.frame.independentMainBoard));
    JDialog prompt = awaitOptionPaneDialog(independent, "independent board context prompt", 4_000);
    boolean correctOwner = runOnEdt(() -> prompt.getOwner() == independent);
    dismissMessage(prompt);
    await(() -> !prompt.isShowing(), "independent context prompt cleanup", 4_000);
    boolean sameBoard =
        beforeNode == runOnEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode())
            && beforeConfig.equals(runOnEdt(() -> Lizzie.config.uiConfig.toString()));
    evidence
        .append("independent-context.owner=")
        .append(correctOwner)
        .append('\n')
        .append("independent-context.same-board=")
        .append(sameBoard)
        .append('\n');
    if (!correctOwner || !sameBoard) {
      throw new AssertionError(
          "independent board context navigation changed state or used another owner");
    }
    runOnEdtAction(independent::dispose);
    await(() -> !independent.isShowing(), "independent board cleanup", 4_000);
  }

  private static void checkDestructiveCancellation(
      FunctionSearchController controller, StringBuilder evidence) throws Exception {
    closeVisibleDialogs();
    Object beforeNode = runOnEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode());
    String beforeConfig = runOnEdt(() -> Lizzie.config.uiConfig.toString());
    assertTrue(runOnEdt(() -> Lizzie.board.hasRealMoveOrPassHistory()));
    LizzieFrame productionFrame = Lizzie.frame;
    TrackingFrame cancellationFrame = allocateWithoutConstructor(TrackingFrame.class);
    String reason =
        runOnEdt(
            () -> {
              Lizzie.frame = cancellationFrame;
              try {
                return controller.activate("menu.convertCurrentPosition");
              } finally {
                Lizzie.frame = productionFrame;
              }
            });
    boolean cancelled =
        reason == null
            && cancellationFrame.startingPositionConversionConfirmations == 1
            && beforeNode == runOnEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode())
            && beforeConfig.equals(runOnEdt(() -> Lizzie.config.uiConfig.toString()));
    evidence
        .append("conversion.confirmations=")
        .append(cancellationFrame.startingPositionConversionConfirmations)
        .append('\n')
        .append("conversion.cancelled=")
        .append(cancelled)
        .append('\n');
    if (!cancelled) {
      throw new AssertionError("cancelled destructive navigation changed game or configuration");
    }
  }

  private static JDialog awaitOptionPaneDialog(Window owner, String label, long timeoutMillis)
      throws Exception {
    await(() -> findShowingOptionPaneDialog(owner) != null, label, timeoutMillis);
    return runOnEdt(() -> findShowingOptionPaneDialog(owner));
  }

  private static JDialog findShowingOptionPaneDialog(Window owner) {
    for (Window window : Window.getWindows()) {
      if (window instanceof JDialog dialog
          && dialog.isShowing()
          && dialog.getOwner() == owner
          && optionPane(dialog) != null) return dialog;
    }
    return null;
  }

  private static JDialog awaitDialog(
      Class<? extends JDialog> type, String label, long timeoutMillis) throws Exception {
    await(() -> findShowingDialog(type) != null, label, timeoutMillis);
    return runOnEdt(() -> findShowingDialog(type));
  }

  private static JDialog findShowingDialog(Class<? extends JDialog> type) {
    for (Window window : Window.getWindows()) {
      if (type.isInstance(window) && window.isShowing()) return (JDialog) window;
    }
    return null;
  }

  private static JOptionPane optionPane(Container root) {
    if (root instanceof JOptionPane pane) return pane;
    for (java.awt.Component child : root.getComponents()) {
      if (child instanceof JOptionPane pane) return pane;
      if (child instanceof Container container) {
        JOptionPane nested = optionPane(container);
        if (nested != null) return nested;
      }
    }
    return null;
  }

  private static void dismissMessage(JDialog dialog) throws Exception {
    JOptionPane pane = runOnEdt(() -> optionPane(dialog));
    if (pane == null) throw new AssertionError("message prompt did not contain a JOptionPane");
    runOnEdtAction(() -> pane.setValue(JOptionPane.OK_OPTION));
  }

  private static void closeVisibleDialogs() throws Exception {
    runOnEdtAction(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog && dialog.isShowing()) dialog.dispose();
          }
        });
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

  private static JButton findButton(Component root, String text) {
    if (root instanceof JButton button && text.equals(button.getText())) return button;
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        JButton match = findButton(child, text);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static JList<?> findResultList(Component root) {
    if (root instanceof JList<?> list) return list;
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        JList<?> match = findResultList(child);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static <T> T runOnEdt(Callable<T> action) throws Exception {
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

  private static void runOnEdtAction(ThrowingRunnable action) throws Exception {
    runOnEdt(
        () -> {
          action.run();
          return null;
        });
  }

  private static void flushEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static void clearMenuSelection() throws Exception {
    runOnEdtAction(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
  }

  private static void await(BooleanSupplier condition, String label, long timeoutMillis)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (runOnEdt(condition::getAsBoolean)) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + label);
  }

  private static <T> T allocateWithoutConstructor(Class<T> type) throws InstantiationException {
    return type.cast(UnsafeHolder.UNSAFE.allocateInstance(type));
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int startingPositionConversionConfirmations;

    @Override
    protected boolean confirmStartingPositionConversion() {
      startingPositionConversionConfirmations++;
      return false;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
