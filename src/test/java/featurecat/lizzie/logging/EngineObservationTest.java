package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import featurecat.lizzie.analysis.EngineStartupDiagnostics;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class EngineObservationTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void withoutRuntimeDoesNotEmitEvenWhenLoggersAreEnabled() {
    LoggingRuntime.resetForTests();
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    Logger gtp = (Logger) LoggerFactory.getLogger(LogCategories.GTP);
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    engine.setLevel(Level.DEBUG);
    gtp.setLevel(Level.DEBUG);
    trace.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> engineEvents = attach(engine);
    ListAppender<ILoggingEvent> gtpEvents = attach(gtp);
    ListAppender<ILoggingEvent> traceEvents = attach(trace);

    assertFalse(EngineObservation.engineDiagnosticsEnabled());
    assertFalse(EngineObservation.gtpDiagnosticsEnabled());
    assertFalse(EngineObservation.traceEnabled());

    EngineObservation.recordStarted("eng-1", "MAIN_BOARD");
    EngineObservation.recordBootstrap("eng-1", EngineBootstrapFacts.unknown("MAIN_BOARD"));
    EngineObservation.recordQueue("eng-1", 1, 1);
    EngineObservation.recordCommandSent("eng-1", "cmd-1", "play", 0, 1);
    EngineObservation.recordProbeStarted("eng-1");
    EngineObservation.recordProbeCapabilityCheck("eng-1", true);
    EngineObservation.recordProbeFailed("eng-1", "exited");
    EngineObservation.recordProbeStderr("eng-1", "cuda failed");
    EngineObservation.traceRawCommand("eng-1", "cmd-1", "play B D4");

    assertTrue(engineEvents.list.isEmpty(), engineEvents.list.toString());
    assertTrue(gtpEvents.list.isEmpty(), gtpEvents.list.toString());
    assertTrue(traceEvents.list.isEmpty(), traceEvents.list.toString());
  }

  @Test
  void initializedRuntimeWithoutFullTraceDoesNotEmitRawCommands() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    ListAppender<ILoggingEvent> traceEvents = attach(trace);

    assertFalse(EngineObservation.traceEnabled());
    EngineObservation.traceRawCommand("eng-1", "cmd-1", "play B D4");
    runtime.awaitIdle();

    assertTrue(traceEvents.list.isEmpty(), traceEvents.list.toString());
  }

  @Test
  void rawTraceEventIsUtf8BoundedBeforeItReachesTheAppender() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    ListAppender<ILoggingEvent> traceEvents = attach(trace);

    EngineObservation.traceRawStream("eng-1", "cmd-1", "棋😀".repeat(10_000));

    assertEquals(1, traceEvents.list.size(), traceEvents.list.toString());
    String message = traceEvents.list.get(0).getFormattedMessage();
    String payload = message.substring(message.indexOf('=') + 1);
    assertTrue(
        payload.getBytes(StandardCharsets.UTF_8).length <= ObservationText.RAW_EVENT_MAX_UTF8_BYTES,
        Integer.toString(payload.getBytes(StandardCharsets.UTF_8).length));
    assertTrue(payload.endsWith(" [truncated]"), payload);
  }

  @Test
  void transportFailureUsesBoundedStructuredDimensions() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    EngineObservation.recordTransportFailure(
        "eng-1", "stdout", "io-error", new java.io.IOException("secret raw payload"));

    assertEquals(1, events.list.size(), events.list.toString());
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("event=transport-failure"), message);
    assertTrue(message.contains("stream=stdout"), message);
    assertTrue(message.contains("reason=io-error"), message);
    assertTrue(message.contains("errorType=IOException"), message);
    assertFalse(message.contains("secret raw payload"), message);
  }

  @Test
  void bootstrapUsesStructuredFieldsAndOmitsUnknownStages() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    EngineBootstrapFacts facts =
        EngineBootstrapFacts.fromCommand(
            "\"C:\\\\Users\\\\Player\\\\katago.exe\" gtp -model model.bin.gz", "MAIN_BOARD");
    EngineObservation.recordBootstrap("eng-bootstrap", facts);

    assertEquals(1, events.list.size(), events.list.toString());
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("event=bootstrap"), message);
    assertTrue(message.contains("engineType=katago"), message);
    assertTrue(message.contains("purpose=MAIN_BOARD"), message);
    assertTrue(message.contains("source=user-configured"), message);
    assertTrue(message.contains("backend=unknown"), message);
    assertTrue(message.contains("onnxProvider=unknown"), message);
    assertTrue(message.contains("model=model.bin.gz"), message);
    assertFalse(message.contains("Player"), message);
    assertFalse(message.contains("process-started="), message);
  }

  @Test
  void readyAndFailedReuseTheSameIdentityAsBootstrap() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    Object owner = new Object();
    String id =
        EngineObservation.ensureStarted(
            owner, "MAIN_BOARD", EngineBootstrapFacts.fromCommand("katago gtp", "MAIN_BOARD"));
    EngineObservation.markStartupStage(id, EngineObservation.STAGE_PROCESS_STARTED);
    EngineObservation.recordReady(id);

    assertEquals(id, EngineObservation.identityFor(owner));
    String readyLogs = formatted(events);
    assertTrue(readyLogs.contains("event=bootstrap"), readyLogs);
    assertTrue(readyLogs.contains("event=started"), readyLogs);
    assertTrue(readyLogs.contains("event=ready"), readyLogs);
    assertTrue(readyLogs.contains("process-started="), readyLogs);

    events.list.clear();
    EngineObservation.discardIdentity(owner);
    String failedId = EngineObservation.mintIdentity(owner);
    EngineObservation.recordBootstrap(
        failedId, EngineBootstrapFacts.fromCommand("katago gtp", "MAIN_BOARD"));
    EngineObservation.recordFailed(failedId, "process start failed");
    String failedLogs = formatted(events);
    assertTrue(failedLogs.contains("event=bootstrap"), failedLogs);
    assertTrue(failedLogs.contains("event=failed reason=process start failed"), failedLogs);
    assertFalse(failedLogs.contains("event=started"), failedLogs);
  }

  @Test
  void probeEventsStayVisibleWithoutEngineDiagnosticsDebugAndBoundStderr() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    EngineObservation.recordRecentStderr("eng-1", "debug-only-stderr");
    EngineObservation.recordProbeStarted("eng-1");
    EngineObservation.recordProbeCapabilityCheck("eng-1", false);
    EngineObservation.recordProbeFailed("eng-1", "exited");
    EngineObservation.recordProbeFailed("eng-1", "free-form exception text");
    EngineObservation.recordProbeStderr("eng-1", "x".repeat(100_000));

    String logs = formatted(events);
    assertFalse(logs.contains("debug-only-stderr"), logs);
    assertFalse(logs.contains("engine event=stderr"), logs);
    assertTrue(logs.contains("probe event=started"), logs);
    assertTrue(logs.contains("probe event=capability-check outcome=failure"), logs);
    assertTrue(logs.contains("probe event=failed stage=exited"), logs);
    assertTrue(logs.contains("probe event=failed stage=unknown"), logs);
    assertFalse(logs.contains("free-form exception text"), logs);
    ILoggingEvent stderrEvent = null;
    for (ILoggingEvent event : events.list) {
      if (event.getFormattedMessage().contains("probe event=failed stage=exited")) {
        assertEquals(Level.WARN, event.getLevel(), event.getFormattedMessage());
      }
      if (event.getFormattedMessage().contains("probe event=stderr facts=")) {
        stderrEvent = event;
      }
    }
    assertTrue(stderrEvent != null, logs);
    assertEquals(Level.WARN, stderrEvent.getLevel());
    String stderrMessage = stderrEvent.getFormattedMessage();
    String facts = stderrMessage.substring(stderrMessage.indexOf("facts=") + "facts=".length());
    assertTrue(
        facts.getBytes(StandardCharsets.UTF_8).length <= ObservationText.RAW_EVENT_MAX_UTF8_BYTES,
        Integer.toString(facts.getBytes(StandardCharsets.UTF_8).length));
    assertTrue(facts.endsWith(" [truncated]"), facts);
  }

  @Test
  void bootstrapIsRecordedOncePerIdentity() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    String id = EngineObservation.mintIdentity(new Object());
    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand("katago gtp", "MAIN_BOARD");
    EngineObservation.recordBootstrap(id, facts);
    EngineObservation.recordBootstrap(id, facts);

    assertEquals(1, events.list.size(), events.list.toString());
    assertTrue(events.list.get(0).getFormattedMessage().contains("event=bootstrap"));
  }

  @Test
  void analysisCacheDecisionUsesEngineTraceWhenFullTraceIsOn() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    ListAppender<ILoggingEvent> events = attach(trace);

    EngineObservation.traceAnalysisCacheDecision(
        null,
        206,
        1842L,
        true,
        "KataGo",
        10621,
        99.87,
        30.6,
        10555,
        0.12,
        -27.1,
        "ACCEPT",
        "HIGHER_VISITS");
    runtime.awaitIdle();

    assertEquals(1, events.list.size(), formatted(events));
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("analysis-cache "), message);
    assertTrue(message.contains("nodeMove=206"), message);
    assertTrue(message.contains("boardRevision=1842"), message);
    assertTrue(message.contains("blackToPlay=true"), message);
    assertTrue(message.contains("engine=KataGo"), message);
    assertTrue(message.contains("incomingVisits=10621"), message);
    assertTrue(message.contains("incomingWinrate=99.87"), message);
    assertTrue(message.contains("incomingScoreLead=30.6"), message);
    assertTrue(message.contains("cachedVisits=10555"), message);
    assertTrue(message.contains("cachedWinrate=0.12"), message);
    assertTrue(message.contains("cachedScoreLead=-27.1"), message);
    assertTrue(message.contains("decision=ACCEPT"), message);
    assertTrue(message.contains("reason=HIGHER_VISITS"), message);
    String persisted =
        Files.readString(tempDir.resolve("logs/engine-trace.log"), StandardCharsets.UTF_8);
    assertTrue(persisted.contains("analysis-cache "), persisted);
    assertTrue(persisted.contains("reason=HIGHER_VISITS"), persisted);
  }

  @Test
  void analysisCacheDecisionIsSkippedWhenFullTraceIsOffEvenIfTraceLoggerIsInfo() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    trace.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> events = attach(trace);

    assertFalse(EngineObservation.traceEnabled());
    EngineObservation.traceAnalysisCacheDecision(
        null,
        206,
        1842L,
        true,
        "KataGo",
        868,
        99.91,
        30.4,
        10555,
        0.12,
        -27.1,
        "REJECT",
        "LOWER_VISITS");

    assertTrue(events.list.isEmpty(), formatted(events));
  }

  @Test
  void analysisCacheDecisionSanitizesUnknownReasonTokens() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    ListAppender<ILoggingEvent> events = attach(trace);

    EngineObservation.traceAnalysisCacheDecision(
        null, 1, 1L, false, "KataGo", 1, 50.0, 0.0, 1, 50.0, 0.0, "REJECT", "not-a-real-reason");

    assertEquals(1, events.list.size(), formatted(events));
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("reason=unknown"), message);
    assertFalse(message.contains("not-a-real-reason"), message);
  }

  @Test
  void startupDiagnosticLogsWarnCoreForEachNoFindingsTerminalReasonAndOutcome() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    var policy = new EngineStartupDiagnostics.Policy(1, 1, 100, 2, 128, 4096, 10, 8192);
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(policy, EngineObservation::recordStartupDiagnostic)) {

      // 1. not-applicable: preflight failure before process creation
      events.list.clear();
      var naAttempt = service.begin("eng-na", "MAIN_BOARD", List.of("engine"), true);
      naAttempt.fail("runtime-preflight", "missing executable");
      assertEquals(1, events.list.size());
      ILoggingEvent naEvent = events.list.get(0);
      assertEquals(Level.WARN, naEvent.getLevel());
      assertTrue(naEvent.getFormattedMessage().contains("engine event=startup-failed"));
      JSONObject naCore = extractCore(naEvent);
      assertEquals("eng-na", naCore.getString("engineId"));
      assertEquals(naAttempt.id(), naCore.getString("attemptId"));
      assertEquals(1L, naCore.getLong("diagnosticRevision"));
      assertEquals("not-applicable", naCore.getString("outcome"));
      assertEquals(
          "not-applicable",
          naCore
              .getJSONObject("sources")
              .getJSONObject("pe-import-scan")
              .getString("applicability"));

      // 2. no-specific-dll-identified: normal completion with no findings
      events.list.clear();
      var normAttempt = service.begin("eng-norm", "MAIN_BOARD", List.of("engine"), true);
      normAttempt.fail("startup-exit", "clean exit");
      normAttempt.collect(
          "runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "all clean"));
      awaitSettled(normAttempt);
      ILoggingEvent normEvent = lastEventWithEngineId(events, "eng-norm");
      assertEquals(Level.WARN, normEvent.getLevel());
      assertTrue(normEvent.getFormattedMessage().contains("engine event=dependency-diagnostic"));
      JSONObject normCore = extractCore(normEvent);
      assertEquals("eng-norm", normCore.getString("engineId"));
      assertEquals(normAttempt.id(), normCore.getString("attemptId"));
      assertTrue(normCore.getLong("diagnosticRevision") > 1L);
      assertEquals("no-specific-dll-identified", normCore.getString("outcome"));
      assertEquals(
          "completed",
          normCore.getJSONObject("sources").getJSONObject("runtime").getString("collectionState"));

      // 3. timeout: collector times out against deadline
      events.list.clear();
      var timeoutAttempt = service.begin("eng-timeout", "MAIN_BOARD", List.of("engine"), true);
      timeoutAttempt.fail("startup-exit", "exit code 1");
      timeoutAttempt.collect(
          "runtime",
          () -> {
            try {
              Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            return new EngineStartupDiagnostics.Evidence(List.of(), "late");
          });
      awaitSettled(timeoutAttempt);
      ILoggingEvent timeoutEvent = lastEventWithEngineId(events, "eng-timeout");
      assertEquals(Level.WARN, timeoutEvent.getLevel());
      JSONObject timeoutCore = extractCore(timeoutEvent);
      assertEquals("eng-timeout", timeoutCore.getString("engineId"));
      assertEquals("partial", timeoutCore.getString("outcome"));
      assertEquals(
          "timeout",
          timeoutCore
              .getJSONObject("sources")
              .getJSONObject("runtime")
              .getString("terminalReason"));
      assertEquals(
          "timeout",
          timeoutCore
              .getJSONObject("sources")
              .getJSONObject("runtime")
              .getString("collectionState"));

      // 4. queue-full: worker is busy, queue is full, next job is rejected
      events.list.clear();
      java.util.concurrent.CountDownLatch workerBusy = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch releaseWorker =
          new java.util.concurrent.CountDownLatch(1);
      var occupier = service.begin("eng-occ", "MAIN_BOARD", List.of("engine"), true);
      occupier.fail("runtime-preflight", "busy");
      occupier.collect(
          "runtime",
          () -> {
            workerBusy.countDown();
            while (true) {
              try {
                releaseWorker.await();
                break;
              } catch (InterruptedException ignored) {
              }
            }
            return new EngineStartupDiagnostics.Evidence(List.of(), "done");
          });
      assertTrue(workerBusy.await(1, java.util.concurrent.TimeUnit.SECONDS));

      var queuedAttempt = service.begin("eng-queued", "MAIN_BOARD", List.of("engine"), true);
      queuedAttempt.fail("process-create", "fail-q");
      queuedAttempt.collect("runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "q"));

      var qfullAttempt = service.begin("eng-qfull", "MAIN_BOARD", List.of("engine"), true);
      qfullAttempt.fail("process-create", "fail-qfull");
      qfullAttempt.collect(
          "runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "qfull"));

      ILoggingEvent qfullEvent = lastEventWithEngineId(events, "eng-qfull");
      assertEquals(Level.WARN, qfullEvent.getLevel());
      JSONObject qfullCore = extractCore(qfullEvent);
      assertEquals("eng-qfull", qfullCore.getString("engineId"));
      assertEquals("partial", qfullCore.getString("outcome"));
      assertEquals(
          "queue-full",
          qfullCore.getJSONObject("sources").getJSONObject("runtime").getString("terminalReason"));
      assertEquals(
          "rejected",
          qfullCore.getJSONObject("sources").getJSONObject("runtime").getString("collectionState"));

      releaseWorker.countDown();

      // 5. cancelled: attempt is cancelled before completion
      events.list.clear();
      var cancelAttempt = service.begin("eng-cancel", "MAIN_BOARD", List.of("engine"), true);
      cancelAttempt.fail("startup-exit", "cancelled exit");
      cancelAttempt.collect(
          "runtime",
          () -> {
            try {
              Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            return new EngineStartupDiagnostics.Evidence(List.of(), "never");
          });
      cancelAttempt.cancel();
      ILoggingEvent cancelEvent = lastEventWithEngineId(events, "eng-cancel");
      assertEquals(Level.WARN, cancelEvent.getLevel());
      JSONObject cancelCore = extractCore(cancelEvent);
      assertEquals("eng-cancel", cancelCore.getString("engineId"));
      assertEquals("partial", cancelCore.getString("outcome"));
      assertEquals(
          "cancelled",
          cancelCore.getJSONObject("sources").getJSONObject("runtime").getString("terminalReason"));
      assertEquals(
          "cancelled",
          cancelCore
              .getJSONObject("sources")
              .getJSONObject("runtime")
              .getString("collectionState"));

      // 6. read-error: collector throws exception
      events.list.clear();
      var errorAttempt = service.begin("eng-err", "MAIN_BOARD", List.of("engine"), true);
      errorAttempt.fail("startup-exit", "exit with error");
      errorAttempt.collect(
          "runtime",
          () -> {
            throw new java.io.IOException("simulated read error");
          });
      awaitSettled(errorAttempt);
      ILoggingEvent errorEvent = lastEventWithEngineId(events, "eng-err");
      assertEquals(Level.WARN, errorEvent.getLevel());
      JSONObject errorCore = extractCore(errorEvent);
      assertEquals("eng-err", errorCore.getString("engineId"));
      assertEquals("partial", errorCore.getString("outcome"));
      assertEquals(
          "read-error",
          errorCore.getJSONObject("sources").getJSONObject("runtime").getString("terminalReason"));
      assertEquals(
          "failed",
          errorCore.getJSONObject("sources").getJSONObject("runtime").getString("collectionState"));
    }
  }

  @Test
  void startupDiagnosticOversizedTailsCannotTruncateIdentityStatusOrSources() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    var policy = new EngineStartupDiagnostics.Policy(1, 1, 1000, 2, 128, 4096, 2, 8192);
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(policy, EngineObservation::recordStartupDiagnostic)) {
      var attempt =
          service.begin("eng-huge", "MAIN_BOARD", List.of("engine.exe", "x".repeat(10_000)), true);
      attempt.output("stdout", "long-stdout-line-".repeat(1000));
      attempt.output("stderr", "long-stderr-line-".repeat(1000));
      attempt.fail("startup-exit", "massive-error-text-".repeat(1000));

      ILoggingEvent event = lastEventWithEngineId(events, "eng-huge");
      assertEquals(Level.WARN, event.getLevel());
      assertTrue(event.getFormattedMessage().contains("engine event=startup-failed"));

      JSONObject core = extractCore(event);
      assertEquals("eng-huge", core.getString("engineId"));
      assertEquals(attempt.id(), core.getString("attemptId"));
      assertEquals(1L, core.getLong("diagnosticRevision"));
      assertTrue(core.getBoolean("truncated"));
      assertTrue(core.has("statusName"));
      assertTrue(core.has("errorDomain"));
      assertTrue(core.has("phase"));
      assertEquals("startup-exit", core.getString("phase"));

      JSONObject sources = core.getJSONObject("sources");
      assertTrue(sources.has("pe-import-scan"));
      assertTrue(sources.has("process-tail"));
      assertEquals(
          "not-applicable", sources.getJSONObject("process-tail").getString("applicability"));

      assertFalse(core.getString("engineId").contains("truncated"));
      assertFalse(core.getString("attemptId").contains("truncated"));
    }
  }

  @Test
  void runtimeSearchScopeStaysInCopyButNotOrdinaryWarn() throws Exception {
    LoggingRuntime runtime = LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()), LoggingLimits.production());
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    ListAppender<ILoggingEvent> events = attach((Logger) LoggerFactory.getLogger(LogCategories.ENGINE));
    try (var service = new EngineStartupDiagnostics(
        EngineStartupDiagnostics.Policy.production(), EngineObservation::recordStartupDiagnostic)) {
      var attempt = service.begin("scope-privacy", "MAIN_BOARD", List.of("engine"), true);
      attempt.fail("process-create", "cannot start");
      attempt.collect("runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(
          new EngineStartupDiagnostics.Finding("not-found-in-checked-search-scope", "cudnn64_9.dll",
              null, List.of(), "runtime-preflight", "complete", "UNLOGGED_SEARCH_CONTEXT",
              "Missing cudnn64_9.dll; token=runtime-secret", java.time.Instant.now())),
          "UNLOGGED_SEARCH_CONTEXT"));
      awaitSettled(attempt);
      String log = formatted(events);
      assertTrue(log.contains("cudnn64_9.dll"));
      assertTrue(log.contains("runtime-preflight"));
      assertFalse(log.contains("UNLOGGED_SEARCH_CONTEXT"));
      assertFalse(log.contains("runtime-secret"));
      assertTrue(attempt.snapshot().shareText().contains("UNLOGGED_SEARCH_CONTEXT"));
      assertFalse(attempt.snapshot().shareText().contains("runtime-secret"));
    }
  }

  private static JSONObject extractCore(ILoggingEvent event) {
    String msg = event.getFormattedMessage();
    int diagStart = msg.indexOf("diagnostic={");
    assertTrue(diagStart >= 0, "Missing diagnostic={ in: " + msg);
    int jsonStart = diagStart + "diagnostic=".length();
    int summaryStart = msg.indexOf(" summary={", jsonStart);
    assertTrue(summaryStart >= 0, "Missing summary={ in: " + msg);
    String jsonStr = msg.substring(jsonStart, summaryStart);
    return new JSONObject(jsonStr);
  }

  private static ILoggingEvent lastEventWithEngineId(
      ListAppender<ILoggingEvent> events, String engineId) {
    ILoggingEvent found = null;
    for (ILoggingEvent event : events.list) {
      if (event.getFormattedMessage().contains("\"engineId\":\"" + engineId + "\"")
          || event.getFormattedMessage().contains(engineId)) {
        found = event;
      }
    }
    assertTrue(found != null, "No event found for engineId: " + engineId);
    return found;
  }

  private static void awaitSettled(EngineStartupDiagnostics.Attempt attempt) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      var snap = attempt.snapshot();
      if (snap != null && !snap.toJson().getString("outcome").equals("collecting")) {
        return;
      }
      Thread.sleep(5);
    }
    org.junit.jupiter.api.Assertions.fail("Attempt did not settle");
  }

  private static String formatted(ListAppender<ILoggingEvent> events) {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : events.list) {
      text.append(event.getFormattedMessage()).append('\n');
    }
    return text.toString();
  }

  private static ListAppender<ILoggingEvent> attach(Logger logger) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }
}
