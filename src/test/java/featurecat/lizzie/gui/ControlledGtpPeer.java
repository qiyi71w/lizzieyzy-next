package featurecat.lizzie.gui;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded deterministic GTP peer used by real-process engine probes. */
public final class ControlledGtpPeer {
  private static final Pattern COMMAND =
      Pattern.compile("^(?:(\\d+)\\s+)?([a-z][a-z0-9_-]*)(?:\\s+(.*))?$");
  private static final Pattern INTEGER = Pattern.compile("[1-9][0-9]?");
  private static final Pattern DECIMAL = Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
  private static final Pattern SGF_COORDINATE = Pattern.compile("[a-z]{2}");
  private static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
  private static final Set<String> COMMANDS =
      new LinkedHashSet<>(
          List.of(
              "protocol_version",
              "name",
              "version",
              "list_commands",
              "known_command",
              "boardsize",
              "komi",
              "clear_board",
              "loadsgf",
              "play",
              "kata-get-param",
              "kata-analyze",
              "stop",
              "quit"));
  private static final Object OUTPUT_LOCK = new Object();
  private static final Object ERROR_LOCK = new Object();
  private static final int BURST_LINES = 2048;
  private static final int BURST_MIDPOINT = BURST_LINES / 2;
  private static final long MAX_BURST_BYTES = 8L * 1024 * 1024;

  private static Path controlRoot;
  private static Path root;
  private static BufferedWriter output;
  private static BufferedWriter errorOutput;
  private static ScheduledExecutorService analysisExecutor;
  private static final TreeMap<String, String> stones = new TreeMap<>();
  private static final List<String> tail = new ArrayList<>();
  private static Scenario scenario;
  private static int launchOrdinal;
  private static int boardSize = 19;
  private static double komi = 7.5;
  private static String turn = "B";
  private static boolean analysisActive;
  private static int analysisCount;
  private static int lateAnalysisCount;
  private static String stopCommand = "";
  private static Path loadedPath;

