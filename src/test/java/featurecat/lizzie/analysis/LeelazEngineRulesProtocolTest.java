package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LeelazEngineRulesProtocolTest {
  private static final String CHINESE =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";
  private static final String POSITIONAL_CHINESE =
      "{\"ko\":\"POSITIONAL\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";

  @Test
  void successfulReadbackBecomesConfirmedActualRulesNotTheRequest() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.applyEngineRules(KataGoRules.parse("chinese").orElseThrow());
      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      fixture.engine.dispatchReaderLineForTest("=" + setId);
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + POSITIONAL_CHINESE);

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertTrue(result.isConfirmed());
      assertEquals("POSITIONAL", result.observed().string("ko"));
      assertFalse(result.observed().semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
      assertEquals(1, fixture.engine.usingSpecificRules);
    }
  }

  @Test
  void matchingQueryErrorIsNotMaskedByPreviousConfirmedRules() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.confirm(CHINESE);
      String previous = fixture.engine.recentRulesLine;

      fixture.engine.queryEngineRules();
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("?" + queryId + " unknown command");

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, result.status());
      assertEquals(EngineRulesResult.Reason.QUERY_REJECTED, result.reason());
      assertTrue(result.lastKnownStale());
      assertEquals(previous, fixture.engine.recentRulesLine);
      assertFalse(result.isConfirmed());
    }
  }

  @Test
  void lateResponseDoesNotUpdateAReplacementEngine() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.queryEngineRules();
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      Leelaz replacement = fixture.replaceEngine();
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);

      assertEquals("", replacement.recentRulesLine);
      assertEquals("", Lizzie.config.currentKataGoRules);
      assertFalse(replacement.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void invalidReadbackIsFailureNotSuccess() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.queryEngineRules();
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " not-json");

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, result.status());
      assertEquals(EngineRulesResult.Reason.INVALID_READBACK, result.reason());
    }
  }

  @Test
  void settableButNotQueryableStartupIsUnconfirmed() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.commandLists.clear();
      fixture.engine.commandLists.add("kata-set-rules");
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = CHINESE;
      fixture.engine.confirmKataRulesAfterStartup(false, 50L, TimeUnit.SECONDS.toMillis(2));
      assertTrue(fixture.output.toString().contains("kata-set-rules"));
      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      fixture.engine.dispatchReaderLineForTest("=" + setId);
      assertFalse(fixture.output.toString().contains("kata-get-rules"));
      assertTrue(fixture.engine.engineRulesResult().isUnconfirmed());
      assertEquals(
          EngineRulesResult.Reason.QUERY_UNSUPPORTED, fixture.engine.engineRulesResult().reason());
    }
  }

  @Test
  void missingSetCapabilityDoesNotSendSetCommand() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.commandLists.clear();
      fixture.engine.commandLists.add("kata-get-rules");
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = CHINESE;
      fixture.engine.confirmKataRulesAfterStartup(false, 50L, TimeUnit.SECONDS.toMillis(2));
      assertFalse(fixture.output.toString().contains("kata-set-rules"));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);
      assertTrue(fixture.engine.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void isolatedStartupDoesNotPublishForegroundCache() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Lizzie.config.currentKataGoRules = "= previous";
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = CHINESE;
      Leelaz isolated = fixture.secondEngine();
      isolated.confirmKataRulesAfterStartup(true, 50L, TimeUnit.SECONDS.toMillis(2));
      int setId = commandIdFor(fixture.secondOutput.toString(), "kata-set-rules");
      isolated.dispatchReaderLineForTest("=" + setId);
      int queryId = commandIdFor(fixture.secondOutput.toString(), "kata-get-rules");
      isolated.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);
      assertTrue(isolated.engineRulesResult().isConfirmed());
      assertEquals("= previous", Lizzie.config.currentKataGoRules);
      assertEquals("", Lizzie.leelaz.recentRulesLine);
    }
  }

  @Test
  void unparseableStartupDefaultDoesNotSendSetCommand() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = "not-a-rules-value";
      fixture.engine.confirmKataRulesAfterStartup(false, 50L, TimeUnit.SECONDS.toMillis(2));
      assertFalse(fixture.output.toString().contains("kata-set-rules"));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);
      assertTrue(fixture.engine.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void startupConfirmationDoesNotBlockCallerBeforeCommandListCompletes() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Field ready = Leelaz.class.getDeclaredField("endGetCommandList");
      ready.setAccessible(true);
      ready.setBoolean(fixture.engine, false);
      long startedAt = System.nanoTime();
      fixture.engine.confirmKataRulesAfterStartup(
          false, TimeUnit.SECONDS.toMillis(2), TimeUnit.SECONDS.toMillis(2));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500L);
      assertFalse(fixture.output.toString().contains("kata-get-rules"));
      assertEquals(EngineRulesResult.Status.PENDING, fixture.engine.engineRulesResult().status());
      ready.setBoolean(fixture.engine, true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (System.nanoTime() < deadline && !fixture.output.toString().contains("kata-get-rules")) {
        Thread.sleep(20L);
      }
      assertTrue(fixture.output.toString().contains("kata-get-rules"));
    }
  }

  @Test
  void occupancyRejectsRuleMutation() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.beginForegroundRestoreForTest();
      boolean sent = fixture.engine.applyEngineRules(KataGoRules.parse("chinese").orElseThrow());
      assertFalse(sent);
      assertEquals(EngineRulesResult.Status.SET_FAILED, fixture.engine.engineRulesResult().status());
      assertEquals(EngineRulesResult.Reason.OCCUPIED, fixture.engine.engineRulesResult().reason());
      assertFalse(fixture.output.toString().contains("kata-set-rules"));
    }
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

  private static final class Fixture implements AutoCloseable {
    private final Config previousConfig;
    private final Leelaz previousLeelaz;
    private final GtpConsolePane previousConsole;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final EngineManager previousManager;
    final Leelaz engine;
    final ByteArrayOutputStream output;
    ByteArrayOutputStream secondOutput;

    private Fixture() throws Exception {
      previousConfig = Lizzie.config;
      previousLeelaz = Lizzie.leelaz;
      previousConsole = Lizzie.gtpConsole;
      previousBoard = Lizzie.board;
      previousFrame = Lizzie.frame;
      previousManager = Lizzie.engineManager;
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("engine-rules"));
      Lizzie.gtpConsole = null;
      Lizzie.frame = null;
      EngineManager.resetEngineGameTransactionStateForTest();
      output = new ByteArrayOutputStream();
      engine = liveEngine(output);
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
    }

    static Fixture ordinary() throws Exception {
      return new Fixture();
    }

    void confirm(String payload) throws Exception {
      engine.queryEngineRules();
      int queryId = commandIdFor(output.toString(), "kata-get-rules");
      engine.dispatchReaderLineForTest("=" + queryId + " " + payload);
    }

    Leelaz replaceEngine() throws Exception {
      Leelaz replacement = liveEngine(new ByteArrayOutputStream());
      Lizzie.leelaz = replacement;
      return replacement;
    }

    Leelaz secondEngine() throws Exception {
      secondOutput = new ByteArrayOutputStream();
      return liveEngine(secondOutput);
    }

    private static Leelaz liveEngine(ByteArrayOutputStream stream) throws Exception {
      Leelaz created = new Leelaz("");
      created.installFreshCommandOutputForTest(stream);
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
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.engineManager = previousManager;
    }
  }
}
