package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChatGptRuntimeSmokeIT {
  @Test
  void shadedArtifactAcceptsLoopbackAndCancelsWithoutCredentials() throws Exception {
    var result = run(false);
    assertEquals(0, result.exitCode, result.output);
    assertTrue(result.output.contains("CHATGPT_LOOPBACK_SMOKE_OK"), result.output);
  }

  @Test
  void oldRuntimeReportsIncompletePackageInsteadOfNetworkFailure() throws Exception {
    var result = run(true);
    assertNotEquals(0, result.exitCode, result.output);
    assertTrue(result.output.contains("Install the latest complete package"), result.output);
    assertFalse(result.output.contains("NoClassDefFoundError"), result.output);
    assertFalse(result.output.contains("Check your network"), result.output);
    assertTrue(ChatGptText.isKnownError(ChatGptHttp.error("runtime").getMessage()));
  }

  @Test
  void timeoutRetainsDiagnosticsAndStopsItsOwnedProbe() throws Exception {
    Path evidence = evidenceDirectory("controlled-timeout-");
    var command =
        List.of(
            tool("java"),
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            HungProbe.class.getName());

    AssertionError failure = assertThrows(AssertionError.class, () -> run(command, evidence, 0));

    assertTrue(failure.getMessage().contains("Runtime callback probe timed out"));
    String lifecycle = Files.readString(evidence.resolve("lifecycle.txt"));
    long pid =
        Long.parseLong(
            lifecycle
                .lines()
                .filter(line -> line.startsWith("pid="))
                .findFirst()
                .orElseThrow()
                .substring(4));
    assertTrue(pid > 0, lifecycle);
    assertTrue(lifecycle.contains("alive=false"), lifecycle);
    assertTrue(
        Files.readString(evidence.resolve("command.txt")).contains(HungProbe.class.getName()));
    assertTrue(Files.isRegularFile(evidence.resolve("runtime.log")));
    assertTrue(Files.isRegularFile(evidence.resolve("thread-stacks.log")));
  }

  private Result run(boolean oldRuntime) throws Exception {
    String jar = System.getProperty("lizzie.shaded.jar", "");
    assertFalse(jar.isBlank());
    assertTrue(Files.isRegularFile(Path.of(jar)));
    var command = new ArrayList<String>();
    command.add(tool("java"));
    if (oldRuntime) command.addAll(List.of("--limit-modules", "java.se,jdk.crypto.ec"));
    command.addAll(
        List.of(
            "-Duser.language=en",
            "-Duser.country=US",
            "-cp",
            Path.of(jar).toAbsolutePath().toString(),
            ChatGptRuntimeSmoke.class.getName()));
    return run(command, evidenceDirectory(oldRuntime ? "old-runtime-" : "complete-runtime-"), 30);
  }

  private static Result run(List<String> command, Path evidence, long timeoutSeconds)
      throws Exception {
    Files.writeString(evidence.resolve("command.txt"), String.join("\n", command) + "\n");
    Path log = evidence.resolve("runtime.log");
    Path lifecycle = evidence.resolve("lifecycle.txt");
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    Throwable failure = null;
    try {
      Files.writeString(lifecycle, "pid=" + process.pid() + "\nstarted=" + Instant.now() + "\n");
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        AssertionError timeout =
            new AssertionError("Runtime callback probe timed out; evidence: " + evidence);
        try {
          captureThreads(process, evidence.resolve("thread-stacks.log"));
        } catch (Throwable diagnosticFailure) {
          timeout.addSuppressed(diagnosticFailure);
        }
        throw timeout;
      }
      Files.writeString(lifecycle, "exit=" + process.exitValue() + "\n", StandardOpenOption.APPEND);
      return new Result(process.exitValue(), Files.readString(log));
    } catch (Throwable error) {
      failure = error;
      throw error;
    } finally {
      try {
        stop(process);
        Files.writeString(
            lifecycle,
            "alive=" + process.isAlive() + "\nfinished=" + Instant.now() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (Throwable cleanupFailure) {
        if (failure != null) failure.addSuppressed(cleanupFailure);
        else throw cleanupFailure;
      }
    }
  }

  private static void captureThreads(Process process, Path log) throws Exception {
    if (!process.isAlive()) {
      Files.writeString(log, "Owned probe exited before thread capture\n");
      return;
    }
    Process diagnostic =
        new ProcessBuilder(tool("jcmd"), Long.toString(process.pid()), "Thread.print", "-l")
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      if (!diagnostic.waitFor(5, TimeUnit.SECONDS))
        throw new IOException("Thread dump timed out: " + log);
      if (diagnostic.exitValue() != 0)
        throw new IOException("Thread dump exited " + diagnostic.exitValue() + ": " + log);
    } finally {
      stop(diagnostic);
    }
  }

  private static void stop(Process process) throws InterruptedException {
    if (process.isAlive()) process.destroyForcibly();
    assertTrue(
        process.waitFor(5, TimeUnit.SECONDS), "Probe process did not stop: " + process.pid());
  }

  private static Path evidenceDirectory(String prefix) throws IOException {
    Path root = Path.of("target", "failsafe-reports", "chatgpt-runtime-smoke").toAbsolutePath();
    Files.createDirectories(root);
    return Files.createTempDirectory(root, prefix);
  }

  private static String tool(String name) {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""))
        .toString();
  }

  public static final class HungProbe {
    public static void main(String[] args) throws InterruptedException {
      System.out.println("Controlled runtime probe waiting for its test owner");
      new java.util.concurrent.CountDownLatch(1).await();
    }
  }

  private record Result(int exitCode, String output) {}
}
