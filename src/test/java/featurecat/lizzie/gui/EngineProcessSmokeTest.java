package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Real-window production ownership probe for SNAPSHOT restore, analysis, stop, and quit. */
public final class EngineProcessSmokeTest {
  private static final String FIXTURE =
      "src/test/resources/featurecat/lizzie/rules/issue223-snapshot-removed-stone.sgf";
  private static final Set<String> BOOLEAN_KEYS =
      Set.of(
          "startup",
          "snapshot",
          "tail",
          "position",
          "analysis.current-node",
          "stop",
          "quit",
          "cleanup.process",
          "cleanup.readers",
          "cleanup.sgf");
  private static final Set<String> RESULT_KEYS =
      new LinkedHashSet<>(
          List.of(
              "scenario",
              "peer.kind",
              "file-access",
              "fixture.path",
              "peer.pid",
              "startup",
              "snapshot",
              "tail",
              "position",
              "analysis.current-node",
              "stop",
              "quit",
              "cleanup.process",
              "cleanup.readers",
              "cleanup.sgf",
              "position.board",
              "position.komi",
              "position.stones",
              "position.empty",
              "position.turn",
              "tail.previous",
              "tail.current",
              "target.node",
              "analysis.move",
              "analysis.visits",
              "analysis.emitted",
              "analysis.received",
              "stop.command",
              "lifecycle.extra-stop",
              "evidence.commands",
              "evidence.snapshot-state",
              "evidence.peer-state",
              "evidence.loaded-sgf",
              "evidence.stopped",
              "evidence.quit",
              "evidence.peer-complete",
              "evidence.stdout",
              "evidence.stderr",
              "evidence.app-log",
              "evidence.staged-sgf",
              "evidence.phases"));
  private static final Set<String> EXISTING_EVIDENCE_KEYS =
      Set.of(
          "fixture.path",
          "evidence.commands",
          "evidence.snapshot-state",
          "evidence.peer-state",
          "evidence.loaded-sgf",
          "evidence.stopped",
          "evidence.quit",
          "evidence.peer-complete",
          "evidence.stdout",
          "evidence.stderr",
          "evidence.app-log",
          "evidence.phases");

