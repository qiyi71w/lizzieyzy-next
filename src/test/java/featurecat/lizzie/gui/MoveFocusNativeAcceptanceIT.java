package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.TrackingAnalysisController;
import featurecat.lizzie.rules.SGFParser;
import java.awt.Robot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Explicit, isolated native-window acceptance; ordinary unit runs never download an engine. */
public class MoveFocusNativeAcceptanceIT {
  private static final String SOURCE = "47aadc08518b3e121f22539796c911002f699584";

  @Test
  void realPinnedEngineUpdatesFocusAndSgfInTheProductionWindow() throws Exception {
    assumeTrue(Boolean.getBoolean("lizzie.focus.acceptance"), "native focus acceptance is opt-in");
    DesktopProbeProcess.requireDisplay();
    Path engine = Path.of(System.getProperty("lizzie.focus.engine")).toAbsolutePath();
    Path model = Path.of(System.getProperty("lizzie.focus.model")).toAbsolutePath();
    Path receiptPath = Path.of(System.getProperty("lizzie.focus.receipt",
        engine.resolveSibling("source-package.json").toString()));
    JSONObject receipt = new JSONObject(Files.readString(receiptPath));
    assertEquals(SOURCE, receipt.getString("sourceCommit"));
    assertEquals("PASS", receipt.getString("packagingStatus"));
    assertEquals("PASS", receipt.getString("dependencyAuditStatus"));
    String engineHash = sha256(engine);
    assertEquals(engineHash, receipt.getJSONObject("executable").getString("sha256"));
    assertTrue(Files.isRegularFile(model));
    Path marker = DesktopProbeProcess.run(MoveFocusNativeAcceptanceIT.class, "native-focus",
        List.of(), List.of(engine.toString(), model.toString(), engineHash, sha256(model)), 240);
    JSONObject result = new JSONObject(Files.readString(marker));
    assertEquals("PASS", result.getString("status"));
    assertEquals(engineHash, result.getString("engineSha256"));
    assertEquals(SOURCE, result.getString("sourceCommit"));
    assertEquals("complete", result.getString("phase"));
    assertTrue(result.getInt("rootVisits") > 0);
    assertTrue(Files.size(marker.resolveSibling("multiple-points.png")) > 0);
    assertTrue(Files.size(marker.resolveSibling("resumed.png")) > 0);
    assertTrue(Files.size(marker.resolveSibling("saved.sgf")) > 0);
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void edt(Runnable action) throws Exception {
    FutureTask<Void> task = new FutureTask<>(action, null);
    SwingUtilities.invokeLater(task);
    task.get(15, TimeUnit.SECONDS);
  }

  private static void await(BooleanSupplier condition, int seconds, String detail) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(30);
    }
    throw new AssertionError("Timeout: " + detail);
  }

  private static String quote(Path path) {
    return "\"" + path + "\"";
  }

  private static void phase(Path output, JSONObject result, String name) throws Exception {
    result.put("phase", name);
    Files.writeString(output, result.toString(2));
    DesktopProbeProcess.phase(output, name);
  }

  private static void screenshot(Path output, String name) throws Exception {
    ImageIO.write(new Robot().createScreenCapture(Lizzie.frame.getBounds()), "png",
        output.resolveSibling(name + ".png").toFile());
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 6) throw new IllegalArgumentException("engine, model, hashes, work, result required");
    Path engine = Path.of(args[0]);
    Path model = Path.of(args[1]);
    Path work = Path.of(args[4]);
    Path output = Path.of(args[5]);
    JSONObject result = new JSONObject().put("status", "FAIL").put("sourceCommit", SOURCE)
        .put("engineSha256", args[2]).put("modelSha256", args[3]);
    int exit = 1;
    try {
      assertEquals(args[2], sha256(engine));
      assertEquals(args[3], sha256(model));
      System.setProperty("lizzie.work.dir", work.toString());
      Path config = work.resolve("gtp.cfg");
      Files.writeString(config, "rules = chinese\nnumSearchThreads = 2\nnnMaxBatchSize = 2\nponderingEnabled = false\nlogToStderr = true\nlogAllGTPCommunication = true\nlogSearchInfo = true\n");
      String command = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);
      JSONObject entry = new JSONObject().put("command", command).put("name", "Same-tree acceptance")
          .put("isDefault", true).put("width", 19).put("height", 19).put("komi", 7.5);
      JSONObject ui = new JSONObject().put("autoload-default", true).put("first-time-load", false)
          .put("use-language", 1).put("auto-quick-analyze-on-load", false)
          .put("quick-analysis-lightweight-model-enabled", false)
          .put("tracking-analysis-max-visits", 1000000).put("confirm-exit", false);
      Files.writeString(work.resolve("config.txt"), new JSONObject()
          .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray().put(entry)))
          .put("ui", ui).toString(2));
      Path sgf = work.resolve("game.sgf");
      Files.writeString(sgf, "(;FF[4]GM[1]SZ[19]KM[7.5];B[dd];W[pd])");
      phase(output, result, "startup");
      Lizzie.main(new String[0]);
      await(() -> Lizzie.frame != null && Lizzie.frame.isShowing() && Lizzie.leelaz != null
          && Lizzie.leelaz.isLoaded(), 120, "production startup");
      await(() -> Lizzie.leelaz.moveFocusCapability() == Leelaz.MoveFocusCapability.SUPPORTED,
          30, "real focus capability");
      phase(output, result, "load-game");
      edt(() -> assertTrue(Lizzie.frame.loadFile(sgf.toFile(), false, true)));
      await(() -> Lizzie.frame.canStartTrackingAnalysis() && Lizzie.leelaz.isPondering()
          && Lizzie.frame.getDisplayNode().getData().rootVisits > 0, 30, "ordinary result");
      phase(output, result, "single-point");
      edt(() -> assertEquals(TrackingAnalysisController.AddResult.ADDED, Lizzie.frame.addTrackingPoint("Q4")));
      await(() -> Lizzie.frame.trackingDisplaySnapshot().visits().getOrDefault("Q4", 0) > 0, 20, "Q4 visits");
      phase(output, result, "multiple-points");
      edt(() -> assertEquals(TrackingAnalysisController.AddResult.ADDED, Lizzie.frame.addTrackingPoint("D16")));
      await(() -> Lizzie.frame.trackingDisplaySnapshot().visits().getOrDefault("D16", 0) > 0, 20, "D16 visits");
      assertEquals(2, Lizzie.frame.trackingDisplaySnapshot().selectedPoints().size());
      screenshot(output, "multiple-points");
      phase(output, result, "remove-clear");
      edt(() -> assertTrue(Lizzie.frame.removeTrackingPoint("Q4")));
      assertTrue(!Lizzie.frame.isTrackingPoint("Q4") && Lizzie.frame.isTrackingPoint("D16"));
      edt(() -> Lizzie.frame.clearTrackingPoints());
      await(() -> !Lizzie.frame.hasTrackingPoints()
          && !Lizzie.frame.trackingDisplaySnapshot().cancellationPending()
          && Lizzie.leelaz.isPondering(), 20, "clear settled");
      phase(output, result, "pause-resume");
      edt(() -> Lizzie.frame.togglePonderMannul());
      await(() -> Lizzie.frame.isUserAnalysisPaused() && !Lizzie.leelaz.isPondering(), 20, "pause");
      edt(() -> Lizzie.frame.togglePonderMannul());
      await(() -> !Lizzie.frame.isUserAnalysisPaused() && Lizzie.leelaz.isPondering()
          && Lizzie.frame.canStartTrackingAnalysis(), 20, "resume");
      edt(() -> assertEquals(TrackingAnalysisController.AddResult.ADDED, Lizzie.frame.addTrackingPoint("Q4")));
      await(() -> Lizzie.frame.trackingDisplaySnapshot().visits().getOrDefault("Q4", 0) > 0, 20, "fresh visits");
      phase(output, result, "save-results");
      FutureTask<String> save = new FutureTask<>(() -> SGFParser.saveToString(false));
      SwingUtilities.invokeLater(save);
      String saved = save.get(15, TimeUnit.SECONDS);
      assertTrue(saved.contains("rootVisits="), "SGF must retain exact root visits");
      assertTrue(saved.contains("edgeVisits "), "SGF must retain edge allocations");
      Files.writeString(output.resolveSibling("saved.sgf"), saved);
      assertTrue(Lizzie.frame.getDisplayNode().getData().rootVisits > 0);
      screenshot(output, "resumed");
      result.put("rootVisits", Lizzie.frame.getDisplayNode().getData().rootVisits).put("status", "PASS");
      phase(output, result, "complete");
      exit = 0;
    } catch (Throwable failure) {
      result.put("status", "FAIL").put("error", failure.toString());
      Files.writeString(output, result.toString(2));
      if (Lizzie.frame != null && Lizzie.frame.isShowing()) {
        try {
          screenshot(output, "failure");
        } catch (Exception captureFailure) {
          failure.addSuppressed(captureFailure);
        }
      }
      failure.printStackTrace();
    } finally {
      // The parent also tracks and waits for these owned descendants on timeout or failure.
      ProcessHandle.current().descendants().forEach(ProcessHandle::destroy);
      System.exit(exit);
    }
  }
}
