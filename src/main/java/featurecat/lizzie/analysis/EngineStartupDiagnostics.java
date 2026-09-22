package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.EngineObservation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Observational session service; launch, readiness, cleanup and presentation remain owner
 * decisions.
 */
public final class EngineStartupDiagnostics implements AutoCloseable {
  private static final java.util.Set<String> COLLECTOR_SOURCES =
      java.util.Set.of("process-tail", "runtime", "pe-import-scan");

  public record Policy(
      int workers,
      int queuedJobs,
      long deadlineMillis,
      int tailLines,
      int tailBytes,
      int diagnosticBytes,
      int historyCount,
      int historyBytes) {
    public Policy {
      if (workers < 1
          || queuedJobs < 1
          || deadlineMillis < 1
          || tailLines < 1
          || tailBytes < 32
          || diagnosticBytes < 4096
          || historyCount < 1
          || historyBytes < diagnosticBytes)
        throw new IllegalArgumentException("Invalid diagnostic policy");
    }

    public static Policy production() {
      return new Policy(2, 32, 2000, 40, 16 * 1024, 256 * 1024, 32, 8 * 1024 * 1024);
    }
  }

  public record Finding(
      String outcome,
      String dll,
      String importer,
      List<String> chain,
      String evidence,
      String completeness,
      String checkedScope,
      String detail,
      Instant checkedAt) {
    public Finding {
      chain = chain == null ? List.of() : List.copyOf(chain);
    }

    JSONObject json() {
      return new JSONObject()
          .put("outcome", outcome)
          .put("dll", nullable(dll))
          .put("importer", nullable(importer))
          .put("chain", new JSONArray(chain))
          .put("evidence", evidence)
          .put("completeness", completeness)
          .put("checkedScope", checkedScope)
          .put("detail", detail)
          .put("checkedAt", checkedAt == null ? Instant.now().toString() : checkedAt.toString());
    }
  }

  public record Evidence(List<Finding> findings, String checkedScope) {
    public Evidence {
      findings = List.copyOf(findings);
    }
  }

  @FunctionalInterface
  public interface Collector {
    Evidence collect() throws Exception;
  }

  public record History(List<EngineStartupDiagnostic> failures, long evicted) {
    public History {
      failures = List.copyOf(failures);
    }

    public JSONObject toJson() {
      JSONArray records = new JSONArray();
      failures.forEach(f -> records.put(f.toJson()));
      return new JSONObject()
          .put("status", failures.isEmpty() ? "no-failures" : "available")
          .put("evicted", evicted)
          .put("failures", records);
    }
  }

  private static final EngineStartupDiagnostics DEFAULT =
      new EngineStartupDiagnostics(Policy.production(), EngineObservation::recordStartupDiagnostic);

  public static EngineStartupDiagnostics getDefault() {
    return DEFAULT;
  }

  private final Policy policy;
  private final Consumer<EngineStartupDiagnostic> publisher;
  private final ThreadPoolExecutor workers;
  private final ScheduledThreadPoolExecutor deadlines;
  private final LinkedHashMap<String, EngineStartupDiagnostic> history = new LinkedHashMap<>();
  private final java.util.Set<Job> activeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private long evicted;
  private int historyBytes;

