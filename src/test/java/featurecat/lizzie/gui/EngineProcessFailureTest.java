package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.LeelazResponseTestBridge;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.analysis.SnapshotFileAccessTestBridge;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.BoardNodeKind;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.awt.Dialog;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Real-window failure, recovery, and stale-output probes for production-owned engine processes. */
public final class EngineProcessFailureTest {
  private static final String FIXTURE =
      "src/test/resources/featurecat/lizzie/rules/issue223-snapshot-removed-stone.sgf";
  private static final Set<String> COMMON_BOOLEAN_KEYS =
      Set.of(
          "stimulus.observed", "app.position", "cleanup.process", "cleanup.readers", "cleanup.sgf");
  private static final Set<String> COMMON_RESULT_KEYS =
      new LinkedHashSet<>(
          List.of(
              "scenario",
              "peer.kind",
              "file-access",
              "fixture.path",
              "outcome",
              "stimulus.observed",
              "app.position",
              "cleanup.process",
              "cleanup.readers",
              "cleanup.sgf",
              "peer.old.pid",
              "peer.new.pid",
              "peer.old.launch",
              "peer.new.launch",
              "analysis.old",
              "analysis.new",
              "evidence.old-manifest",
              "evidence.old-state",
              "evidence.old-commands",
              "evidence.old-receipt",
              "evidence.new-manifest",
              "evidence.new-state",
              "evidence.new-commands",
              "evidence.switch-stop-receipt",
              "evidence.app-log",
              "evidence.stdout",
              "evidence.stderr",
              "evidence.phases",
              "evidence.staged-sgfs"));

  @Test
  void rejectsSnapshotErrorWithoutTailOrAnalysis() throws Exception {
    runScenario(Scenario.SNAPSHOT_ERROR);
  }

  @Test
  void retiresSnapshotTimeoutAndRejectsLateResponse() throws Exception {
    runScenario(Scenario.SNAPSHOT_TIMEOUT);
  }

  @Test
  void recoversSnapshotAfterPeerCrash() throws Exception {
    runScenario(Scenario.CRASH_RESTART);
  }

  @Test
  void isolatesLateOutputAfterEngineSwitch() throws Exception {
    runScenario(Scenario.LATE_OUTPUT_SWITCH);
  }

  @Test
  void drainsPeerPipeBurstAndRemainsResponsive() throws Exception {
    runScenario(Scenario.PIPE_BURST);
  }

  @Test
  void cleansUpPeerThatRefusesQuit() throws Exception {
    runScenario(Scenario.QUIT_REFUSAL);
  }