  @Test
  void restoresSnapshotAnalyzesAndQuits() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path fixture = Path.of(FIXTURE).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(fixture), fixture.toString());

    Path result =
        DesktopProbeProcess.run(
            EngineProcessSmokeTest.class,
            "engine-process",
            List.of(),
            List.of("probe", fixture.toString()));
    Map<String, String> records = parseResult(result);

    assertEquals("d1-success", records.get("scenario"));
    assertEquals("controlled-java", records.get("peer.kind"));
    assertEquals("test-trusted-local", records.get("file-access"));
    assertEquals(fixture.toString(), records.get("fixture.path"));
    for (String key : BOOLEAN_KEYS) {
      assertEquals("true", records.get(key), key + " must be affirmative");
    }
    assertEquals("19x19", records.get("position.board"));
    assertEquals("6.5", records.get("position.komi"));
    assertEquals("B:fd;W:ee,ff,hh", records.get("position.stones"));
    assertEquals("dd", records.get("position.empty"));
    assertEquals("W", records.get("position.turn"));
    assertEquals("MOVE:W[hh]", records.get("tail.previous"));
    assertEquals("PASS:B[]", records.get("tail.current"));
    assertEquals("captured-final-pass", records.get("target.node"));
    assertEquals("D4", records.get("analysis.move"));
    assertTrue(Integer.parseInt(records.get("analysis.visits")) > 0);
    assertTrue(Integer.parseInt(records.get("analysis.emitted")) > 0);
    assertTrue(Integer.parseInt(records.get("analysis.received")) > 0);
    assertTrue(Set.of("stop", "name").contains(records.get("stop.command")));
    assertEquals("pre-process-placeholder-retirement", records.get("lifecycle.extra-stop"));

    long peerPid = Long.parseLong(records.get("peer.pid"));
    assertTrue(peerPid > 0);
    assertFalse(ProcessHandle.of(peerPid).map(ProcessHandle::isAlive).orElse(false));
    for (String key : EXISTING_EVIDENCE_KEYS) {
      assertTrue(Files.isRegularFile(Path.of(records.get(key))), key + " missing");
    }
    assertFalse(Files.exists(Path.of(records.get("evidence.staged-sgf"))));
  }

  private static Map<String, String> parseResult(Path result) throws IOException {
    Map<String, String> records = new LinkedHashMap<>();
    for (String line : Files.readAllLines(result, StandardCharsets.UTF_8)) {
      int separator = line.indexOf('=');
      if (separator <= 0 || separator == line.length() - 1) {
        throw new AssertionError("Malformed child result record: " + line);
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (!key.matches("[a-z][a-z0-9.-]*")) {
        throw new AssertionError("Malformed child result key: " + key);
      }
      if (!RESULT_KEYS.contains(key)) {
        throw new AssertionError("Unknown child result key: " + key);
      }
      if (records.putIfAbsent(key, value) != null) {
        throw new AssertionError("Duplicate child result key: " + key);
      }
    }
    Set<String> missing = new LinkedHashSet<>(RESULT_KEYS);
    missing.removeAll(records.keySet());
    if (!missing.isEmpty()) {
      throw new AssertionError("Missing child result keys: " + missing);
    }
    return records;
  }

  /** Child-process entry point. Starts the real application and exits only after owned cleanup. */
  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !"probe".equals(args[0])) {
      throw new IllegalArgumentException(
          "expected probe, fixture path, work directory, and result path");
    }
    Path fixture = Path.of(args[1]).toAbsolutePath().normalize();
    Path work = Path.of(args[2]).toAbsolutePath().normalize();
    Path result = Path.of(args[3]).toAbsolutePath().normalize();
    Leelaz engine = null;
    int exitCode = 1;
    try {
      DesktopProbeProcess.phase(result, "production-startup");
      engine = runProbe(fixture, work, result);
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
      if (exitCode != 0 && engine != null) {
        engine.forceQuit();
      }
      System.exit(exitCode);
    }
  }

  private static Leelaz runProbe(Path fixture, Path work, Path result) throws Exception {
    Files.createDirectories(work);
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}",
        StandardCharsets.UTF_8);
    System.setProperty("lizzie.work.dir", work.toString());

    Deadline startupDeadline = Deadline.after(Duration.ofSeconds(30));
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
      // Select the fixture's final PASS node before the production engine is switched in.
    }
    BoardHistoryNode capturedTarget = parsed.getCurrentHistoryNode();
    SwingUtilities.invokeAndWait(() -> Lizzie.board.setHistory(parsed));
    assertApplicationPosition(parsed, capturedTarget, false);

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
            + quote(work.toString());
    EngineManager manager = Lizzie.engineManager;
    int index = manager.engineList.size();
    EngineData catalogEntry = catalogEntry(index, command);
    Utils.saveEngineSettings(new ArrayList<>(List.of(catalogEntry)));
    CatalogNamedLeelaz controlled = new CatalogNamedLeelaz(catalogEntry);
    SnapshotFileAccessTestBridge.trustDirectLocalSnapshotFileAccessForTest(controlled);
    manager.engineList.add(controlled);

    Deadline engineDeadline = Deadline.after(Duration.ofSeconds(20));
    if (!callWithin(
        () -> manager.switchEngineIfAvailable(index, true),
        engineDeadline,
        "production engine switch")) {
      throw new AssertionError("production engine switch rejected controlled catalog entry");
    }
    Path peerPidPath = work.resolve("peer.pid");
    await(() -> Files.isRegularFile(peerPidPath), engineDeadline, "controlled peer PID");
    long peerPid = Long.parseLong(Files.readString(peerPidPath, StandardCharsets.UTF_8));
    ReaderThreads readers = awaitReaderThreads(engineDeadline);

    Path peerStatePath = work.resolve("peer-state.txt");
    await(
        () ->
            controlled.isLoaded()
                && manager.isEngineSwitchActive(index, true)
                && peerFinalPosition(peerStatePath),
        engineDeadline,
        "production snapshot restore");
    Deadline analysisDeadline = Deadline.after(Duration.ofSeconds(8));
    await(
        () -> controlled.isPondering() && hasExpectedAnalysis(capturedTarget),
        analysisDeadline,
        "current-node analysis publication");
    assertApplicationPosition(Lizzie.board.getHistory(), capturedTarget, true);
    MoveData received = expectedAnalysis(capturedTarget).orElseThrow();
    Map<String, String> snapshotState =
        readRecords(
            work.resolve("snapshot-state.txt"),
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
    requireEquals(snapshotState, "board", "19");
    requireEquals(snapshotState, "komi", "6.5");
    requireEquals(snapshotState, "stones", "B:fd;W:ee,ff");
    requireEquals(snapshotState, "turn", "W");
    requireEquals(snapshotState, "tail", "");
    String loadedSgf = Files.readString(work.resolve("loaded.sgf"), StandardCharsets.UTF_8);
    if (!loadedSgf.equals("(;FF[4]GM[1]CA[UTF-8]SZ[19]KM[6.5]PL[W]AB[fd]AW[ee]AW[ff])")) {
      throw new AssertionError("unexpected production snapshot: " + loadedSgf);
    }
    Path stagedSgf = Path.of(Files.readString(work.resolve("loaded.path"), StandardCharsets.UTF_8));

    DesktopProbeProcess.phase(result, "analysis-observed");
    Deadline shutdownDeadline = Deadline.after(Duration.ofSeconds(5));
    controlled.togglePonder();
    Path stoppedPath = work.resolve("stopped.txt");
    await(
        () -> Files.isRegularFile(stoppedPath) && !controlled.isPondering(),
        shutdownDeadline,
        "production analysis stop");
    Map<String, String> stopped = readRecords(stoppedPath, Set.of("command", "analysis.count"));
    String stopCommand = stopped.get("command");
    if (!Set.of("stop", "name").contains(stopCommand)) {
      throw new AssertionError("unexpected analysis terminator: " + stopCommand);
    }
    awaitAnalysisQuiescence(capturedTarget, peerStatePath, shutdownDeadline);
    Map<String, String> stoppedState = peerState(peerStatePath);
    requireEquals(stoppedState, "analysis.active", "false");
    int emitted = Integer.parseInt(stoppedState.get("analysis.count"));
    if (emitted <= 0) {
      throw new AssertionError("controlled peer emitted no analysis");
    }
    String commands = Files.readString(work.resolve("commands.log"), StandardCharsets.UTF_8);
    int analysisCommand = commands.indexOf("kata-analyze");
    int terminatorCommand = commands.indexOf("\n" + stopCommand, analysisCommand);
    if (analysisCommand < 0 || terminatorCommand < 0) {
      throw new AssertionError("controlled peer did not consume analysis termination: " + commands);
    }

    DesktopProbeProcess.phase(result, "normal-quit");
    callWithin(
        () -> {
          controlled.normalQuit();
          return null;
        },
        shutdownDeadline,
        "production normal quit");
    Path quitPath = work.resolve("quit.txt");
    Path peerCompletePath = work.resolve("peer-complete.txt");
    await(() -> Files.isRegularFile(quitPath), shutdownDeadline, "peer quit receipt");
    await(() -> Files.isRegularFile(peerCompletePath), shutdownDeadline, "peer-owned completion");
    await(
        () -> !ProcessHandle.of(peerPid).map(ProcessHandle::isAlive).orElse(false),
        shutdownDeadline,
        "owned peer exit");
    await(readers::terminated, shutdownDeadline, "production reader termination");
    await(() -> !Files.exists(stagedSgf), shutdownDeadline, "staged snapshot deletion");

    Path appLog = work.resolve("logs/app.log");
    Path stderr = result.getParent().resolve("stderr.log");
    await(
        () -> Files.isRegularFile(appLog) && Files.readString(appLog).contains("process-started"),
        shutdownDeadline,
        "application lifecycle log");
    String applicationLog = Files.readString(appLog, StandardCharsets.UTF_8);
    int unavailableStop = applicationLog.indexOf("gtp command=stop outcome=failed");
    int processStarted = applicationLog.indexOf("engine event=process-started");
    int consumedStop = applicationLog.indexOf("gtp command=stop outcome=sent", processStarted);
    if (unavailableStop < 0 || processStarted < 0 || unavailableStop >= processStarted) {
      throw new AssertionError("placeholder stop was not proven pre-process: " + applicationLog);
    }
    if (consumedStop < processStarted) {
      throw new AssertionError("controlled peer stop was not sent after process startup");
    }
    String standardError = Files.readString(stderr, StandardCharsets.UTF_8);
    if (!standardError.contains("Failed to send GTP command 'stop': outputStream unavailable")) {
      throw new AssertionError("missing pre-process placeholder stop diagnostic");
    }

    Map<String, String> records = new LinkedHashMap<>();
    records.put("scenario", "d1-success");
    records.put("peer.kind", "controlled-java");
    records.put("file-access", "test-trusted-local");
    records.put("fixture.path", fixture.toString());
    records.put("peer.pid", Long.toString(peerPid));
    for (String key : BOOLEAN_KEYS) {
      records.put(key, "true");
    }
    records.put("position.board", "19x19");
    records.put("position.komi", "6.5");
    records.put("position.stones", "B:fd;W:ee,ff,hh");
    records.put("position.empty", "dd");
    records.put("position.turn", "W");
    records.put("tail.previous", "MOVE:W[hh]");
    records.put("tail.current", "PASS:B[]");
    records.put("target.node", "captured-final-pass");
    records.put("analysis.move", received.coordinate);
    records.put("analysis.visits", Integer.toString(received.playouts));
    records.put("analysis.emitted", Integer.toString(emitted));
    records.put("analysis.received", Integer.toString(capturedTarget.getData().getPlayouts()));
    records.put("stop.command", stopCommand);
    records.put("lifecycle.extra-stop", "pre-process-placeholder-retirement");
    records.put("evidence.commands", work.resolve("commands.log").toString());
    records.put("evidence.snapshot-state", work.resolve("snapshot-state.txt").toString());
    records.put("evidence.peer-state", peerStatePath.toString());
    records.put("evidence.loaded-sgf", work.resolve("loaded.sgf").toString());
    records.put("evidence.stopped", stoppedPath.toString());
    records.put("evidence.quit", quitPath.toString());
    records.put("evidence.peer-complete", peerCompletePath.toString());
    records.put("evidence.stdout", result.getParent().resolve("stdout.log").toString());
    records.put("evidence.stderr", stderr.toString());
    records.put("evidence.app-log", appLog.toString());
    records.put("evidence.staged-sgf", stagedSgf.toString());
    records.put("evidence.phases", result.resolveSibling("phases.log").toString());
    writeResult(result, records);
    return controlled;
  }

  private static EngineData catalogEntry(int index, String command) {
    EngineData entry = new EngineData();
    entry.id = UUID.randomUUID().toString();
    entry.index = index;
    entry.commands = command;
    entry.name = "D1 controlled KataGo";
    entry.preload = false;
    entry.width = 19;
    entry.height = 19;
    entry.isDefault = false;
    entry.komi = 6.5f;
    return entry;
  }

  private static void assertApplicationPosition(
      BoardHistoryList history, BoardHistoryNode capturedTarget, boolean requireAnalysis) {
    if (Board.boardWidth != 19 || Board.boardHeight != 19) {
      throw new AssertionError("application board size is not 19x19");
    }
    if (Double.compare(history.getGameInfo().getKomi(), 6.5) != 0) {
      throw new AssertionError("application komi is not 6.5");
    }
    if (history.getCurrentHistoryNode() != capturedTarget
        || Lizzie.board.getHistory().getCurrentHistoryNode() != capturedTarget) {
      throw new AssertionError("application current node changed from captured target");
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
    if (requireAnalysis && !hasExpectedAnalysis(capturedTarget)) {
      throw new AssertionError("captured current node has no deterministic D4 analysis");
    }
  }

  private static void requireStone(BoardData data, String coordinate, Stone expected) {
    int x = coordinate.charAt(0) - 'a';
    int y = coordinate.charAt(1) - 'a';
    Stone actual = data.stones[Board.getIndex(x, y)];
    if (actual != expected) {
      throw new AssertionError(coordinate + " expected " + expected + " but was " + actual);
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
          throw new AssertionError(
              "unexpected application stone state at " + coordinate + ": " + stone);
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

  private static Optional<MoveData> expectedAnalysis(BoardHistoryNode target) {
    List<MoveData> moves = target.getData().bestMoves;
    if (moves == null) {
      return Optional.empty();
    }
    return moves.stream()
        .filter(move -> "D4".equals(move.coordinate) && move.playouts > 0)
        .findFirst();
  }

  private static boolean hasExpectedAnalysis(BoardHistoryNode target) {
    return target == Lizzie.board.getHistory().getCurrentHistoryNode()
        && target.getData().getPlayouts() > 0
        && expectedAnalysis(target).isPresent();
  }

  private static void awaitAnalysisQuiescence(
      BoardHistoryNode target, Path peerStatePath, Deadline deadline) throws Exception {
    int peerCount = -1;
    int applicationVisits = -1;
    long stableSince = -1;
    while (deadline.hasTime()) {
      Map<String, String> state = peerState(peerStatePath);
      int currentPeerCount = Integer.parseInt(state.get("analysis.count"));
      int currentApplicationVisits = target.getData().getPlayouts();
      if (currentPeerCount != peerCount || currentApplicationVisits != applicationVisits) {
        peerCount = currentPeerCount;
        applicationVisits = currentApplicationVisits;
        stableSince = System.nanoTime();
      } else if (stableSince >= 0
          && System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(400)) {
        return;
      }
      TimeUnit.NANOSECONDS.sleep(
          Math.min(TimeUnit.MILLISECONDS.toNanos(25), deadline.remainingNanos()));
    }
    throw new AssertionError("analysis stream did not become quiescent");
  }

  private static ReaderThreads awaitReaderThreads(Deadline deadline) throws Exception {
    AtomicReference<ReaderThreads> found = new AtomicReference<>();
    await(
        () -> {
          Optional<ReaderThreads> readers = findReaderThreads();
          readers.ifPresent(found::set);
          return readers.isPresent();
        },
        deadline,
        "two production reader threads");
    return found.get();
  }

  private static Optional<ReaderThreads> findReaderThreads() {
    Thread stdout = null;
    Thread stderr = null;
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      Thread thread = entry.getKey();
      if (!thread.isAlive()) {
        continue;
      }
      boolean readsStdout = false;
      boolean readsStderr = false;
      for (StackTraceElement frame : entry.getValue()) {
        if (!frame.getClassName().equals(Leelaz.class.getName())) {
          continue;
        }
        readsStdout |= frame.getMethodName().equals("read");
        readsStderr |= frame.getMethodName().equals("readError");
      }
      if (readsStdout) {
        if (stdout != null && stdout != thread) {
          return Optional.empty();
        }
        stdout = thread;
      }
      if (readsStderr) {
        if (stderr != null && stderr != thread) {
          return Optional.empty();
        }
        stderr = thread;
      }
    }
    return stdout == null || stderr == null || stdout == stderr
        ? Optional.empty()
        : Optional.of(new ReaderThreads(stdout, stderr));
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

  private static void requireEquals(Map<String, String> records, String key, String expected) {
    String actual = records.get(key);
    if (!expected.equals(actual)) {
      throw new AssertionError(key + " expected " + expected + " but was " + actual);
    }
  }

  private static void writeResult(Path path, Map<String, String> records) throws IOException {
    if (!records.keySet().equals(RESULT_KEYS)) {
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
    Files.writeString(path, text, StandardCharsets.UTF_8);
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
    AssertionError timeoutFailure = new AssertionError("Timed out awaiting " + description);
    if (lastFailure != null) {
      timeoutFailure.initCause(lastFailure);
    }
    throw timeoutFailure;
  }

  private static <T> T callWithin(CheckedSupplier<T> action, Deadline deadline, String description)
      throws Exception {
    FutureTask<T> task = new FutureTask<>(action::get);
    Thread worker = new Thread(task, "engine-process-smoke-" + description.replace(' ', '-'));
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
    return '\"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '\"';
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