  private ControlledGtpPeer() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) {
      throw new IllegalArgumentException("expected evidence directory and optional scenario");
    }
    controlRoot = Path.of(args[0]).toAbsolutePath().normalize();
    Files.createDirectories(controlRoot);
    scenario = args.length == 1 ? Scenario.D1_SUCCESS : Scenario.parse(args[1]);
    root = initializeIncarnation();
    writeUnchecked(root.resolve("peer.pid"), Long.toString(ProcessHandle.current().pid()));
    writeUnchecked(
        root.resolve("identity.txt"),
        "pid="
            + ProcessHandle.current().pid()
            + "\nlaunch.ordinal="
            + launchOrdinal
            + "\nscenario="
            + scenario.argument
            + "\n");
    startDeadlineWatchdog();
    analysisExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "controlled-gtp-analysis");
              thread.setDaemon(true);
              return thread;
            });
    analysisExecutor.scheduleAtFixedRate(
        ControlledGtpPeer::emitAnalysis, 0, 75, TimeUnit.MILLISECONDS);

    try (BufferedReader input =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter peerOutput =
            new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        BufferedWriter peerError =
            new BufferedWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8))) {
      output = peerOutput;
      errorOutput = peerError;
      startScenarioWatcher();
      boolean quitConsumed = false;
      String line;
      while ((line = input.readLine()) != null) {
        append(root.resolve("commands.log"), line + "\n");
        Matcher matcher = COMMAND.matcher(line.trim());
        if (!matcher.matches()) {
          respond(false, "", "malformed command");
          continue;
        }
        String id = matcher.group(1) == null ? "" : matcher.group(1);
        String command = matcher.group(2);
        String arguments = matcher.group(3) == null ? "" : matcher.group(3).trim();
        CommandResult result = handle(command, arguments);
        if (result.responseRelease() != null) {
          awaitRelease(result.responseRelease(), "delayed response");
        }
        respond(result.success(), id, result.payload());
        if (result.responseReceipt() != null) {
          writeUnchecked(
              result.responseReceipt(),
              "id=" + id + "\npid=" + ProcessHandle.current().pid() + "\n");
        }
        if (result.afterResponse() != null) {
          result.afterResponse().run();
        }
        if (result.quit()) {
          quitConsumed = true;
          break;
        }
      }
      if (quitConsumed) {
        synchronized (OUTPUT_LOCK) {
          analysisActive = false;
        }
        analysisExecutor.shutdownNow();
        if (!analysisExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
          throw new AssertionError("analysis stream did not terminate before peer exit");
        }
        writeUnchecked(root.resolve("peer-complete.txt"), "natural\n");
      }
    } finally {
      synchronized (OUTPUT_LOCK) {
        analysisActive = false;
      }
      if (analysisExecutor != null) {
        analysisExecutor.shutdownNow();
      }
    }
  }

  private static CommandResult handle(String command, String arguments) {
    try {
      return switch (command) {
        case "protocol_version" -> noArguments(arguments, "2");
        case "name" -> name(arguments);
        case "version" -> noArguments(arguments, "1.16.0-controlled");
        case "list_commands" -> noArguments(arguments, String.join("\n", COMMANDS));
        case "known_command" -> knownCommand(arguments);
        case "boardsize" -> boardsize(arguments);
        case "komi" -> komi(arguments);
        case "clear_board" -> clearBoard(arguments);
        case "loadsgf" -> loadSgf(arguments);
        case "play" -> play(arguments);
        case "kata-get-param" -> kataGetParam(arguments);
        case "kata-analyze" -> analyze(arguments);
        case "stop" -> stop(arguments, "stop");
        case "quit" -> quit(arguments);
        default -> failure("unsupported command: " + command);
      };
    } catch (IOException error) {
      return failure("I/O failure: " + clean(error.getMessage()));
    } catch (IllegalArgumentException error) {
      return failure(clean(error.getMessage()));
    }
  }

  private static CommandResult name(String arguments) {
    requireNoArguments(arguments);
    boolean wasActive;
    synchronized (OUTPUT_LOCK) {
      wasActive = analysisActive;
      if (wasActive) {
        analysisActive = false;
        stopCommand = "name";
      }
    }
    Runnable afterResponse = wasActive ? ControlledGtpPeer::recordStopUnchecked : null;
    if (scenario == Scenario.PIPE_BURST) {
      Runnable stopAction = afterResponse;
      afterResponse =
          () -> {
            if (stopAction != null) {
              stopAction.run();
            }
            writeUnchecked(
                root.resolve("burst-control-received.txt"),
                "command=name\npid="
                    + ProcessHandle.current().pid()
                    + "\nlaunch.ordinal="
                    + launchOrdinal
                    + "\n");
          };
    }
    return success("KataGo", afterResponse, false);
  }

  private static CommandResult knownCommand(String arguments) {
    String[] words = words(arguments, 1);
    return success(Boolean.toString(COMMANDS.contains(words[0])), null, false);
  }

  private static CommandResult boardsize(String arguments) {
    String[] words = words(arguments, 1);
    if (!INTEGER.matcher(words[0]).matches()) {
      throw new IllegalArgumentException("invalid boardsize");
    }
    int requested = Integer.parseInt(words[0]);
    if (requested < 2 || requested > GTP_COLUMNS.length()) {
      throw new IllegalArgumentException("unsupported boardsize");
    }
    synchronized (OUTPUT_LOCK) {
      boardSize = requested;
      stones.clear();
      tail.clear();
      turn = "B";
      writeStateUnchecked();
    }
    return success("", null, false);
  }

  private static CommandResult komi(String arguments) {
    String[] words = words(arguments, 1);
    komi = finiteDecimal(words[0], "komi");
    synchronized (OUTPUT_LOCK) {
      writeStateUnchecked();
    }
    return success("", null, false);
  }

  private static CommandResult clearBoard(String arguments) {
    requireNoArguments(arguments);
    synchronized (OUTPUT_LOCK) {
      stones.clear();
      tail.clear();
      turn = "B";
      writeStateUnchecked();
    }
    return success("", null, false);
  }

  private static CommandResult loadSgf(String arguments) throws IOException {
    String[] words = words(arguments, 1);
    Path path = Path.of(words[0]);
    if (!path.isAbsolute()) {
      path = Path.of("").toAbsolutePath().resolve(path);
    }
    path = path.normalize().toRealPath();
    String text = Files.readString(path, StandardCharsets.UTF_8);
    Snapshot snapshot = parseSnapshot(text);
    synchronized (OUTPUT_LOCK) {
      loadedPath = path;
      Files.writeString(root.resolve("loaded.sgf"), text, StandardCharsets.UTF_8);
      Files.writeString(root.resolve("loaded.path"), path.toString(), StandardCharsets.UTF_8);
      writeUnchecked(
          root.resolve("loadsgf-received.txt"),
          "pid="
              + ProcessHandle.current().pid()
              + "\npath="
              + path
              + "\nbytes="
              + text.getBytes(StandardCharsets.UTF_8).length
              + "\n");
      if (scenario != Scenario.SNAPSHOT_ERROR) {
        installSnapshot(snapshot);
      }
    }
    if (scenario == Scenario.SNAPSHOT_ERROR) {
      return new CommandResult(
          false,
          "controlled snapshot rejection",
          null,
          false,
          controlRoot.resolve("release-001-error"),
          root.resolve("error-response-emitted.txt"));
    }
    if (scenario == Scenario.SNAPSHOT_TIMEOUT) {
      return success(
          "",
          null,
          false,
          controlRoot.resolve("release-001-timeout-ack"),
          root.resolve("late-ack-emitted.txt"));
    }
    return success("", null, false);
  }

  private static void installSnapshot(Snapshot snapshot) {
    boardSize = snapshot.boardSize();
    komi = snapshot.komi();
    turn = snapshot.turn();
    stones.clear();
    stones.putAll(snapshot.stones());
    tail.clear();
    writeUnchecked(root.resolve("snapshot-state.txt"), stateText());
    writeStateUnchecked();
  }

  private static CommandResult play(String arguments) {
    String[] words = words(arguments, 2);
    String color = words[0].toUpperCase(Locale.ROOT);
    if (!color.equals("B") && !color.equals("W")) {
      throw new IllegalArgumentException("invalid move color");
    }
    synchronized (OUTPUT_LOCK) {
      if (!color.equals(turn)) {
        throw new IllegalArgumentException("move color does not match side to play");
      }
      String move;
      if (words[1].equalsIgnoreCase("pass")) {
        move = color + "[]";
      } else {
        String coordinate = gtpToSgf(words[1]);
        if (stones.containsKey(coordinate)) {
          throw new IllegalArgumentException("move occupies an existing stone");
        }
        stones.put(coordinate, color);
        move = color + "[" + coordinate + "]";
      }
      tail.add(move);
      turn = color.equals("B") ? "W" : "B";
      writeStateUnchecked();
    }
    return success("", null, false);
  }

  private static CommandResult kataGetParam(String arguments) {
    String[] words = words(arguments, 1);
    return switch (words[0]) {
      case "playoutDoublingAdvantage" -> success("0", null, false);
      case "analysisWideRootNoise" -> success("false", null, false);
      default -> failure("unsupported kata parameter");
    };
  }

  private static CommandResult analyze(String arguments) {
    if (arguments.isBlank()) {
      throw new IllegalArgumentException("kata-analyze requires arguments");
    }
    synchronized (OUTPUT_LOCK) {
      writeOnceUnchecked(root.resolve("analysis-start-state.txt"), stateText());
    }
    return success(
        "",
        () -> {
          synchronized (OUTPUT_LOCK) {
            analysisActive = true;
            writeStateUnchecked();
          }
        },
        false);
  }

  private static CommandResult stop(String arguments, String command) {
    requireNoArguments(arguments);
    synchronized (OUTPUT_LOCK) {
      analysisActive = false;
      stopCommand = command;
    }
    return success("", ControlledGtpPeer::recordStopUnchecked, false);
  }

  private static CommandResult quit(String arguments) {
    requireNoArguments(arguments);
    synchronized (OUTPUT_LOCK) {
      analysisActive = false;
    }
    if (scenario == Scenario.QUIT_REFUSAL) {
      return success(
          "",
          () -> {
            writeUnchecked(
                root.resolve("quit-refused.txt"),
                "command=quit\nrefusal=stay-alive\npid="
                    + ProcessHandle.current().pid()
                    + "\nlaunch.ordinal="
                    + launchOrdinal
                    + "\n");
            synchronized (OUTPUT_LOCK) {
              writeStateUnchecked();
            }
          },
          false);
    }
    return success(
        "",
        () -> {
          writeUnchecked(root.resolve("quit.txt"), "graceful\n");
          synchronized (OUTPUT_LOCK) {
            writeStateUnchecked();
          }
        },
        true);
  }

  private static void emitAnalysis() {
    synchronized (OUTPUT_LOCK) {
      if (!analysisActive || output == null) {
        return;
      }
      try {
        int visits = 10 + analysisCount;
        emitAnalysisLine(analysisMove(), visits);
        analysisCount++;
        writeStateUnchecked();
      } catch (IOException error) {
        analysisActive = false;
        throw new UncheckedIOException(error);
      }
    }
  }

  private static String analysisMove() {
    return scenario == Scenario.HEALTHY_DISTINCT
            || (scenario == Scenario.CRASH_ON_RELEASE && launchOrdinal > 1)
        ? "Q16"
        : "D4";
  }

  private static void emitAnalysisLine(String move, int visits) throws IOException {
    output.write(analysisLine(move, visits, 1));
    output.flush();
  }

  private static String analysisLine(String move, int visits, int pvMoves) {
    StringBuilder line =
        new StringBuilder("info move ")
            .append(move)
            .append(" visits ")
            .append(visits)
            .append(
                " winrate 0.55 scoreMean 1.0 scoreStdev 2.0 prior 0.1 lcb 0.5 order 0 pv");
    for (int index = 0; index < pvMoves; index++) {
      line.append(' ').append(move);
    }
    return line.append('\n').toString();
  }

  private static Snapshot parseSnapshot(String text) {
    if (!text.startsWith("(;") || !text.endsWith(")")) {
      throw new IllegalArgumentException("snapshot must be one flat SGF node");
    }
    String body = text.substring(2, text.length() - 1);
    Map<String, List<String>> properties = new LinkedHashMap<>();
    int offset = 0;
    while (offset < body.length()) {
      int nameStart = offset;
      while (offset < body.length() && body.charAt(offset) >= 'A' && body.charAt(offset) <= 'Z') {
        offset++;
      }
      if (nameStart == offset) {
        throw new IllegalArgumentException("malformed SGF property name");
      }
      String name = body.substring(nameStart, offset);
      if (!Set.of("FF", "GM", "CA", "SZ", "KM", "PL", "AB", "AW").contains(name)) {
        throw new IllegalArgumentException("unsupported SGF property: " + name);
      }
      List<String> values = properties.computeIfAbsent(name, ignored -> new ArrayList<>());
      int countBefore = values.size();
      while (offset < body.length() && body.charAt(offset) == '[') {
        int valueStart = ++offset;
        while (offset < body.length() && body.charAt(offset) != ']') {
          char valueCharacter = body.charAt(offset);
          if (valueCharacter == '[' || valueCharacter == '\\') {
            throw new IllegalArgumentException("unsupported SGF property escape");
          }
          offset++;
        }
        if (offset >= body.length()) {
          throw new IllegalArgumentException("unterminated SGF property value");
        }
        values.add(body.substring(valueStart, offset));
        offset++;
      }
      if (values.size() == countBefore) {
        throw new IllegalArgumentException("SGF property has no value: " + name);
      }
    }

    requireSingle(properties, "FF", "4");
    requireSingle(properties, "GM", "1");
    requireSingle(properties, "CA", "UTF-8");
    String sizeValue = requireSingle(properties, "SZ", null);
    if (!INTEGER.matcher(sizeValue).matches()) {
      throw new IllegalArgumentException("invalid SGF board size");
    }
    int size = Integer.parseInt(sizeValue);
    if (size < 2 || size > GTP_COLUMNS.length()) {
      throw new IllegalArgumentException("unsupported SGF board size");
    }
    double snapshotKomi = finiteDecimal(requireSingle(properties, "KM", null), "SGF komi");
    String snapshotTurn = requireSingle(properties, "PL", null);
    if (!snapshotTurn.equals("B") && !snapshotTurn.equals("W")) {
      throw new IllegalArgumentException("invalid SGF side to play");
    }

    TreeMap<String, String> snapshotStones = new TreeMap<>();
    addSetupStones(properties.getOrDefault("AB", List.of()), "B", size, snapshotStones);
    addSetupStones(properties.getOrDefault("AW", List.of()), "W", size, snapshotStones);
    return new Snapshot(size, snapshotKomi, snapshotTurn, snapshotStones);
  }

  private static void addSetupStones(
      List<String> coordinates, String color, int size, Map<String, String> target) {
    for (String coordinate : coordinates) {
      if (!SGF_COORDINATE.matcher(coordinate).matches()
          || coordinate.charAt(0) - 'a' >= size
          || coordinate.charAt(1) - 'a' >= size) {
        throw new IllegalArgumentException("invalid SGF setup coordinate");
      }
      if (target.putIfAbsent(coordinate, color) != null) {
        throw new IllegalArgumentException("duplicate SGF setup coordinate");
      }
    }
  }

  private static String requireSingle(
      Map<String, List<String>> properties, String name, String expected) {
    List<String> values = properties.get(name);
    if (values == null || values.size() != 1 || values.get(0).isEmpty()) {
      throw new IllegalArgumentException("missing or duplicate SGF property: " + name);
    }
    String value = values.get(0);
    if (expected != null && !expected.equals(value)) {
      throw new IllegalArgumentException("invalid SGF property: " + name);
    }
    return value;
  }

  private static String gtpToSgf(String value) {
    String coordinate = value.toUpperCase(Locale.ROOT);
    if (coordinate.length() < 2) {
      throw new IllegalArgumentException("invalid GTP coordinate");
    }
    int x = GTP_COLUMNS.indexOf(coordinate.charAt(0));
    int row;
    try {
      row = Integer.parseInt(coordinate.substring(1));
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("invalid GTP coordinate", error);
    }
    if (x < 0 || x >= boardSize || row < 1 || row > boardSize) {
      throw new IllegalArgumentException("GTP coordinate outside board");
    }
    int y = boardSize - row;
    return "" + (char) ('a' + x) + (char) ('a' + y);
  }

  private static double finiteDecimal(String value, String description) {
    if (!DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid " + description);
    }
    double parsed = Double.parseDouble(value);
    if (!Double.isFinite(parsed)) {
      throw new IllegalArgumentException("invalid " + description);
    }
    return parsed;
  }

  private static CommandResult noArguments(String arguments, String payload) {
    requireNoArguments(arguments);
    return success(payload, null, false);
  }

  private static void requireNoArguments(String arguments) {
    if (!arguments.isBlank()) {
      throw new IllegalArgumentException("unexpected command arguments");
    }
  }

  private static String[] words(String arguments, int expected) {
    String[] words = arguments.isBlank() ? new String[0] : arguments.split("\\s+");
    if (words.length != expected) {
      throw new IllegalArgumentException("wrong command argument count");
    }
    return words;
  }

  private static CommandResult success(String payload, Runnable afterResponse, boolean quit) {
    return success(payload, afterResponse, quit, null, null);
  }

  private static CommandResult success(
      String payload,
      Runnable afterResponse,
      boolean quit,
      Path responseRelease,
      Path responseReceipt) {
    return new CommandResult(true, payload, afterResponse, quit, responseRelease, responseReceipt);
  }

  private static CommandResult failure(String payload) {
    return new CommandResult(false, payload, null, false, null, null);
  }

  private static void respond(boolean success, String id, String payload) throws IOException {
    synchronized (OUTPUT_LOCK) {
      output.write(success ? '=' : '?');
      output.write(id);
      if (!payload.isEmpty()) {
        output.write(' ');
        output.write(payload);
      }
      output.write("\n\n");
      output.flush();
    }
  }

  private static void recordStopUnchecked() {
    synchronized (OUTPUT_LOCK) {
      writeStateUnchecked();
      String receipt = stopReceipt();
      writeOnceUnchecked(root.resolve("fast-change-stop.txt"), receipt);
      writeUnchecked(root.resolve("stopped.txt"), receipt);
    }
  }

  private static String stopReceipt() {
    synchronized (OUTPUT_LOCK) {
      return "command=" + stopCommand + "\nanalysis.count=" + analysisCount + "\n";
    }
  }

  private static String stateText() {
    return "board="
        + boardSize
        + "\nkomi="
        + komi
        + "\nstones="
        + formatStones()
        + "\nturn="
        + turn
        + "\ntail="
        + String.join(",", tail)
        + "\nanalysis.count="
        + analysisCount
        + "\nanalysis.active="
        + analysisActive
        + "\nstop.command="
        + stopCommand
        + "\nloaded.path="
        + (loadedPath == null ? "" : loadedPath)
        + "\n";
  }

  private static String formatStones() {
    List<String> groups = new ArrayList<>();
    for (String color : List.of("B", "W")) {
      List<String> coordinates =
          stones.entrySet().stream()
              .filter(entry -> entry.getValue().equals(color))
              .map(Map.Entry::getKey)
              .toList();
      if (!coordinates.isEmpty()) {
        groups.add(color + ":" + String.join(",", coordinates));
      }
    }
    return String.join(";", groups);
  }

  private static void writeStateUnchecked() {
    writeUnchecked(root.resolve("peer-state.txt"), stateText());
  }

  private static void writeUnchecked(Path path, String text) {
    try {
      PeerEvidenceFiles.write(path, text);
    } catch (IOException error) {
      error.printStackTrace();
      throw new UncheckedIOException(error);
    }
  }

  private static void writeOnceUnchecked(Path path, String text) {
    try {
      Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    } catch (FileAlreadyExistsException ignored) {
      // The first receipt is the immutable lifecycle boundary.
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static void append(Path path, String text) throws IOException {
    Files.writeString(
        path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private static String clean(String message) {
    return message == null ? "unspecified failure" : message.replace('\n', ' ').replace('\r', ' ');
  }

  private static Path initializeIncarnation() throws IOException {
    if (scenario == Scenario.D1_SUCCESS) {
      launchOrdinal = 1;
      return controlRoot;
    }
    for (int candidate = 1; candidate <= 999; candidate++) {
      Path directory =
          controlRoot.resolve(String.format(Locale.ROOT, "incarnation-%03d", candidate));
      try {
        Files.createDirectory(directory);
        launchOrdinal = candidate;
        writeUnchecked(controlRoot.resolve("latest-incarnation.txt"), directory.toString());
        return directory;
      } catch (FileAlreadyExistsException ignored) {
        // A prior peer owns this launch ordinal.
      }
    }
    throw new IOException("controlled peer exhausted launch ordinals");
  }

  private static void startScenarioWatcher() throws IOException {
    if (scenario == Scenario.CRASH_ON_RELEASE) {
      try {
        Files.createFile(controlRoot.resolve("first-crash.claimed"));
      } catch (FileAlreadyExistsException alreadyClaimed) {
        return;
      }
      startControlWatcher(
          "controlled-gtp-crash",
          controlRoot.resolve("release-001-crash"),
          () -> {
            writeUnchecked(
                root.resolve("crash-receipt.txt"),
                "pid="
                    + ProcessHandle.current().pid()
                    + "\nlaunch.ordinal="
                    + launchOrdinal
                    + "\n");
            Runtime.getRuntime().halt(71);
          });
    } else if (scenario == Scenario.LATE_OUTPUT) {
      startControlWatcher(
          "controlled-gtp-late-output",
          controlRoot.resolve("release-001-late-output"),
          ControlledGtpPeer::emitLateOutput);
    } else if (scenario == Scenario.PIPE_BURST) {
      startControlWatcher(
          "controlled-gtp-pipe-burst",
          controlRoot.resolve("release-001-burst"),
          ControlledGtpPeer::startPipeBurst);
    }
  }

  private static void startControlWatcher(String name, Path release, Runnable action) {
    Thread watcher =
        new Thread(
            () -> {
              try {
                awaitRelease(release, name);
                action.run();
              } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                Runtime.getRuntime().halt(72);
              }
            },
            name);
    watcher.setDaemon(true);
    watcher.start();
  }

  private static void emitLateOutput() {
    synchronized (OUTPUT_LOCK) {
      try {
        writeUnchecked(
            root.resolve("late-output-started.txt"),
            "pid=" + ProcessHandle.current().pid() + "\nlaunch.ordinal=" + launchOrdinal + "\n");
        for (int index = 1; index <= 5; index++) {
          emitAnalysisLine("C3", 9000 + index);
          lateAnalysisCount++;
        }
        writeUnchecked(
            root.resolve("late-output-complete.txt"),
            "pid="
                + ProcessHandle.current().pid()
                + "\nlaunch.ordinal="
                + launchOrdinal
                + "\nfirst.visits=9001\nlast.visits=9005\ncount="
                + lateAnalysisCount
                + "\n");
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }
  }

  private static void startPipeBurst() {
    synchronized (OUTPUT_LOCK) {
      analysisActive = false;
      writeStateUnchecked();
    }
    CountDownLatch finishRelease = new CountDownLatch(1);
    startControlWatcher(
        "controlled-gtp-burst-finish",
        controlRoot.resolve("release-002-burst-finish"),
        finishRelease::countDown);
    startBurstWriter(
        "controlled-gtp-stdout-burst", "stdout", () -> emitStdoutBurst(finishRelease));
    startBurstWriter(
        "controlled-gtp-stderr-burst", "stderr", () -> emitStderrBurst(finishRelease));
  }

  private static void startBurstWriter(String threadName, String stream, Runnable writer) {
    Thread thread =
        new Thread(
            () -> {
              try {
                writer.run();
              } catch (Throwable failure) {
                writeUnchecked(
                    root.resolve("burst-" + stream + "-error.txt"),
                    "failure=" + clean(failure.toString()) + "\n");
                failure.printStackTrace(System.err);
                Runtime.getRuntime().halt(73);
              }
            },
            threadName);
    thread.setDaemon(true);
    thread.start();
  }

  private static void emitStdoutBurst(CountDownLatch finishRelease) {
    long started = System.nanoTime();
    long bytes = 0;
    for (int sequence = 1; sequence <= BURST_LINES; sequence++) {
      int visits = sequence == BURST_LINES ? 900000 : 100000 + sequence;
      String line = analysisLine("D4", visits, 512);
      synchronized (OUTPUT_LOCK) {
        try {
          output.write(line);
          output.flush();
          analysisCount++;
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }
      bytes += line.getBytes(StandardCharsets.UTF_8).length;
      recordBurstProgress("stdout", sequence, bytes, started);
      pauseBurstWriter();
      if (sequence == BURST_MIDPOINT) {
        awaitBurstFinish(finishRelease, "stdout");
      }
    }
    synchronized (OUTPUT_LOCK) {
      writeStateUnchecked();
    }
    recordBurstCompletion("stdout", bytes, started);
  }

  private static void emitStderrBurst(CountDownLatch finishRelease) {
    long started = System.nanoTime();
    long bytes = 0;
    String padding = "x".repeat(1800);
    for (int sequence = 1; sequence <= BURST_LINES; sequence++) {
      String line = "burst diagnostic sequence=" + sequence + " " + padding + "\n";
      synchronized (ERROR_LOCK) {
        try {
          errorOutput.write(line);
          errorOutput.flush();
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }
      bytes += line.getBytes(StandardCharsets.UTF_8).length;
      recordBurstProgress("stderr", sequence, bytes, started);
      pauseBurstWriter();
      if (sequence == BURST_MIDPOINT) {
        awaitBurstFinish(finishRelease, "stderr");
      }
    }
    recordBurstCompletion("stderr", bytes, started);
  }

  private static void recordBurstProgress(
      String stream, int sequence, long bytes, long started) {
    String suffix = sequence == 1 ? "first" : sequence == BURST_MIDPOINT ? "progress" : null;
    if (suffix != null) {
      writeUnchecked(
          root.resolve("burst-" + stream + "-" + suffix + ".txt"),
          burstReceipt(stream, 1, sequence, bytes, System.nanoTime() - started));
    }
  }

  private static void recordBurstCompletion(String stream, long bytes, long started) {
    if (bytes <= 0 || bytes > MAX_BURST_BYTES) {
      throw new AssertionError(stream + " burst exceeded bounded byte contract: " + bytes);
    }
    writeUnchecked(
        root.resolve("burst-" + stream + "-last.txt"),
        burstReceipt(stream, 1, BURST_LINES, bytes, System.nanoTime() - started));
  }

  private static String burstReceipt(
      String stream, int firstSequence, int lastSequence, long bytes, long durationNanos) {
    return "stream="
        + stream
        + "\npid="
        + ProcessHandle.current().pid()
        + "\nlaunch.ordinal="
        + launchOrdinal
        + "\nfirst.sequence="
        + firstSequence
        + "\nlast.sequence="
        + lastSequence
        + "\nbytes="
        + bytes
        + "\nduration.nanos="
        + durationNanos
        + "\n";
  }

  private static void pauseBurstWriter() {
    try {
      Thread.sleep(1);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("burst writer interrupted", interrupted);
    }
  }

  private static void awaitBurstFinish(CountDownLatch finishRelease, String stream) {
    try {
      if (!finishRelease.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError(stream + " burst finish release timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(stream + " burst finish interrupted", interrupted);
    }
  }

  private static void awaitRelease(Path release, String phase) throws IOException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!Files.isRegularFile(release) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted awaiting " + phase, interrupted);
      }
    }
    if (!Files.isRegularFile(release)) {
      throw new IOException("timed out awaiting " + phase + " release " + release);
    }
    writeUnchecked(
        root.resolve("release-consumed-" + release.getFileName() + ".txt"),
        "pid=" + ProcessHandle.current().pid() + "\n");
  }

  private static void startDeadlineWatchdog() {
    Thread watchdog =
        new Thread(
            () -> {
              try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(75));
                Files.writeString(
                    root.resolve("peer-timeout.txt"),
                    "deadline exceeded\n",
                    StandardCharsets.UTF_8);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
              } catch (IOException error) {
                error.printStackTrace(System.err);
              }
              Runtime.getRuntime().halt(70);
            },
            "controlled-gtp-deadline");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  private record CommandResult(
      boolean success,
      String payload,
      Runnable afterResponse,
      boolean quit,
      Path responseRelease,
      Path responseReceipt) {}

  private record Snapshot(
      int boardSize, double komi, String turn, TreeMap<String, String> stones) {}

  private enum Scenario {
    D1_SUCCESS("d1-success"),
    SNAPSHOT_ERROR("snapshot-error"),
    SNAPSHOT_TIMEOUT("snapshot-timeout"),
    CRASH_ON_RELEASE("crash-on-release"),
    LATE_OUTPUT("late-output"),
    PIPE_BURST("pipe-burst"),
    QUIT_REFUSAL("quit-refusal"),
    HEALTHY_DISTINCT("healthy-distinct");

    private final String argument;

    Scenario(String argument) {
      this.argument = argument;
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
}
