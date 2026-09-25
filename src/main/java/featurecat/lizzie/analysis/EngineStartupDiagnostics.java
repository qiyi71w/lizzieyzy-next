package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.util.KataGoRuntimeHelper.NvidiaRuntimeStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Captures one user-facing startup failure without taking ownership of the engine process. */
public final class EngineStartupDiagnostics implements AutoCloseable {
  public record Policy(
      int pendingFailures, long deadlineMillis, int tailLines, int tailBytes, int diagnosticBytes) {
    public Policy {
      if (pendingFailures < 1
          || deadlineMillis < 1
          || tailLines < 1
          || tailBytes < 32
          || diagnosticBytes < 4096) {
        throw new IllegalArgumentException("Invalid failure capture policy");
      }
    }

    public static Policy production() {
      return new Policy(32, 2000, 40, 16 * 1024, 256 * 1024);
    }
  }

  public record Finding(
      String outcome, String dll, String evidence, String detail, Instant checkedAt) {
    JSONObject json() {
      return new JSONObject()
          .put("outcome", outcome)
          .put("dll", nullable(dll))
          .put("evidence", evidence)
          .put("detail", detail)
          .put("checkedAt", checkedAt.toString());
    }
  }

  private static final EngineStartupDiagnostics DEFAULT =
      new EngineStartupDiagnostics(Policy.production(), EngineObservation::recordStartupDiagnostic);

  public static EngineStartupDiagnostics getDefault() {
    return DEFAULT;
  }

  private final Policy policy;
  private final Consumer<EngineStartupDiagnostic> publisher;
  private final ScheduledThreadPoolExecutor tails;
  private final Semaphore slots;
  private final Set<Attempt> pending = ConcurrentHashMap.newKeySet();

