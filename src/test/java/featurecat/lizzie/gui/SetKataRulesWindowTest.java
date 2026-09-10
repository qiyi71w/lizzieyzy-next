package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SetKataRulesWindowTest {
  private static final String CHINESE =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";
  private static final String POSITIONAL_CHINESE =
      "{\"ko\":\"POSITIONAL\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true,"
          + "\"experimentalKo\":\"X\"}";
  private static final String JAPANESE =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"TERRITORY\",\"tax\":\"SEKI\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"0\",\"friendlyPassOk\":true}";

  @Test
  void mismatchedManualReadbackDoesNotChangeSessionTarget() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = new SetKataRules(fixture.engine);
      assertTrue(dialog.statusText().contains("Confirming engine rules"));
      assertTrue(
          dialog.chkbxAutoLoadRules.getText().contains("startup default"),
          dialog.chkbxAutoLoadRules.getText());

      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + POSITIONAL_CHINESE);
      awaitConfirmedReadback(dialog);
      assertTrue(dialog.hasRulesResponse());
      assertTrue(dialog.getRules());
      assertTrue(dialog.rdoPositionKo.isSelected());
      assertEquals(1, fixture.engine.usingSpecificRules);
      BoardHistoryList.SessionRulesTarget originalTarget =
          Lizzie.board.getHistory().captureSessionRules();

      dialog.rdoTerritory.setSelected(true);
      dialog.rdoSeKiTax.setSelected(true);
      dialog.rdoNoHandicapKomi.setSelected(true);
      dialog.rdoSimpleKo.setSelected(true);
      dialog.rdoNoSuicide.setSelected(true);
      SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
      dialog.applySelectedRules();

      assertTrue(dialog.statusText().contains("Confirming engine rules"));
      assertEquals(1, fixture.engine.usingSpecificRules);
      assertTrue(fixture.output.toString().contains("experimentalKo"));
      assertFalse(fixture.engine.engineRulesResult().isConfirmed());

      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      dispatch(fixture.engine, "=" + setId);
      int readbackId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + readbackId + " " + JAPANESE);
      awaitStatus(dialog, "Failed to set engine rules");
      assertFalse(dialog.getRules());

      assertTrue(dialog.statusText().contains("Failed to set engine rules"));
      assertTrue(dialog.rdoTerritory.isSelected());
      assertFalse(dialog.rdoArea.isSelected());
      assertEquals(3, fixture.engine.usingSpecificRules);
      assertTrue(fixture.engine.engineRulesResult().isConfirmed());
      assertEquals("TERRITORY", fixture.engine.engineRulesResult().observed().string("scoring"));
      assertSame(originalTarget, Lizzie.board.getHistory().captureSessionRules());
      assertTrue(dialog.isVisible(), "A mismatched readback must remain visible");
      assertTrue(KataGoRules.parse(POSITIONAL_CHINESE).orElseThrow().hasField("experimentalKo"));
      closeDialog(dialog);
    }
  }

  @Test
  void closingAfterApplyStillPublishesMatchingManualReadback() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = new SetKataRules(fixture.engine);
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget original = history.captureSessionRules();

      dialog.rdoTerritory.setSelected(true);
      dialog.rdoSeKiTax.setSelected(true);
      dialog.rdoNoHandicapKomi.setSelected(true);
      dialog.rdoSimpleKo.setSelected(true);
      dialog.rdoNoSuicide.setSelected(true);
      dialog.applySelectedRules();
      closeDialog(dialog);

      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      dispatch(fixture.engine, "=" + setId);
      int readbackId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + readbackId + " " + JAPANESE);

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (history.captureSessionRules() == original && System.nanoTime() < deadline) {
        Thread.sleep(20L);
      }
      BoardHistoryList.SessionRulesTarget published = history.captureSessionRules();
      assertFalse(published == original);
      assertTrue(
          published
              .parsedRules()
              .orElseThrow()
              .semanticallyEquals(KataGoRules.parse(JAPANESE).orElseThrow()));
    }
  }

  @Test
  void confirmSavesStartupRulesAndClosesAfterMatchingReadback() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = new SetKataRules(fixture.engine);
      SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget original = history.captureSessionRules();
      assertTrue(dialog.isVisible(), "Opening readback must not close the editor");
      dialog.chkbxAutoLoadRules.setSelected(true);

      dialog.rdoTerritory.setSelected(true);
      dialog.rdoSeKiTax.setSelected(true);
      dialog.rdoNoHandicapKomi.setSelected(true);
      dialog.rdoSimpleKo.setSelected(true);
      dialog.rdoNoSuicide.setSelected(true);
      SwingUtilities.invokeAndWait(
          () -> {
            for (java.awt.Component component : dialog.getContentPane().getComponents()) {
              if (component instanceof JFontButton button
                  && button
                      .getText()
                      .equals(Lizzie.resourceBundle.getString("SetKataRules.btnApply"))) {
                button.doClick();
                return;
              }
            }
            throw new AssertionError("Confirm button missing");
          });
      assertTrue(dialog.isVisible(), "Keep the editor open until the engine confirms");
      JSONObject saved =
          new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())))
              .getJSONObject("ui");
      assertTrue(saved.getBoolean("auto-load-kata-rules"));
      assertTrue(
          KataGoRules.parse(saved.getString("kata-rules"))
              .orElseThrow()
              .semanticallyEquals(KataGoRules.parse(JAPANESE).orElseThrow()));
      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      dispatch(fixture.engine, "=" + setId);
      int readbackId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + readbackId + " " + JAPANESE);
      awaitConfirmedReadback(dialog);
      Thread.sleep(100L);

      BoardHistoryList.SessionRulesTarget published = history.captureSessionRules();
      assertEquals(original.revision() + 1L, published.revision());
      assertTrue(dialog.getRules());
      assertEquals(original.revision() + 1L, history.captureSessionRules().revision());
      assertFalse(dialog.isVisible(), "Successful confirmation must close the editor");
      closeDialog(dialog);
    }
  }


  @Test
  void delayedQueryStaysPendingUntilProtocolSettlesThenAppliesRadios() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = new SetKataRules(fixture.engine);
      Thread.sleep(250L);
      SwingUtilities.invokeAndWait(() -> {});
      assertTrue(dialog.statusText().contains("Confirming engine rules"));
      assertFalse(dialog.hasRulesResponse());
      assertFalse(dialog.rdoPositionKo.isSelected());

      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + POSITIONAL_CHINESE);
      awaitConfirmedReadback(dialog);
      assertTrue(dialog.hasRulesResponse());
      assertTrue(dialog.rdoPositionKo.isSelected());
      assertFalse(dialog.statusText().contains("Failed to read engine rules"));
      closeDialog(dialog);
    }
  }

  @Test
  void windowShowsQueryFailureWithoutTreatingLastKnownAsSuccess() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = new SetKataRules(fixture.engine);
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      assertTrue(dialog.getRules());
      String previous = fixture.engine.recentRulesLine;

      fixture.engine.queryEngineRules();
      int failedQueryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "?" + failedQueryId + " unknown command");
      awaitStatus(dialog, "Failed to read engine rules");

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, result.status());
      assertTrue(dialog.statusText().contains("Failed to read engine rules"));
      assertTrue(dialog.statusText().contains("Last known rules may be stale"));
      assertEquals(previous, fixture.engine.recentRulesLine);
      assertFalse(result.isConfirmed());
      assertTrue(result.lastKnownStale());
      closeDialog(dialog);
    }
  }

  @Test
  void composeConfirmPreservesChineseFriendlyPassOkAndExtrasWithoutEngineCommands()
      throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      JSONObject json = new JSONObject(CHINESE);
      json.put("experimentalKo", "X");
      KataGoRules initial = KataGoRules.fromJson(json);

      SetKataRules cancelled = new SetKataRules(fixture.engine, true, initial);
      closeDialog(cancelled);
      assertNull(composedRules(cancelled));

      SetKataRules dialog = new SetKataRules(fixture.engine, true, initial);
      assertTrue(dialog.rdoSimpleKo.isSelected());
      assertTrue(dialog.rdoArea.isSelected());
      dialog.applySelectedRules();
      KataGoRules confirmed = composedRules(dialog);
      assertTrue(confirmed.hasRequiredFields());
      assertTrue(confirmed.hasField("friendlyPassOk"));
      assertTrue(confirmed.bool("friendlyPassOk"));
      assertTrue(confirmed.hasField("experimentalKo"));
      assertTrue(initial.semanticallyEquals(confirmed));

      SetKataRules edited = new SetKataRules(fixture.engine, true, initial);
      edited.rdoPositionKo.setSelected(true);
      edited.applySelectedRules();
      KataGoRules overlaid = composedRules(edited);
      assertEquals("POSITIONAL", overlaid.string("ko"));
      assertTrue(overlaid.hasRequiredFields());
      assertTrue(overlaid.bool("friendlyPassOk"));
      assertTrue(overlaid.hasField("experimentalKo"));
      assertFalse(initial.semanticallyEquals(overlaid));

      String commands = fixture.output.toString();
      assertFalse(commands.contains("kata-get-rules"));
      assertFalse(commands.contains("kata-set-rules"));
      closeDialog(dialog);
      closeDialog(edited);
    }
  }

  private static KataGoRules composedRules(SetKataRules dialog) throws Exception {
    Field field = SetKataRules.class.getDeclaredField("composed");
    field.setAccessible(true);
    return (KataGoRules) field.get(dialog);
  }

  private static void awaitConfirmedReadback(SetKataRules dialog) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> {});
      if (dialog.hasRulesResponse() && dialog.statusText().isEmpty()) {
        return;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("Confirmed readback did not settle: " + dialog.statusText());
  }

  private static void awaitStatus(SetKataRules dialog, String fragment) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> {});
      if (dialog.statusText().contains(fragment)) {
        return;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("status was: " + dialog.statusText());
  }

  private static void closeDialog(SetKataRules dialog) {
    dialog.dispatchEvent(
        new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
  }

  private static int commandIdFor(String commands, String command) {
    String[] lines = commands.split("\\R");
    for (int index = lines.length - 1; index >= 0; index--) {
      String trimmed = lines[index].trim();
      int split = trimmed.indexOf(' ');
      if (split <= 0) {
        continue;
      }
      if (trimmed.substring(split + 1).startsWith(command)) {
        return Integer.parseInt(trimmed.substring(0, split));
      }
    }
    throw new AssertionError("missing " + command + " in " + commands);
  }

  private static void dispatch(Leelaz engine, String line) throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod("dispatchReaderLineForTest", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void installOutput(Leelaz engine, ByteArrayOutputStream stream) throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod(
            "installFreshCommandOutputForTest", java.io.OutputStream.class);
    method.setAccessible(true);
    method.invoke(engine, stream);
  }

  private static void resetEngineGame() throws Exception {
    java.lang.reflect.Method method =
        EngineManager.class.getDeclaredMethod("resetEngineGameTransactionStateForTest");
    method.setAccessible(true);
    method.invoke(null);
  }

  private static final class Fixture implements AutoCloseable {
    private final Config previousConfig;
    private final Leelaz previousLeelaz;
    private final Leelaz previousSecondary;
    private final GtpConsolePane previousConsole;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final EngineManager previousManager;
    private final ResourceBundle previousBundle;
    final Leelaz engine;
    final ByteArrayOutputStream output;

    private Fixture() throws Exception {
      previousConfig = Lizzie.config;
      previousLeelaz = Lizzie.leelaz;
      previousSecondary = Lizzie.leelaz2;
      previousConsole = Lizzie.gtpConsole;
      previousBoard = Lizzie.board;
      previousFrame = Lizzie.frame;
      previousManager = Lizzie.engineManager;
      previousBundle = Lizzie.resourceBundle;
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("set-kata-rules"));
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.config =
          new JSONObject().put("ui", Lizzie.config.uiConfig).put("leelaz", Lizzie.config.leelazConfig);
      Lizzie.gtpConsole = null;
      Lizzie.frame = null;
      resetEngineGame();
      output = new ByteArrayOutputStream();
      engine = liveEngine(output);
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
    }

    static Fixture ordinary() throws Exception {
      return new Fixture();
    }

    private static Leelaz liveEngine(ByteArrayOutputStream stream) throws Exception {
      Leelaz created = new Leelaz("");
      installOutput(created, stream);
      created.started = true;
      created.isLoaded = true;
      created.isKatago = true;
      created.commandLists.addAll(List.of("kata-set-rules", "kata-get-rules"));
      Field ready = Leelaz.class.getDeclaredField("endGetCommandList");
      ready.setAccessible(true);
      ready.setBoolean(created, true);
      return created;
    }

    @Override
    public void close() {
      try {
        resetEngineGame();
      } catch (Exception ignored) {
        // Restore global Lizzie fields even if engine-game reset fails.
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.engineManager = previousManager;
      Lizzie.resourceBundle = previousBundle;
    }
  }
}
