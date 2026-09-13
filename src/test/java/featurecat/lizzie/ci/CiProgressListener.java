package featurecat.lizzie.ci;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.json.JSONObject;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Emits flushed, append-only JUnit execution evidence for the CI stall supervisor.
 *
 * <p>This listener is intentionally test-scope only. It does no work unless {@value
 * #EVIDENCE_DIRECTORY_ENV} names an output directory.
 */
public final class CiProgressListener implements TestExecutionListener {
  static final String EVIDENCE_DIRECTORY_ENV = "LIZZIE_CI_EVIDENCE_DIR";
  private final Path evidenceDirectory;
  private final long processId;
  private static final Object FILE_WRITE_LOCK = new Object();
  private BufferedWriter writer;
  private boolean outputFailed;
  private boolean failureReported;

  /** Creates a listener configured from {@value #EVIDENCE_DIRECTORY_ENV}. */
  public CiProgressListener() {
    this(readEvidenceDirectory());
  }

  /**
   * Creates a listener writing to the supplied directory.
   *
   * <p>Package-private so focused behavior tests can use a temporary directory without changing the
   * child process environment.
   */
  CiProgressListener(Path evidenceDirectory) {
    this.evidenceDirectory = evidenceDirectory;
    this.processId = evidenceDirectory == null ? -1L : ProcessHandle.current().pid();
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    emitPlanEvent("PLAN_STARTED", true);
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    emitPlanEvent("PLAN_FINISHED", false);
    synchronized (FILE_WRITE_LOCK) {
      closeWriter();
    }
  }

  @Override
  public void executionStarted(TestIdentifier testIdentifier) {
    emitIdentifierEvent("START", testIdentifier, null, null);
  }

  @Override
  public void executionFinished(
      TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
    if (evidenceDirectory != null) {
      emitIdentifierEvent("FINISH", testIdentifier, testExecutionResult.getStatus().name(), null);
    }
  }

  @Override
  public void executionSkipped(TestIdentifier testIdentifier, String reason) {
    emitIdentifierEvent("SKIPPED", testIdentifier, "SKIPPED", reason);
  }

  private static Path readEvidenceDirectory() {
    try {
      String configured = System.getenv(EVIDENCE_DIRECTORY_ENV);
      if (configured == null || configured.isBlank()) {
        return null;
      }
      return Path.of(configured);
    } catch (RuntimeException exception) {
      reportFailure(exception);
      return null;
    }
  }

  private void emitPlanEvent(String event, boolean includeJvmIdentity) {
    if (evidenceDirectory == null) {
      return;
    }
    synchronized (FILE_WRITE_LOCK) {
      if (outputFailed) {
        return;
      }
      try {
        JSONObject record = newRecord(event);
        if (includeJvmIdentity) {
          record.put("jvm", ManagementFactory.getRuntimeMXBean().getName());
          record.put("javaVersion", System.getProperty("java.runtime.version"));
          record.put("javaVendor", System.getProperty("java.vendor"));
        }
        writeRecord(record);
      } catch (IOException | RuntimeException exception) {
        disableOutput(exception);
      }
    }
  }

  private void emitIdentifierEvent(
      String event, TestIdentifier testIdentifier, String status, String reason) {
    if (evidenceDirectory == null) {
      return;
    }
    synchronized (FILE_WRITE_LOCK) {
      if (outputFailed) {
        return;
      }
      try {
        JSONObject record = newRecord(event);
        record.put("id", testIdentifier.getUniqueId());
        record.put("displayName", testIdentifier.getDisplayName());
        if (status != null) {
          record.put("status", status);
        }
        if (reason != null && !reason.isBlank()) {
          record.put("reason", reason);
        }
        writeRecord(record);
      } catch (IOException | RuntimeException exception) {
        disableOutput(exception);
      }
    }
  }

  private JSONObject newRecord(String event) {
    return new JSONObject()
        .put("time", Instant.now().toString())
        .put("pid", processId)
        .put("event", event);
  }

  private void writeRecord(JSONObject record) throws IOException {
    if (writer == null) {
      Files.createDirectories(evidenceDirectory);
      Path outputFile = evidenceDirectory.resolve("junit-" + processId + ".jsonl");
      writer =
          Files.newBufferedWriter(
              outputFile,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND);
    }
    writer.write(record.toString());
    writer.newLine();
    writer.flush();
  }

  private void disableOutput(Exception exception) {
    outputFailed = true;
    closeWriter();
    reportFailureOnce(exception);
  }

  private void closeWriter() {
    if (writer == null) {
      return;
    }
    try {
      writer.close();
    } catch (IOException exception) {
      reportFailureOnce(exception);
    } finally {
      writer = null;
    }
  }

  private void reportFailureOnce(Exception exception) {
    if (!failureReported) {
      failureReported = true;
      reportFailure(exception);
    }
  }

  private static void reportFailure(Exception exception) {
    System.err.println(
        "CiProgressListener: unable to write CI evidence ("
            + exception.getClass().getSimpleName()
            + "): "
            + exception.getMessage());
  }
}
