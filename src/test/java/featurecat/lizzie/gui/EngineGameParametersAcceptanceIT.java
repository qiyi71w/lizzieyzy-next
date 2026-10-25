package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameOpeningParameters;
import featurecat.lizzie.enginegame.EngineGameOpeningPlan;
import featurecat.lizzie.enginegame.EngineGameOutputChoices;
import featurecat.lizzie.enginegame.EngineGameParameterState;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameResignPolicy;
import featurecat.lizzie.enginegame.EngineGameSideLimits;
import featurecat.lizzie.enginegame.EngineGameTimeMode;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.StartFailure;
import featurecat.lizzie.enginegame.StartObserver;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in real dual-KataGo acceptance, using an isolated app and the production parameter panel. */
public final class EngineGameParametersAcceptanceIT {
  private static final Logger LOG = LoggerFactory.getLogger(EngineGameParametersAcceptanceIT.class);
  private static final String WRN = "analysisWideRootNoise";
  private static final String PDA = "playoutDoublingAdvantage";

  @Test
  void realParticipantsApplyPanelChangesPreservePauseAndRestoreAfterStop() throws Exception {
    run("stop");
  }

  @Test
  void realBatchResetsOpeningParametersAcrossColorSwapAndRestoresAfterCompletion()
      throws Exception {
    run("batch");
  }

