package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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
    var policy = new EngineStartupDiagnostics.Policy(1, 150, 2, 128, 4096);
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
      assertEquals("no-specific-dll-identified", json.getString("outcome"));
      assertTrue(
          json.getString("stderr").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 128);
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void originalProcessTailCompletesFailureWithoutChangingCapturedCommand() throws Exception {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      ProcessBuilder builder =
          new ProcessBuilder(
              "/bin/sh",
              "-c",
              "exec 1>&-; sleep 0.2; printf 'late original output\\n' >&2; exit 17");
      var attempt = service.begin("eng-original", "MAIN_BOARD", builder.command(), true);
      attempt.capture(builder);
      Process process = builder.start();
      builder.command("replacement-engine");
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
      assertEquals("/bin/sh", last.toJson().getJSONObject("launch").getString("executable"));
      assertEquals(first.attemptId(), last.attemptId());
      assertEquals("collecting", first.toJson().getString("collectionState"));
      assertEquals("completed", last.toJson().getString("collectionState"));
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
  void runtimeInformationDoesNotInventSpecificDll() {
    try (var service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      var attempt = service.begin("custom", "MAIN_BOARD", List.of("custom.exe"), true);
      attempt.runtimePreflight(
          featurecat.lizzie.util.KataGoRuntimeHelper.inspectNvidiaRuntime(
              java.nio.file.Path.of("custom.exe")));
      var failure = attempt.fail("process-create", "Cannot create process").toJson();
      assertEquals("no-specific-dll-identified", failure.getString("outcome"));
      assertTrue(failure.getJSONArray("findings").getJSONObject(0).isNull("dll"));
      assertEquals("Cannot create process", failure.getString("originalError"));
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void stalledTailSettlesWithoutInventingExitOrAcceptingLaterOutput() throws Exception {
    try (var service =
        new EngineStartupDiagnostics(
            new EngineStartupDiagnostics.Policy(1, 100, 2, 128, 4096), null)) {
      Process process = new ProcessBuilder("/bin/sh", "-c", "exec sleep 5").start();
      try {
        var attempt = service.begin("stalled", "MAIN_BOARD", List.of("engine"), true);
        attempt.attachProcess(process);
        attempt.fail("startup-exit", "stdout EOF before ready");
        var failure = awaitTerminal(attempt);
        assertEquals("timeout", reason(attempt, "process-tail"));
        assertEquals("partial", failure.toJson().getString("collectionState"));
        assertTrue(failure.toJson().isNull("exitCode"));
        attempt.output("stderr", "late.dll was not found");
        assertSame(failure, attempt.snapshot());
        assertEquals("", failure.toJson().getString("stderr"));
      } finally {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  @Test
  void concurrentAttemptsMaintainStrictOutputIsolation() {
    var policy = new EngineStartupDiagnostics.Policy(4, 1000, 5, 512, 4096);
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

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void mergedProcessOutputDoesNotClaimStderrWasStdout() throws Exception {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      ProcessBuilder builder =
          new ProcessBuilder("/bin/sh", "-c", "printf 'missing x.dll\\n' >&2; exit 17");
      builder.redirectErrorStream(true);
      var attempt = service.begin("eng-merged", "PROBE", builder.command(), true);
      attempt.capture(builder);
      Process process = builder.start();
      attempt.attachProcess(process);
      String line =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))
              .readLine();
      attempt.output("merged", line);
      assertEquals(17, process.waitFor());
      attempt.streamEnded("stdout", null);
      attempt.streamEnded("stderr", null);
      var result = attempt.fail("startup-exit", "probe failed").toJson();
      assertEquals("merged", result.getString("stdoutOrigin"));
      assertEquals(line, result.getString("stdout"));
      assertEquals("", result.getString("stderr"));
      assertEquals(
          "engine-merged", result.getJSONArray("findings").getJSONObject(0).getString("evidence"));
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
