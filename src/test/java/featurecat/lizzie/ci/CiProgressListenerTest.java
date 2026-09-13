package featurecat.lizzie.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class CiProgressListenerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void actualLauncherEmitsChronologyIdentifiersAndStatuses() throws IOException {
    Path evidenceDirectory = temporaryDirectory.resolve("evidence");
    CiProgressListener listener = new CiProgressListener(evidenceDirectory);
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(LauncherFixture.class))
            .build();

    Launcher launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(request);

    List<Path> evidenceFiles;
    try (var files = Files.list(evidenceDirectory)) {
      evidenceFiles = files.collect(Collectors.toList());
    }
    assertEquals(1, evidenceFiles.size());
    assertTrue(evidenceFiles.get(0).getFileName().toString().matches("junit-\\d+\\.jsonl"));

    List<JSONObject> events =
        Files.readAllLines(evidenceFiles.get(0), StandardCharsets.UTF_8).stream()
            .map(JSONObject::new)
            .toList();
    assertEquals("PLAN_STARTED", events.get(0).getString("event"));
    assertEquals("PLAN_FINISHED", events.get(events.size() - 1).getString("event"));

    long processId = ProcessHandle.current().pid();
    JSONObject planStarted = events.get(0);
    assertEquals(processId, planStarted.getLong("pid"));
    assertFalse(planStarted.getString("jvm").isBlank());
    assertFalse(planStarted.has("id"));
    assertFalse(planStarted.has("displayName"));

    for (JSONObject event : events) {
      assertEquals(processId, event.getLong("pid"));
    }

    JSONObject passingStart = findEvent(events, "START", "[method:passingTest()]");
    JSONObject passingFinish = findEvent(events, "FINISH", "[method:passingTest()]");
    assertEquals("passingTest()", passingFinish.getString("displayName"));
    assertEquals("SUCCESSFUL", passingFinish.getString("status"));
    assertTrue(events.indexOf(passingStart) < events.indexOf(passingFinish));

    JSONObject failingStart = findEvent(events, "START", "[method:failingTest()]");
    JSONObject failingFinish = findEvent(events, "FINISH", "[method:failingTest()]");
    assertEquals("failingTest()", failingFinish.getString("displayName"));
    assertEquals("FAILED", failingFinish.getString("status"));
    assertTrue(events.indexOf(failingStart) < events.indexOf(failingFinish));

    JSONObject skipped = findEvent(events, "SKIPPED", "[method:skippedTest()]");
    assertEquals("skippedTest()", skipped.getString("displayName"));
    assertEquals("SKIPPED", skipped.getString("status"));
    assertEquals("fixture skip", skipped.getString("reason"));
    assertTrue(skipped.getString("id").contains("LauncherFixture"));
  }

  @Test
  void unwritableEvidenceDoesNotChangeTestResults() throws IOException {
    Path occupied = temporaryDirectory.resolve("not-a-directory");
    Files.writeString(occupied, "existing file");
    Launcher launcher = LauncherFactory.create();
    var summary = new org.junit.platform.launcher.listeners.SummaryGeneratingListener();
    launcher.registerTestExecutionListeners(new CiProgressListener(occupied), summary);
    launcher.execute(
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(LauncherFixture.class))
            .build());
    assertEquals(1, summary.getSummary().getTestsSucceededCount());
    assertEquals(1, summary.getSummary().getTestsFailedCount());
    assertEquals(1, summary.getSummary().getTestsSkippedCount());
    assertEquals("existing file", Files.readString(occupied));
  }

  private static JSONObject findEvent(List<JSONObject> events, String event, String idPart) {
    return events.stream()
        .filter(candidate -> event.equals(candidate.getString("event")))
        .filter(candidate -> candidate.getString("id").contains(idPart))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + event + " for " + idPart));
  }

  static class LauncherFixture {
    @Test
    void passingTest() {}

    @Test
    void failingTest() {
      throw new AssertionError("intentional fixture failure");
    }

    @Test
    @Disabled("fixture skip")
    void skippedTest() {}
  }
}