  private static void runScenario(Scenario scenario) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path fixture = Path.of(FIXTURE).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(fixture), fixture.toString());
    Path result =
        DesktopProbeProcess.run(
            EngineProcessFailureTest.class,
            "engine-process-" + scenario.argument,
            List.of(),
            List.of("probe", scenario.argument, fixture.toString()));
    Map<String, String> records = parseResult(result, scenario);

    assertEquals(scenario.argument, records.get("scenario"));
    assertEquals("controlled-java", records.get("peer.kind"));
    assertEquals("test-trusted-local", records.get("file-access"));
    assertEquals(fixture.toString(), records.get("fixture.path"));
    assertEquals("passed", records.get("outcome"));
    for (String key : COMMON_BOOLEAN_KEYS) {
      assertEquals("true", records.get(key), key + " must be affirmative");
    }
    for (String key : scenario.verdictKeys) {
      assertEquals("true", records.get(key), key + " must be affirmative");
    }

    long oldPid = positiveLong(records, "peer.old.pid");
    assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
    assertEquals(1, positiveInt(records, "peer.old.launch"));
    int oldAnalysis = nonnegativeInt(records, "analysis.old");
    if (scenario.oldAnalysisRequired) {
      assertTrue(oldAnalysis > 0, "old peer must emit analysis");
    } else {
      assertEquals(0, oldAnalysis, "failed restore must not emit analysis");
    }

    long newPid = Long.parseLong(records.get("peer.new.pid"));
    if (scenario.newPeerRequired) {
      assertTrue(newPid > 0);
      assertNotEquals(oldPid, newPid);
      assertFalse(ProcessHandle.of(newPid).map(ProcessHandle::isAlive).orElse(false));
      assertEquals(scenario.expectedNewLaunch, positiveInt(records, "peer.new.launch"));
      assertTrue(positiveInt(records, "analysis.new") > 0);
    } else {
      assertEquals(0, newPid);
      assertEquals("0", records.get("peer.new.launch"));
      assertEquals("0", records.get("analysis.new"));
    }

    for (String key :
        List.of(
            "evidence.old-manifest",
            "evidence.old-state",
            "evidence.old-commands",
            "evidence.old-receipt",
            "evidence.app-log",
            "evidence.stdout",
            "evidence.stderr",
            "evidence.phases")) {
      assertTrue(Files.isRegularFile(Path.of(records.get(key))), key + " missing");
    }
    assertIncarnationEvidence(records, "old", scenario.oldPeerScenario, oldPid, 1);
    if (scenario.newPeerRequired) {
      assertTrue(Files.isRegularFile(Path.of(records.get("evidence.new-manifest"))));
      assertTrue(Files.isRegularFile(Path.of(records.get("evidence.new-state"))));
      assertTrue(Files.isRegularFile(Path.of(records.get("evidence.new-commands"))));
      assertIncarnationEvidence(
          records, "new", scenario.newPeerScenario, newPid, scenario.expectedNewLaunch);
    } else {
      assertEquals("none", records.get("evidence.new-manifest"));
      assertEquals("none", records.get("evidence.new-state"));
      assertEquals("none", records.get("evidence.new-commands"));
    }
    if (scenario == Scenario.LATE_OUTPUT_SWITCH) {
      assertTrue(Files.isRegularFile(Path.of(records.get("evidence.switch-stop-receipt"))));
    } else {
      assertEquals("none", records.get("evidence.switch-stop-receipt"));
    }
    for (String staged : records.get("evidence.staged-sgfs").split("\\|")) {
      assertFalse(Files.exists(Path.of(staged)), "staged SGF survived: " + staged);
    }
    assertScenarioEvidence(records, scenario, oldPid);
  }

  private static Map<String, String> parseResult(Path result, Scenario scenario)
      throws IOException {
    Set<String> expected = expectedResultKeys(scenario);
    Map<String, String> records = new LinkedHashMap<>();
    for (String line : Files.readAllLines(result, StandardCharsets.UTF_8)) {
      int separator = line.indexOf('=');
      if (separator <= 0 || separator == line.length() - 1) {
        throw new AssertionError("Malformed child result record: " + line);
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (!key.matches("[a-z][a-z0-9.-]*") || !expected.contains(key)) {
        throw new AssertionError("Unknown child result key: " + key);
      }
      if (records.putIfAbsent(key, value) != null) {
        throw new AssertionError("Duplicate child result key: " + key);
      }
    }
    if (!records.keySet().equals(expected)) {
      Set<String> missing = new LinkedHashSet<>(expected);
      missing.removeAll(records.keySet());
      throw new AssertionError("Child result schema mismatch; missing=" + missing);
    }
    return records;
  }

  private static Set<String> expectedResultKeys(Scenario scenario) {
    Set<String> keys = new LinkedHashSet<>(COMMON_RESULT_KEYS);
    keys.addAll(scenario.verdictKeys);
    keys.addAll(scenario.detailKeys());
    return keys;
  }

  private static void assertScenarioEvidence(
      Map<String, String> records, Scenario scenario, long oldPid) throws IOException {
    if (scenario == Scenario.QUIT_REFUSAL) {
      Path refusalPath = Path.of(records.get("evidence.old-receipt"));
      Map<String, String> refusal =
          readRecords(
              refusalPath, Set.of("command", "refusal", "pid", "launch.ordinal"));
      assertEquals("quit", refusal.get("command"));
      assertEquals("stay-alive", refusal.get("refusal"));
      assertEquals(Long.toString(oldPid), refusal.get("pid"));
      assertEquals("1", refusal.get("launch.ordinal"));
      assertFalse(
          Files.exists(refusalPath.resolveSibling("peer-complete.txt")),
          "quit-refusing peer claimed natural completion");
      return;
    }
    if (scenario != Scenario.PIPE_BURST) {
      return;
    }
    Map<String, String> stdoutLast =
        assertBurstReceipt(
            Path.of(records.get("evidence.burst-stdout-last")),
            "stdout",
            1,
            2048,
            oldPid,
            1);
    Map<String, String> stderrLast =
        assertBurstReceipt(
            Path.of(records.get("evidence.burst-stderr-last")),
            "stderr",
            1,
            2048,
            oldPid,
            1);
    assertBurstReceipt(
        Path.of(records.get("evidence.burst-stdout-first")), "stdout", 1, 1, oldPid, 1);
    assertBurstReceipt(
        Path.of(records.get("evidence.burst-stdout-progress")),
        "stdout",
        1,
        1024,
        oldPid,
        1);
    assertBurstReceipt(
        Path.of(records.get("evidence.burst-stderr-first")), "stderr", 1, 1, oldPid, 1);
    assertBurstReceipt(
        Path.of(records.get("evidence.burst-stderr-progress")),
        "stderr",
        1,
        1024,
        oldPid,
        1);
    assertEquals(stdoutLast.get("bytes"), records.get("burst.stdout.bytes"));
    assertEquals(stderrLast.get("bytes"), records.get("burst.stderr.bytes"));
    assertEquals(stdoutLast.get("duration.nanos"), records.get("burst.stdout.duration-nanos"));
    assertEquals(stderrLast.get("duration.nanos"), records.get("burst.stderr.duration-nanos"));
    Path controlReceipt = Path.of(records.get("evidence.burst-control"));
    Map<String, String> control =
        readRecords(controlReceipt, Set.of("command", "pid", "launch.ordinal"));
    assertEquals("name", control.get("command"));
    assertEquals(Long.toString(oldPid), control.get("pid"));
    assertEquals("1", control.get("launch.ordinal"));
  }

  private static Map<String, String> assertBurstReceipt(
      Path path,
      String stream,
      int firstSequence,
      int lastSequence,
      long expectedPid,
      int expectedLaunch)
      throws IOException {
    Map<String, String> receipt =
        readRecords(
            path,
            Set.of(
                "stream",
                "pid",
                "launch.ordinal",
                "first.sequence",
                "last.sequence",
                "bytes",
                "duration.nanos"));
    assertEquals(stream, receipt.get("stream"));
    assertEquals(Long.toString(expectedPid), receipt.get("pid"));
    assertEquals(Integer.toString(expectedLaunch), receipt.get("launch.ordinal"));
    assertEquals(Integer.toString(firstSequence), receipt.get("first.sequence"));
    assertEquals(Integer.toString(lastSequence), receipt.get("last.sequence"));
    long bytes = positiveLong(receipt, "bytes");
    assertTrue(bytes <= 8L * 1024 * 1024, stream + " burst exceeded 8MiB");
    positiveLong(receipt, "duration.nanos");
    return receipt;
  }

  private static long positiveLong(Map<String, String> records, String key) {
    long value = Long.parseLong(records.get(key));
    assertTrue(value > 0, key + " must be positive");
    return value;
  }

  private static int positiveInt(Map<String, String> records, String key) {
    int value = Integer.parseInt(records.get(key));
    assertTrue(value > 0, key + " must be positive");
    return value;
  }

  private static int nonnegativeInt(Map<String, String> records, String key) {
    int value = Integer.parseInt(records.get(key));
    assertTrue(value >= 0, key + " must be nonnegative");
    return value;
  }

  private static void assertIncarnationEvidence(
      Map<String, String> records,
      String role,
      String expectedScenario,
      long expectedPid,
      int expectedLaunch)
      throws IOException {
    Path identityPath = Path.of(records.get("evidence." + role + "-manifest"));
    Map<String, String> identity =
        readRecords(identityPath, Set.of("pid", "launch.ordinal", "scenario"));
    assertEquals(Long.toString(expectedPid), identity.get("pid"));
    assertEquals(Integer.toString(expectedLaunch), identity.get("launch.ordinal"));
    assertEquals(expectedScenario, identity.get("scenario"));

    Path statePath = Path.of(records.get("evidence." + role + "-state"));
    Map<String, String> state = peerState(statePath);
    nonnegativeRecord(state, "analysis.count", statePath);
    if (!Set.of("true", "false").contains(state.get("analysis.active"))) {
      throw new AssertionError("invalid analysis.active in " + statePath + ": " + state);
    }
    if (!identityPath.getParent().equals(statePath.getParent())) {
      throw new AssertionError("incarnation evidence paths disagree for " + role);
    }
  }

  private static int nonnegativeRecord(Map<String, String> records, String key, Path source) {
    int value;
    try {
      value = Integer.parseInt(records.get(key));
    } catch (NumberFormatException failure) {
      throw new AssertionError("invalid numeric record " + key + " in " + source, failure);
    }
    if (value < 0) {
      throw new AssertionError("negative numeric record " + key + " in " + source);
    }
    return value;
  }

  /** Child-process entry point. Each invocation owns one application and all peer descendants. */
  public static void main(String[] args) throws Exception {
    if (args.length != 5 || !"probe".equals(args[0])) {
      throw new IllegalArgumentException(
          "expected probe, scenario, fixture path, work directory, and result path");
    }
    Scenario scenario = Scenario.parse(args[1]);
    Path fixture = Path.of(args[2]).toAbsolutePath().normalize();
    Path work = Path.of(args[3]).toAbsolutePath().normalize();
    Path result = Path.of(args[4]).toAbsolutePath().normalize();
    List<Leelaz> ownedEngines = new ArrayList<>();
    int exitCode = 1;
    try {
      DesktopProbeProcess.phase(result, "production-startup");
      runProbe(scenario, fixture, work, result, ownedEngines);
      DesktopProbeProcess.phase(result, "production-cleanup-complete");
      exitCode = 0;
    } catch (Throwable failure) {
      Files.writeString(
          result,
          "failure.class="
              + failure.getClass().getName()
              + "\nfailure.message="
              + clean(failure.getMessage())
              + "\n",
          StandardCharsets.UTF_8);
      failure.printStackTrace(System.err);
    } finally {
      if (exitCode != 0) {
        for (Leelaz engine : ownedEngines) {
          try {
            engine.forceQuit();
          } catch (Throwable cleanupFailure) {
            cleanupFailure.printStackTrace(System.err);
          }
        }
      }
      System.exit(exitCode);
    }
  }

  private static void runProbe(
      Scenario scenario, Path fixture, Path work, Path result, List<Leelaz> ownedEngines)
      throws Exception {
    ProbeBudget budget = new ProbeBudget();
    Harness harness = initializeApplication(fixture, work, result, ownedEngines, budget);
    ScenarioEvidence evidence =
        switch (scenario) {
          case SNAPSHOT_ERROR -> runSnapshotError(harness);
          case SNAPSHOT_TIMEOUT -> runSnapshotTimeout(harness);
          case CRASH_RESTART -> runCrashRestart(harness);
          case LATE_OUTPUT_SWITCH -> runLateOutputSwitch(harness);
          case PIPE_BURST -> runPipeBurst(harness);
          case QUIT_REFUSAL -> runQuitRefusal(harness);
        };
    assertApplicationPosition(
        harness.history, harness.target, evidence.expectedMove, evidence.expectedMove != null);
    writeResult(result, scenario, fixture, evidence);
  }

  private static Harness initializeApplication(
      Path fixture, Path work, Path result, List<Leelaz> ownedEngines, ProbeBudget budget)
      throws Exception {
    Files.createDirectories(work);
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[],\"fast-engine-change\":true},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}",
        StandardCharsets.UTF_8);
    System.setProperty("lizzie.work.dir", work.toString());

    Deadline startupDeadline = budget.startup();
    callWithin(
        () -> {
          Lizzie.main(new String[0]);
          return null;
        },
        startupDeadline,
        "production application startup");
    await(
        () -> Lizzie.frame != null && Lizzie.frame.isShowing(),
        startupDeadline,
        "real main window");
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog) {
              window.dispose();
            }
          }
        });

    BoardHistoryList parsed =
        SGFParser.parseSgf(Files.readString(fixture, StandardCharsets.UTF_8), true);
    while (parsed.next().isPresent()) {
      // Select the fixture's final PASS before any production engine is started.
    }
    BoardHistoryNode target = parsed.getCurrentHistoryNode();
    SwingUtilities.invokeAndWait(() -> Lizzie.board.setHistory(parsed));
    assertApplicationPosition(parsed, target, null, false);
    DesktopProbeProcess.phase(result, "production-startup-complete");
    return new Harness(
        fixture,
        work,
        result,
        parsed,
        target,
        Lizzie.engineManager,
        ownedEngines,
        new ArrayList<>(),
        budget);
  }

  private static ScenarioEvidence runSnapshotError(Harness harness) throws Exception {
    DesktopProbeProcess.phase(harness.result, "snapshot-error-start");
    Deadline initialRestore = harness.budget.initialRestore();
    Set<Thread> existingReaders = productionReaderThreads();
    EngineRun failed =
        addAndSwitch(
            harness, "error-peer", "snapshot-error", existingReaders, true, initialRestore);
    Incarnation old = failed.incarnation;
    await(
        () -> Files.isRegularFile(old.directory.resolve("loadsgf-received.txt")),
        initialRestore,
        "snapshot error receipt");
    recordPhase(harness, "initial-restore-complete", failed, null);

    Deadline recovery = harness.budget.failureRecovery();
    Files.writeString(harness.work.resolve("error-peer/release-001-error"), "release\n");
    await(
        () -> Files.isRegularFile(old.directory.resolve("error-response-emitted.txt")),
        recovery,
        "snapshot error response");
    recordPhase(harness, "stimulus-observed", failed, null);
    Path staged = stagedSgf(old);
    await(
        () ->
            !failed.engine.isLoaded()
                && !failed.engine.isPondering()
                && !harness.manager.isEngineSwitchActive(failed.index, true),
        recovery,
        "snapshot error fail-closed disposition");
    dismissDialogs(recovery);
    await(() -> !Files.exists(staged), recovery, "failed snapshot staged-file deletion");
    assertNoTailOrAnalysis(old);
    assertApplicationPosition(harness.history, harness.target, null, false);
    recordPhase(harness, "failure-recovery-complete", failed, null);

    Deadline cleanup = harness.budget.cleanup();
    closeEngine(failed, cleanup);
    recordPhase(harness, "cleanup-complete", failed, null);
    return evidence(
        harness,
        failed,
        null,
        old.directory.resolve("error-response-emitted.txt"),
        List.of(staged),
        null);
  }

  private static ScenarioEvidence runSnapshotTimeout(Harness harness) throws Exception {
    DesktopProbeProcess.phase(harness.result, "snapshot-timeout-start");
    Deadline initialRestore = harness.budget.initialRestore();
    Set<Thread> existingReaders = productionReaderThreads();
    EngineRun timedOut =
        addAndSwitch(
            harness, "timeout-peer", "snapshot-timeout", existingReaders, true, initialRestore);
    Incarnation old = timedOut.incarnation;
    await(
        () -> Files.isRegularFile(old.directory.resolve("loadsgf-received.txt")),
        initialRestore,
        "snapshot timeout receipt");
    Path oldStaged = stagedSgf(old);
    recordPhase(harness, "initial-restore-complete", timedOut, null);

    Deadline recovery = harness.budget.failureRecovery();
    await(
        () ->
            !timedOut.engine.isLoaded()
                && !timedOut.engine.isPondering()
                && !harness.manager.isEngineSwitchActive(timedOut.index, true),
        recovery,
        "snapshot timeout fail-closed disposition");
    recordPhase(harness, "stimulus-observed", timedOut, null);
    await(() -> !Files.exists(oldStaged), recovery, "timed-out staged-file deletion");
    dismissDialogs(recovery);
    assertNoTailOrAnalysis(old);

    Files.writeString(harness.work.resolve("timeout-peer/release-001-timeout-ack"), "release\n");
    await(
        () -> Files.isRegularFile(old.directory.resolve("late-ack-emitted.txt")),
        recovery,
        "late timeout ACK emission");
    Set<Thread> beforeHealthy = productionReaderThreads();
    EngineRun healthy =
        addAndSwitch(harness, "healthy-peer", "healthy-distinct", beforeHealthy, true, recovery);
    awaitHealthy(harness, healthy, "Q16", recovery);
    Path newStaged = stagedSgf(healthy.incarnation);
    assertNoTailOrAnalysis(old);
    assertApplicationPosition(harness.history, harness.target, "Q16", true);
    recordPhase(harness, "failure-recovery-complete", timedOut, healthy);

    Deadline cleanup = harness.budget.cleanup();
    closeEngine(timedOut, cleanup);
    closeEngine(healthy, cleanup);
    await(() -> !Files.exists(newStaged), cleanup, "healthy staged-file deletion");
    recordPhase(harness, "cleanup-complete", timedOut, healthy);
    return evidence(
        harness,
        timedOut,
        healthy,
        old.directory.resolve("late-ack-emitted.txt"),
        List.of(oldStaged, newStaged),
        "Q16");
  }

  private static ScenarioEvidence runCrashRestart(Harness harness) throws Exception {
    DesktopProbeProcess.phase(harness.result, "crash-restart-start");
    Deadline initialRestore = harness.budget.initialRestore();
    Set<Thread> existingReaders = productionReaderThreads();
    EngineRun engine =
        addAndSwitch(
            harness, "crash-peer", "crash-on-release", existingReaders, true, initialRestore);
    awaitHealthy(harness, engine, "D4", initialRestore);
    Incarnation old = engine.incarnation;
    Path oldStaged = stagedSgf(old);
    recordPhase(harness, "initial-restore-complete", engine, null);

    Deadline recovery = harness.budget.failureRecovery();
    Files.writeString(harness.work.resolve("crash-peer/release-001-crash"), "release\n");
    await(
        () -> Files.isRegularFile(old.directory.resolve("crash-receipt.txt")),
        recovery,
        "peer crash receipt");
    recordPhase(harness, "stimulus-observed", engine, null);
    await(
        () -> !ProcessHandle.of(old.pid).map(ProcessHandle::isAlive).orElse(false),
        recovery,
        "old peer death");
    await(engine.readers::terminated, recovery, "old reader cleanup");
    await(() -> !engine.engine.isStarted(), recovery, "old engine stopped state");

    Set<Thread> beforeRestart = productionReaderThreads();
    harness.manager.reStartEngine();
    Incarnation replacement =
        awaitIncarnation(harness.work.resolve("crash-peer"), 2, "crash-on-release", recovery);
    ReaderThreads replacementReaders = awaitReaderThreads(beforeRestart, recovery);
    EngineRun restarted =
        new EngineRun(engine.engine, engine.index, replacement, replacementReaders);
    await(
        () ->
            engine.engine.isLoaded()
                && peerFinalPosition(replacement.directory.resolve("peer-state.txt")),
        recovery,
        "restarted exact snapshot restore");
    if (!engine.engine.isPondering()) {
      engine.engine.ponder();
    }
    awaitHealthy(harness, restarted, "Q16", recovery);
    assertAnalysisStartedAfterRestore(replacement);
    Path newStaged = stagedSgf(replacement);
    assertApplicationPosition(harness.history, harness.target, "Q16", true);
    recordPhase(harness, "failure-recovery-complete", engine, restarted);

    Deadline cleanup = harness.budget.cleanup();
    closeEngine(restarted, cleanup);
    await(() -> !Files.exists(oldStaged), cleanup, "old staged-file deletion");
    await(() -> !Files.exists(newStaged), cleanup, "new staged-file deletion");
    recordPhase(harness, "cleanup-complete", engine, restarted);
    return evidence(
        harness,
        engine,
        restarted,
        old.directory.resolve("crash-receipt.txt"),
        List.of(oldStaged, newStaged),
        "Q16");
  }

  private static ScenarioEvidence runLateOutputSwitch(Harness harness) throws Exception {
    DesktopProbeProcess.phase(harness.result, "late-output-switch-start");
    Deadline initialRestore = harness.budget.initialRestore();
    Set<Thread> existingReaders = productionReaderThreads();
    EngineRun old =
        addAndSwitch(harness, "old-peer", "late-output", existingReaders, true, initialRestore);
    awaitHealthy(harness, old, "D4", initialRestore);
    Path oldStaged = stagedSgf(old.incarnation);
    recordPhase(harness, "initial-restore-complete", old, null);

    Deadline recovery = harness.budget.failureRecovery();
    Set<Thread> beforeNew = productionReaderThreads();
    EngineRun current =
        addAndSwitch(harness, "new-peer", "healthy-distinct", beforeNew, true, recovery);
    awaitHealthy(harness, current, "Q16", recovery);
    Path newStaged = stagedSgf(current.incarnation);
    Path fastChangeReceipt = old.incarnation.directory.resolve("fast-change-stop.txt");
    await(() -> Files.isRegularFile(fastChangeReceipt), recovery, "old fast-change stop receipt");
    assertFastChangeReceipt(fastChangeReceipt);
    if (!ProcessHandle.of(old.incarnation.pid).map(ProcessHandle::isAlive).orElse(false)) {
      throw new AssertionError("fast-change predecessor exited before stale-output stimulus");
    }
    Files.writeString(harness.work.resolve("old-peer/release-001-late-output"), "release\n");
    await(
        () -> Files.isRegularFile(old.incarnation.directory.resolve("late-output-complete.txt")),
        recovery,
        "old late-output flush");
    assertLateOutputReceipt(old.incarnation);
    recordPhase(harness, "stimulus-observed", old, current);

    CountDownLatch oldReaderDrained = new CountDownLatch(1);
    LeelazResponseTestBridge.sendCommandWithResponse(
        old.engine, "name", oldReaderDrained::countDown);
    await(
        () -> oldReaderDrained.getCount() == 0,
        recovery,
        "old production reader stale-output barrier");
    if (!ProcessHandle.of(old.incarnation.pid).map(ProcessHandle::isAlive).orElse(false)) {
      throw new AssertionError("old peer died instead of flushing bounded stale output");
    }
    int beforePeerCount = analysisCount(current.incarnation);
    int beforeApplicationVisits = analysis(harness.target, "Q16").orElseThrow().playouts;
    await(
        () ->
            analysisCount(current.incarnation) > beforePeerCount
                && analysis(harness.target, "Q16")
                    .map(move -> move.playouts > beforeApplicationVisits)
                    .orElse(false),
        recovery,
        "further active-engine analysis marker");
    assertEquals(current.engine, Lizzie.leelaz, "new peer must remain primary");
    assertEquals(current.index, EngineManager.currentEngineNo);
    assertTrue(current.engine.isPondering(), "new peer must remain the active analysis owner");
    assertApplicationPosition(harness.history, harness.target, "Q16", true);
    if (analysis(harness.target, "C3").isPresent()) {
      throw new AssertionError("retired engine output reached the active history node");
    }
    recordPhase(harness, "failure-recovery-complete", old, current);

    Deadline cleanup = harness.budget.cleanup();
    closeEngine(old, cleanup);
    closeEngine(current, cleanup);
    await(() -> !Files.exists(oldStaged), cleanup, "old staged-file deletion");
    await(() -> !Files.exists(newStaged), cleanup, "new staged-file deletion");
    recordPhase(harness, "cleanup-complete", old, current);
    return evidence(
        harness,
        old,
        current,
        old.incarnation.directory.resolve("late-output-complete.txt"),
        List.of(oldStaged, newStaged),
        "Q16");
  }

  private static ScenarioEvidence runPipeBurst(Harness harness) throws Exception {
    DesktopProbeProcess.phase(harness.result, "pipe-burst-start");
    Deadline initialRestore = harness.budget.initialRestore();
    Set<Thread> existingReaders = productionReaderThreads();
    EngineRun engine =
        addAndSwitch(harness, "burst-peer", "pipe-burst", existingReaders, true, initialRestore);
    awaitHealthy(harness, engine, "D4", initialRestore);
    Path staged = stagedSgf(engine.incarnation);
    recordPhase(harness, "initial-restore-complete", engine, null);

    Deadline recovery = harness.budget.failureRecovery();
    Deadline burstDeadline = Deadline.after(Duration.ofSeconds(10));
    Path peer = engine.incarnation.directory;
    Files.writeString(harness.work.resolve("burst-peer/release-001-burst"), "release\n");
    Path stdoutFirst = peer.resolve("burst-stdout-first.txt");
    Path stdoutProgress = peer.resolve("burst-stdout-progress.txt");
    Path stdoutLast = peer.resolve("burst-stdout-last.txt");
    Path stderrFirst = peer.resolve("burst-stderr-first.txt");
    Path stderrProgress = peer.resolve("burst-stderr-progress.txt");
    Path stderrLast = peer.resolve("burst-stderr-last.txt");
    await(
        () -> Files.isRegularFile(stdoutFirst) && Files.isRegularFile(stderrFirst),
        burstDeadline,
        "independent stdout/stderr burst start");
    assertBurstReceipt(stdoutFirst, "stdout", 1, 1, engine.incarnation.pid, 1);
    assertBurstReceipt(stderrFirst, "stderr", 1, 1, engine.incarnation.pid, 1);
    recordPhase(harness, "stimulus-observed", engine, null);

    CountDownLatch controlResponse = new CountDownLatch(1);
    LeelazResponseTestBridge.sendCommandWithResponse(engine.engine, "name", controlResponse::countDown);
    await(
        () -> controlResponse.getCount() == 0,
        burstDeadline,
        "production control response during pipe burst");
    Path controlReceipt = peer.resolve("burst-control-received.txt");
    await(() -> Files.isRegularFile(controlReceipt), burstDeadline, "peer control receipt");
    callWithin(
        () -> {
          SwingUtilities.invokeAndWait(() -> {});
          return null;
        },
        burstDeadline,
        "EDT round-trip during pipe burst");
    await(
        () -> Files.isRegularFile(stdoutProgress) && Files.isRegularFile(stderrProgress),
        burstDeadline,
        "independent stdout/stderr burst progress");
    assertBurstReceipt(stdoutProgress, "stdout", 1, 1024, engine.incarnation.pid, 1);
    assertBurstReceipt(stderrProgress, "stderr", 1, 1024, engine.incarnation.pid, 1);
    if (Files.exists(stdoutLast) || Files.exists(stderrLast)) {
      throw new AssertionError("burst writers completed before responsiveness probes");
    }
    Files.writeString(harness.work.resolve("burst-peer/release-002-burst-finish"), "release\n");
    await(
        () -> Files.isRegularFile(stdoutLast) && Files.isRegularFile(stderrLast),
        burstDeadline,
        "bounded stdout/stderr burst completion");
    Map<String, String> stdoutCompletion =
        assertBurstReceipt(stdoutLast, "stdout", 1, 2048, engine.incarnation.pid, 1);
    Map<String, String> stderrCompletion =
        assertBurstReceipt(stderrLast, "stderr", 1, 2048, engine.incarnation.pid, 1);
    await(
        () -> analysis(harness.target, "D4").map(move -> move.playouts >= 900000).orElse(false),
        burstDeadline,
        "final burst analysis marker");
    assertApplicationPosition(harness.history, harness.target, "D4", true);
    recordPhase(harness, "failure-recovery-complete", engine, null);

    Deadline cleanup = harness.budget.cleanup();
    closeEngine(engine, cleanup);
    await(() -> Files.isRegularFile(peer.resolve("quit.txt")), cleanup, "peer quit receipt");
    await(
        () -> Files.isRegularFile(peer.resolve("peer-complete.txt")),
        cleanup,
        "peer natural completion");
    await(() -> !Files.exists(staged), cleanup, "burst staged-file deletion");
    recordPhase(harness, "cleanup-complete", engine, null);

    Map<String, String> details = new LinkedHashMap<>();
    details.put("burst.stdout.bytes", stdoutCompletion.get("bytes"));
    details.put("burst.stderr.bytes", stderrCompletion.get("bytes"));
    details.put("burst.stdout.duration-nanos", stdoutCompletion.get("duration.nanos"));
    details.put("burst.stderr.duration-nanos", stderrCompletion.get("duration.nanos"));
    details.put("evidence.burst-stdout-first", stdoutFirst.toString());
    details.put("evidence.burst-stdout-progress", stdoutProgress.toString());
    details.put("evidence.burst-stdout-last", stdoutLast.toString());
    details.put("evidence.burst-stderr-first", stderrFirst.toString());
    details.put("evidence.burst-stderr-progress", stderrProgress.toString());
    details.put("evidence.burst-stderr-last", stderrLast.toString());
    details.put("evidence.burst-control", controlReceipt.toString());
    return evidence(
        harness, engine, null, stdoutLast, List.of(staged), "D4", Map.copyOf(details));
  }

  private static ScenarioEvidence runQuitRefusal(Harness harness) throws Exception {
    DesktopProbeProcess.phase(harness.result, "quit-refusal-start");
    Deadline initialRestore = harness.budget.initialRestore();
    Set<Thread> existingReaders = productionReaderThreads();
    EngineRun engine =
        addAndSwitch(
            harness, "quit-peer", "quit-refusal", existingReaders, true, initialRestore);
    awaitHealthy(harness, engine, "D4", initialRestore);
    Path staged = stagedSgf(engine.incarnation);
    recordPhase(harness, "initial-restore-complete", engine, null);

    Deadline recovery = harness.budget.failureRecovery();
    assertApplicationPosition(harness.history, harness.target, "D4", true);
    await(
        () -> ProcessHandle.of(engine.incarnation.pid).map(ProcessHandle::isAlive).orElse(false),
        recovery,
        "quit-refusing peer before normal quit");

    Deadline cleanup = harness.budget.cleanup();
    try {
      callWithin(
          () -> {
            engine.engine.normalQuit();
            return null;
          },
          cleanup,
          "normal quit of refusing peer");
    } catch (Throwable normalQuitFailure) {
      Deadline containment = Deadline.after(Duration.ofSeconds(5));
      try {
        engine.engine.forceQuit();
        await(
            () ->
                !ProcessHandle.of(engine.incarnation.pid)
                    .map(ProcessHandle::isAlive)
                    .orElse(false),
            containment,
            "forced containment of refusing peer");
        await(engine.readers::terminated, containment, "forced reader containment");
        await(() -> !Files.exists(staged), containment, "forced staged-file containment");
      } catch (Throwable containmentFailure) {
        normalQuitFailure.addSuppressed(containmentFailure);
      }
      if (normalQuitFailure instanceof Exception exception) {
        throw exception;
      }
      if (normalQuitFailure instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(normalQuitFailure);
    }

    Path refusal = engine.incarnation.directory.resolve("quit-refused.txt");
    await(() -> Files.isRegularFile(refusal), cleanup, "explicit quit refusal receipt");
    await(
        () -> !ProcessHandle.of(engine.incarnation.pid).map(ProcessHandle::isAlive).orElse(false),
        cleanup,
        "OS termination of quit-refusing peer");
    await(engine.readers::terminated, cleanup, "refusing peer reader termination");
    await(() -> !Files.exists(staged), cleanup, "refusing peer staged-file deletion");
    await(
        () ->
            !engine.engine.isStarted()
                && !engine.engine.isLoaded()
                && !engine.engine.isPondering(),
        cleanup,
        "terminal nonpondering application state");
    assertApplicationPosition(harness.history, harness.target, "D4", true);
    recordPhase(harness, "stimulus-observed", engine, null);
    recordPhase(harness, "failure-recovery-complete", engine, null);
    recordPhase(harness, "cleanup-complete", engine, null);
    return evidence(harness, engine, null, refusal, List.of(staged), "D4");
  }

  private static EngineRun addAndSwitch(
      Harness harness,
      String directoryName,
      String peerScenario,
      Set<Thread> existingReaders,
      boolean main,
      Deadline deadline)
      throws Exception {
    Path control = Files.createDirectory(harness.work.resolve(directoryName));
    Path java =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            "java" + (System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : ""));
    String command =
        quote(java.toString())
            + " -cp "
            + quote(System.getProperty("java.class.path"))
            + " "
            + ControlledGtpPeer.class.getName()
            + " "
            + quote(control.toString())
            + " "
            + peerScenario;
    int index = harness.manager.engineList.size();
    EngineData entry = catalogEntry(index, command, directoryName);
    harness.catalogEntries.add(entry);
    Utils.saveEngineSettings(new ArrayList<>(harness.catalogEntries));
    CatalogNamedLeelaz controlled = new CatalogNamedLeelaz(entry);
    SnapshotFileAccessTestBridge.trustDirectLocalSnapshotFileAccessForTest(controlled);
    harness.manager.engineList.add(controlled);
    harness.ownedEngines.add(controlled);

    if (!callWithin(
        () -> harness.manager.switchEngineIfAvailable(index, main),
        deadline,
        "production engine switch")) {
      throw new AssertionError("production engine switch rejected controlled catalog entry");
    }
    Incarnation incarnation = awaitIncarnation(control, 1, peerScenario, deadline);
    ReaderThreads readers = awaitReaderThreads(existingReaders, deadline);
    return new EngineRun(controlled, index, incarnation, readers);
  }

  private static Incarnation awaitIncarnation(
      Path control, int ordinal, String expectedScenario, Deadline deadline) throws Exception {
    Path directory = control.resolve(String.format("incarnation-%03d", ordinal));
    Path identityPath = directory.resolve("identity.txt");
    AtomicReference<Incarnation> found = new AtomicReference<>();
    await(
        () -> {
          if (!Files.isRegularFile(identityPath)) {
            return false;
          }
          Map<String, String> identity =
              readRecords(identityPath, Set.of("pid", "launch.ordinal", "scenario"));
          long pid = Long.parseLong(identity.get("pid"));
          int actualOrdinal = Integer.parseInt(identity.get("launch.ordinal"));
          if (pid <= 0
              || actualOrdinal != ordinal
              || !identity.get("scenario").equals(expectedScenario)) {
            throw new AssertionError("invalid peer incarnation identity: " + identity);
          }
          found.set(new Incarnation(directory, identityPath, pid, actualOrdinal));
          return true;
        },
        deadline,
        "controlled peer incarnation " + ordinal);
    return found.get();
  }

  private static void awaitHealthy(Harness harness, EngineRun run, String move, Deadline deadline)
      throws Exception {
    await(
        () ->
            run.engine.isLoaded()
                && harness.manager.isEngineSwitchActive(run.index, true)
                && peerFinalPosition(run.incarnation.directory.resolve("peer-state.txt")),
        deadline,
        "production snapshot restore");
    await(
        () -> run.engine.isPondering() && analysis(harness.target, move).isPresent(),
        deadline,
        "current-node analysis " + move);
    assertApplicationPosition(harness.history, harness.target, move, true);
  }

  private static void closeEngine(EngineRun run, Deadline deadline) throws Exception {
    callWithin(
        () -> {
          run.engine.normalQuit();
          return null;
        },
        deadline,
        "production normal quit");
    await(
        () -> !ProcessHandle.of(run.incarnation.pid).map(ProcessHandle::isAlive).orElse(false),
        deadline,
        "owned peer exit");
    await(run.readers::terminated, deadline, "production reader termination");
  }

  private static void assertNoTailOrAnalysis(Incarnation incarnation) throws IOException {
    String commands =
        Files.readString(incarnation.directory.resolve("commands.log"), StandardCharsets.UTF_8);
    if (commands
        .lines()
        .map(EngineProcessFailureTest::commandName)
        .anyMatch(command -> command.equals("play") || command.equals("kata-analyze"))) {
      throw new AssertionError("failed restore released tail or analysis: " + commands);
    }
    Map<String, String> state = peerState(incarnation.directory.resolve("peer-state.txt"));
    if (!state.get("tail").isEmpty()
        || Integer.parseInt(state.get("analysis.count")) != 0
        || Boolean.parseBoolean(state.get("analysis.active"))) {
      throw new AssertionError("failed peer observed tail or analysis: " + state);
    }
  }

  private static String commandName(String line) {
    String[] words = line.trim().split("\\s+");
    if (words.length == 0 || words[0].isEmpty()) {
      return "";
    }
    return words[0].chars().allMatch(Character::isDigit) && words.length > 1 ? words[1] : words[0];
  }

  private static void assertLateOutputReceipt(Incarnation incarnation) throws IOException {
    Path receipt = incarnation.directory.resolve("late-output-complete.txt");
    Map<String, String> records =
        readRecords(
            receipt, Set.of("pid", "launch.ordinal", "first.visits", "last.visits", "count"));
    if (!records.get("pid").equals(Long.toString(incarnation.pid))
        || !records.get("launch.ordinal").equals(Integer.toString(incarnation.ordinal))
        || !records.get("first.visits").equals("9001")
        || !records.get("last.visits").equals("9005")
        || !records.get("count").equals("5")) {
      throw new AssertionError("invalid late-output receipt: " + records);
    }
  }

  private static void assertFastChangeReceipt(Path receipt) throws IOException {
    Map<String, String> records = readRecords(receipt, Set.of("command", "analysis.count"));
    if (!records.get("command").equals("stop")) {
      throw new AssertionError("KataGo nameCmdfornoponder branch did not send stop: " + records);
    }
    nonnegativeRecord(records, "analysis.count", receipt);
  }

  private static void assertAnalysisStartedAfterRestore(Incarnation incarnation)
      throws IOException {
    Path analysisStart = incarnation.directory.resolve("analysis-start-state.txt");
    if (!Files.isRegularFile(incarnation.directory.resolve("snapshot-state.txt"))
        || !Files.isRegularFile(incarnation.directory.resolve("loaded.sgf"))
        || !peerFinalPosition(analysisStart)) {
      throw new AssertionError("analysis began before exact snapshot and tail restore");
    }
    Map<String, String> startState = peerState(analysisStart);
    if (!startState.get("analysis.active").equals("false")) {
      throw new AssertionError("analysis-start state was already active: " + startState);
    }

    List<String> commands =
        Files.readAllLines(incarnation.directory.resolve("commands.log"), StandardCharsets.UTF_8)
            .stream()
            .map(EngineProcessFailureTest::normalizedCommand)
            .toList();
    int load = firstCommand(commands, 0, "loadsgf ");
    int whiteTail = firstCommand(commands, load + 1, "play W H12");
    int passTail = firstCommand(commands, whiteTail + 1, "play B pass");
    int analysis = firstCommand(commands, 0, "kata-analyze ");
    if (load < 0 || whiteTail <= load || passTail <= whiteTail || analysis <= passTail) {
      throw new AssertionError("analysis did not follow exact restore ordering: " + commands);
    }
  }

  private static int firstCommand(List<String> commands, int start, String expected) {
    for (int index = Math.max(0, start); index < commands.size(); index++) {
      String command = commands.get(index);
      if (command.equals(expected) || command.startsWith(expected)) {
        return index;
      }
    }
    return -1;
  }

  private static String normalizedCommand(String line) {
    return line.trim().replaceFirst("^[0-9]+\\s+", "");
  }

  private static Path stagedSgf(Incarnation incarnation) throws IOException {
    return Path.of(
        Files.readString(incarnation.directory.resolve("loaded.path"), StandardCharsets.UTF_8));
  }

  private static int analysisCount(Incarnation incarnation) throws IOException {
    return Integer.parseInt(
        peerState(incarnation.directory.resolve("peer-state.txt")).get("analysis.count"));
  }

  private static boolean peerFinalPosition(Path statePath) throws IOException {
    if (!Files.isRegularFile(statePath)) {
      return false;
    }
    Map<String, String> state = peerState(statePath);
    return state.get("board").equals("19")
        && state.get("komi").equals("6.5")
        && state.get("stones").equals("B:fd;W:ee,ff,hh")
        && state.get("turn").equals("W")
        && state.get("tail").equals("W[hh],B[]");
  }

  private static Map<String, String> peerState(Path path) throws IOException {
    return readRecords(
        path,
        Set.of(
            "board",
            "komi",
            "stones",
            "turn",
            "tail",
            "analysis.count",
            "analysis.active",
            "stop.command",
            "loaded.path"));
  }

  private static ScenarioEvidence evidence(
      Harness harness,
      EngineRun oldRun,
      EngineRun newRun,
      Path oldReceipt,
      List<Path> stagedSgfs,
      String expectedMove)
      throws IOException {
    return evidence(harness, oldRun, newRun, oldReceipt, stagedSgfs, expectedMove, Map.of());
  }

  private static ScenarioEvidence evidence(
      Harness harness,
      EngineRun oldRun,
      EngineRun newRun,
      Path oldReceipt,
      List<Path> stagedSgfs,
      String expectedMove,
      Map<String, String> details)
      throws IOException {
    int oldAnalysis = analysisCount(oldRun.incarnation);
    int newAnalysis = newRun == null ? 0 : analysisCount(newRun.incarnation);
    return new ScenarioEvidence(
        oldRun,
        newRun,
        oldReceipt,
        stagedSgfs,
        expectedMove,
        oldAnalysis,
        newAnalysis,
        harness.work.resolve("logs/app.log"),
        details);
  }

  private static void recordPhase(Harness harness, String phase, EngineRun oldRun, EngineRun newRun)
      throws IOException {
    StringBuilder description = new StringBuilder(phase);
    if (oldRun != null) {
      description
          .append(" old.pid=")
          .append(oldRun.incarnation.pid)
          .append(" old.launch=")
          .append(oldRun.incarnation.ordinal);
    }
    if (newRun != null) {
      description
          .append(" new.pid=")
          .append(newRun.incarnation.pid)
          .append(" new.launch=")
          .append(newRun.incarnation.ordinal);
    }
    DesktopProbeProcess.phase(harness.result, description.toString());
  }

  private static void writeResult(
      Path result, Scenario scenario, Path fixture, ScenarioEvidence evidence) throws IOException {
    Map<String, String> records = new LinkedHashMap<>();
    records.put("scenario", scenario.argument);
    records.put("peer.kind", "controlled-java");
    records.put("file-access", "test-trusted-local");
    records.put("fixture.path", fixture.toString());
    records.put("outcome", "passed");
    for (String key : COMMON_BOOLEAN_KEYS) {
      records.put(key, "true");
    }
    for (String key : scenario.verdictKeys) {
      records.put(key, "true");
    }
    records.putAll(evidence.details);
    records.put("peer.old.pid", Long.toString(evidence.oldRun.incarnation.pid));
    records.put(
        "peer.new.pid",
        evidence.newRun == null ? "0" : Long.toString(evidence.newRun.incarnation.pid));
    records.put("peer.old.launch", Integer.toString(evidence.oldRun.incarnation.ordinal));
    records.put(
        "peer.new.launch",
        evidence.newRun == null ? "0" : Integer.toString(evidence.newRun.incarnation.ordinal));
    records.put("analysis.old", Integer.toString(evidence.oldAnalysis));
    records.put("analysis.new", Integer.toString(evidence.newAnalysis));
    records.put("evidence.old-manifest", evidence.oldRun.incarnation.identityPath.toString());
    records.put(
        "evidence.old-state",
        evidence.oldRun.incarnation.directory.resolve("peer-state.txt").toString());
    records.put(
        "evidence.old-commands",
        evidence.oldRun.incarnation.directory.resolve("commands.log").toString());
    records.put("evidence.old-receipt", evidence.oldReceipt.toString());
    records.put(
        "evidence.new-manifest",
        evidence.newRun == null ? "none" : evidence.newRun.incarnation.identityPath.toString());
    records.put(
        "evidence.new-state",
        evidence.newRun == null
            ? "none"
            : evidence.newRun.incarnation.directory.resolve("peer-state.txt").toString());
    records.put(
        "evidence.new-commands",
        evidence.newRun == null
            ? "none"
            : evidence.newRun.incarnation.directory.resolve("commands.log").toString());
    records.put(
        "evidence.switch-stop-receipt",
        scenario == Scenario.LATE_OUTPUT_SWITCH
            ? evidence.oldRun.incarnation.directory.resolve("fast-change-stop.txt").toString()
            : "none");
    records.put("evidence.app-log", evidence.appLog.toString());
    records.put("evidence.stdout", result.getParent().resolve("stdout.log").toString());
    records.put("evidence.stderr", result.getParent().resolve("stderr.log").toString());
    records.put("evidence.phases", result.resolveSibling("phases.log").toString());
    records.put(
        "evidence.staged-sgfs",
        String.join("|", evidence.stagedSgfs.stream().map(Path::toString).toList()));
    if (!records.keySet().equals(expectedResultKeys(scenario))) {
      throw new AssertionError("child result schema mismatch: " + records.keySet());
    }
    StringBuilder text = new StringBuilder();
    records.forEach(
        (key, value) -> {
          if (value == null
              || value.isEmpty()
              || value.indexOf('\n') >= 0
              || value.indexOf('\r') >= 0) {
            throw new AssertionError("invalid child result value: " + key);
          }
          text.append(key).append('=').append(value).append('\n');
        });
    Files.writeString(result, text, StandardCharsets.UTF_8);
  }

  private static void assertApplicationPosition(
      BoardHistoryList history,
      BoardHistoryNode capturedTarget,
      String expectedMove,
      boolean requireAnalysis) {
    if (Board.boardWidth != 19 || Board.boardHeight != 19) {
      throw new AssertionError("application board size is not 19x19");
    }
    if (Double.compare(history.getGameInfo().getKomi(), 6.5) != 0) {
      throw new AssertionError("application komi is not 6.5");
    }
    if (history.getCurrentHistoryNode() != capturedTarget
        || Lizzie.board.getHistory() != history
        || Lizzie.board.getHistory().getCurrentHistoryNode() != capturedTarget) {
      throw new AssertionError("application history identity or current node changed");
    }
    BoardData current = capturedTarget.getData();
    String occupied = applicationStones(current);
    if (!occupied.equals("B:fd;W:ee,ff,hh")) {
      throw new AssertionError("unexpected application stones: " + occupied);
    }
    requireStone(current, "dd", Stone.EMPTY);
    if (current.blackToPlay) {
      throw new AssertionError("application side to play is not white");
    }
    BoardData previous = capturedTarget.previous().orElseThrow().getData();
    if (current.getNodeKind() != BoardNodeKind.PASS
        || current.lastMove.isPresent()
        || current.lastMoveColor != Stone.BLACK
        || current.dummy
        || current.moveNumber != previous.moveNumber + 1) {
      throw new AssertionError("current node is not the genuine consuming black PASS");
    }
    if (previous.getNodeKind() != BoardNodeKind.MOVE
        || previous.lastMoveColor != Stone.WHITE
        || previous.lastMove.isEmpty()
        || !Arrays.equals(previous.lastMove.get(), new int[] {7, 7})) {
      throw new AssertionError("previous node is not W[hh]");
    }
    if (requireAnalysis
        && (expectedMove == null || analysis(capturedTarget, expectedMove).isEmpty())) {
      throw new AssertionError("captured current node has no " + expectedMove + " analysis");
    }
  }

  private static String applicationStones(BoardData data) {
    List<String> black = new ArrayList<>();
    List<String> white = new ArrayList<>();
    for (int x = 0; x < Board.boardWidth; x++) {
      for (int y = 0; y < Board.boardHeight; y++) {
        Stone stone = data.stones[Board.getIndex(x, y)];
        if (stone == Stone.EMPTY) {
          continue;
        }
        String coordinate = "" + (char) ('a' + x) + (char) ('a' + y);
        if (stone == Stone.BLACK) {
          black.add(coordinate);
        } else if (stone == Stone.WHITE) {
          white.add(coordinate);
        } else {
          throw new AssertionError("unexpected stone at " + coordinate + ": " + stone);
        }
      }
    }
    List<String> groups = new ArrayList<>();
    if (!black.isEmpty()) {
      groups.add("B:" + String.join(",", black));
    }
    if (!white.isEmpty()) {
      groups.add("W:" + String.join(",", white));
    }
    return String.join(";", groups);
  }

  private static void requireStone(BoardData data, String coordinate, Stone expected) {
    int x = coordinate.charAt(0) - 'a';
    int y = coordinate.charAt(1) - 'a';
    Stone actual = data.stones[Board.getIndex(x, y)];
    if (actual != expected) {
      throw new AssertionError(coordinate + " expected " + expected + " but was " + actual);
    }
  }

  private static Optional<MoveData> analysis(BoardHistoryNode target, String coordinate) {
    List<MoveData> moves = target.getData().bestMoves;
    if (moves == null) {
      return Optional.empty();
    }
    return moves.stream()
        .filter(move -> coordinate.equals(move.coordinate) && move.playouts > 0)
        .findFirst();
  }

  private static ReaderThreads awaitReaderThreads(Set<Thread> existing, Deadline deadline)
      throws Exception {
    AtomicReference<ReaderThreads> found = new AtomicReference<>();
    await(
        () -> {
          List<Thread> candidates =
              productionReaderThreads().stream()
                  .filter(thread -> !existing.contains(thread))
                  .toList();
          Thread stdout = null;
          Thread stderr = null;
          for (Thread candidate : candidates) {
            ReaderKind kind = readerKind(candidate);
            if (kind == ReaderKind.STDOUT) {
              if (stdout != null && stdout != candidate) {
                return false;
              }
              stdout = candidate;
            } else if (kind == ReaderKind.STDERR) {
              if (stderr != null && stderr != candidate) {
                return false;
              }
              stderr = candidate;
            }
          }
          if (stdout == null || stderr == null || stdout == stderr) {
            return false;
          }
          found.set(new ReaderThreads(stdout, stderr));
          return true;
        },
        deadline,
        "new production reader pair");
    return found.get();
  }

  private static Set<Thread> productionReaderThreads() {
    Set<Thread> readers = new LinkedHashSet<>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && readerKind(thread) != ReaderKind.NONE) {
        readers.add(thread);
      }
    }
    return readers;
  }

  private static ReaderKind readerKind(Thread thread) {
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (!frame.getClassName().equals(Leelaz.class.getName())) {
        continue;
      }
      if (frame.getMethodName().equals("read")) {
        return ReaderKind.STDOUT;
      }
      if (frame.getMethodName().equals("readError")) {
        return ReaderKind.STDERR;
      }
    }
    return ReaderKind.NONE;
  }

  private static void dismissDialogs(Deadline deadline) throws Exception {
    await(
        () ->
            Arrays.stream(Window.getWindows())
                .anyMatch(window -> window instanceof Dialog && window.isShowing()),
        deadline,
        "engine synchronization failure dialog");
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog) {
              window.dispose();
            }
          }
        });
    await(
        () ->
            Arrays.stream(Window.getWindows())
                .noneMatch(window -> window instanceof Dialog && window.isShowing()),
        deadline,
        "failure dialog dismissal");
  }

  private static Map<String, String> readRecords(Path path, Set<String> expectedKeys)
      throws IOException {
    Map<String, String> records = new LinkedHashMap<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      int separator = line.indexOf('=');
      if (separator <= 0) {

        throw new AssertionError("malformed evidence record in " + path + ": " + line);
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (!expectedKeys.contains(key) || records.putIfAbsent(key, value) != null) {
        throw new AssertionError("unexpected or duplicate evidence key in " + path + ": " + key);
      }
    }
    if (!records.keySet().equals(expectedKeys)) {
      Set<String> missing = new LinkedHashSet<>(expectedKeys);
      missing.removeAll(records.keySet());
      throw new AssertionError("missing evidence keys in " + path + ": " + missing);
    }
    return records;
  }

  private static EngineData catalogEntry(int index, String command, String name) {
    EngineData entry = new EngineData();
    entry.id = UUID.randomUUID().toString();
    entry.index = index;
    entry.commands = command;
    entry.name = "D2 " + name;
    entry.preload = false;
    entry.width = 19;
    entry.height = 19;
    entry.isDefault = false;
    entry.komi = 6.5f;
    return entry;
  }

  private static void await(CheckedCondition condition, Deadline deadline, String description)
      throws Exception {
    Throwable lastFailure = null;
    while (deadline.hasTime()) {
      try {
        if (condition.test()) {
          return;
        }
        lastFailure = null;
      } catch (IOException | IllegalArgumentException failure) {
        lastFailure = failure;
      }
      TimeUnit.NANOSECONDS.sleep(
          Math.min(TimeUnit.MILLISECONDS.toNanos(25), deadline.remainingNanos()));
    }
    AssertionError timeout = new AssertionError("Timed out awaiting " + description);
    if (lastFailure != null) {
      timeout.initCause(lastFailure);
    }
    throw timeout;
  }

  private static <T> T callWithin(CheckedSupplier<T> action, Deadline deadline, String description)
      throws Exception {
    FutureTask<T> task = new FutureTask<>(action::get);
    Thread worker = new Thread(task, "engine-process-failure-" + description.replace(' ', '-'));
    worker.setDaemon(true);
    worker.start();
    try {
      return task.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      task.cancel(true);
      throw new AssertionError("Timed out awaiting " + description, failure);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(cause);
    }
  }

  private static String quote(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private static String clean(String message) {
    return message == null ? "unspecified failure" : message.replace('\n', ' ').replace('\r', ' ');
  }

  @FunctionalInterface
  private interface CheckedCondition {
    boolean test() throws Exception;
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }

  private enum ReaderKind {
    NONE,
    STDOUT,
    STDERR
  }

  private enum Scenario {
    SNAPSHOT_ERROR(
        "snapshot-error", List.of("error.fail-closed"), "snapshot-error", null, false, false, 0),
    SNAPSHOT_TIMEOUT(
        "snapshot-timeout",
        List.of("timeout.fail-closed", "timeout.late-isolated"),
        "snapshot-timeout",
        "healthy-distinct",
        true,
        false,
        1),
    CRASH_RESTART(
        "crash-restart",
        List.of("restart.converged"),
        "crash-on-release",
        "crash-on-release",
        true,
        true,
        2),
    LATE_OUTPUT_SWITCH(
        "late-output-switch",
        List.of("switch.stale-isolated"),
        "late-output",
        "healthy-distinct",
        true,
        true,
        1),
    PIPE_BURST(
        "pipe-burst",
        List.of("burst.drained", "burst.responsive"),
        "pipe-burst",
        null,
        false,
        true,
        0),
    QUIT_REFUSAL(
        "quit-refusal",
        List.of("quit.refused", "quit.cleaned"),
        "quit-refusal",
        null,
        false,
        true,
        0);

    private final String argument;
    private final List<String> verdictKeys;
    private final String oldPeerScenario;
    private final String newPeerScenario;
    private final boolean newPeerRequired;
    private final boolean oldAnalysisRequired;
    private final int expectedNewLaunch;

    Scenario(
        String argument,
        List<String> verdictKeys,
        String oldPeerScenario,
        String newPeerScenario,
        boolean newPeerRequired,
        boolean oldAnalysisRequired,
        int expectedNewLaunch) {
      this.argument = argument;
      this.verdictKeys = verdictKeys;
      this.oldPeerScenario = oldPeerScenario;
      this.newPeerScenario = newPeerScenario;
      this.newPeerRequired = newPeerRequired;
      this.oldAnalysisRequired = oldAnalysisRequired;
      this.expectedNewLaunch = expectedNewLaunch;
    }

    private Set<String> detailKeys() {
      if (this != PIPE_BURST) {
        return Set.of();
      }
      return Set.of(
          "burst.stdout.bytes",
          "burst.stderr.bytes",
          "burst.stdout.duration-nanos",
          "burst.stderr.duration-nanos",
          "evidence.burst-stdout-first",
          "evidence.burst-stdout-progress",
          "evidence.burst-stdout-last",
          "evidence.burst-stderr-first",
          "evidence.burst-stderr-progress",
          "evidence.burst-stderr-last",
          "evidence.burst-control");
    }

    private static Scenario parse(String argument) {
      for (Scenario candidate : values()) {
        if (candidate.argument.equals(argument)) {
          return candidate;
        }
      }
      throw new IllegalArgumentException("unknown scenario: " + argument);
    }
  }

  private static final class ProbeBudget {
    private int nextPhase;

    Deadline startup() {
      return start(0, Duration.ofSeconds(30));
    }

    Deadline initialRestore() {
      return start(1, Duration.ofSeconds(20));
    }

    Deadline failureRecovery() {
      return start(2, Duration.ofSeconds(25));
    }

    Deadline cleanup() {
      return start(3, Duration.ofSeconds(5));
    }

    private Deadline start(int expectedPhase, Duration duration) {
      if (nextPhase != expectedPhase) {
        throw new IllegalStateException(
            "probe phase budget requested out of order: " + expectedPhase + " after " + nextPhase);
      }
      nextPhase++;
      return Deadline.after(duration);
    }
  }

  private record Deadline(long nanoTime) {
    static Deadline after(Duration duration) {
      return new Deadline(System.nanoTime() + duration.toNanos());
    }

    boolean hasTime() {
      return remainingNanos() > 0;
    }

    long remainingNanos() {
      return Math.max(0, nanoTime - System.nanoTime());
    }
  }

  private record ReaderThreads(Thread stdout, Thread stderr) {
    boolean terminated() {
      return !stdout.isAlive() && !stderr.isAlive();
    }
  }

  private record Incarnation(Path directory, Path identityPath, long pid, int ordinal) {}

  private record EngineRun(
      Leelaz engine, int index, Incarnation incarnation, ReaderThreads readers) {}

  private record Harness(
      Path fixture,
      Path work,
      Path result,
      BoardHistoryList history,
      BoardHistoryNode target,
      EngineManager manager,
      List<Leelaz> ownedEngines,
      List<EngineData> catalogEntries,
      ProbeBudget budget) {}

  private record ScenarioEvidence(
      EngineRun oldRun,
      EngineRun newRun,
      Path oldReceipt,
      List<Path> stagedSgfs,
      String expectedMove,
      int oldAnalysis,
      int newAnalysis,
      Path appLog,
      Map<String, String> details) {}

  /** The only override is the display-name lookup permitted by the frozen test boundary. */
  private static final class CatalogNamedLeelaz extends Leelaz {
    private final String catalogName;

    private CatalogNamedLeelaz(EngineData data) throws Exception {
      super(data.commands);
      catalogName = data.name;
      savedEntryId = data.id;
      preload = data.preload;
      width = data.width;
      height = data.height;
      oriWidth = data.width;
      oriHeight = data.height;
      komi = data.komi;
      orikomi = data.komi;
      useJavaSSH = data.useJavaSSH;
      ip = data.ip;
      port = data.port;
      useKeyGen = data.useKeyGen;
      keyGenPath = data.keyGenPath;
      userName = data.userName;
      password = data.password;
      initialCommand = data.initialCommand;
      gtpConfigurationProtocol = data.gtpConfigurationProtocol;
      gtpConfigurationProfile =
          data.gtpConfigurationProfile == null
              ? null
              : new org.json.JSONObject(data.gtpConfigurationProfile.toString());
    }

    @Override
    public String getEngineName(int index) {
      return catalogName;
    }
  }
}
