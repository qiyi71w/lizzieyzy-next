package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One slot-owned local KataGo benchmark. No GTP streams or readiness participate in this lifetime.
 */
public final class BenchmarkExecution {
  public enum State {
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public record Snapshot(State state, Integer exitCode, String detail, String outputTail) {}

  private static final int TAIL_CHARS = 16_384;
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private final Leelaz owner;
  private final long invocationId = SEQUENCE.incrementAndGet();
  private final int engineIndex;
  private final boolean main;
  private final List<String> tokens;
  private final String engineCommand;
  private final String consolePrefix;
  private final CompletableFuture<Snapshot> completion = new CompletableFuture<>();
  private final CountDownLatch reaped = new CountDownLatch(1);
  private final StringBuilder tail = new StringBuilder();
  private final Object lock = new Object();
  private State state = State.STARTING;
  private Snapshot terminal;
  private Process process;
  private boolean cancelled;
  private String failureDetail;

  BenchmarkExecution(Leelaz owner, int engineIndex, boolean main, String engineCommand) {
    this.owner = owner;
    this.engineIndex = engineIndex;
    this.main = main;
    this.engineCommand = engineCommand;
    this.tokens = List.copyOf(Utils.splitCommand(engineCommand));
    this.consolePrefix =
        "benchmark["
            + (main ? "main" : "secondary")
            + ":"
            + (engineIndex + 1)
            + "#"
            + invocationId
            + "] ";
  }

  public Snapshot snapshot() {
    synchronized (lock) {
      return terminal != null
          ? terminal
          : new Snapshot(state, null, failureDetail, tail.toString());
    }
  }

  public CompletableFuture<Snapshot> completion() {
    return completion;
  }

  /** Cancellation claims an unfinished invocation before terminating its exact process. */
  public void cancel() {
    Process running;
    synchronized (lock) {
      if (terminal != null || (process != null && !process.isAlive())) return;
      cancelled = true;
      running = process;
    }
    if (running != null) running.destroyForcibly();
  }

  long invocationId() {
    return invocationId;
  }

  int engineIndex() {
    return engineIndex;
  }

  boolean main() {
    return main;
  }

  void startAfterReaping(BenchmarkExecution previous) {
    Process created = null;
    Thread stdout = null;
    Thread stderr = null;
    Integer exitCode = null;
    try {
      if (previous != null) previous.reap();
      synchronized (lock) {
        if (owner.benchmarkExecution() != this) cancelled = true;
        if (cancelled) return;
      }
      if (CommandLaunchHelper.classifyCommand(tokens)
          != CommandLaunchHelper.EngineCommandPurpose.BENCHMARK) {
        throw new IOException("Command is not a local KataGo benchmark");
      }
      CommandLaunchHelper.LaunchSpec launch = CommandLaunchHelper.prepare(tokens);
      List<String> argv = launch.getCommandParts();
      Path executable = KataGoRuntimeHelper.resolveCommandExecutable(argv);
      boolean bundled = Config.isBundledKataGoCommand(engineCommand);
      if (bundled) {
        KataGoRuntimeHelper.ensureBundledRuntimeReady(executable, argv, engineCommand, null);
      }
      synchronized (lock) {
        if (cancelled) return;
      }
      ProcessBuilder builder = new ProcessBuilder(argv);
      CommandLaunchHelper.configureProcessBuilder(builder, launch);
      if (bundled) KataGoRuntimeHelper.configureBundledProcessBuilder(builder, executable);
      created = builder.start();
      synchronized (lock) {
        process = created;
        if (cancelled) created.destroyForcibly();
        else state = State.RUNNING;
      }
      // A CLI owns no protocol input. Closing stdin is EOF, never a synthetic GTP quit.
      created.getOutputStream().close();
      stdout = startReader(created.getInputStream(), "stdout");
      stderr = startReader(created.getErrorStream(), "stderr");
      exitCode = awaitExit(created);
    } catch (Throwable failure) {
      recordFailure(failure);
    } finally {
      if (created != null) {
        if (created.isAlive()) created.destroyForcibly();
        exitCode = awaitExit(created);
        joinReader(stdout);
        joinReader(stderr);
        closeStream(created.getInputStream());
        closeStream(created.getErrorStream());
        try {
          created.getOutputStream().close();
        } catch (IOException failure) {
          recordFailure(failure);
        }
      }
      settle(exitCode);
    }
  }