  public EngineStartupDiagnostics(Policy policy, Consumer<EngineStartupDiagnostic> publisher) {
    this.policy = policy;
    this.publisher = publisher;
    workers =
        new ThreadPoolExecutor(
            policy.workers(),
            policy.workers(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(policy.queuedJobs()),
            r -> daemon(r, "engine-diagnostic-worker"));
    deadlines = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "engine-diagnostic-deadline"));
    deadlines.setRemoveOnCancelPolicy(true);
  }

  private static Thread daemon(Runnable r, String name) {
    Thread thread = new Thread(r, name);
    thread.setDaemon(true);
    return thread;
  }

  public Attempt begin(String engineId, String purpose, List<String> command, boolean local) {
    return new Attempt(engineId, purpose, command, local);
  }

  public synchronized History snapshot() {
    return new History(new ArrayList<>(history.values()), evicted);
  }

  public synchronized EngineStartupDiagnostic find(String attemptId) {
    return history.get(attemptId);
  }

  private synchronized void retain(EngineStartupDiagnostic diagnostic) {
    if (diagnostic.revision() > 1 && !history.containsKey(diagnostic.attemptId())) return;
    EngineStartupDiagnostic previous = history.put(diagnostic.attemptId(), diagnostic);
    if (previous != null) historyBytes -= previous.sizeBytes();
    historyBytes += diagnostic.sizeBytes();
    while (history.size() > policy.historyCount() || historyBytes > policy.historyBytes()) {
      String oldest = history.keySet().iterator().next();
      historyBytes -= history.remove(oldest).sizeBytes();
      evicted++;
    }
  }

  public final class Attempt {
    private final String id = UUID.randomUUID().toString();
    private final JSONObject launch = new JSONObject();
    private final JSONObject sources = new JSONObject();
    private final JSONArray findings = new JSONArray();
    private int findingBytes = 2;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Tail stdout = new Tail();
    private final Tail stderr = new Tail();
    private boolean launchTruncated;
    private final boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    private Process process;
    private boolean ready;
    private boolean terminatedByOwner;
    private boolean stdoutEnded;
    private boolean stderrEnded;
    private String outputError;
    private Integer naturalExit;
    private String phase;
    private String originalError;
    private Instant failedAt;
    private long deadlineNanos;
    private long revision;
    private EngineStartupDiagnostic published;

    private Attempt(String engineId, String purpose, List<String> command, boolean local) {
      launch
          .put("attemptId", id)
          .put("engineId", limited(engineId, 128))
          .put("launchPurpose", limited(purpose, 128))
          .put("startedAt", Instant.now().toString())
          .put("environmentState", "not-formed")
          .put("local", local)
          .put("platform", windows ? "windows" : "non-windows")
          .put("configuredCommand", launchText(String.join(" ", command), 16384));
      sources.put(
          "pe-import-scan",
          state(
              "not-applicable",
              "not-applicable",
              "not-collected",
              "No static dependency scan evidence"));
    }

    public String id() {
      return id;
    }

    public synchronized EngineStartupDiagnostic snapshot() {
      return published;
    }

    /** Called once, only after all builder mutations and before start. */
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
      String path =
          builder.environment().entrySet().stream()
              .filter(
                  e -> windows ? e.getKey().equalsIgnoreCase("PATH") : e.getKey().equals("PATH"))
              .map(Map.Entry::getValue)
              .findFirst()
              .orElse("");
      launch.put("effectivePath", launchText(path, 65536));
      String executable = command.get(0).toLowerCase(Locale.ROOT);
      boolean wrapper = Leelaz.isIndirectLauncher(executable);
      String applicability =
          !launch.getBoolean("local")
              ? "remote"
              : !windows
                  ? "non-windows"
                  : wrapper || !executable.endsWith(".exe")
                      ? "non-native-or-wrapper"
                      : "not-collected";
      launch.put("dependencyApplicability", applicability);
      launch.put(
          "searchContext",
          new JSONObject()
              .put("systemRoot", limited(builder.environment().get("SystemRoot"), 4096))
              .put(
                  "architecture", limited(builder.environment().get("PROCESSOR_ARCHITECTURE"), 128))
              .put("checkedScope", "captured launch environment; no static scan"));
      sources.put(
          "pe-import-scan",
          state(
              "not-applicable",
              "not-applicable",
              applicability,
              "No static dependency scan evidence"));
    }

    private String launchText(String value, int maxBytes) {
      String result = limited(value, maxBytes);
      launchTruncated |= value != null && !result.equals(value);
      return result;
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
      ready = true;
    }

    public synchronized boolean collectingOutput() {
      return !ready && (published == null || jobs.values().stream().anyMatch(j -> !j.terminal));
    }

    public synchronized void output(String stream, String line) {
      if (!collectingOutput()) return;
      ("stderr".equals(stream) ? stderr : stdout).add(line);
      notifyAll();
    }

    public synchronized void streamEnded(String stream, String error) {
      if ("stderr".equals(stream)) stderrEnded = true;
      else stdoutEnded = true;
      if (error != null) outputError = limited(error, 1024);
      notifyAll();
    }

    /**
     * Observe before any destroy/cleanup: a later forced exit must never be decoded as Loader
     * evidence.
     */
    public synchronized void beforeTermination() {
      observeExit();
      if (naturalExit == null) terminatedByOwner = true;
      notifyAll();
    }

    public synchronized EngineStartupDiagnostic fail(String stage, String error) {
      if (ready || failedAt != null) return published;
      phase = limited(stage, 128);
      originalError = limited(error, 16384);
      failedAt = Instant.now();
      deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.deadlineMillis());
      observeExit();
      sources.put(
          "process-tail",
          state(
              process == null ? "not-applicable" : "applicable",
              process == null ? "not-applicable" : "queued",
              process == null ? "no-process" : "",
              "Original process exit and bounded stdout and stderr; no DLL dependency scan"));
      publish();
      if (process != null)
        collect(
            "process-tail",
            () -> {
              synchronized (Attempt.this) {
                while (true) {
                  observeExit();
                  if ((naturalExit != null || terminatedByOwner) && stdoutEnded && stderrEnded)
                    break;
                  long remaining = deadlineNanos - System.nanoTime();
                  if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                  TimeUnit.NANOSECONDS.timedWait(
                      Attempt.this, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)));
                }
                if (outputError != null) throw new IOException(outputError);
                if (terminatedByOwner && naturalExit == null)
                  throw new CollectionFailure("exit-unavailable");
                return new Evidence(
                    List.of(),
                    "Observed original process exit and drained bounded output; no DLL dependency scan");
              }
            });
      return published;
    }

    private void observeExit() {
      if (naturalExit != null || process == null || terminatedByOwner) return;
      try {
        naturalExit = process.exitValue();
      } catch (IllegalThreadStateException ignored) {
      }
    }

    /** All collectors share the original failure deadline and the same bounded session workers. */
    public synchronized void collect(String source, Collector collector) {
      if (failedAt == null) throw new IllegalStateException("Save base failure before collecting");
      if (!COLLECTOR_SOURCES.contains(source))
        throw new IllegalArgumentException("Unknown diagnostic collector: " + source);
      if (jobs.containsKey(source)) return;
      Job job = new Job(this, source, collector);
      jobs.put(source, job);
      activeJobs.add(job);
      sources.put(source, state("applicable", "queued", "", "collection pending"));
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0) {
        finish(job, "timeout", "timeout", null);
        return;
      }
      publish();
      try {
        workers.execute(job.task);
        job.alarm =
            deadlines.schedule(
                () -> finish(job, "timeout", "timeout", null), remaining, TimeUnit.NANOSECONDS);
        if (job.terminal) job.alarm.cancel(false);
      } catch (RejectedExecutionException rejected) {
        finish(job, "rejected", "queue-full", null);
      }
    }

    public synchronized void cancel() {
      beforeTermination();
      for (Job job : jobs.values()) finish(job, "cancelled", "cancelled", null);
    }

    private synchronized void finish(
        Job job, String collectionState, String reason, Evidence evidence) {
      if (job.terminal) return;
      job.terminal = true;
      activeJobs.remove(job);
      if (job.alarm != null) job.alarm.cancel(false);
      if (!"completed".equals(collectionState)) {
        job.task.cancel(true);
        workers.remove(job.task);
      }
      if (evidence != null) {
        for (Finding finding : evidence.findings()) {
          JSONObject next = finding.json();
          int nextBytes =
              EngineStartupDiagnostic.bytes(next.toString()) + (findings.isEmpty() ? 0 : 1);
          if (findingBytes + nextBytes > policy.diagnosticBytes() / 2) {
            collectionState = "partial";
            reason = "result-limit";
            break;
          }
          findings.put(next);
          findingBytes += nextBytes;
        }
      }
      sources.put(
          job.source,
          state(
              "applicable",
              collectionState,
              reason,
              evidence == null
                  ? "partial collection; no static scan evidence"
                  : evidence.checkedScope()));
      publish();
    }

    private void publish() {
      String outcome = "not-applicable";
      boolean applicable = false;
      boolean pending = false;
      boolean incomplete = false;
      for (String key : sources.keySet()) {
        JSONObject source = sources.getJSONObject(key);
        if (!"applicable".equals(source.getString("applicability"))) continue;
        applicable = true;
        String status = source.getString("collectionState");
        pending |= status.equals("queued") || status.equals("collecting");
        incomplete |= !status.equals("completed");
      }
      if (applicable)
        outcome =
            pending
                ? "collecting"
                : incomplete
                    ? "partial"
                    : findings.isEmpty() ? "no-specific-dll-identified" : "evidence-available";
      JSONObject value =
          new JSONObject()
              .put("attemptId", id)
              .put("engineId", launch.getString("engineId"))
              .put("diagnosticRevision", ++revision)
              .put("launchPurpose", launch.getString("launchPurpose"))
              .put("phase", phase)
              .put("originalError", originalError)
              .put("failedAt", failedAt.toString())
              .put("checkedAt", Instant.now().toString())
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
              .put("stderr", stderr.text())
              .put("stdoutTruncated", stdout.truncated)
              .put("stderrTruncated", stderr.truncated)
              .put("sources", sources)
              .put("findings", findings)
              .put("outcome", outcome)
              .put("truncated", launchTruncated || stdout.truncated || stderr.truncated);
      published = new EngineStartupDiagnostic(value, policy.diagnosticBytes());
      retain(published);
      if (publisher != null) {
        try {
          publisher.accept(published);
        } catch (RuntimeException ignored) {
        }
      }
    }
  }

  private final class Job {
    final Attempt attempt;
    final String source;
    final FutureTask<Void> task;
    boolean terminal;
    java.util.concurrent.ScheduledFuture<?> alarm;

    Job(Attempt attempt, String source, Collector collector) {
      this.attempt = attempt;
      this.source = source;
      task =
          new FutureTask<>(
              () -> {
                synchronized (attempt) {
                  if (terminal) return null;
                  if (System.nanoTime() >= attempt.deadlineNanos) {
                    attempt.finish(this, "timeout", "timeout", null);
                    return null;
                  }
                  attempt.sources.put(
                      source, state("applicable", "collecting", "", "collection in progress"));
                  attempt.publish();
                }
                try {
                  Evidence result = collector.collect();
                  synchronized (attempt) {
                    if (System.nanoTime() >= attempt.deadlineNanos)
                      attempt.finish(this, "timeout", "timeout", null);
                    else attempt.finish(this, "completed", "", result);
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  attempt.finish(this, "cancelled", "cancelled", null);
                } catch (CollectionFailure unavailable) {
                  attempt.finish(this, "partial", unavailable.getMessage(), null);
                } catch (java.util.concurrent.TimeoutException timeout) {
                  attempt.finish(this, "timeout", "timeout", null);
                } catch (Exception failure) {
                  attempt.finish(this, "failed", "read-error", null);
                }
                return null;
              });
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

  private static JSONObject state(
      String applicability, String collection, String reason, String scope) {
    return new JSONObject()
        .put("applicability", applicability)
        .put("collectionState", collection)
        .put("terminalReason", limited(reason, 128))
        .put("checkedScope", limited(scope, 2048));
  }

  private static Object nullable(Object value) {
    return value == null ? JSONObject.NULL : value;
  }

  private static String limited(String value, int bytes) {
    return EngineStartupDiagnostic.bounded(value, bytes);
  }

  private static final class CollectionFailure extends Exception {
    private static final long serialVersionUID = 1L;

    CollectionFailure(String reason) {
      super(reason);
    }
  }

  @Override
  public void close() {
    workers.shutdownNow();
    for (Job job : activeJobs) job.attempt.finish(job, "cancelled", "cancelled", null);
    deadlines.shutdownNow();
  }
}
