package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EngineStartupDiagnosticsTest {
  @Test
  void decodesWindowsBitPatternsWithoutGuessingUnknownCodes() {
    assertEquals("STATUS_DLL_NOT_FOUND", EngineStartupDiagnostic.windowsStatus(-1073741515));
    assertEquals("STATUS_INVALID_IMAGE_FORMAT", EngineStartupDiagnostic.windowsStatus(0xC000007B));
    assertEquals("STATUS_ENTRYPOINT_NOT_FOUND", EngineStartupDiagnostic.windowsStatus(0xC0000139));
    assertEquals("STATUS_DLL_INIT_FAILED", EngineStartupDiagnostic.windowsStatus(0xC0000142));
    assertEquals("STATUS_ILLEGAL_INSTRUCTION", EngineStartupDiagnostic.windowsStatus(0xC000001D));
    assertEquals("STATUS_ACCESS_VIOLATION", EngineStartupDiagnostic.windowsStatus(0xC0000005));
    assertEquals("unknown", EngineStartupDiagnostic.windowsStatus(-1));
    assertEquals("unknown", EngineStartupDiagnostic.windowsStatus(0));
  }

  @Test
  void preflightAndOversizedTailsRetainIdentityWithoutInventingProcess() {
    var policy = new EngineStartupDiagnostics.Policy(1, 1, 150, 2, 128, 4096, 2, 8192);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var attempt =
          service.begin("eng-bounded", "MAIN_BOARD", List.of("engine", "x".repeat(50000)), true);
      attempt.output("stderr", "old line");
      attempt.output("stderr", "long".repeat(50000));
      attempt.output("stdout", "\u001b".repeat(50000));
      var failure = attempt.fail("runtime-preflight", "error".repeat(50000));
      var json = failure.toJson();
      assertEquals("eng-bounded", failure.engineId());
      assertTrue(failure.sizeBytes() <= 4096);
      assertTrue(json.getBoolean("truncated"));
      assertTrue(json.getBoolean("stderrTruncated"));
      assertTrue(json.isNull("exitCode"));
      assertFalse(json.getJSONObject("launch").has("pid"));
      assertEquals("not-formed", json.getJSONObject("launch").getString("environmentState"));
      assertEquals("not-applicable", json.getString("outcome"));
      assertTrue(
          json.getString("stderr").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 128);
      assertEquals("no-process", reason(attempt, "process-tail"));
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void originalProcessTailRevisesFailureWithoutChangingCapturedEnvironment() throws Exception {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      ProcessBuilder builder =
          new ProcessBuilder(
              "/bin/sh",
              "-c",
              "exec 1>&-; sleep 0.2; printf 'late original output\\n' >&2; exit 17");
      builder.environment().put("PATH", "/original/runtime");
      var attempt = service.begin("eng-original", "MAIN_BOARD", builder.command(), true);
      attempt.capture(builder);
      // Absolute executable is retained, regardless of later builder/config edits.
      builder.environment().put("PATH", "/usr/bin:/bin");
      Process process = builder.start();
      attempt.attach(process, 7);
      assertNull(
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))
              .readLine());
      attempt.streamEnded("stdout", null);
      var first = attempt.fail("startup-exit", "EOF before ready");
      assertTrue(first.toJson().isNull("exitCode"));
      var stderr =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()));
      attempt.output("stderr", stderr.readLine());
      assertNull(stderr.readLine());
      attempt.streamEnded("stderr", null);
      var last = awaitTerminal(attempt);
      assertEquals(17, last.toJson().getInt("exitCode"));
      assertEquals("late original output", last.toJson().getString("stderr"));
      assertEquals(
          "/original/runtime", last.toJson().getJSONObject("launch").getString("effectivePath"));
      assertTrue(last.revision() > first.revision());
      assertTrue(first.toJson().isNull("exitCode"));
      assertEquals("no-specific-dll-identified", last.toJson().getString("outcome"));
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void cancelledProcessDoesNotBecomeLoaderEvidence() throws Exception {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", "sleep 5");
      var attempt = service.begin("eng-cancel", "MAIN_BOARD", builder.command(), true);
      attempt.capture(builder);
      Process process = builder.start();
      attempt.attach(process, 8);
      attempt.fail("startup-exit", "EOF");
      attempt.cancel();
      process.destroyForcibly();
      process.waitFor();
      var diagnostic = attempt.snapshot().toJson();
      assertTrue(diagnostic.isNull("exitCode"));
      assertEquals(
          "cancelled",
          diagnostic
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));
    }
  }

  @Test
  void queueRejectionAndSharedDeadlineCannotBeOverwrittenByLateSuccess() throws Exception {
    var policy = new EngineStartupDiagnostics.Policy(1, 1, 150, 2, 128, 4096, 2, 8192);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var first = service.begin("eng-1", "MAIN_BOARD", List.of("engine"), true);
      first.fail("runtime-preflight", "missing runtime");
      first.collect(
          "runtime",
          () -> {
            entered.countDown();
            while (true) {
              try {
                release.await();
                break;
              } catch (InterruptedException ignored) {
              }
            }
            return new EngineStartupDiagnostics.Evidence(List.of(), "checked runtime");
          });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      var queued = service.begin("eng-2", "MAIN_BOARD", List.of("engine"), true);
      queued.fail("process-create", "cannot start");
      queued.collect("runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "runtime"));
      var rejected = service.begin("eng-3", "MAIN_BOARD", List.of("engine"), true);
      rejected.fail("process-create", "cannot start");
      rejected.collect(
          "runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "runtime"));
      assertEquals("queue-full", reason(rejected, "runtime"));
      awaitTerminal(first);
      awaitTerminal(queued);
      long revision = first.snapshot().revision();
      release.countDown();
      Thread.sleep(50);
      assertEquals("timeout", reason(first, "runtime"));
      assertEquals("timeout", reason(queued, "runtime"));
      assertEquals(revision, first.snapshot().revision());
      assertEquals(2, service.snapshot().failures().size());
      assertTrue(service.snapshot().evicted() >= 1);
    } finally {
      release.countDown();
    }
  }

  @Test
  void queuedTimeoutAndCancelReleasesSchedulerCapacity() throws Exception {
    var policy = new EngineStartupDiagnostics.Policy(1, 1, 150, 2, 128, 4096, 5, 8192);
    CountDownLatch occupierRunning = new CountDownLatch(1);
    CountDownLatch releaseOccupier = new CountDownLatch(1);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var occupier = service.begin("eng-occ", "MAIN_BOARD", List.of("engine"), true);
      occupier.fail("runtime-preflight", "busy");
      occupier.collect(
          "runtime",
          () -> {
            occupierRunning.countDown();
            while (true) {
              try {
                releaseOccupier.await();
                break;
              } catch (InterruptedException ignored) {
              }
            }
            return new EngineStartupDiagnostics.Evidence(List.of(), "done");
          });
      assertTrue(occupierRunning.await(1, TimeUnit.SECONDS));

      // Worker is occupied. Queuing job 1 fills the single queue slot.
      var queued1 = service.begin("eng-q1", "MAIN_BOARD", List.of("engine"), true);
      queued1.fail("process-create", "fail-q1");
      queued1.collect("runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "q1"));

      // A new job when queue is full is rejected with queue-full.
      var rejected = service.begin("eng-rej", "MAIN_BOARD", List.of("engine"), true);
      rejected.fail("process-create", "fail-rej");
      rejected.collect("runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "rej"));
      assertEquals("queue-full", reason(rejected, "runtime"));

      // Cancel queued1 -> releases queue capacity.
      queued1.cancel();
      assertEquals("cancelled", reason(queued1, "runtime"));

      // A new job is now queued instead of rejected.
      var queued2 = service.begin("eng-q2", "MAIN_BOARD", List.of("engine"), true);
      queued2.fail("process-create", "fail-q2");
      queued2.collect("runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "q2"));
      assertEquals(
          "queued",
          queued2
              .snapshot()
              .toJson()
              .getJSONObject("sources")
              .getJSONObject("runtime")
              .getString("collectionState"));

      // Let queued2 time out -> releases queue capacity again.
      awaitTerminal(queued2);
      assertEquals("timeout", reason(queued2, "runtime"));

      // A subsequent job can now be queued without rejection.
      var queued3 = service.begin("eng-q3", "MAIN_BOARD", List.of("engine"), true);
      queued3.fail("process-create", "fail-q3");
      queued3.collect("runtime", () -> new EngineStartupDiagnostics.Evidence(List.of(), "q3"));
      assertEquals(
          "queued",
          queued3
              .snapshot()
              .toJson()
              .getJSONObject("sources")
              .getJSONObject("runtime")
              .getString("collectionState"));
    } finally {
      releaseOccupier.countDown();
    }
  }

  @Test
  void interruptIgnoringJobCannotReplaceTerminalStateOrFindings() throws Exception {
    var policy = new EngineStartupDiagnostics.Policy(1, 1, 150, 2, 128, 4096, 2, 8192);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var attempt = service.begin("eng-interrupt", "MAIN_BOARD", List.of("engine"), true);
      attempt.fail("startup-exit", "exit 1");
      attempt.collect(
          "runtime",
          () -> {
            running.countDown();
            while (true) {
              try {
                proceed.await();
                break;
              } catch (InterruptedException ignored) {
              }
            }
            return new EngineStartupDiagnostics.Evidence(
                List.of(
                    new EngineStartupDiagnostics.Finding(
                        "missing-dll",
                        "late.dll",
                        "engine.exe",
                        List.of("late.dll"),
                        "pe-import-scan",
                        "complete",
                        "late-scope",
                        "late-detail",
                        Instant.now())),
                "late-scope");
          });
      assertTrue(running.await(1, TimeUnit.SECONDS));

      attempt.cancel();
      assertEquals("cancelled", reason(attempt, "runtime"));
      long revision = attempt.snapshot().revision();
      assertTrue(attempt.snapshot().toJson().getJSONArray("findings").isEmpty());
      assertEquals("partial", attempt.snapshot().toJson().getString("outcome"));

      proceed.countDown();
      Thread.sleep(50);

      var current = attempt.snapshot();
      assertEquals("cancelled", reason(attempt, "runtime"));
      assertEquals(revision, current.revision());
      assertTrue(current.toJson().getJSONArray("findings").isEmpty());
      assertEquals("partial", current.toJson().getString("outcome"));
    } finally {
      proceed.countDown();
    }
  }

  @Test
  void multipleCollectorsShareOriginalFailureDeadline() throws Exception {
    var policy = new EngineStartupDiagnostics.Policy(2, 2, 100, 2, 128, 4096, 5, 8192);
    CountDownLatch bothRunning = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var attempt = service.begin("eng-shared", "MAIN_BOARD", List.of("engine"), true);
      attempt.fail("startup-exit", "failed");

      attempt.collect(
          "runtime",
          () -> {
            bothRunning.countDown();
            while (true) {
              try {
                release.await();
                break;
              } catch (InterruptedException ignored) {
              }
            }
            return new EngineStartupDiagnostics.Evidence(List.of(), "runtime");
          });

      attempt.collect(
          "pe-import-scan",
          () -> {
            bothRunning.countDown();
            while (true) {
              try {
                release.await();
                break;
              } catch (InterruptedException ignored) {
              }
            }
            return new EngineStartupDiagnostics.Evidence(List.of(), "pe");
          });

      assertTrue(bothRunning.await(1, TimeUnit.SECONDS));
      awaitTerminal(attempt);

      assertEquals("timeout", reason(attempt, "runtime"));
      assertEquals("timeout", reason(attempt, "pe-import-scan"));
      assertEquals("partial", attempt.snapshot().toJson().getString("outcome"));

      attempt.collect(
          "process-tail", () -> new EngineStartupDiagnostics.Evidence(List.of(), "tail"));
      assertEquals("timeout", reason(attempt, "process-tail"));
    } finally {
      release.countDown();
    }
  }

  @Test
  void concurrentAttemptsMaintainStrictOutputIsolation() {
    var policy = new EngineStartupDiagnostics.Policy(2, 4, 1000, 5, 512, 4096, 5, 8192);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var attemptA = service.begin("eng-A", "MAIN_BOARD", List.of("engine-a"), true);
      var attemptB = service.begin("eng-B", "COMPARE", List.of("engine-b"), true);

      attemptA.output("stdout", "stdout-line-A1");
      attemptA.output("stderr", "stderr-line-A1");
      attemptB.output("stdout", "stdout-line-B1");
      attemptB.output("stderr", "stderr-line-B1");
      attemptA.output("stdout", "stdout-line-A2");
      attemptB.output("stderr", "stderr-line-B2");

      var failureA = attemptA.fail("startup-exit", "failure in A");
      var failureB = attemptB.fail("startup-exit", "failure in B");

      var jsonA = failureA.toJson();
      var jsonB = failureB.toJson();

      assertEquals("eng-A", failureA.engineId());
      assertEquals("stdout-line-A1\nstdout-line-A2", jsonA.getString("stdout"));
      assertEquals("stderr-line-A1", jsonA.getString("stderr"));
      assertFalse(jsonA.getString("stdout").contains("B1"));
      assertFalse(jsonA.getString("stderr").contains("B1"));

      assertEquals("eng-B", failureB.engineId());
      assertEquals("stdout-line-B1", jsonB.getString("stdout"));
      assertEquals("stderr-line-B1\nstderr-line-B2", jsonB.getString("stderr"));
      assertFalse(jsonB.getString("stdout").contains("A1"));
      assertFalse(jsonB.getString("stderr").contains("A1"));
    }
  }

  private static String reason(EngineStartupDiagnostics.Attempt attempt, String source) {
    return attempt
        .snapshot()
        .toJson()
        .getJSONObject("sources")
        .getJSONObject(source)
        .getString("terminalReason");
  }

  private static EngineStartupDiagnostic awaitTerminal(EngineStartupDiagnostics.Attempt attempt)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      var value = attempt.snapshot();
      if (!value.toJson().getString("outcome").equals("collecting")) return value;
      Thread.sleep(5);
    }
    fail("Diagnostic did not settle");
    return null;
  }
}
