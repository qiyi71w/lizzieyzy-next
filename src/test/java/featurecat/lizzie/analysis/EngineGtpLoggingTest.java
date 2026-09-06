package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.LogStream;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineGtpLoggingTest {
  private static final String RAW_MOVE = "T03_RAW_MOVE";
  private static final String INFO_CANARY = "T03_INFO_CANARY";

  @TempDir Path tempDir;
  private Config previousConfig;
  private Leelaz previousLeelaz;
  private GtpConsolePane previousConsole;
  private Board previousBoard;
  private LizzieFrame previousFrame;

  @AfterEach
  void tearDown() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    Lizzie.config = previousConfig;
    Lizzie.leelaz = previousLeelaz;
    Lizzie.gtpConsole = previousConsole;
    Lizzie.board = previousBoard;
    Lizzie.frame = previousFrame;
  }

  @Test
  void engineLifecycleUsesNewIdentitiesAndReadyWithoutRawProtocol() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.isLoaded = false;
    Method markLoaded = Leelaz.class.getDeclaredMethod("markEngineLoaded");
    markLoaded.setAccessible(true);
    markLoaded.invoke(engine);
    String first = EngineObservation.identityFor(engine);
    EngineObservation.restartInstance(engine, "MAIN_BOARD");
    String second = EngineObservation.identityFor(engine);
    awaitLogs(runtime);

    assertNotEquals(first, second);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=started"), app);
    assertTrue(app.contains("engine event=ready"), app);
    assertTrue(app.contains("engine=" + first), app);
    assertTrue(app.contains("engine=" + second), app);
    assertTrue(app.contains("purpose=MAIN_BOARD"), app);
    assertFalse(app.contains("gtp raw"), app);
  }

  @Test
  void startupFailureAllocatesEngineIdentityWithoutStartedEvent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    engine.setEngineCommand(
        "\"C:\\\\Users\\\\Player\\\\secret\\\\katago.exe\" gtp "
            + "-model \"C:\\\\Users\\\\Player\\\\secret\\\\model.bin.gz\"");
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "ssh login failed");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=failed reason=ssh login failed"), app);
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engineType=katago"), app);
    assertTrue(app.contains("source=user-configured"), app);
    assertTrue(app.contains("purpose=MAIN_BOARD"), app);
    assertTrue(app.contains("model=model.bin.gz"), app);
    assertFalse(app.contains("engine event=started"), app);
    assertFalse(app.contains("Player"), app);
    assertFalse(app.contains("secret"), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
    assertTrue(app.contains("engine=eng-"), app);
  }

  @Test
  void processStartFailureKeepsBootstrapIdentityWithoutStartedEvent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "process start failed");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=failed reason=process start failed"), app);
    assertTrue(app.contains("engineType=unknown"), app);
    assertTrue(app.contains("backend=unknown"), app);
    assertTrue(app.contains("onnxProvider=unknown"), app);
    assertFalse(app.contains("engine event=started"), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
  }

  @Test
  void exitBeforeReadyReusesBootstrapIdentity() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Method started = Leelaz.class.getDeclaredMethod("noteEngineStarted");
    started.setAccessible(true);
    started.invoke(engine);
    String identity = EngineObservation.identityFor(engine);
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "exit-before-ready");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=started"), app);
    assertTrue(app.contains("engine event=failed reason=exit-before-ready"), app);
    assertTrue(identity != null && app.contains("engine=" + identity), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
    assertSameEngineIdentity(app, "event=started", "event=failed");
  }

  @Test
  void bundledLaunchBootstrapRecordsSourceAndAvailableBackend() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Path enginePath =
        tempDir
            .resolve("engines")
            .resolve("katago")
            .resolve("windows-x64-nvidia")
            .resolve("katago.exe");
    Files.createDirectories(enginePath.getParent());
    Files.writeString(enginePath, "", StandardCharsets.UTF_8);
    engine.setEngineCommand(
        "\""
            + enginePath.toAbsolutePath().normalize()
            + "\" gtp -model kata1.bin.gz -config gtp.cfg");
    Method started = Leelaz.class.getDeclaredMethod("noteEngineStarted");
    started.setAccessible(true);
    started.invoke(engine);
    Method loaded = Leelaz.class.getDeclaredMethod("markEngineLoaded");
    loaded.setAccessible(true);
    engine.isLoaded = false;
    loaded.invoke(engine);
    awaitLogs(runtime);
    String app = readApp();
    String identity = EngineObservation.identityFor(engine);
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("source=bundled"), app);
    assertTrue(app.contains("backend=nvidia"), app);
    assertTrue(app.contains("engineType=katago"), app);
    assertEquals(
        KataGoRuntimeHelper.resolveNvidiaBackend(enginePath),
        EngineStartupBootstrap.factsFor(engine.engineCommand, "MAIN_BOARD").backend());
    assertEquals(
        "unknown",
        EngineStartupBootstrap.factsFor(engine.engineCommand, "MAIN_BOARD").onnxProvider());
    assertTrue(app.contains("engine event=ready"), app);
    assertTrue(identity != null && app.contains("engine=" + identity), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=ready");
    String bootstrapLine = eventLine(app, "event=bootstrap");
    assertTrue(bootstrapLine != null && !bootstrapLine.contains(tempDir.toString()), app);
  }

  @Test
  void consecutiveFailuresAndRetryMintDistinctIdentities() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "ssh login failed");
    fail.invoke(engine, "ssh login failed");
    Method started = Leelaz.class.getDeclaredMethod("noteEngineStarted");
    started.setAccessible(true);
    started.invoke(engine);
    awaitLogs(runtime);
    String app = readApp();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("engine=(eng-[0-9a-f-]+)").matcher(app);
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    assertTrue(ids.size() >= 3, app);
    assertTrue(app.contains("engine event=failed"), app);
    assertTrue(app.contains("engine event=started"), app);
    String successId = EngineObservation.identityFor(engine);
    assertTrue(successId != null && ids.contains(successId), app);
  }

  @Test
  void gtpSummaryOmitsRawArgumentsAndTraceCapturesThem() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    ExactSnapshotRestoreProtocolFixture.install(
        engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("play B " + RAW_MOVE);
    awaitLogs(runtime);
    String appBeforeTrace = readApp();
    assertTrue(appBeforeTrace.contains("gtp command=play outcome=sent"), appBeforeTrace);
    assertTrue(appBeforeTrace.contains("gtp command=play outcome=ok"), appBeforeTrace);
    assertFalse(appBeforeTrace.contains(RAW_MOVE), appBeforeTrace);
    assertFalse(Files.exists(tempDir.resolve("logs/engine-trace.log")));

    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    engine.sendCommand("play B " + RAW_MOVE);
    // Raw-stream persistence is a logging contract, independent of analysis ownership. The
    // parser now deliberately quarantines an unowned info line, so exercise the observation seam
    // directly instead of manufacturing an invalid analysis owner in this logging-only test.
    EngineObservation.traceRawStream(
        EngineObservation.identityFor(engine), null, "info move D4 visits 1 " + INFO_CANARY);
    awaitLogs(runtime);
    runtime.stopFullTrace();
    awaitLogs(runtime);

    String app = readApp();
    assertFalse(app.contains(RAW_MOVE), app);
    assertFalse(app.contains(INFO_CANARY), app);
    String trace = Files.readString(tempDir.resolve("logs/engine-trace.log"));
    assertTrue(trace.contains("gtp raw command="), trace);
    assertTrue(trace.contains("play B " + RAW_MOVE), trace);
    assertTrue(trace.contains("gtp raw stream="), trace);
    assertTrue(trace.contains(INFO_CANARY), trace);
    assertTrue(trace.contains("Full Trace session started"), trace);
  }

  @Test
  void engineAndGtpModulesAreIndependent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("name");
    awaitLogs(runtime);
    String engineOnly = readApp();
    assertTrue(engineOnly.contains("engine event=queue"), engineOnly);
    assertFalse(engineOnly.contains("gtp command=name"), engineOnly);

    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    engine.sendCommand("version");
    awaitLogs(runtime);
    String gtpOnly = readApp();
    assertTrue(gtpOnly.contains("gtp command=version outcome=sent"), gtpOnly);
  }

  @Test
  void staleResponseKeepsOriginalEngineIdentity() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    String first = EngineObservation.identityFor(engine);
    engine.sendCommand("name");
    String second = EngineObservation.restartInstance(engine, "MAIN_BOARD");
    engine.processCommandResponseLineForTest("=");
    awaitLogs(runtime);

    String app = readApp();
    int sent = app.indexOf("gtp command=name outcome=sent");
    int ok = app.indexOf("gtp command=name outcome=ok");
    assertTrue(sent >= 0, app);
    assertTrue(ok >= 0, app);
    String sentLine = app.substring(Math.max(0, sent - 120), sent + 40);
    String okLine = app.substring(Math.max(0, ok - 120), ok + 40);
    assertTrue(sentLine.contains("engine=" + first), sentLine);
    assertTrue(okLine.contains("engine=" + first), okLine);
    assertFalse(okLine.contains("engine=" + second), okLine);
  }

  @Test
  void protocolCommandIdentityIsReusedWhenPresent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommandWithResponseForTest("kata-set-param numSearchThreads 12", () -> {});
    engine.processCommandResponseLineForTest("=700000000");
    awaitLogs(runtime);

    String app = readApp();
    assertTrue(app.contains("command=700000000"), app);
    assertTrue(app.contains("gtp command=kata-set-param"), app);
    assertFalse(app.contains("numSearchThreads 12"), app);
  }

  @Test
  void stalledTraceQueueDoesNotBlockCommandSend() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(32, 1, 1, 1, 7, 10_000, 1_000));
    installGlobals();
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(true));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    CountDownLatch gate = new CountDownLatch(1);
    Method block =
        LoggingRuntime.class.getDeclaredMethod(
            "blockPersistenceForTests", LogStream.class, CountDownLatch.class);
    block.setAccessible(true);
    block.invoke(runtime, LogStream.ENGINE_TRACE, gate);
    org.slf4j.LoggerFactory.getLogger("lizzie.engine.trace").info("block-one");
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    long began = System.nanoTime();
    engine.sendCommand("play B " + RAW_MOVE);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    gate.countDown();
    assertTrue(elapsed < 500, "send blocked for " + elapsed + "ms");
  }

  @Test
  void disabledTraceDoesNotFormatRawCommand() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("play B " + RAW_MOVE);
    awaitLogs(runtime);
    assertFalse(readApp().contains(RAW_MOVE), readApp());
    assertFalse(Files.exists(tempDir.resolve("logs/engine-trace.log")));
  }

  private LoggingRuntime startRuntime() throws Exception {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    installGlobals();
    return runtime;
  }

  private void installGlobals() throws Exception {
    previousConfig = Lizzie.config;
    previousLeelaz = Lizzie.leelaz;
    previousConsole = Lizzie.gtpConsole;
    previousBoard = Lizzie.board;
    previousFrame = Lizzie.frame;
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.gtpConsole = null;
    Lizzie.frame = allocate(LizzieFrame.class);
  }

  private Leelaz prepareEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.isCheckingName = true;
    engine.commandLists.addAll(List.of("stop", "kata-analyze", "name", "version"));
    setField(engine, "endGetCommandList", true);
    engine.installCommandOutputForTest(new ByteArrayOutputStream());
    Lizzie.leelaz = engine;
    return engine;
  }

  private String readApp() throws Exception {
    Path file = tempDir.resolve("logs/app.log");
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  private static void assertSameEngineIdentity(String app, String firstEvent, String secondEvent) {
    String first = engineIdOnEventLine(app, firstEvent);
    String second = engineIdOnEventLine(app, secondEvent);
    assertTrue(
        first != null && first.equals(second),
        firstEvent + "=" + first + " " + secondEvent + "=" + second + "\n" + app);
  }

  private static String eventLine(String app, String eventToken) {
    for (String line : app.split("\n")) {
      if (line.contains(eventToken)) {
        return line;
      }
    }
    return null;
  }

  private static String engineIdOnEventLine(String app, String eventToken) {
    String line = eventLine(app, eventToken);
    if (line == null) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("engine=(eng-[0-9a-f-]+)").matcher(line);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static void awaitLogs(LoggingRuntime runtime) throws Exception {
    Method method = LoggingRuntime.class.getDeclaredMethod("awaitIdle");
    method.setAccessible(true);
    method.invoke(runtime);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }
}
