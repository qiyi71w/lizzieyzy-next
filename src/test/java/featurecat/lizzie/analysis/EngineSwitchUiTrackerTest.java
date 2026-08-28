package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EngineSwitchUiTrackerTest {
  @Test
  void delayedStartupKeepsTargetPendingUntilTheMatchingReadyCommit() {
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();

    EngineManager.EngineSwitchUiSnapshot pending =
        tracker.begin(true, 0, "Current", 1, "Target");

    assertEquals(EngineManager.EngineSwitchUiPhase.SWITCHING, pending.phase());
    assertEquals(0, pending.activeIndex());
    assertEquals("Current", pending.activeName());
    assertEquals(1, pending.targetIndex());
    assertEquals("Target", pending.targetName());

    Optional<EngineManager.EngineSwitchUiSnapshot> committed =
        tracker.succeed(pending.token(), true, 1, "Target");

    assertTrue(committed.isPresent());
    assertEquals(EngineManager.EngineSwitchUiPhase.ACTIVE, committed.get().phase());
    assertEquals(1, committed.get().activeIndex());
  }

  @Test
  void rapidSwitchIgnoresLateSuccessAndFailureFromTheSupersededTarget() {
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();
    EngineManager.EngineSwitchUiSnapshot first =
        tracker.begin(true, 0, "Current", 1, "First target");
    EngineManager.EngineSwitchUiSnapshot second =
        tracker.begin(true, 0, "Current", 2, "Second target");

    assertFalse(tracker.succeed(first.token(), true, 1, "First target").isPresent());
    assertFalse(tracker.fail(first.token(), true, "late failure").isPresent());
    assertEquals(second.token(), tracker.current(true).token());
    assertEquals("Second target", tracker.current(true).targetName());

    assertTrue(tracker.succeed(second.token(), true, 2, "Second target").isPresent());
    assertEquals(2, tracker.current(true).activeIndex());
  }

  @Test
  void failedSwitchRollsBackToTheCommittedEngineWithoutActivatingTheTarget() {
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();
    EngineManager.EngineSwitchUiSnapshot pending =
        tracker.begin(false, 3, "Committed", 4, "Broken target");

    EngineManager.EngineSwitchUiSnapshot failed =
        tracker.fail(pending.token(), false, "controlled failure").orElseThrow();

    assertEquals(EngineManager.EngineSwitchUiPhase.FAILED, failed.phase());
    assertEquals(3, failed.activeIndex());
    assertEquals("Committed", failed.activeName());
    assertEquals(4, failed.targetIndex());
    assertEquals("controlled failure", failed.failureDetail());
  }

  @Test
  void successfulRollbackRestoresActivePrimaryAndKeepsTheFailedTargetNotice() {
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();
    EngineManager.EngineSwitchUiSnapshot pending =
        tracker.begin(true, 0, "Engine A", 1, "Engine B");
    Leelaz recovered = availableEngineUnchecked("engine-a");
    tracker.fail(pending.token(), true, "engine failed").orElseThrow();

    EngineManager.EngineSwitchUiSnapshot restored =
        tracker
            .restoreActive(pending.token(), true, 0, "Engine A", recovered)
            .orElseThrow();

    assertEquals(EngineManager.EngineSwitchUiPhase.ACTIVE, restored.phase());
    assertEquals(pending.token(), restored.token());
    assertEquals(0, restored.activeIndex());
    assertEquals("Engine A", restored.activeName());
    assertEquals(0, restored.targetIndex());
    assertEquals("Engine A", restored.targetName());
    assertEquals("engine failed", restored.failureDetail());
    assertSame(restored, tracker.current(true));
  }

  @Test
  void staleRollbackRestoreDoesNotOverwriteANewerSwitch() {
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();
    EngineManager.EngineSwitchUiSnapshot first =
        tracker.begin(true, 0, "Engine A", 1, "Engine B");
    tracker.fail(first.token(), true, "engine failed").orElseThrow();
    EngineManager.EngineSwitchUiSnapshot second =
        tracker.begin(true, 0, "Engine A", 2, "Engine C");

    assertFalse(
        tracker.restoreActive(first.token(), true, 0, "Engine A", null).isPresent());
    assertEquals(second.token(), tracker.current(true).token());
    assertEquals(EngineManager.EngineSwitchUiPhase.SWITCHING, tracker.current(true).phase());
    assertEquals("Engine C", tracker.current(true).targetName());
  }

  @Test
  void abandonPendingTurnsSwitchingIntoIdleAndRejectsStaleCompletion() {
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();
    EngineManager.EngineSwitchUiSnapshot pending =
        tracker.begin(true, 0, "Engine A", 1, "Engine B");

    EngineManager.EngineSwitchUiSnapshot abandoned =
        tracker.abandonPending(pending.token(), true).orElseThrow();

    assertEquals(EngineManager.EngineSwitchUiPhase.IDLE, abandoned.phase());
    assertEquals(EngineManager.EngineSwitchUiPhase.IDLE, tracker.current(true).phase());
    assertFalse(tracker.succeed(pending.token(), true, 1, "Engine B").isPresent());
    assertFalse(tracker.fail(pending.token(), true, "late failure").isPresent());
    assertFalse(
        tracker.restoreActive(pending.token(), true, 0, "Engine A", null).isPresent());
  }

  @Test
  void finalLifecycleFailureRollsBackAReadyTargetForTheSameSwitchToken() {
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();
    EngineManager.EngineSwitchUiSnapshot pending =
        tracker.begin(true, 2, "Committed", 3, "Ready target");

    EngineManager.EngineSwitchUiSnapshot ready =
        tracker.succeed(pending.token(), true, 3, "Ready target").orElseThrow();
    EngineManager.EngineSwitchUiSnapshot failed =
        tracker.fail(pending.token(), true, "final board fence failed").orElseThrow();

    assertFalse(tracker.isCurrent(ready), "an older phase of the same token is stale");
    assertTrue(tracker.isCurrent(failed));
    assertEquals(EngineManager.EngineSwitchUiPhase.FAILED, failed.phase());
    assertEquals(2, failed.activeIndex());
    assertEquals("Committed", failed.activeName());
    assertEquals(3, failed.targetIndex());
  }

  @Test
  void failedPrimaryRollbackStaysUnroutableUntilAdmissionIdentityIsResynchronized()
      throws Exception {
    Leelaz capturedPrevious = availableEngine("captured");
    Leelaz replacement = availableEngine("replacement");
    Leelaz target = availableEngine("target");
    EngineManager manager =
        new EngineManager(new ArrayList<>(List.of(capturedPrevious, target)));
    EngineManager.EngineSwitchUiTracker tracker = new EngineManager.EngineSwitchUiTracker();
    EngineManager.EngineSwitchUiSnapshot pending =
        tracker.begin(true, 0, "Captured", capturedPrevious, 1, "Target", target);
    EngineManager.EngineSwitchUiSnapshot failed =
        tracker.fail(pending.token(), true, "controlled failure").orElseThrow();
    Leelaz originalPrimary = Lizzie.leelaz;
    int originalIndex = EngineManager.currentEngineNo;
    boolean originalEmpty = EngineManager.isEmpty;
    try {
      manager.engineList.set(0, replacement);
      Lizzie.setPrimaryEngine(target);
      EngineManager.currentEngineNo = 1;
      EngineManager.isEmpty = false;

      manager.rollbackEngineSelectionAfterFailedSwitch(failed);

      assertEquals(null, Lizzie.leelaz);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertTrue(EngineManager.isEmpty);
      assertFalse(
          manager.isSnapshotActiveEngineAvailable(failed),
          "capturedPrevious must not be reported available before exact restore and ACK");
    } finally {
      Lizzie.setPrimaryEngine(originalPrimary);
      EngineManager.currentEngineNo = originalIndex;
      EngineManager.isEmpty = originalEmpty;
    }
  }

  private static Leelaz availableEngine(String command) throws Exception {
    Leelaz engine = new Leelaz(command);
    engine.started = true;
    engine.isLoaded = true;
    return engine;
  }

  private static Leelaz availableEngineUnchecked(String command) {
    try {
      return availableEngine(command);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  void backgroundStatePublicationRunsOnTheEventDispatchThread() throws Exception {
    CountDownLatch rendered = new CountDownLatch(1);
    AtomicBoolean renderedOnEdt = new AtomicBoolean(false);
    Thread publisher =
        new Thread(
            () ->
                EngineManager.runEngineSwitchUiUpdate(
                    () -> {
                      renderedOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
                      rendered.countDown();
                    }),
            "engine-switch-ui-test-publisher");

    publisher.start();
    publisher.join();

    assertTrue(rendered.await(1, TimeUnit.SECONDS));
    assertTrue(renderedOnEdt.get());
  }
}
