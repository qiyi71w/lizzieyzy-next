package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class HumanSlAnalysisRunnerTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void closeWaitsUntilTheCompanionProcessHasActuallyExited() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      DelayedExitProcess process =
          new DelayedExitProcess(request -> new JSONObject().put("id", request.optString("id")));
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);
      assertTrue(runner.start());
      java.util.concurrent.atomic.AtomicReference<Throwable> closeFailure =
          new java.util.concurrent.atomic.AtomicReference<>();
      Thread closer =
          new Thread(
              () -> {
                try {
                  runner.close();
                } catch (Throwable failure) {
                  closeFailure.set(failure);
                }
              });

      closer.start();
      assertTrue(process.destroyRequested.await(1, TimeUnit.SECONDS));
      closer.join(100L);
      assertTrue(closer.isAlive(), "close must not return while the child is still alive");

      process.allowExit.countDown();
      closer.join(1000L);
      assertFalse(closer.isAlive());
      assertNull(closeFailure.get());
    }
  }

  @Test
  void restartWaitsForTheCancelledCompanionToActuallyExit() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      DelayedExitProcess first =
          new DelayedExitProcess(request -> new JSONObject().put("id", request.optString("id")));
      FakeProcess replacement =
          new FakeProcess(request -> new JSONObject().put("id", request.optString("id")));
      AtomicInteger launches = new AtomicInteger();
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(
              List.of("katago", "analysis"),
              ignored -> launches.getAndIncrement() == 0 ? first : replacement);
      java.util.concurrent.atomic.AtomicReference<Throwable> restartFailure =
          new java.util.concurrent.atomic.AtomicReference<>();

      assertTrue(runner.start());
      runner.cancelActiveRequests();
      assertTrue(first.destroyRequested.await(1, TimeUnit.SECONDS));
      Thread restarter =
          new Thread(
              () -> {
                try {
                  assertTrue(runner.start());
                } catch (Throwable failure) {
                  restartFailure.set(failure);
                }
              });

      restarter.start();
      restarter.join(100L);
      assertTrue(restarter.isAlive(), "replacement must wait for the old OS process");
      assertEquals(1, launches.get(), "a second process must not overlap the terminating one");

      first.allowExit.countDown();
      restarter.join(1000L);
      assertFalse(restarter.isAlive());
      assertNull(restartFailure.get());
      assertEquals(2, launches.get());
      runner.close();
    }
  }

  @Test
  void buildHumanSlCommand_replacesGtpAndAddsHumanModel() {
    List<String> command =
        HumanSlAnalysisRunner.buildHumanSlCommand(
            "katago gtp -model main.bin.gz -config gtp.cfg", Path.of("human.bin.gz"));

    assertEquals("analysis", command.get(1));
    assertTrue(command.contains("-human-model"));
    assertTrue(command.get(command.indexOf("-human-model") + 1).endsWith("human.bin.gz"));
  }

  @Test
  void buildHumanSlRequest_includesPolicyProfileAndPositionSettings() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);

      JSONObject request =
          HumanSlAnalysisRunner.buildHumanSlRequest(
              "humansl-1", history.getCurrentHistoryNode(), "rank_1d", 1);

      assertEquals("humansl-1", request.getString("id"));
      assertTrue(request.getBoolean("includePolicy"));
      assertEquals(1, request.getInt("maxVisits"));
      assertEquals(
          "rank_1d", request.getJSONObject("overrideSettings").getString("humanSLProfile"));
      assertFalse(request.getJSONObject("overrideSettings").getBoolean("ignorePreRootHistory"));
      assertEquals(
          0.5,
          request.getJSONObject("overrideSettings").getDouble("humanSLRootExploreProbWeightless"),
          0.0001);
      assertEquals(BOARD_SIZE, request.getInt("boardXSize"));
      assertEquals(BOARD_SIZE, request.getInt("boardYSize"));
    }
  }

  @Test
  void buildHumanSlRequest_appliesConfiguredRootSymmetryBudget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);

      JSONObject request =
          HumanSlAnalysisRunner.buildHumanSlRequest(
              "humansl-pro", history.getCurrentHistoryNode(), "proyear_2023", 128, 2);

      assertEquals(128, request.getInt("maxVisits"));
      assertEquals(
          2, request.getJSONObject("overrideSettings").getInt("rootNumSymmetriesToSample"));
    }
  }

  @Test
  void buildHumanSlVerificationRequest_limitsRootToHumanCandidates() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);

      JSONObject request =
          HumanSlAnalysisRunner.buildHumanSlVerificationRequest(
              "humansl-verify",
              history.getCurrentHistoryNode(),
              "rank_7d",
              256,
              2,
              List.of("A3", "B2"));

      assertEquals(256, request.getInt("maxVisits"));
      assertEquals(
          2.0,
          request.getJSONObject("overrideSettings").getDouble("humanSLCpuctPermanent"),
          0.0001);
      assertEquals(
          0.8,
          request.getJSONObject("overrideSettings").getDouble("humanSLRootExploreProbWeightless"),
          0.0001);
      JSONObject allowance = request.getJSONArray("allowMoves").getJSONObject(0);
      assertEquals("B", allowance.getString("player"));
      assertEquals(1, allowance.getInt("untilDepth"));
      assertEquals(List.of("A3", "B2"), allowance.getJSONArray("moves").toList());
    }
  }

  @Test
  void adaptiveVerificationVisits_scalesWithMeasuredSpeedAndRemainingTime() {
    assertEquals(
        88,
        HumanSlAnalysisRunner.adaptiveVerificationVisits(
            64, Duration.ofMillis(3_500).toNanos(), Duration.ofMillis(6_150).toNanos()));
    assertEquals(
        4_096,
        HumanSlAnalysisRunner.adaptiveVerificationVisits(
            64, Duration.ofMillis(100).toNanos(), Duration.ofSeconds(9).toNanos()));
    assertEquals(
        80,
        HumanSlAnalysisRunner.adaptiveVerificationVisits(
            64, Duration.ofSeconds(7).toNanos(), Duration.ofSeconds(3).toNanos()));
    assertEquals(
        64,
        HumanSlAnalysisRunner.adaptiveVerificationVisits(
            64, Duration.ofSeconds(4).toNanos(), Duration.ofMillis(900).toNanos()));
  }

  @Test
  void eliteProfilesUseSpareTimeEvenWhenShallowCandidatesLookStable() {
    assertTrue(HumanSlAnalysisRunner.shouldAdaptivelyDeepen("rank_7d", false));
    assertTrue(HumanSlAnalysisRunner.shouldAdaptivelyDeepen("rank_9d", false));
    assertTrue(HumanSlAnalysisRunner.shouldAdaptivelyDeepen("proyear_2023", false));
    assertTrue(HumanSlAnalysisRunner.shouldAdaptivelyDeepen("rank_3k", true));
    assertFalse(HumanSlAnalysisRunner.shouldAdaptivelyDeepen("rank_3k", false));
  }

  @Test
  void samplePolicyMove_samplesByProbabilityAndCanExcludePass() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      JSONArray pairPolicy =
          new JSONArray()
              .put(new JSONArray().put("A3").put(0.25))
              .put(new JSONArray().put("B2").put(0.75))
              .put(new JSONArray().put("pass").put(100.0));
      assertEquals(
          "A3",
          HumanSlAnalysisRunner.samplePolicyMove(pairPolicy, BOARD_SIZE, BOARD_SIZE, 0.20, false));
      assertEquals(
          "B2",
          HumanSlAnalysisRunner.samplePolicyMove(pairPolicy, BOARD_SIZE, BOARD_SIZE, 0.30, false));
      assertEquals(
          "pass",
          HumanSlAnalysisRunner.samplePolicyMove(pairPolicy, BOARD_SIZE, BOARD_SIZE, 0.99, true));
    }
  }

  @Test
  void samplePolicyMove_numericPolicyUsesKatagoRowMajorOrderAndFiltersOccupiedPoints()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] stones = stones(placement(0, 0, Stone.BLACK));
      JSONArray numericPolicy = new JSONArray();
      for (int i = 0; i < BOARD_AREA + 1; i++) {
        numericPolicy.put(0.0);
      }
      numericPolicy.put(0, 0.9);
      numericPolicy.put(1, 0.1);

      assertEquals(
          "B3",
          HumanSlAnalysisRunner.samplePolicyMove(
              numericPolicy, BOARD_SIZE, BOARD_SIZE, stones, 0.0, false));
    }
  }

  @Test
  void argmaxPolicyMove_picksHighestProbabilityForEachPolicyShape() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      JSONObject objectPolicy = new JSONObject().put("A3", 0.1).put("B2", 0.7).put("C1", 0.2);
      assertEquals(
          "B2", HumanSlAnalysisRunner.argmaxPolicyMove(objectPolicy, BOARD_SIZE, BOARD_SIZE));

      JSONArray pairPolicy =
          new JSONArray()
              .put(new JSONArray().put("A3").put(0.2))
              .put(new JSONArray().put("C1").put(0.8));
      assertEquals(
          "C1", HumanSlAnalysisRunner.argmaxPolicyMove(pairPolicy, BOARD_SIZE, BOARD_SIZE));

      JSONArray numericPolicy = new JSONArray();
      for (int i = 0; i < BOARD_AREA + 1; i++) {
        numericPolicy.put(0.0);
      }
      numericPolicy.put(Board.getIndex(0, 0), 0.9);
      assertEquals(
          "A3", HumanSlAnalysisRunner.argmaxPolicyMove(numericPolicy, BOARD_SIZE, BOARD_SIZE));

      JSONArray passPolicy = new JSONArray();
      for (int i = 0; i < BOARD_AREA + 1; i++) {
        passPolicy.put(0.0);
      }
      passPolicy.put(BOARD_AREA, 0.95);
      assertEquals(
          "pass", HumanSlAnalysisRunner.argmaxPolicyMove(passPolicy, BOARD_SIZE, BOARD_SIZE));
    }
  }

  @Test
  void bestHumanMove_ignoresSearchPassGuardBeforeEndgame() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      FakeProcess process =
          new FakeProcess(
              request -> {
                JSONObject policy = new JSONObject().put("A3", 0.1).put("B2", 0.8);
                if (request.has("allowMoves")) {
                  return new JSONObject()
                      .put("id", request.getString("id"))
                      .put("humanPolicy", policy)
                      .put(
                          "moveInfos",
                          new JSONArray()
                              .put(
                                  new JSONObject()
                                      .put("move", "B2")
                                      .put("order", 0)
                                      .put("utility", 0.50))
                              .put(
                                  new JSONObject()
                                      .put("move", "A3")
                                      .put("order", 1)
                                      .put("utility", 0.45)));
                }
                return new JSONObject()
                    .put("id", request.getString("id"))
                    .put("rootInfo", new JSONObject().put("humanPolicy", policy))
                    .put(
                        "moveInfos",
                        new JSONArray().put(new JSONObject().put("move", "pass").put("order", 0)));
              });
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);

      java.util.Optional<String> best =
          runner.bestHumanMove(history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1));

      assertTrue(best.isPresent());
      assertFalse("pass".equals(best.get()));
      assertEquals(2, process.sentRequests.size());
      assertEquals(1, process.sentRequests.get(0).getInt("maxVisits"));
      assertEquals(64, process.sentRequests.get(1).getInt("maxVisits"));
      assertTrue(process.sentRequests.get(1).has("allowMoves"));
      assertFalse(process.sentRequests.get(0).has("maxTime"));
      assertFalse(
          process
              .sentRequests
              .get(0)
              .getJSONObject("overrideSettings")
              .getBoolean("ignorePreRootHistory"));
      runner.close();
    }
  }

  @Test
  void bestHumanMove_doesNotInventAMoveFromAnEmptyPolicy() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      JSONArray emptyPolicy = new JSONArray();
      for (int i = 0; i < BOARD_AREA + 1; i++) {
        emptyPolicy.put(0.0);
      }
      FakeProcess process =
          new FakeProcess(
              request ->
                  new JSONObject()
                      .put("id", request.getString("id"))
                      .put("humanPolicy", emptyPolicy)
                      .put("moveInfos", new JSONArray()));
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);

      java.util.Optional<String> best =
          runner.bestHumanMove(history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1));

      assertTrue(best.isEmpty());
      runner.close();
    }
  }

  @Test
  void bestHumanMove_acceptsPassOnlyWhenEndgameSearchSelectsIt() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = endgameHistory();
      boardWithHistory(history);
      FakeProcess process =
          new FakeProcess(
              request ->
                  new JSONObject()
                      .put("id", request.getString("id"))
                      .put("humanPolicy", new JSONObject().put("C3", 0.9).put("pass", 0.1))
                      .put(
                          "moveInfos",
                          new JSONArray()
                              .put(
                                  new JSONObject()
                                      .put("move", "pass")
                                      .put("order", 0)
                                      .put("utility", 0.8))));
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);

      java.util.Optional<String> best =
          runner.bestHumanMove(history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1));

      assertEquals(java.util.Optional.of("pass"), best);
      assertEquals(2, process.sentRequests.size());
      assertEquals(1, process.sentRequests.get(0).getInt("maxVisits"));
      assertEquals(64, process.sentRequests.get(1).getInt("maxVisits"));
      runner.close();
    }
  }

  @Test
  void bestHumanMove_excludesPolicyPassWhenSearchPrefersARealMove() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = endgameHistory();
      boardWithHistory(history);
      FakeProcess process =
          new FakeProcess(
              request ->
                  new JSONObject()
                      .put("id", request.getString("id"))
                      .put("humanPolicy", new JSONObject().put("C3", 0.1).put("pass", 100.0))
                      .put(
                          "moveInfos",
                          new JSONArray()
                              .put(
                                  new JSONObject()
                                      .put("move", "C3")
                                      .put("order", 0)
                                      .put("utility", 0.5))));
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);

      java.util.Optional<String> best =
          runner.bestHumanMove(history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1));

      assertEquals(java.util.Optional.of("C3"), best);
      runner.close();
    }
  }

  @Test
  void bestHumanMove_deepensVolatileCandidatesAndRejectsTheTacticalBlunder() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      FakeProcess process =
          new FakeProcess(
              request -> {
                JSONObject response =
                    new JSONObject()
                        .put("id", request.getString("id"))
                        .put("humanPolicy", new JSONObject().put("A3", 0.45).put("B2", 0.55));
                int visits = request.getInt("maxVisits");
                if (visits == 1) {
                  return response.put(
                      "moveInfos",
                      new JSONArray()
                          .put(
                              new JSONObject()
                                  .put("move", "A3")
                                  .put("order", 0)
                                  .put("utility", 0.8)));
                }
                double badUtility = visits >= 512 ? -0.12 : 0.20;
                return response.put(
                    "moveInfos",
                    new JSONArray()
                        .put(
                            new JSONObject().put("move", "A3").put("order", 0).put("utility", 0.80))
                        .put(
                            new JSONObject()
                                .put("move", "B2")
                                .put("order", 1)
                                .put("utility", badUtility)));
              });
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);

      assertEquals(
          java.util.Optional.of("A3"),
          runner.bestHumanMove(
              history.getCurrentHistoryNode(), "rank_7d", 256, 2, Duration.ofSeconds(10)));

      assertEquals(3, process.sentRequests.size());
      assertEquals(1, process.sentRequests.get(0).getInt("maxVisits"));
      assertEquals(256, process.sentRequests.get(1).getInt("maxVisits"));
      assertEquals(4_096, process.sentRequests.get(2).getInt("maxVisits"));
      for (int index = 1; index < process.sentRequests.size(); index++) {
        JSONObject request = process.sentRequests.get(index);
        assertTrue(request.has("allowMoves"));
        assertEquals(
            2.0,
            request.getJSONObject("overrideSettings").getDouble("humanSLCpuctPermanent"),
            0.0001);
        assertEquals(
            List.of("B2", "A3"),
            request.getJSONArray("allowMoves").getJSONObject(0).getJSONArray("moves").toList());
      }
      runner.close();
    }
  }

  @Test
  void verifyReady_requiresARealHumanSlResponse() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      FakeProcess process =
          new FakeProcess(
              request ->
                  new JSONObject()
                      .put("id", request.getString("id"))
                      .put("humanPolicy", new JSONObject().put("A3", 1.0)));
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);
      List<HumanSlAnalysisRunner.StartupStage> stages =
          java.util.Collections.synchronizedList(
              new ArrayList<HumanSlAnalysisRunner.StartupStage>());
      runner.setStartupListener((stage, detail) -> stages.add(stage));

      assertTrue(
          runner.verifyReady(history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1)));
      assertEquals(1, process.sentRequests.size());
      assertEquals(1, process.sentRequests.get(0).getInt("maxVisits"));
      assertTrue(stages.contains(HumanSlAnalysisRunner.StartupStage.READY));
      runner.close();
    }
  }

  @Test
  void verifyReady_rejectsAProcessThatNeverAnswers() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      FakeProcess process = new FakeProcess(request -> null);
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);

      assertFalse(
          runner.verifyReady(history.getCurrentHistoryNode(), "rank_3k", Duration.ofMillis(40)));
      assertFalse(runner.isStarted());
      assertTrue(runner.getUnavailableReason().contains("Timed out"));
      runner.close();
    }
  }

  @Test
  void startupProgressClassifiesKatagoModelAndGpuStages() {
    assertEquals(
        HumanSlAnalysisRunner.StartupStage.LOADING_MODELS,
        HumanSlAnalysisRunner.startupStageForLine("Analysis Engine starting..."));
    assertEquals(
        HumanSlAnalysisRunner.StartupStage.OPTIMIZING_GPU,
        HumanSlAnalysisRunner.startupStageForLine(
            "Initializing (may take a long time) TensorRT backend"));
    assertEquals(
        HumanSlAnalysisRunner.StartupStage.CACHE_READY,
        HumanSlAnalysisRunner.startupStageForLine("Saved new timing cache"));
    assertEquals(
        HumanSlAnalysisRunner.StartupStage.CACHE_READY,
        HumanSlAnalysisRunner.startupStageForLine("Started, ready to begin handling requests"));
  }

  @Test
  void bundledRuntimeCheckUsesResolvedExecutableWithSpacesAndUnicode() throws Exception {
    Path tempRoot = Files.createTempDirectory("humansl bundled 路径 ");
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime work"));
    Path engine =
        tempRoot
            .resolve("application with spaces")
            .resolve("engines")
            .resolve("katago")
            .resolve("windows-x64-nvidia50-cuda")
            .resolve("katago.exe");
    Files.createDirectories(engine.getParent());
    Files.write(engine, new byte[0]);
    Files.writeString(
        engine.getParent().resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda\n");
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    AtomicInteger launches = new AtomicInteger();
    try {
      System.setProperty("os.name", "Windows 11");
      Config config = ConfigTestHelper.createForTests(runtimeWorkDirectory);
      config.config = new JSONObject();
      config.leelazConfig = new JSONObject();
      config.uiConfig = new JSONObject();
      config.config.put("leelaz", config.leelazConfig);
      config.config.put("ui", config.uiConfig);
      Lizzie.config = config;
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(
              List.of(engine.toString(), "analysis"),
              ignored -> {
                launches.incrementAndGet();
                return new FakeProcess(request -> null);
              });

      assertFalse(runner.start());
      assertEquals(0, launches.get());
      assertTrue(runner.getUnavailableReason().contains("NVRTC"));
    } finally {
      if (previousOsName == null) {
        System.clearProperty("os.name");
      } else {
        System.setProperty("os.name", previousOsName);
      }
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void readinessTimeoutIncludesRecentKatagoDiagnosticsAndReportsProgress() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      FakeProcess process = new FakeProcess(request -> null);
      List<HumanSlAnalysisRunner.StartupStage> stages =
          java.util.Collections.synchronizedList(
              new ArrayList<HumanSlAnalysisRunner.StartupStage>());
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(List.of("katago", "analysis"), ignored -> process);
      runner.setStartupListener((stage, detail) -> stages.add(stage));

      assertTrue(runner.start());
      process.emitLine("Initializing (may take a long time) TensorRT backend");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (!stages.contains(HumanSlAnalysisRunner.StartupStage.OPTIMIZING_GPU)
          && System.nanoTime() < deadline) {
        Thread.sleep(5L);
      }

      assertFalse(
          runner.verifyReady(history.getCurrentHistoryNode(), "rank_3k", Duration.ofMillis(40)));
      assertTrue(stages.contains(HumanSlAnalysisRunner.StartupStage.STARTING));
      assertTrue(stages.contains(HumanSlAnalysisRunner.StartupStage.OPTIMIZING_GPU));
      assertTrue(runner.getUnavailableReason().contains("Timed out"));
      assertTrue(runner.getUnavailableReason().contains("Initializing (may take a long time)"));
      runner.close();
    }
  }

  @Test
  void cancelActiveRequests_unblocksAndAllowsCleanRestart() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      FakeProcess stalled = new FakeProcess(request -> null);
      FakeProcess recovered =
          new FakeProcess(
              request ->
                  new JSONObject()
                      .put("id", request.getString("id"))
                      .put("humanPolicy", new JSONObject().put("B2", 1.0))
                      .put(
                          "moveInfos",
                          new JSONArray().put(new JSONObject().put("move", "B2").put("order", 0))));
      AtomicInteger launches = new AtomicInteger();
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(
              List.of("katago", "analysis"),
              ignored -> launches.getAndIncrement() == 0 ? stalled : recovered);
      ExecutorService worker = Executors.newSingleThreadExecutor();
      try {
        Future<java.util.Optional<String>> blocked =
            worker.submit(
                () ->
                    runner.bestHumanMove(
                        history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(30)));
        waitForRequest(stalled, 1, 1, TimeUnit.SECONDS);

        runner.cancelActiveRequests();

        assertTrue(blocked.get(1, TimeUnit.SECONDS).isEmpty());
        assertEquals(
            java.util.Optional.of("B2"),
            runner.bestHumanMove(
                history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1)));
        assertEquals(2, launches.get());
      } finally {
        worker.shutdownNow();
        runner.close();
      }
    }
  }

  @Test
  void staleCancelledRequestCannotStopReplacementEngine() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      CountDownLatch oldRequestEntered = new CountDownLatch(1);
      CountDownLatch releaseOldRequest = new CountDownLatch(1);
      FakeProcess stalled =
          new FakeProcess(
              request -> {
                oldRequestEntered.countDown();
                try {
                  releaseOldRequest.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IOException("Interrupted stale HumanSL request.", e);
                }
                return null;
              });
      FakeProcess recovered =
          new FakeProcess(
              request ->
                  new JSONObject()
                      .put("id", request.getString("id"))
                      .put("humanPolicy", new JSONObject().put("B2", 1.0))
                      .put(
                          "moveInfos",
                          new JSONArray().put(new JSONObject().put("move", "B2").put("order", 0))));
      AtomicInteger launches = new AtomicInteger();
      HumanSlAnalysisRunner runner =
          new HumanSlAnalysisRunner(
              List.of("katago", "analysis"),
              ignored -> launches.getAndIncrement() == 0 ? stalled : recovered);
      ExecutorService worker = Executors.newSingleThreadExecutor();
      try {
        Future<java.util.Optional<String>> staleRequest =
            worker.submit(
                () ->
                    runner.bestHumanMove(
                        history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(30)));
        assertTrue(oldRequestEntered.await(1, TimeUnit.SECONDS));

        runner.cancelActiveRequests();
        assertEquals(
            java.util.Optional.of("B2"),
            runner.bestHumanMove(
                history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1)));

        releaseOldRequest.countDown();
        assertTrue(staleRequest.get(1, TimeUnit.SECONDS).isEmpty());
        assertTrue(runner.isStarted());
        assertEquals(
            java.util.Optional.of("B2"),
            runner.bestHumanMove(
                history.getCurrentHistoryNode(), "rank_3k", Duration.ofSeconds(1)));
        assertEquals(2, launches.get());
      } finally {
        releaseOldRequest.countDown();
        worker.shutdownNow();
        runner.close();
      }
    }
  }

  @Test
  void close_isPermanentAndDoesNotRelaunchTheEngine() throws Exception {
    AtomicInteger launches = new AtomicInteger();
    HumanSlAnalysisRunner runner =
        new HumanSlAnalysisRunner(
            List.of("katago", "analysis"),
            ignored -> {
              launches.incrementAndGet();
              return new FakeProcess(request -> null);
            });

    assertTrue(runner.start());
    runner.close();

    assertFalse(runner.start());
    assertEquals(1, launches.get());
  }

  private static void waitForRequest(FakeProcess process, int expected, long timeout, TimeUnit unit)
      throws Exception {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      synchronized (process.sentRequests) {
        if (process.sentRequests.size() >= expected) {
          return;
        }
      }
      Thread.sleep(5L);
    }
    throw new AssertionError("HumanSL request was not sent before timeout.");
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    Lizzie.board = board;
    return board;
  }

  private static BoardHistoryList endgameHistory() {
    Stone[] position = stones(placement(0, 0, Stone.BLACK));
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(position, new int[] {0, 0}, Stone.BLACK, false, 200));
    return history;
  }

  private static BoardData moveNode(
      Stone[] stones, int[] lastMove, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.move(
        stones,
        lastMove,
        color,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int i = 0; i < stones.length; i++) {
      stones[i] = Stone.EMPTY;
    }
    for (Placement placement : placements) {
      stones[Board.getIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private interface ResponseFactory {
    JSONObject response(JSONObject request) throws IOException;
  }

  private static class FakeProcess extends Process {
    private final PipedInputStream stdout;
    private final PipedOutputStream stdoutWriter;
    private final OutputStream stdin;
    private final List<JSONObject> sentRequests =
        java.util.Collections.synchronizedList(new ArrayList<JSONObject>());
    private volatile boolean alive = true;

    private FakeProcess(ResponseFactory responseFactory) throws IOException {
      this.stdout = new PipedInputStream();
      this.stdoutWriter = new PipedOutputStream(stdout);
      this.stdin =
          new OutputStream() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) throws IOException {
              if (b == '\n') {
                handleLine(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
              } else {
                buffer.write(b);
              }
            }

            private void handleLine(String line) throws IOException {
              JSONObject request = new JSONObject(line);
              sentRequests.add(request);
              JSONObject response = responseFactory.response(request);
              if (response != null) {
                stdoutWriter.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                stdoutWriter.flush();
              }
            }
          };
    }

    private synchronized void emitLine(String line) throws IOException {
      stdoutWriter.write(((line == null ? "" : line) + "\n").getBytes(StandardCharsets.UTF_8));
      stdoutWriter.flush();
    }

    @Override
    public OutputStream getOutputStream() {
      return stdin;
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() throws InterruptedException {
      alive = false;
      return 0;
    }

    @Override
    public int exitValue() {
      return alive ? 0 : 0;
    }

    @Override
    public void destroy() {
      alive = false;
    }

    @Override
    public Process destroyForcibly() {
      destroy();
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }
  }

  private static final class DelayedExitProcess extends FakeProcess {
    private final CountDownLatch destroyRequested = new CountDownLatch(1);
    private final CountDownLatch allowExit = new CountDownLatch(1);
    private volatile boolean exited;

    private DelayedExitProcess(ResponseFactory responseFactory) throws IOException {
      super(responseFactory);
    }

    @Override
    public Process destroyForcibly() {
      destroyRequested.countDown();
      return this;
    }

    @Override
    public int waitFor() throws InterruptedException {
      allowExit.await();
      exited = true;
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (!allowExit.await(timeout, unit)) {
        return false;
      }
      exited = true;
      return true;
    }

    @Override
    public int exitValue() {
      if (!exited) {
        throw new IllegalThreadStateException("process is still running");
      }
      return 0;
    }

    @Override
    public boolean isAlive() {
      return !exited;
    }
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone color;

    private Placement(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.analysisUseCurrentRules = false;
      config.analysisSpecificRules = "";
      config.currentKataGoRules = "";
      config.autoLoadKataRules = false;
      config.kataRules = "";
      config.readKomi = true;
      Lizzie.config = config;
      return new TestEnvironment(
          previousBoardWidth, previousBoardHeight, previousConfig, previousBoard);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE;

    static {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        UNSAFE = (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
