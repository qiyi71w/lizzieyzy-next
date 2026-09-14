package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Exercises lifecycle failures with real JVMs, not production navigation acceptance. */
class DesktopProbeProcessTest {
  @Test
  void failedAndHungChildrenRetainIsolatedEvidenceAndCannotOutliveDeadline() throws Exception {
    long started = System.nanoTime();
    AssertionError failed =
        assertThrows(
            AssertionError.class,
            () ->
                DesktopProbeProcess.run(
                    Fixture.class, "explicit-failure", List.of(), List.of("fail"), 5));
    assertTrue(failed.getMessage().contains("exited 7"), failed.getMessage());
    Path failedDirectory = Path.of(failed.getMessage().split(": ", 2)[1]);
    AssertionError hung =
        assertThrows(
            AssertionError.class,
            () ->
                DesktopProbeProcess.run(Fixture.class, "hung-edt", List.of(), List.of("hang"), 2));
    assertTrue(hung.getMessage().contains("timed out"), hung.getMessage());
    Path hungDirectory = Path.of(hung.getMessage().split(": ", 2)[1]);
    assertNotEquals(failedDirectory, hungDirectory);
    assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 30);
    for (Path directory : List.of(failedDirectory, hungDirectory)) {
      assertTrue(Files.readString(directory.resolve("stdout.log")).contains("fixture stdout"));
      assertTrue(Files.readString(directory.resolve("stderr.log")).contains("fixture stderr"));
      assertEquals("fixture config", Files.readString(directory.resolve("work/config.txt")));
      assertEquals(
          "fixture application log", Files.readString(directory.resolve("work/logs/app.log")));
      String lifecycle = Files.readString(directory.resolve("lifecycle.txt"));
      long pid =
          Long.parseLong(
              lifecycle
                  .lines()
                  .filter(line -> line.startsWith("pid="))
                  .findFirst()
                  .orElseThrow()
                  .substring(4));
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(lifecycle.contains("survivors=[]"), lifecycle);
    }
    long descendant =
        Long.parseLong(Files.readString(hungDirectory.resolve("work/descendant.pid")));
    assertFalse(ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false));
    assertTrue(Files.readString(hungDirectory.resolve("phases.log")).contains("timeout"));
    assertTrue(Files.isRegularFile(hungDirectory.resolve("thread-stacks.log")));
    assertTrue(Files.isRegularFile(hungDirectory.resolve("screenshot.log")));
  }

  public static final class Fixture {
    public static void main(String[] args) throws Exception {
      System.setProperty("java.awt.headless", "true");
      if ("descendant".equals(args[0])) {
        while (true) java.util.concurrent.locks.LockSupport.park();
      }
      Path work = Path.of(args[1]);
      Files.writeString(work.resolve("config.txt"), "fixture config");
      Files.createDirectories(work.resolve("logs"));
      Files.writeString(work.resolve("logs/app.log"), "fixture application log");
      System.out.println("fixture stdout");
      System.err.println("fixture stderr");
      if ("fail".equals(args[0])) System.exit(7);
      Process descendant =
          new ProcessBuilder(
                  ProcessHandle.current().info().command().orElseThrow(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  Fixture.class.getName(),
                  "descendant")
              .inheritIO()
              .start();
      Files.writeString(work.resolve("descendant.pid"), Long.toString(descendant.pid()));
      javax.swing.SwingUtilities.invokeAndWait(
          () -> {
            while (true) java.util.concurrent.locks.LockSupport.park();
          });
    }
  }
}
