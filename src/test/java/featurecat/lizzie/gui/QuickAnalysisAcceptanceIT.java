package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Dialog;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Explicit local acceptance with a real KataGo executable and model, not a default unit test. */
public class QuickAnalysisAcceptanceIT {
  @Test
  public void resumesForegroundAfterImportedGame() throws Exception {
    run("complete");
  }

  @Test
  public void preservesExplicitPauseDuringQuickAnalysis() throws Exception {
    run("pause");
  }

  private void run(String mode) throws Exception {
    DesktopProbeProcess.requireDisplay();
    String engine = System.getProperty("lizzie.acceptance.engine", "");
    String model = System.getProperty("lizzie.acceptance.model", "");
    assertTrue(Files.isRegularFile(Path.of(engine)), "Real engine path is required");
    assertTrue(Files.isRegularFile(Path.of(model)), "Real model path is required");
    Path result =
        DesktopProbeProcess.run(
            QuickAnalysisAcceptanceIT.class,
            "quick-analysis-" + mode,
            List.of(),
            List.of(
                mode,
                Path.of(engine).toAbsolutePath().toString(),
                Path.of(model).toAbsolutePath().toString()),
            180);
    assertTrue(Files.readString(result).contains("result=PASS\n"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[3]);
    Path result = Path.of(args[4]);
    int exit = 1;
    try {
      probe(args[0], Path.of(args[1]), Path.of(args[2]), work, result);
      exit = 0;
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, "result=FAIL\nerror=" + failure + "\n");
    } finally {
      System.exit(exit);
    }
  }

  private static void probe(String mode, Path engine, Path model, Path work, Path result)
      throws Exception {
    Path config = work.resolve("gtp.cfg");
    Files.writeString(
        config,
        "rules = tromp-taylor\nnumSearchThreads = 4\n"
            + "nnMaxBatchSize = 4\nnnCacheSizePowerOfTwo = 16\n"
            + "nnMutexPoolSizePowerOfTwo = 12\nlogToStderr = true\n"
            + "logAllGTPCommunication = true\nlogSearchInfo = true\n"
            + "logSearchInfoForChosenMove = false\nponderingEnabled = false\n"
            + "allowResignation = true\nresignThreshold = -0.90\nresignConsecTurns = 3\n");
    String command = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);
    JSONObject entry =
        new JSONObject()
            .put("command", command)
            .put("name", "Acceptance CPU")
            .put("isDefault", true)
            .put("width", 19)
            .put("height", 19)
            .put("komi", 7.5);
    JSONObject ui =
        new JSONObject()
            .put("autoload-default", true)
            .put("first-time-load", false)
            .put("use-language", 2)
            .put("auto-quick-analyze-on-load", true)
            .put("analysis-reuse-current-engine", true)
            .put("analysis-max-visits", 1)
            .put("quick-analysis-lightweight-model-enabled", false);
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray().put(entry)))
            .put("ui", ui)
            .toString(2));
    Path fixture = work.resolve("import-game.sgf");
    Files.writeString(fixture, "(;FF[4]GM[1]SZ[19]KM[7.5];B[pd];W[dd];B[qp];W[dq])");
    System.setProperty("lizzie.work.dir", work.toString());
    DesktopProbeProcess.phase(result, "production-startup");
    Lizzie.main(new String[0]);
    await(
        () ->
            Lizzie.frame != null
                && Lizzie.frame.isShowing()
                && Lizzie.leelaz != null
                && Lizzie.leelaz.isLoaded(),
        70,
        "real engine startup");
    edt(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog) window.dispose();
          }
        });
    Leelaz primary = Lizzie.leelaz;
    await(
        () ->
            primary.isPondering()
                && Lizzie.board.getHistory().getCurrentHistoryNode().getData().getPlayouts() > 0,
        30,
        "initial foreground analysis usable");
    DesktopProbeProcess.phase(result, "import-sgf");
    edt(
        () ->
            assertTrue(
                Lizzie.frame.loadFile(fixture.toFile(), false, true), "Production SGF import"));
    await(
        () ->
            Lizzie.frame.analysisEngine != null
                && Lizzie.frame.analysisEngine.usesSharedForegroundEngine()
                && Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress(),
        40,
        "quick analysis owns engine");
    DesktopProbeProcess.phase(result, "quick-analysis-observed");
    if (mode.equals("pause")) {
      edt(() -> Lizzie.frame.togglePonderMannul());
      await(
          () ->
              Lizzie.frame.isUserAnalysisPaused()
                  && !primary.isPondering()
                  && (Lizzie.frame.analysisEngine == null
                      || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress()),
          30,
          "pause cleanup");
      long quietUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (System.nanoTime() < quietUntil) {
        assertTrue(Lizzie.frame.isUserAnalysisPaused(), "Explicit pause remains authoritative");
        assertFalse(primary.isPondering(), "Cleanup must not resume pondering");
        Thread.sleep(100);
      }
      DesktopProbeProcess.phase(result, "explicit-pause-preserved");
    } else {
      await(
          () ->
              allMovesAnalyzed()
                  && primary.isPondering()
                  && (Lizzie.frame.analysisEngine == null
                      || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress()),
          70,
          "completion and handback");
      assertSame(primary, Lizzie.leelaz, "Foreground engine reused");
      BoardHistoryNode target = Lizzie.board.getHistory().getCurrentHistoryNode();
      int before = target.getData().getPlayouts();
      await(
          () -> target.getData().getPlayouts() > before,
          15,
          "ordinary analysis visits grow after handback");
      Files.writeString(
          result.getParent().resolve("visits.txt"),
          before + " -> " + target.getData().getPlayouts() + "\n");
      DesktopProbeProcess.phase(result, "foreground-visits-growing");
    }
    ImageIO.write(
        new Robot().createScreenCapture(Lizzie.frame.getBounds()),
        "png",
        result.getParent().resolve("window.png").toFile());
    primary.normalQuit();
    Files.writeString(result, "result=PASS\nmode=" + mode + "\n");
  }

  private static boolean allMovesAnalyzed() {
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    int moves = 0;
    while (node.next().isPresent()) {
      node = node.next().get();
      if (!node.getData().hasDisplayablePrimaryAnalysis()) return false;
      moves++;
    }
    return moves == 4;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath() + "\"";
  }

  private static void edt(Runnable action) throws Exception {
    FutureTask<Void> task = new FutureTask<>(action, null);
    SwingUtilities.invokeLater(task);
    task.get(15, TimeUnit.SECONDS);
  }

  private static void await(BooleanSupplier condition, int seconds, String description)
      throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (System.nanoTime() < until) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out: " + description);
  }
}