  private void run(String mode) throws Exception {
    assumeTrue(Boolean.getBoolean("lizzie.acceptance.engineGameParameters"));
    DesktopProbeProcess.requireDisplay();
    String engine = System.getProperty("lizzie.acceptance.engine", "");
    String model = System.getProperty("lizzie.acceptance.model", "");
    assertTrue(Files.isRegularFile(Path.of(engine)), "Real KataGo executable is required");
    assertTrue(Files.isRegularFile(Path.of(model)), "Real model is required");
    Path result =
        DesktopProbeProcess.run(
            getClass(),
            "engine-game-parameters-" + mode,
            List.of(),
            List.of(mode, engine, model),
            240);
    assertTrue(Files.readString(result).contains("result=PASS"));
    assertTrue(Files.readString(result).contains("clock-helper-closed=PASS"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[3]);
    Path result = Path.of(args[4]);
    int exit = 1;
    try {
      probe(args[0], Path.of(args[1]), Path.of(args[2]), work, result);
      exit = 0;
    } catch (Throwable failure) {
      LOG.error("Engine-game parameter acceptance failed", failure);
      Files.writeString(
          result,
          "result=FAIL\nerror=" + failure + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } finally {
      try {
        shutdownClockHelper();
        record(result, "clock-helper-closed=PASS");
      } catch (Throwable failure) {
        exit = 1;
        LOG.error("Engine-game acceptance cleanup failed", failure);
        try {
          record(result, "result=FAIL\ncleanup=clock-helper");
        } catch (Throwable recordingFailure) {
          LOG.error("Unable to record acceptance cleanup failure", recordingFailure);
        }
      } finally {
        // DesktopProbeProcess owns and reaps this JVM and both engine descendants, even on failure.
        System.exit(exit);
      }
    }
  }

  private static void probe(String mode, Path engine, Path model, Path work, Path result)
      throws Exception {
    boolean batch = mode.equals("batch");
    JSONArray entries = new JSONArray();
    double[] originalWrn = {0.05, 0.15};
    double[] originalPda = {-0.25, 0.75};
    for (int index = 0; index < 2; index++) {
      Path config = work.resolve("engine-" + index + ".cfg");
      Path log = work.resolve("gtp-" + index);
      Files.createDirectories(log);
      Files.writeString(
          config,
          "rules = chinese\nkomi = 7.5\nnumSearchThreads = 2\nnnMaxBatchSize = 4\n"
              + "nnCacheSizePowerOfTwo = 16\nnnMutexPoolSizePowerOfTwo = 12\n"
              + "ponderingEnabled = false\nlogAllGTPCommunication = true\n"
              + "logSearchInfo = false\nlogSearchInfoForChosenMove = false\n"
              + "logDir = "
              + log.toString().replace('\\', '/')
              + "\n"
              + "analysisWideRootNoise = "
              + originalWrn[index]
              + "\n"
              + "playoutDoublingAdvantage = "
              + originalPda[index]
              + "\n");
      entries.put(
          new JSONObject()
              .put("name", "Acceptance KataGo " + (index + 1))
              .put(
                  "command",
                  quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config))
              .put("preload", true)
              .put("isDefault", index == 0)
              .put("width", 19)
              .put("height", 19)
              .put("komi", 7.5)
              .put("useJavaSSH", false));
    }
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("use-language", 2)
            .put("autoload-empty", false)
            .put("autoload-default", true)
            .put("autoload-last", false)
            .put("default-engine", 0)
            .put("auto-quick-analyze-on-load", false)
            .put("not-start-pondering", true)
            .put("autoload-kata-engine-wrn", false)
            .put("autoload-kata-engine-pda", false)
            .put("engine-pk-ponder", !batch)
            .put("play-sound", false);
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("ui", ui)
            .put("leelaz", new JSONObject().put("engine-settings-list", entries))
            .toString(2));
    System.setProperty("lizzie.work.dir", work.toString());
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), 30, "application window");
    await(
        () ->
            Lizzie.engineManager != null
                && Lizzie.engineManager.engineList.size() == 2
                && Lizzie.engineManager.engineList.stream()
                    .allMatch(
                        e -> e.isLoaded() && e.isStarted() && !e.hasExclusiveGtpWorkInProgress()),
        70,
        "both real engines ready");
    List<Leelaz> participants = edt(() -> List.copyOf(Lizzie.engineManager.engineList));
    List<Object> incarnations = participants.stream().map(Leelaz::engineIncarnationToken).toList();
    for (int index = 0; index < 2; index++) {
      assertNotNull(incarnations.get(index));
      assertEquals(originalWrn[index], readParameter(participants.get(index), WRN), 0.000001);
      assertEquals(originalPda[index], readParameter(participants.get(index), PDA), 0.000001);
    }
    record(result, "pre-batch-distinct-values=PASS");
    AtomicReference<StartFailure> startFailure = new AtomicReference<>();
    edt(
        () -> {
          EngineGameSideLimits limits =
              new EngineGameSideLimits(
                  EngineGameTimeMode.FIXED,
                  2,
                  "",
                  -1,
                  -1,
                  new EngineGameResignPolicy(1000, 2, 0.0));
          EngineGameBatchSpec spec =
              new EngineGameBatchSpec(
                  identity(entries, 0),
                  identity(entries, 1),
                  EngineGamePlayMode.ANALYSIS,
                  limits,
                  limits,
                  7.5,
                  0,
                  new EngineGameOpeningPlan.EmptyBoard(),
                  batch,
                  batch,
                  batch ? 2 : 1,
                  true,
                  batch ? 6 : 200,
                  new EngineGameOutputChoices(false, false, "parameter-acceptance"),
                  KataGoRules.parse("chinese").orElseThrow(),
                  new EngineGameOpeningParameters(0.2, 0.4, 0.5, -0.5));
          assertInstanceOf(
              Acceptance.Accepted.class,
              Lizzie.engineGame.accept(
                  spec,
                  new StartObserver() {
                    @Override
                    public void playing() {}

                    @Override
                    public void startFailed(StartFailure failure) {
                      startFailure.set(failure);
                    }
                  }));
          return null;
        });
    await(
        () -> {
          assertNull(startFailure.get(), "Game startup failed");
          return Lizzie.engineGame.current().playing();
        },
        70,
        "first game starts");
    awaitParameters(0.2, 0.4, 0.5, -0.5);
    assertTrue(edt(() -> Lizzie.engineGame.current().view().firstIsBlack()));
    record(result, "opening-both-participants=PASS");
    CompletableFuture<?> restoration =
        edt(
            () -> {
              var sessionField = EngineManager.class.getDeclaredField("engineGameParameterSession");
              sessionField.setAccessible(true);
              Object session = sessionField.get(Lizzie.engineManager);
              var restoredField = session.getClass().getDeclaredField("restored");
              restoredField.setAccessible(true);
              return (CompletableFuture<?>) restoredField.get(session);
            });

    if (batch) {
      applyPanel(0.7, null, null, null);
      awaitParameters(0.7, 0.4, 0.5, -0.5);
      await(
          () ->
              Lizzie.engineGame.current().playing()
                  && Lizzie.engineGame.current().view().gameOrdinal() == 2,
          50,
          "second batch game");
      edt(
          () -> {
            Lizzie.engineGame.pause();
            return null;
          });
      awaitParameters(0.2, 0.4, 0.5, -0.5);
      assertFalse(edt(() -> Lizzie.engineGame.current().view().firstIsBlack()));
      record(result, "second-game-position-settings-and-color-swap=PASS");
      edt(
          () -> {
            Lizzie.engineGame.resume();
            return null;
          });
      await(
          () ->
              !Lizzie.engineGame.current().startingOrPlaying()
                  && !Lizzie.engineGame.current().betweenGames(),
          50,
          "batch natural completion");
      assertEquals(2, edt(() -> Lizzie.engineGame.lastSummary().maxMoveGames()));
      record(result, "two-games-natural-completion=PASS");
    } else {
      await(() -> moveNumber() >= 1, 20, "real game advances before edit");
      int[][] before = parameterWrites(work);
      applyPanel(null, 0.75, null, null);
      awaitParameters(0.2, 0.75, 0.5, -0.5);
      assertWriteDelta(before, parameterWrites(work), new int[][] {{0, 0}, {1, 0}});
      record(result, "single-side-edit-only=PASS");
      edt(
          () -> {
            Lizzie.engineGame.pause();
            return null;
          });
      await(() -> Lizzie.engineGame.current().paused(), 10, "game pause");
      before = parameterWrites(work);
      applyPanel(null, null, 1.25, -1.25);
      awaitParameters(0.2, 0.75, 1.25, -1.25);
      assertWriteDelta(before, parameterWrites(work), new int[][] {{0, 1}, {0, 1}});
      assertTrue(edt(() -> Lizzie.engineGame.current().paused()));
      // Confirmation drains moves admitted before the pause intent reached the reader.
      int pausedMove = edt(EngineGameParametersAcceptanceIT::moveNumber);
      record(result, "combined-edit-only-and-pause-preserved=PASS");
      edt(
          () -> {
            Lizzie.engineGame.resume();
            return null;
          });
      await(() -> moveNumber() > pausedMove, 20, "real move after resume");
      record(result, "resume-produces-real-move=PASS");
      edt(
          () -> {
            Lizzie.engineGame.stop();
            return null;
          });
    }
    await(() -> !EngineManager.occupiesEngineGameAdmission(), 25, "game ownership retires");
    // Wait for production restoration, not merely delivery of its set commands.
    restoration.get(25, TimeUnit.SECONDS);
    assertTrue(restoreCommandsPresent(work, originalWrn, originalPda));
    for (int index = 0; index < 2; index++) {
      Leelaz participant = participants.get(index);
      assertTrue(
          participant.isCurrentEngineIncarnationToken(incarnations.get(index)),
          "Acceptance must restore the same physical process");
      assertEquals(originalWrn[index], readParameter(participant, WRN), 0.000001);
      assertEquals(originalPda[index], readParameter(participant, PDA), 0.000001);
    }
    assertNull(edt(() -> Lizzie.engineGame.parameterRestoreFailure()));
    record(result, "same-process-original-values-restored=PASS\nmode=" + mode + "\nresult=PASS");
  }

  private static EngineParticipantIdentity identity(JSONArray entries, int index) {
    JSONObject entry = entries.getJSONObject(index);
    return new EngineParticipantIdentity(entry.getString("command"), entry.getString("name"));
  }

  private static void applyPanel(
      Double firstWrn, Double secondWrn, Double firstPda, Double secondPda) throws Exception {
    edt(
        () -> {
          assertFalse(
              LizzieFrame.menu.chkWRN.isEnabled(), "Ordinary breadth control is game-owned");
          assertFalse(LizzieFrame.menu.chkPDA.isEnabled(), "Ordinary PDA control is game-owned");
          EngineGameParametersDialog.open(Lizzie.frame);
          EngineGameParametersDialog dialog = null;
          for (Window window : Window.getWindows()) {
            if (window instanceof EngineGameParametersDialog found && found.isVisible())
              dialog = found;
          }
          assertNotNull(dialog, "Production parameters panel is visible");
          List<JTextField> fields = new ArrayList<>();
          fields(dialog.getContentPane(), fields);
          assertEquals(4, fields.size());
          Double[] edits = {firstWrn, firstPda, secondWrn, secondPda};
          for (int i = 0; i < edits.length; i++) {
            if (edits[i] != null) {
              assertTrue(fields.get(i).isEnabled());
              fields.get(i).setText(Double.toString(edits[i]));
            }
          }
          dialog.getRootPane().getDefaultButton().doClick();
          return null;
        });
    await(() -> !EngineGameParametersDialog.isOpen(), 20, "panel closes after confirmation");
  }

  private static void fields(Container parent, List<JTextField> result) {
    for (Component component : parent.getComponents()) {
      if (component instanceof JTextField field) result.add(field);
      else if (component instanceof Container child) fields(child, result);
    }
  }

  private static void awaitParameters(
      double firstWrn, double secondWrn, double firstPda, double secondPda) throws Exception {
    await(
        () -> {
          EngineGameParameterState state = Lizzie.engineGame.parameterState();
          if (state == null || state.pending()) return false;
          assertNull(state.failure());
          return value(state.first().wrn(), firstWrn)
              && value(state.second().wrn(), secondWrn)
              && value(state.first().pda(), firstPda)
              && value(state.second().pda(), secondPda);
        },
        20,
        "both participants confirm requested parameters");
  }

  private static boolean value(EngineGameParameterState.Value actual, double expected) {
    return actual.supported()
        && actual.target() == null
        && actual.confirmed() != null
        && Math.abs(actual.confirmed() - expected) < 0.000001;
  }

  /** Query the actual idle engine, using the existing exact-incarnation response callback seam. */
  private static double readParameter(Leelaz engine, String name) throws Exception {
    Method confirm = null;
    for (Method method : Leelaz.class.getDeclaredMethods()) {
      if (method.getName().equals("confirmEngineGameCommand")) confirm = method;
    }
    assertNotNull(confirm);
    confirm.setAccessible(true);
    CompletableFuture<String> response = new CompletableFuture<>();
    AtomicReference<String> payload = new AtomicReference<>();
    Function<String, String> capture =
        text -> {
          payload.set(text);
          return null;
        };
    Runnable success = () -> response.complete(payload.get());
    Consumer<String> failure = detail -> response.completeExceptionally(new AssertionError(detail));
    Method command = confirm;
    edt(
        () -> {
          command.invoke(
              engine,
              null,
              engine.engineIncarnationToken(),
              "kata-get-param " + name,
              capture,
              success,
              failure);
          return null;
        });
    return Double.parseDouble(response.get(15, TimeUnit.SECONDS).trim());
  }

  private static int[][] parameterWrites(Path work) throws Exception {
    int[][] counts = new int[2][2];
    for (int i = 0; i < 2; i++) {
      String log = gtpLog(work.resolve("gtp-" + i));
      String[] names = {WRN, PDA};
      for (int p = 0; p < 2; p++) {
        counts[i][p] =
            (int)
                Pattern.compile("Controller: \\d*\\s*kata-set-param " + names[p] + " ")
                    .matcher(log)
                    .results()
                    .count();
      }
    }
    return counts;
  }

  private static void assertWriteDelta(int[][] before, int[][] after, int[][] expected) {
    for (int i = 0; i < 2; i++) {
      for (int p = 0; p < 2; p++) {
        assertEquals(
            expected[i][p],
            after[i][p] - before[i][p],
            "Only the edited parameter is written: participant=" + i + " parameter=" + p);
      }
    }
  }

  private static boolean restoreCommandsPresent(Path work, double[] wrn, double[] pda)
      throws Exception {
    for (int i = 0; i < 2; i++) {
      String log = gtpLog(work.resolve("gtp-" + i));
      // Original values cannot appear in a set command until this batch's restoration.
      if (!restoredSet(log, WRN, wrn[i]) || !restoredSet(log, PDA, pda[i])) return false;
    }
    return true;
  }

  private static boolean restoredSet(String log, String name, double value) {
    return Pattern.compile(
            "Controller: \\d*\\s*kata-set-param "
                + name
                + " "
                + Pattern.quote(Double.toString(value))
                + "(?:\\s|$)")
        .matcher(log)
        .find();
  }

  private static String gtpLog(Path directory) throws Exception {
    try (var paths = Files.list(directory)) {
      StringBuilder result = new StringBuilder();
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        result.append(Files.readString(path));
      }
      return result.toString();
    }
  }

  private static int moveNumber() {
    return Lizzie.board.getHistory().getData().moveNumber;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath() + "\"";
  }

  private static void record(Path result, String text) throws Exception {
    Files.writeString(result, text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  private static void await(Callable<Boolean> condition, int seconds, String label)
      throws Exception {
    awaitOffEdt(() -> edt(condition), seconds, label);
  }

  private static void awaitOffEdt(Callable<Boolean> condition, int seconds, String label)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    do {
      if (condition.call()) return;
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Timed out waiting for " + label);
  }

  private static void shutdownClockHelper() throws Exception {
    Process helper =
        edt(
            () -> {
              if (Lizzie.frame == null) return null;
              var field = LizzieFrame.class.getDeclaredField("processClockHelper");
              field.setAccessible(true);
              Process process = (Process) field.get(Lizzie.frame);
              Lizzie.frame.shutdownClockHelper();
              return process;
            });
    assertTrue(
        helper == null || helper.waitFor(2, TimeUnit.SECONDS),
        "Clock helper survived acceptance cleanup");
  }
}
