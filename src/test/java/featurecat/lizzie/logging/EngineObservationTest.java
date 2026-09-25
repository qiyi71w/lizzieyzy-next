package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import featurecat.lizzie.analysis.EngineStartupDiagnostic;
import featurecat.lizzie.analysis.EngineStartupDiagnostics;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    var policy = new EngineStartupDiagnostics.Policy(1, 150, 10, 1024, 8192);
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(policy, EngineObservation::recordStartupDiagnostic)) {

      // 1. not-applicable / immediate preflight completion before process creation
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
      assertEquals("no-specific-dll-identified", naCore.getString("outcome"));
      assertEquals("completed", naCore.getString("collectionState"));
      assertEquals(
          "completed",
          naCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("collectionState"));
      assertEquals(
          "",
          naCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));

      // 2. normal completion with process tail and no findings
      events.list.clear();
      var normAttempt = service.begin("eng-norm", "MAIN_BOARD", List.of("engine"), true);
      ControllableProcess normProc = new ControllableProcess();
      normAttempt.attachProcess(normProc);
      normAttempt.output("stdout", "clean exit");
      normAttempt.fail("startup-exit", "clean exit");
      ILoggingEvent normInitEvent = lastEventWithEngineId(events, "eng-norm");
      assertEquals(Level.WARN, normInitEvent.getLevel());
      assertTrue(normInitEvent.getFormattedMessage().contains("engine event=startup-failed"));
      JSONObject normInitCore = extractCore(normInitEvent);
      assertEquals("eng-norm", normInitCore.getString("engineId"));
      assertEquals(normAttempt.id(), normInitCore.getString("attemptId"));
      assertEquals("collecting", normInitCore.getString("collectionState"));
      assertEquals("collecting", normInitCore.getString("outcome"));

      normAttempt.streamEnded("stdout", null);
      normAttempt.streamEnded("stderr", null);
      normProc.finish(0);
      awaitSettled(normAttempt);

      ILoggingEvent normEvent = lastEventWithEngineId(events, "eng-norm");
      assertEquals(Level.WARN, normEvent.getLevel());
      assertTrue(normEvent.getFormattedMessage().contains("engine event=startup-failed"));
      JSONObject normCore = extractCore(normEvent);
      assertEquals("eng-norm", normCore.getString("engineId"));
      assertEquals(normAttempt.id(), normCore.getString("attemptId"));
      assertEquals("no-specific-dll-identified", normCore.getString("outcome"));
      assertEquals("completed", normCore.getString("collectionState"));
      assertEquals(
          "completed",
          normCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("collectionState"));
      assertEquals(
          "",
          normCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));

      // 3. timeout: tail times out against deadline
      events.list.clear();
      var timeoutAttempt = service.begin("eng-timeout", "MAIN_BOARD", List.of("engine"), true);
      ControllableProcess timeoutProc = new ControllableProcess();
      timeoutAttempt.attachProcess(timeoutProc);
      timeoutAttempt.fail("startup-exit", "exit code 1");
      awaitSettled(timeoutAttempt);
      ILoggingEvent timeoutEvent = lastEventWithEngineId(events, "eng-timeout");
      assertEquals(Level.WARN, timeoutEvent.getLevel());
      JSONObject timeoutCore = extractCore(timeoutEvent);
      assertEquals("eng-timeout", timeoutCore.getString("engineId"));
      assertEquals("no-specific-dll-identified", timeoutCore.getString("outcome"));
      assertEquals("partial", timeoutCore.getString("collectionState"));
      assertEquals(
          "timeout",
          timeoutCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));
      assertEquals(
          "partial",
          timeoutCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("collectionState"));

      // 4. queue-full: pending failure slots exhausted
      events.list.clear();
      var occupier = service.begin("eng-occ", "MAIN_BOARD", List.of("engine"), true);
      ControllableProcess occProc = new ControllableProcess();
      occupier.attachProcess(occProc);
      occupier.fail("runtime-preflight", "busy");

      var qfullAttempt = service.begin("eng-qfull", "MAIN_BOARD", List.of("engine"), true);
      ControllableProcess qfullProc = new ControllableProcess();
      qfullAttempt.attachProcess(qfullProc);
      qfullAttempt.fail("process-create", "fail-qfull");

      ILoggingEvent qfullEvent = lastEventWithEngineId(events, "eng-qfull");
      assertEquals(Level.WARN, qfullEvent.getLevel());
      JSONObject qfullCore = extractCore(qfullEvent);
      assertEquals("eng-qfull", qfullCore.getString("engineId"));
      assertEquals("no-specific-dll-identified", qfullCore.getString("outcome"));
      assertEquals("partial", qfullCore.getString("collectionState"));
      assertEquals(
          "queue-full",
          qfullCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));
      assertEquals(
          "partial",
          qfullCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("collectionState"));

      occProc.finish(0);
      occupier.cancel();

      // 5. cancelled: attempt is cancelled before completion
      events.list.clear();
      var cancelAttempt = service.begin("eng-cancel", "MAIN_BOARD", List.of("engine"), true);
      ControllableProcess cancelProc = new ControllableProcess();
      cancelAttempt.attachProcess(cancelProc);
      cancelAttempt.fail("startup-exit", "cancelled exit");
      cancelAttempt.cancel();

      ILoggingEvent cancelEvent = lastEventWithEngineId(events, "eng-cancel");
      assertEquals(Level.WARN, cancelEvent.getLevel());
      JSONObject cancelCore = extractCore(cancelEvent);
      assertEquals("eng-cancel", cancelCore.getString("engineId"));
      assertEquals("no-specific-dll-identified", cancelCore.getString("outcome"));
      assertEquals("partial", cancelCore.getString("collectionState"));
      assertEquals(
          "cancelled",
          cancelCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));
      assertEquals(
          "partial",
          cancelCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("collectionState"));

      // 6. read-error: stream error observed on process tail
      events.list.clear();
      var errorAttempt = service.begin("eng-err", "MAIN_BOARD", List.of("engine"), true);
      ControllableProcess errProc = new ControllableProcess();
      errorAttempt.attachProcess(errProc);
      errorAttempt.streamEnded("stderr", "simulated read error");
      errorAttempt.streamEnded("stdout", null);
      errProc.finish(1);
      errorAttempt.fail("startup-exit", "exit with error");

      ILoggingEvent errorEvent = lastEventWithEngineId(events, "eng-err");
      assertEquals(Level.WARN, errorEvent.getLevel());
      JSONObject errorCore = extractCore(errorEvent);
      assertEquals("eng-err", errorCore.getString("engineId"));
      assertEquals("no-specific-dll-identified", errorCore.getString("outcome"));
      assertEquals("partial", errorCore.getString("collectionState"));
      assertEquals(
          "read-error",
          errorCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));
      assertEquals(
          "partial",
          errorCore
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("collectionState"));
    }
  }

  @Test
  void asynchronousLogSupplementRetainsOriginalIdentityAfterSubsequentAttempt() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    var policy = new EngineStartupDiagnostics.Policy(2, 2000, 10, 1024, 8192);
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(policy, EngineObservation::recordStartupDiagnostic)) {
      events.list.clear();
      var attempt1 = service.begin("eng-first", "MAIN_BOARD", List.of("engine-1"), true);
      ControllableProcess proc1 = new ControllableProcess();
      attempt1.attachProcess(proc1);
      attempt1.output("stdout", "eng-first starting...");
      attempt1.fail("startup-exit", "first failure pending tail");

      ILoggingEvent initEvent1 = lastEventWithAttemptId(events, attempt1.id());
      assertEquals(Level.WARN, initEvent1.getLevel());
      JSONObject initCore1 = extractCore(initEvent1);
      assertEquals("eng-first", initCore1.getString("engineId"));
      assertEquals(attempt1.id(), initCore1.getString("attemptId"));
      assertEquals("collecting", initCore1.getString("collectionState"));

      // Subsequent attempt starts and fails while attempt 1 is still collecting
      var attempt2 = service.begin("eng-second", "MAIN_BOARD", List.of("engine-2"), true);
      attempt2.fail("process-create", "second failure immediate");

      ILoggingEvent event2 = lastEventWithAttemptId(events, attempt2.id());
      assertEquals(Level.WARN, event2.getLevel());
      JSONObject core2 = extractCore(event2);
      assertEquals("eng-second", core2.getString("engineId"));
      assertEquals(attempt2.id(), core2.getString("attemptId"));
      assertEquals("completed", core2.getString("collectionState"));

      // Now attempt 1 completes its process tail asynchronously
      attempt1.output(
          "stderr",
          "The code execution cannot proceed because cudnn64_9.dll was not found; token=tail-secret");
      attempt1.streamEnded("stdout", null);
      attempt1.streamEnded("stderr", null);
      proc1.finish(1);
      awaitSettled(attempt1);

      ILoggingEvent completedEvent1 = lastEventWithAttemptId(events, attempt1.id());
      assertNotSame(initEvent1, completedEvent1);
      assertEquals(Level.WARN, completedEvent1.getLevel());
      JSONObject completedCore1 = extractCore(completedEvent1);
      assertEquals("eng-first", completedCore1.getString("engineId"));
      assertEquals(attempt1.id(), completedCore1.getString("attemptId"));
      assertEquals("completed", completedCore1.getString("collectionState"));
      assertEquals("evidence-available", completedCore1.getString("outcome"));

      JSONObject summary1 = extractSummary(completedEvent1);
      assertTrue(summary1.getString("stderr").contains("cudnn64_9.dll"));
      assertFalse(summary1.getString("stderr").contains("tail-secret"));
      assertEquals(
          "cudnn64_9.dll", summary1.getJSONArray("findings").getJSONObject(0).getString("dll"));
    }
  }

  @Test
  void startupDiagnosticLogsWarnWithNtstatusRawHexNameExplicitDllAndScrubbedSecrets()
      throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    String originalOs = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Windows 11");
      var policy = new EngineStartupDiagnostics.Policy(1, 1000, 10, 1024, 8192);
      try (EngineStartupDiagnostics service =
          new EngineStartupDiagnostics(policy, EngineObservation::recordStartupDiagnostic)) {
        var attempt =
            service.begin(
                "eng-ntstatus",
                "MAIN_BOARD",
                List.of("C:\\Users\\admin\\AppData\\engine.exe", "--key=secret-key"),
                true);
        ControllableProcess proc = new ControllableProcess();
        attempt.attachProcess(proc);
        attempt.output(
            "stderr",
            "The program can't start because cudnn64_9.dll is missing from your computer. token=runtime-secret");
        attempt.output("stdout", "engine output token=stdout-secret");
        attempt.streamEnded("stdout", null);
        attempt.streamEnded("stderr", null);
        proc.finish(0xC0000135);
        attempt.fail("startup-exit", "failed with exit token=error-secret");
        awaitSettled(attempt);

        ILoggingEvent event = lastEventWithEngineId(events, "eng-ntstatus");
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("engine event=startup-failed"));

        JSONObject core = extractCore(event);
        assertEquals("eng-ntstatus", core.getString("engineId"));
        assertEquals(attempt.id(), core.getString("attemptId"));
        assertEquals("completed", core.getString("collectionState"));
        assertEquals("evidence-available", core.getString("outcome"));
        assertEquals(-1073741515, core.getInt("exitCode"));
        assertEquals("0xC0000135", core.getString("exitHex"));
        assertEquals("STATUS_DLL_NOT_FOUND", core.getString("statusName"));
        assertEquals("NTSTATUS", core.getString("errorDomain"));
        assertEquals("startup-exit", core.getString("phase"));

        JSONObject summary = extractSummary(event);
        assertTrue(summary.getString("stderr").contains("cudnn64_9.dll"));
        assertEquals(
            "cudnn64_9.dll", summary.getJSONArray("findings").getJSONObject(0).getString("dll"));
        assertEquals(
            "dll-not-found",
            summary.getJSONArray("findings").getJSONObject(0).getString("outcome"));

        String logText = formatted(events);
        assertFalse(logText.contains("runtime-secret"));
        assertFalse(logText.contains("stdout-secret"));
        assertFalse(logText.contains("error-secret"));
        assertFalse(logText.contains("secret-key"));
        assertFalse(logText.contains("C:\\Users\\admin"));
      }
    } finally {
      System.setProperty("os.name", originalOs);
    }

    assertEquals("STATUS_DLL_NOT_FOUND", EngineStartupDiagnostic.windowsStatus(-1073741515));
    assertEquals("STATUS_DLL_NOT_FOUND", EngineStartupDiagnostic.windowsStatus(0xC0000135));
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

    var policy = new EngineStartupDiagnostics.Policy(1, 1000, 2, 128, 4096);
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
      assertTrue(core.getBoolean("truncated"));
      assertTrue(core.has("statusName"));
      assertTrue(core.has("errorDomain"));
      assertTrue(core.has("phase"));
      assertEquals("startup-exit", core.getString("phase"));

      JSONObject sources = core.getJSONObject("sources");
      assertTrue(sources.has("process-tail"));
      assertEquals("completed", sources.getJSONObject("process-tail").getString("collectionState"));

      assertFalse(core.getString("engineId").contains("truncated"));
      assertFalse(core.getString("attemptId").contains("truncated"));

      JSONObject summary = extractSummary(event);
      assertTrue(
          summary.getBoolean("stderrTruncated")
              || summary.getString("stderr").contains("truncated")
              || summary.getString("stderr").length() <= 128);
    }
  }

  @Test
  void runtimeSearchScopeStaysInCopyButNotOrdinaryWarn() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()), LoggingLimits.production());
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    ListAppender<ILoggingEvent> events =
        attach((Logger) LoggerFactory.getLogger(LogCategories.ENGINE));
    try (var service =
        new EngineStartupDiagnostics(
            EngineStartupDiagnostics.Policy.production(),
            EngineObservation::recordStartupDiagnostic)) {
      var attempt = service.begin("scope-privacy", "MAIN_BOARD", List.of("engine"), true);
      attempt.runtimePreflight(
          createPreflightStatus(
              "cudnn64_9.dll",
              "UNLOGGED_SEARCH_CONTEXT",
              "Missing cudnn64_9.dll; token=runtime-secret"));
      attempt.fail("process-create", "cannot start; token=launch-secret");
      awaitSettled(attempt);
      String log = formatted(events);
      assertTrue(log.contains("cudnn64_9.dll"));
      assertTrue(log.contains("runtime-preflight"));
      assertFalse(log.contains("UNLOGGED_SEARCH_CONTEXT"));
      assertFalse(log.contains("runtime-secret"));
      assertFalse(log.contains("launch-secret"));
      assertTrue(attempt.snapshot().shareText().contains("UNLOGGED_SEARCH_CONTEXT"));
      assertFalse(attempt.snapshot().shareText().contains("runtime-secret"));
      assertFalse(attempt.snapshot().shareText().contains("launch-secret"));
    }
  }

  private static KataGoRuntimeHelper.NvidiaRuntimeStatus createPreflightStatus(
      String missingDll, String checkedScope, String detailText) throws Exception {
    var ctor =
        KataGoRuntimeHelper.NvidiaRuntimeStatus.class.getDeclaredConstructor(
            boolean.class,
            boolean.class,
            Path.class,
            Path.class,
            List.class,
            long.class,
            String.class,
            String.class,
            String.class,
            boolean.class,
            boolean.class);
    ctor.setAccessible(true);
    return ctor.newInstance(
        true,
        false,
        Path.of("engine.exe"),
        Path.of("runtime"),
        List.of(missingDll),
        0L,
        detailText,
        "cuda",
        checkedScope,
        false,
        false);
  }

  private static final class ControllableProcess extends Process {
    private final AtomicInteger exitCode = new AtomicInteger(0);
    private final AtomicBoolean exited = new AtomicBoolean(false);

    void finish(int code) {
      exitCode.set(code);
      exited.set(true);
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      while (!exited.get()) {
        Thread.sleep(5);
      }
      return exitCode.get();
    }

    @Override
    public int exitValue() {
      if (!exited.get()) {
        throw new IllegalThreadStateException("Process has not exited");
      }
      return exitCode.get();
    }

    @Override
    public void destroy() {
      exited.set(true);
    }

    @Override
    public long pid() {
      return 12345L;
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

  private static JSONObject extractSummary(ILoggingEvent event) {
    String msg = event.getFormattedMessage();
    int summaryStart = msg.indexOf(" summary={");
    assertTrue(summaryStart >= 0, "Missing summary={ in: " + msg);
    int jsonStart = summaryStart + " summary=".length();
    return new JSONObject(msg.substring(jsonStart));
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
    assertNotNull(found, "No event found for engineId: " + engineId);
    return found;
  }

  private static ILoggingEvent lastEventWithAttemptId(
      ListAppender<ILoggingEvent> events, String attemptId) {
    ILoggingEvent found = null;
    for (ILoggingEvent event : events.list) {
      if (event.getFormattedMessage().contains("\"attemptId\":\"" + attemptId + "\"")
          || event.getFormattedMessage().contains(attemptId)) {
        found = event;
      }
    }
    assertNotNull(found, "No event found for attemptId: " + attemptId);
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
    fail("Attempt did not settle");
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