  public EngineStartupDiagnostics(Policy policy, Consumer<EngineStartupDiagnostic> publisher) {
    this.policy = policy;
    this.publisher = publisher;
    slots = new Semaphore(policy.pendingFailures());
    tails =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread thread = new Thread(r, "engine-failure-tail");
              thread.setDaemon(true);
              return thread;
            });
    tails.setRemoveOnCancelPolicy(true);
  }

  public Attempt begin(String engineId, String purpose, List<String> command, boolean local) {
    return new Attempt(engineId, purpose, command, local);
  }

  public final class Attempt {
    private final String id = UUID.randomUUID().toString();
    private final boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    private final JSONObject launch = new JSONObject();
    private final JSONObject sources = new JSONObject();
    private final List<Finding> preflight = new ArrayList<>();
    private final Tail stdout = new Tail();
    private final Tail stderr = new Tail();
    private Process process;
    private boolean ready;
    private boolean terminatedByOwner;
    private boolean stdoutEnded;
    private boolean stderrEnded;
    private boolean stdoutMerged;
    private boolean launchTruncated;
    private boolean finished;
    private String outputError;
    private Integer naturalExit;
    private String phase;
    private String originalError;
    private Instant failedAt;
    private long deadlineNanos;
    private ScheduledFuture<?> tailTask;
    private EngineStartupDiagnostic published;

    private Attempt(String engineId, String purpose, List<String> command, boolean local) {
      launch
          .put("engineId", limited(engineId, 128))
          .put("launchPurpose", limited(purpose, 128))
          .put("startedAt", Instant.now().toString())
          .put("environmentState", "not-formed")
          .put("local", local)
          .put("platform", windows ? "windows" : "non-windows")
          .put("configuredCommand", launchText(String.join(" ", command), 16384));
    }

    public String id() {
      return id;
    }

    public synchronized EngineStartupDiagnostic snapshot() {
      return published;
    }

    /**
     * Capture the owner's final command; never reconstruct a failed launch from current settings.
     */
    public synchronized void capture(ProcessBuilder builder) {
      if (process != null || !"not-formed".equals(launch.getString("environmentState")))
        throw new IllegalStateException("Launch context already frozen");
      Path cwd =
          builder.directory() == null
              ? Path.of("").toAbsolutePath()
              : builder.directory().toPath().toAbsolutePath();
      List<String> command = builder.command();
      JSONArray arguments = new JSONArray();
      int remaining = 32768;
      for (String argument : command.subList(1, command.size())) {
        if (remaining <= 0) {
          launchTruncated = true;
          break;
        }
        String captured = launchText(argument, remaining);
        arguments.put(captured);
        remaining -= EngineStartupDiagnostic.bytes(captured) + 1;
      }
      launch
          .put("environmentState", "final")
          .put("executable", launchText(command.get(0), 16384))
          .put("arguments", arguments)
          .put("cwd", launchText(cwd.normalize().toString(), 16384))
          .put("cwdSource", builder.directory() == null ? "inherited" : "explicit");
    }

    private String launchText(String value, int bytes) {
      String result = limited(value, bytes);
      launchTruncated |= value != null && !result.equals(value);
      return result;
    }

    /** Reuse the runtime check already performed by the startup owner. */
    public synchronized void runtimePreflight(NvidiaRuntimeStatus status) {
      if (failedAt != null || !"not-formed".equals(launch.getString("environmentState")))
        throw new IllegalStateException("Preflight observation must precede final environment");
      preflight.clear();
      sources.put(
          "runtime-preflight",
          state(
              status.readError ? "partial" : "completed",
              status.readError ? "read-error" : "",
              status.checkedScope));
      if (!status.applicable || status.ready) {
        preflight.add(
            new Finding(
                status.applicable ? "runtime-requirements-satisfied" : "runtime-not-applicable",
                null,
                "runtime-preflight",
                status.detailText,
                status.checkedAt));
      } else {
        for (String missing : status.missingDlls) {
          boolean manifest = missing.contains("manifest");
          String dll = !manifest && missing.matches("(?i)[a-z0-9_.-]+\\.dll") ? missing : null;
          preflight.add(
              new Finding(
                  manifest ? "runtime-manifest-mismatch" : "not-found-in-checked-search-scope",
                  dll,
                  "runtime-preflight",
                  missing,
                  status.checkedAt));
        }
      }
    }

    public synchronized void attachProcess(Process original) {
      if (process != null && process != original)
        throw new IllegalStateException("Attempt process replaced");
      process = original;
      if (original != null) {
        try {
          launch.put("pid", original.pid());
        } catch (UnsupportedOperationException ignored) {
        }
      }
    }

    public synchronized void attach(Process original, long readerIncarnation) {
      attachProcess(original);
      launch.put("readerIncarnation", readerIncarnation);
    }

    public synchronized void ready() {
      if (failedAt == null) ready = true;
    }

    public synchronized boolean collectingOutput() {
      return !ready && !finished;
    }

    public synchronized void output(String stream, String line) {
      if (!collectingOutput()) return;
      if ("merged".equals(stream)) stdoutMerged = true;
      ("stderr".equals(stream) ? stderr : stdout).add(line);
    }

    public synchronized void streamEnded(String stream, String error) {
      if ("stderr".equals(stream)) stderrEnded = true;
      else stdoutEnded = true;
      if (error != null) outputError = limited(error, 1024);
    }

    /** Observe before destroy: an owner-induced exit is not evidence of a Loader failure. */
    public synchronized void beforeTermination() {
      observeExit();
      if (naturalExit == null) terminatedByOwner = true;
    }

    public synchronized EngineStartupDiagnostic fail(String stage, String error) {
      if (ready || failedAt != null) return published;
      phase = limited(stage, 128);
      originalError = limited(error, 16384);
      failedAt = Instant.now();
      deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.deadlineMillis());
      observeExit();
      if (process == null || tailComplete()) {
        finish(
            outputError != null
                ? "read-error"
                : terminatedByOwner && naturalExit == null ? "exit-unavailable" : "");
      } else if (!slots.tryAcquire()) {
        finish("queue-full");
      } else {
        pending.add(this);
        sources.put("process-tail", state("collecting", "", "Original process exit and output"));
        publish("collecting");
        try {
          tailTask = tails.scheduleAtFixedRate(this::checkTail, 0, 20, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closed) {
          finish("cancelled");
        }
      }
      return published;
    }

    private boolean tailComplete() {
      return (naturalExit != null || terminatedByOwner) && stdoutEnded && stderrEnded;
    }

    private synchronized void checkTail() {
      if (finished) return;
      observeExit();
      if (tailComplete()) {
        finish(
            outputError != null
                ? "read-error"
                : terminatedByOwner && naturalExit == null ? "exit-unavailable" : "");
      } else if (System.nanoTime() >= deadlineNanos) {
        finish("timeout");
      }
    }

    private void observeExit() {
      if (naturalExit != null || process == null || terminatedByOwner) return;
      try {
        naturalExit = process.exitValue();
      } catch (IllegalThreadStateException ignored) {
      }
    }

    public synchronized void cancel() {
      beforeTermination();
      if (failedAt != null && !finished) finish("cancelled");
    }

    private void finish(String reason) {
      if (finished) return;
      finished = true;
      if (tailTask != null) tailTask.cancel(false);
      if (pending.remove(this)) slots.release();
      String collection = reason.isEmpty() ? "completed" : "partial";
      sources.put("process-tail", state(collection, reason, "Original process exit and output"));
      publish(collection);
    }

    private void publish(String collection) {
      Instant checked = Instant.now();
      List<Finding> evidence = new ArrayList<>(preflight);
      evidence.addAll(
          EngineOutputDiagnostic.parse(stdoutMerged ? "merged" : "stdout", stdout.text(), checked));
      evidence.addAll(EngineOutputDiagnostic.parse("stderr", stderr.text(), checked));
      JSONArray findings = new JSONArray();
      int bytes = 2;
      boolean truncated = launchTruncated || stdout.truncated || stderr.truncated;
      boolean identified = false;
      for (Finding finding : evidence) {
        JSONObject value = finding.json();
        bytes += EngineStartupDiagnostic.bytes(value.toString()) + 1;
        if (bytes > policy.diagnosticBytes() / 2) {
          truncated = true;
          break;
        }
        findings.put(value);
        identified |= finding.dll() != null && !finding.dll().isBlank();
      }
      JSONObject value =
          new JSONObject()
              .put("attemptId", id)
              .put("engineId", launch.getString("engineId"))
              .put("launchPurpose", launch.getString("launchPurpose"))
              .put("phase", phase)
              .put("originalError", originalError)
              .put("failedAt", failedAt.toString())
              .put("checkedAt", checked.toString())
              .put("launch", launch)
              .put("exitCode", nullable(naturalExit))
              .put(
                  "exitHex",
                  naturalExit == null
                      ? JSONObject.NULL
                      : String.format(Locale.ROOT, "0x%08X", naturalExit))
              .put(
                  "statusName",
                  naturalExit == null
                      ? "unavailable"
                      : windows
                          ? EngineStartupDiagnostic.windowsStatus(naturalExit)
                          : "not-applicable")
              .put(
                  "errorDomain",
                  naturalExit == null ? "unavailable" : windows ? "NTSTATUS" : "process-exit")
              .put("exitObservation", naturalExit == null ? "unavailable" : "natural")
              .put("stdout", stdout.text())
              .put("stdoutOrigin", stdoutMerged ? "merged" : "stdout")
              .put("stderr", stderr.text())
              .put("stdoutTruncated", stdout.truncated)
              .put("stderrTruncated", stderr.truncated)
              .put("sources", sources)
              .put("findings", findings)
              .put("collectionState", collection)
              .put(
                  "outcome",
                  !finished
                      ? "collecting"
                      : identified ? "evidence-available" : "no-specific-dll-identified")
              .put("truncated", truncated);
      published = new EngineStartupDiagnostic(value, policy.diagnosticBytes());
      if (publisher != null) {
        try {
          publisher.accept(published);
        } catch (RuntimeException ignored) {
        }
      }
    }
  }

  private final class Tail {
    final ArrayDeque<String> lines = new ArrayDeque<>();
    int bytes;
    boolean truncated;

    void add(String line) {
      if (line == null) return;
      String bounded = limited(line, policy.tailBytes() - 1);
      truncated |= !bounded.equals(line);
      int size = EngineStartupDiagnostic.bytes(bounded) + 1;
      while (!lines.isEmpty()
          && (lines.size() >= policy.tailLines() || bytes + size > policy.tailBytes())) {
        bytes -= EngineStartupDiagnostic.bytes(lines.removeFirst()) + 1;
        truncated = true;
      }
      lines.addLast(bounded);
      bytes += size;
    }

    String text() {
      return String.join("\n", lines);
    }
  }

  private static JSONObject state(String collection, String reason, String scope) {
    return new JSONObject()
        .put("collectionState", collection)
        .put("terminalReason", reason)
        .put("checkedScope", limited(scope, 2048));
  }

  private static Object nullable(Object value) {
    return value == null ? JSONObject.NULL : value;
  }

  private static String limited(String value, int bytes) {
    return EngineStartupDiagnostic.bounded(value, bytes);
  }

  @Override
  public void close() {
    tails.shutdownNow();
    for (Attempt attempt : pending) attempt.cancel();
  }
}
