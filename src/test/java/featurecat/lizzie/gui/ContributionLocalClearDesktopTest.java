package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Container;
import java.awt.Robot;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual public contribution entry, constructor, Board and process I/O in isolated Swing JVMs. */
public class ContributionLocalClearDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"start", "lease", "reservation"})
  void contributionClearsLocallyAndHonorsRealOccupancy(String scenario) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            ContributionLocalClearDesktopTest.class,
            "contribution-local-" + scenario,
            List.of(),
            List.of(scenario));
    assertEquals("passed", new JSONObject(Files.readString(result)).getString("result"));
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("foreground")) {
      foreground(Path.of(args[1]));
      return;
    }
    if (args[0].equals("contribution")) {
      publishPid(Path.of(args[1]));
      // Keep both streams open until the application's public stop destroys this owned process.
      while (System.in.read() != -1) {}
      return;
    }
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      probe(args[0], work, result);
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(
          result,
          new JSONObject().put("result", "failed").put("error", failure.toString()).toString(2));
      System.exit(1);
    }
  }

  private static void probe(String scenario, Path work, Path result) throws Exception {
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}");
    System.setProperty("lizzie.work.dir", work.toString());
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
        });
    Path commands = work.resolve("foreground-commands.txt");
    Path launched = work.resolve("contribution-pid.txt");
    String command = childCommand("foreground", commands);
    int index = Lizzie.engineManager.engineList.size();
    EngineData entry = new EngineData();
    entry.id = UUID.randomUUID().toString();
    entry.index = index;
    entry.commands = command;
    entry.name = "Contribution foreground fixture";
    entry.width = entry.height = 19;
    entry.komi = 6.5f;
    Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
    Leelaz engine = new Leelaz(command);
    engine.savedEntryId = entry.id;
    engine.width = engine.height = engine.oriWidth = engine.oriHeight = 19;
    engine.komi = engine.orikomi = 6.5f;
    Lizzie.engineManager.engineList.add(engine);
    Lizzie.engineManager.switchEngineIfAvailable(index, true);
    await(
        () ->
            Lizzie.leelaz == engine
                && engine.previewExclusiveGtpLeaseAvailability()
                    == Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE);
    engine.ponder();
    await(() -> read(commands).contains("kata-analyze B"));
    SwingUtilities.invokeAndWait(() -> Lizzie.board.place(3, 3, Stone.BLACK));
    // Observe the new position, not the earlier empty-board analysis, before freezing I/O.
    await(() -> read(commands).contains("kata-analyze W"));
    assertEquals(Stone.BLACK, Lizzie.board.getData().stones[3 * 19 + 3]);
    var oldHistory = Lizzie.board.getHistory();
    long foregroundPid =
        Long.parseLong(Files.readString(work.resolve("foreground-commands.txt.pid")));
    Lizzie.config.contributeUseCommand = true;
    Lizzie.config.contributeUsePureCommand = true;
    Lizzie.config.contributeUserName = "local-fixture";
    Lizzie.config.contributeCommand = childCommand("contribution", launched);
    Lizzie.config.contributeShowEstimate = false;
    Leelaz.ExclusiveGtpLifecycleReservation reservation = null;
    if (scenario.equals("reservation")) {
      reservation = engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation);
    } else if (scenario.equals("lease")) {
      CountDownLatch ready = new CountDownLatch(1);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginExclusiveGtpSession(line -> {}, ready::countDown, () -> {}));
      assertTrue(engine.hasExclusiveGtpLease());
      assertTrue(ready.await(5, TimeUnit.SECONDS), "Foreground lease stop must be acknowledged");
    }
    String before = read(commands);
    String conflict =
        Lizzie.resourceBundle.getString("AnalysisSettings.reuseStatus.existing_lease");
    AtomicInteger conflicts = new AtomicInteger();
    Runnable dismissConflicts =
        () -> {
          for (Window window : Window.getWindows()) {
            if (window.isShowing() && containsText(window, conflict)) {
              conflicts.incrementAndGet();
              window.dispose();
            }
          }
        };
    Timer dialogs = new Timer(20, event -> dismissConflicts.run());
    SwingUtilities.invokeAndWait(dialogs::start);
    try {
      SwingUtilities.invokeAndWait(() -> Lizzie.frame.startContributeEngine());
      // Conflict notifications are enqueued from the reserved constructor; drain after release.
      SwingUtilities.invokeAndWait(() -> {});
      SwingUtilities.invokeAndWait(dismissConflicts);
      SwingUtilities.invokeAndWait(dismissConflicts);
      if (scenario.equals("start")) {
        await(() -> Files.exists(launched));
        assertTrue(Lizzie.frame.isContributing);
        assertNotSame(oldHistory, Lizzie.board.getHistory());
        assertEquals(0, Lizzie.board.getData().moveNumber);
        assertFalse(Lizzie.board.getHistory().getStart().next().isPresent());
        for (Stone stone : Lizzie.board.getData().stones) assertEquals(Stone.EMPTY, stone);
        assertEquals(count(before, "clear_board"), count(read(commands), "clear_board"));
        assertFalse(ProcessHandle.of(foregroundPid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals(
            0, conflicts.get(), "Delayed exclusive-use self-conflict after contribution start");
        long contributionPid = Long.parseLong(Files.readString(launched));
        assertTrue(ProcessHandle.of(contributionPid).orElseThrow().isAlive());
        Path screenshot = result.resolveSibling("contribution-board.png");
        ImageIO.write(
            new Robot().createScreenCapture(Lizzie.frame.getBounds()), "png", screenshot.toFile());
        SwingUtilities.invokeAndWait(() -> Lizzie.frame.contributeEngine.normalQuit());
        await(() -> !ProcessHandle.of(contributionPid).map(ProcessHandle::isAlive).orElse(false));
      } else {
        assertFalse(Lizzie.frame.isContributing);
        assertFalse(Files.exists(launched));
        assertNull(Lizzie.frame.contributeEngine);
        assertEquals(1, conflicts.get());
        assertSame(oldHistory, Lizzie.board.getHistory());
        assertEquals(Stone.BLACK, Lizzie.board.getData().stones[3 * 19 + 3]);
        assertTrue(ProcessHandle.of(foregroundPid).orElseThrow().isAlive());
        assertEquals(before, read(commands), "Rejected start must not send stop/clear commands");
      }
      Files.writeString(
          result,
          new JSONObject()
              .put("result", "passed")
              .put("scenario", scenario)
              .put("foregroundPid", foregroundPid)
              .put("conflictDialogs", conflicts.get())
              .put("contributionStarted", Files.exists(launched))
              .put("foregroundCommands", commands.toString())
              .put("surface", "real-swing-controlled-processes")
              .toString(2));
    } finally {
      SwingUtilities.invokeAndWait(dialogs::stop);
      if (reservation != null) reservation.close();
      if (scenario.equals("lease")) engine.endExclusiveGtpSession();
      if (Lizzie.frame.contributeEngine != null) Lizzie.frame.contributeEngine.normalQuit();
      Lizzie.engineManager.forceKillAllEngines();
    }
  }

  private static void foreground(Path commands) throws Exception {
    publishPid(Path.of(commands + ".pid"));
    try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
      String line;
      while ((line = input.readLine()) != null) {
        Files.writeString(
            commands, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String[] parts = line.trim().split("\\s+", 3);
        String id = parts[0].matches("\\d+") ? parts[0] : "";
        String verb = parts[id.isEmpty() ? 0 : 1];
        String answer =
            switch (verb) {
              case "name" -> "KataGo";
              case "version" -> "1.16.4";
              case "protocol_version" -> "2";
              case "list_commands" ->
                  "protocol_version\n"
                      + "name\n"
                      + "version\n"
                      + "list_commands\n"
                      + "boardsize\n"
                      + "clear_board\n"
                      + "komi\n"
                      + "play\n"
                      + "undo\n"
                      + "stop\n"
                      + "kata-analyze\n"
                      + "kata-get-rules\n"
                      + "kata-set-rules\n"
                      + "set_position";
              case "kata-get-rules" ->
                  "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\",\"suicideLegal\":true,\"hasButton\":false,\"whiteHandicapBonus\":\"N\"}";
              case "kata-get-param" -> "0";
              default -> "";
            };
        System.out.println("=" + id + " " + answer + "\n");
        System.out.flush();
        if (verb.equals("quit")) return;
      }
    }
  }

  private static String childCommand(String mode, Path file) {
    return quote(
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString())
        + " -cp "
        + quote(System.getProperty("java.class.path"))
        + " "
        + ContributionLocalClearDesktopTest.class.getName()
        + " "
        + mode
        + " "
        + quote(file.toString());
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private static String read(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path) : "";
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static long count(String text, String command) {
    return text.lines().filter(line -> line.contains(command)).count();
  }

  private static boolean containsText(Component component, String text) {
    if (component instanceof JLabel label
        && label.getText() != null
        && label.getText().contains(text)) return true;
    if (component instanceof JTextComponent textComponent) {
      try {
        var document = textComponent.getDocument();
        if (document.getText(0, document.getLength()).contains(text)) return true;
      } catch (javax.swing.text.BadLocationException failure) {
        throw new AssertionError(failure);
      }
    }
    if (component instanceof Container container)
      for (Component child : container.getComponents()) if (containsText(child, text)) return true;
    return false;
  }

  static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    do {
      if (condition.getAsBoolean()) return;
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    fail("Timed out waiting for controlled process state");
  }

  /** Writes the pid in one step so a polling parent never reads an empty file. */
  private static void publishPid(Path path) throws Exception {
    Path staged = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(staged, Long.toString(ProcessHandle.current().pid()));
    Files.move(staged, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }
}
