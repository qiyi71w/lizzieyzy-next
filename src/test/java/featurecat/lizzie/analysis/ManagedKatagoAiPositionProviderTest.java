package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedKatagoAiPositionProviderTest {
  private static final String PARTIAL =
      "{\"id\":\"ai-position-1\",\"isDuringSearch\":true,"
          + "\"rootInfo\":{\"visits\":40,\"winrate\":0.2,\"scoreLead\":-8.0},"
          + "\"ownership\":[0.1,-0.2]}";
  private static final String FINAL =
      "{\"id\":\"ai-position-1\",\"isDuringSearch\":false,"
          + "\"rootInfo\":{\"visits\":800,\"winrate\":0.12,\"scoreLead\":-71.4},"
          + "\"ownership\":[0.25,-0.5]}";

  private Config previousConfig;
  private final List<Emission> emissions = new ArrayList<>();

  @BeforeEach
  void installConfig() throws Exception {
    previousConfig = Lizzie.config;
    Lizzie.config = allocate(Config.class);
    Lizzie.config.analysisMaxVisits = 50;
    Lizzie.config.analysisAutoQuit = true;
    Lizzie.config.analysisEnginePreLoad = false;
    Lizzie.config.analysisReuseCurrentEngine = false;
    Lizzie.config.analysisEngineCommand = "katago analysis";
  }

  @AfterEach
  void restoreConfig() {
    Lizzie.config = previousConfig;
  }

  @Test
  void supportsOnlyNonKatagoForeground() throws Exception {
    ManagedKatagoAiPositionProvider provider = provider(new FakeHost());
    Leelaz kataGo = new Leelaz("");
    kataGo.isKatago = true;
    Leelaz other = new Leelaz("");
    other.isKatago = false;
    assertTrue(provider.supports(context(other)));
    assertTrue(provider.supports(context(null)));
    assertFalse(provider.supports(context(kataGo)));
  }

  @Test
  void busyUserTaskLeavesProviderUnavailable() throws Exception {
    FakeHost host = new FakeHost();
    host.inProgress = true;
    host.automatic = false;
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);

    assertFalse(provider.start(context(null), 1L));
    assertTrue(host.engine.commands.isEmpty());
    assertEquals(0, host.lazyStarts);
  }

  @Test
  void lazyStartSendsBlackPerspectiveQuery() throws Exception {
    FakeHost host = new FakeHost();
    host.configured = true;
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);

    assertTrue(provider.start(context(null), 1L));
    assertEquals(1, host.lazyStarts);
    assertEquals(1, host.engine.commands.size());
    JSONObject request = new JSONObject(host.engine.commands.get(0));
    assertTrue(request.getBoolean("includeOwnership"));
    assertEquals("BLACK", request.getJSONObject("overrideSettings").getString("reportAnalysisWinratesAs"));
    assertEquals(7.5, request.getDouble("komi"), 1e-9);
    assertEquals("chinese", request.get("rules"));
    assertEquals(AnalysisEngine.targetAnalysisVisits(), request.getInt("maxVisits"));
  }

  @Test
  void borrowsIdlePreloadedEngineWithoutStartingAnother() throws Exception {
    FakeHost host = new FakeHost();
    host.idle = true;
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);

    assertTrue(provider.start(context(null), 1L));
    assertEquals(0, host.lazyStarts);
    assertEquals(1, host.borrows);
    assertEquals(1, host.engine.commands.size());
  }

  @Test
  void preemptsAutomaticBackgroundTaskThenStarts() throws Exception {
    FakeHost host = new FakeHost();
    host.inProgress = true;
    host.automatic = true;
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);

    assertTrue(provider.start(context(null), 1L));
    assertEquals(1, host.preempts);
    assertEquals(1, host.engine.commands.size());
  }

  @Test
  void refusesNonKatagoForegroundReuseWhenNoDedicatedConfig() throws Exception {
    FakeHost host = new FakeHost();
    host.configured = false;
    host.reuseNonKatago = true;
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);

    assertFalse(provider.start(context(null), 1L));
    assertEquals(0, host.lazyStarts);
    assertTrue(host.engine.commands.isEmpty());
  }

  @Test
  void partialThenFinalPublishIncreasingSequencesAndIgnoreOlder() throws Exception {
    FakeHost host = new FakeHost();
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);
    assertTrue(provider.start(context(null), 1L));

    host.engine.parseResult(PARTIAL.replace("ai-position-1", "ai-position-1"));
    host.engine.parseResult(FINAL);
    host.engine.parseResult(PARTIAL);

    assertEquals(2, emissions.size());
    assertEquals(1L, emissions.get(0).sequence);
    assertEquals(-8.0, emissions.get(0).update.sideToMoveScoreLead(), 1e-9);
    assertEquals(2L, emissions.get(1).sequence);
    assertEquals(-71.4, emissions.get(1).update.sideToMoveScoreLead(), 1e-9);
  }


  @Test
  void finalEmissionReleasesOwnedProcessOnce() throws Exception {
    FakeHost host = new FakeHost();
    host.keepAlive = false;
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);
    assertTrue(provider.start(context(null), 1L));

    host.engine.parseResult(FINAL);
    assertEquals(1, emissions.size());
    assertEquals(1, host.engine.quitCount);
    assertEquals(1, host.abandons);

    host.engine.parseResult(FINAL);
    provider.stop(1L);
    assertEquals(1, emissions.size());
    assertEquals(1, host.engine.quitCount);
    assertEquals(1, host.abandons);
  }
  @Test
  void terminateDiscardsLaterCallbackAndReleasesOnce() throws Exception {
    FakeHost host = new FakeHost();
    host.keepAlive = false;
    host.engine = recordingEngine();
    ManagedKatagoAiPositionProvider provider = provider(host);
    assertTrue(provider.start(context(null), 4L));

    provider.stop(4L);
    assertEquals(1, host.engine.quitCount);
    assertEquals(1, host.abandons);
    int commandsAfterStop = host.engine.commands.size();

    host.engine.parseResult(FINAL.replace("ai-position-1", "ai-position-4"));
    assertTrue(emissions.isEmpty());

    provider.stop(4L);
    assertEquals(1, host.engine.quitCount);
    assertEquals(1, host.abandons);
    assertEquals(commandsAfterStop, host.engine.commands.size());
  }

  @Test
  void managedPurposeNeverReusesForegroundEngine() {
    assertFalse(
        AnalysisEngine.allowsForegroundReuse(
            AnalysisResourceCoordinator.Purpose.AI_POSITION, true, true));
    assertTrue(
        AnalysisEngine.allowsForegroundReuse(
            AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS, true, false));
  }

  @Test
  void singlePositionEmissionsDoNotEnterAnalyzeMap() throws Exception {
    RecordingEngine engine = recordingEngine();
    JSONObject request = new JSONObject().put("id", "ai-position-1");
    List<String> received = new ArrayList<>();
    assertTrue(engine.startSinglePositionQuery(request, (sequence, json) -> received.add(json)));
    engine.parseResult(FINAL);
    assertEquals(1, received.size());
    assertEquals(0, engine.pendingCount());
    assertFalse(engine.isAnalysisInProgress());
  }

  private ManagedKatagoAiPositionProvider provider(FakeHost host) {
    return new ManagedKatagoAiPositionProvider(
        host,
        (generation, sequence, update, blackPerspective) ->
            emissions.add(new Emission(generation, sequence, update, blackPerspective)));
  }

  private static AiPositionRequestContext context(Leelaz engine) {
    return new AiPositionRequestContext(
        "node-a", 1L, "[stones]", true, 19, 19, "chinese", 7.5, engine, 1L);
  }

  private static RecordingEngine recordingEngine() throws Exception {
    RecordingEngine engine = allocate(RecordingEngine.class);
    engine.commands = new CopyOnWriteArrayList<>();
    Field loaded = AnalysisEngine.class.getDeclaredField("isLoaded");
    loaded.setAccessible(true);
    loaded.set(engine, true);
    Field analyzeMap = AnalysisEngine.class.getDeclaredField("analyzeMap");
    analyzeMap.setAccessible(true);
    analyzeMap.set(engine, new java.util.HashMap<Integer, Object>());
    return engine;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class Emission {
    private final long generation;
    private final long sequence;
    private final AiPositionSearchUpdate update;
    private final boolean blackPerspective;

    private Emission(
        long generation, long sequence, AiPositionSearchUpdate update, boolean blackPerspective) {
      this.generation = generation;
      this.sequence = sequence;
      this.update = update;
      this.blackPerspective = blackPerspective;
    }
  }

  private static final class FakeHost
      implements AnalysisResourceCoordinator.SinglePositionEngineHost {
    private RecordingEngine engine;
    private boolean idle;
    private boolean inProgress;
    private boolean automatic;
    private boolean configured = true;
    private boolean reuseNonKatago;
    private boolean keepAlive;
    private int lazyStarts;
    private int borrows;
    private int preempts;
    private int abandons;

    @Override
    public boolean hasIdlePreloaded() {
      return idle;
    }

    @Override
    public boolean isAnalysisInProgress() {
      return inProgress;
    }

    @Override
    public boolean isAutomaticBackgroundTask() {
      return automatic;
    }

    @Override
    public boolean hasConfiguredKatago() {
      return configured;
    }

    @Override
    public boolean wouldReuseNonKatagoForeground() {
      return reuseNonKatago;
    }

    @Override
    public AnalysisEngine borrowIdle() {
      borrows++;
      return engine;
    }

    @Override
    public AnalysisEngine preemptAutomaticAndStart() {
      preempts++;
      return engine;
    }

    @Override
    public AnalysisEngine lazyStart() {
      lazyStarts++;
      return engine;
    }

    @Override
    public void abandon(AnalysisEngine abandoned) {
      abandons++;
    }

    @Override
    public boolean shouldKeepAlive(AnalysisEngine engine, boolean ownsProcess) {
      return keepAlive;
    }
  }

  private static final class RecordingEngine extends AnalysisEngine {
    private List<String> commands;
    private int quitCount;

    private RecordingEngine() throws IOException {
      super(true);
    }

    @Override
    public boolean sendCommand(String command) {
      commands.add(command);
      return true;
    }

    @Override
    public void normalQuit() {
      quitCount++;
    }

    private int pendingCount() throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("analyzeMap");
      field.setAccessible(true);
      return ((java.util.Map<?, ?>) field.get(this)).size();
    }
  }
}