  /** Replacement cannot skip cleanup when the caller was interrupted. */
  void reap() {
    cancel();
    boolean interrupted = false;
    for (; ; ) {
      try {
        reaped.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private Thread startReader(InputStream stream, String streamName) {
    Thread thread =
        new Thread(() -> readStream(stream), "lizzie-benchmark-" + streamName + "-" + invocationId);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void readStream(InputStream stream) {
    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      // Console events have independent UTF-8 byte and line limits. Keep each event below
      // those bounds rather than allowing a burst of CLI output to be truncated downstream.
      char[] buffer = new char[513];
      int count;
      while ((count = reader.read(buffer, 0, 512)) != -1) {
        if (Character.isHighSurrogate(buffer[count - 1])) {
          int next = reader.read();
          if (next != -1) buffer[count++] = (char) next;
        }
        int start = 0;
        for (int i = 0; i < count; i++) {
          if (buffer[i] == '\n' || buffer[i] == '\r') {
            if (buffer[i] == '\r' && i + 1 < count && buffer[i + 1] == '\n') i++;
            publishOutput(new String(buffer, start, i + 1 - start));
            start = i + 1;
          }
        }
        if (start < count) publishOutput(new String(buffer, start, count - start));
      }
    } catch (Throwable failure) {
      recordFailure(failure);
    }
  }

  private void publishOutput(String output) {
    synchronized (lock) {
      int excess = tail.length() + output.length() - TAIL_CHARS;
      if (excess > 0) tail.delete(0, Math.min(excess, tail.length()));
      tail.append(output);
    }
    if (Lizzie.gtpConsole != null) Lizzie.gtpConsole.addLine(consolePrefix + output);
  }

  private void recordFailure(Throwable failure) {
    Process running;
    synchronized (lock) {
      if (!cancelled && failureDetail == null) {
        String message = failure.getLocalizedMessage();
        failureDetail = message == null || message.isBlank() ? failure.toString() : message;
      }
      running = process;
    }
    // A failed reader cannot leave a writer blocked on its full pipe.
    if (running != null && running.isAlive()) running.destroyForcibly();
  }

  private int awaitExit(Process running) {
    boolean interrupted = false;
    try {
      for (; ; ) {
        try {
          return running.waitFor();
        } catch (InterruptedException failure) {
          interrupted = true;
          recordFailure(failure);
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void joinReader(Thread reader) {
    if (reader == null) return;
    boolean interrupted = false;
    for (; ; ) {
      try {
        reader.join();
        break;
      } catch (InterruptedException failure) {
        interrupted = true;
        recordFailure(failure);
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private void closeStream(InputStream stream) {
    try {
      stream.close();
    } catch (IOException failure) {
      recordFailure(failure);
    }
  }

  private void settle(Integer exitCode) {
    Snapshot result;
    synchronized (lock) {
      if (terminal != null) return;
      state =
          cancelled
              ? State.CANCELLED
              : failureDetail != null || exitCode == null || exitCode != 0
                  ? State.FAILED
                  : State.SUCCEEDED;
      if (state == State.FAILED && failureDetail == null) failureDetail = "exit code " + exitCode;
      result = new Snapshot(state, exitCode, failureDetail, tail.toString());
      terminal = result;
    }
    owner.onBenchmarkTerminal(this);
    reaped.countDown();
    completion.complete(result);
  }
}
