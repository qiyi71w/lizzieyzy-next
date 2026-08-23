package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class AiPositionControllerTest {

  private static final String ROOT_LEAD_LINE =
      "info move Q16 visits 10 winrate 0.40 scoreLead -5.0 pv Q16"
          + " rootInfo visits 800 winrate 0.120000 scoreLead -71.4"
          + " ownership 0.2 -0.1 0.05 0.15";

  @Test
  void normalizesWhiteToPlayUpdateToBlackPerspectiveAndFreezesOwnership() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext context = context("node-a", false, "chinese", 7.5, 1L);

    assertTrue(controller.open(context));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);

    AiPositionSnapshot snapshot = requireSnapshot(controller);
    assertEquals(71.4, snapshot.blackScoreLead(), 1e-9);
    assertEquals(88.0, snapshot.blackWinrate(), 1e-9);
    assertEquals(800, snapshot.visits());
    assertEquals("chinese", snapshot.rules());
    assertEquals(7.5, snapshot.komi(), 1e-9);
    assertEquals(List.of(-0.2, 0.1, -0.05, -0.15), snapshot.ownership());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.ownership().set(0, 1.0));
  }

  @Test
  void usesRootScoreLeadNotFirstMoveOrOwnershipHeuristic() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    assertTrue(controller.open(context("node-a", true, "chinese", 7.5, 1L)));
    controller.acceptLine(
        controller.generation(),
        "info move Q16 visits 10 winrate 0.40 scoreLead -13.0 pv Q16"
            + " rootInfo visits 800 winrate 0.120000 scoreLead -71.4"
            + " ownership 1 1 1 1 1 -1 -1 -1 -1 -1");
    AiPositionSnapshot snapshot = requireSnapshot(controller);
    assertEquals(-71.4, snapshot.blackScoreLead(), 1e-9);
    assertNotEquals(-13.0, snapshot.blackScoreLead());
    assertNotEquals(ownershipSignedSum(), snapshot.blackScoreLead());
  }

  @Test
  void contextChangeClearsSnapshotBeforeLaterMatchingUpdate() {
    RecordingProvider provider = new RecordingProvider(true);
    AtomicInteger displayChanges = new AtomicInteger();
    AiPositionController controller =
        new AiPositionController(List.of(provider), displayChanges::incrementAndGet);
    AiPositionRequestContext first = context("node-a", true, "chinese", 7.5, 1L);
    assertTrue(controller.open(first));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    assertTrue(controller.snapshot().isPresent());

    controller.sync(context("node-b", true, "chinese", 7.5, 1L));

    assertTrue(controller.isOpen());
    assertTrue(controller.snapshot().isEmpty());
    assertEquals(2, provider.startCount);
    assertEquals(1, provider.stopCount);
    assertTrue(displayChanges.get() >= 2);
  }

  @Test
  void staleGenerationCannotReplaceCurrentSnapshot() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    assertTrue(controller.open(context("node-a", true, "chinese", 7.5, 1L)));
    long staleGeneration = controller.generation();
    controller.acceptLine(staleGeneration, ROOT_LEAD_LINE);
    assertEquals(-71.4, requireSnapshot(controller).blackScoreLead(), 1e-9);

    controller.sync(context("node-a", true, "japanese", 6.5, 1L));
    assertTrue(controller.snapshot().isEmpty());

    controller.acceptLine(
        staleGeneration,
        "rootInfo visits 900 winrate 0.99 scoreLead 12.0 ownership 0.5 -0.5");
    assertTrue(controller.snapshot().isEmpty());

    controller.acceptLine(
        controller.generation(),
        "rootInfo visits 50 winrate 0.55 scoreLead -8.0 ownership 0.1 -0.2");
    assertEquals(-8.0, requireSnapshot(controller).blackScoreLead(), 1e-9);
    assertEquals("japanese", requireSnapshot(controller).rules());
    assertEquals(6.5, requireSnapshot(controller).komi(), 1e-9);
  }

  @Test
  void visibleSnapshotHidesWhenCurrentContextNoLongerMatches() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext original = context("node-a", true, "chinese", 7.5, 1L);
    assertTrue(controller.open(original));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    assertTrue(controller.snapshot().isPresent());
    assertTrue(controller.visibleSnapshot(original).isPresent());
    assertTrue(
        controller
            .visibleSnapshot(context("node-a", true, "chinese", 6.5, 1L))
            .isEmpty());
    assertTrue(
        controller
            .visibleSnapshot(context("node-a", true, "japanese", 7.5, 1L))
            .isEmpty());
  }


  @Test
  void unsupportedContextDoesNotOpenAndCloseClearsVisibleSnapshot() {
    RecordingProvider provider = new RecordingProvider(false);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    assertFalse(controller.open(context("node-a", true, "chinese", 7.5, 1L)));
    assertFalse(controller.isOpen());
    assertEquals(0, provider.startCount);

    RecordingProvider kataGo = new RecordingProvider(true);
    controller = new AiPositionController(List.of(kataGo), () -> {});
    assertTrue(controller.open(context("node-a", true, "chinese", 7.5, 1L)));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    controller.close();
    assertFalse(controller.isOpen());
    assertTrue(controller.snapshot().isEmpty());
    assertEquals(1, kataGo.stopCount);
  }

  @Test
  void olderSequenceCannotReplaceNewerSnapshot() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    assertTrue(controller.open(context("node-a", true, "chinese", 7.5, 1L)));
    long generation = controller.generation();
    controller.acceptEmission(generation, 2L, requireUpdate(ROOT_LEAD_LINE));
    assertEquals(-71.4, requireSnapshot(controller).blackScoreLead(), 1e-9);
    assertEquals(2L, requireSnapshot(controller).sequence());

    controller.acceptEmission(
        generation,
        1L,
        requireUpdate("rootInfo visits 10 winrate 0.9 scoreLead 3.0 ownership 0.1"));
    assertEquals(-71.4, requireSnapshot(controller).blackScoreLead(), 1e-9);
    assertEquals(2L, requireSnapshot(controller).sequence());

    controller.acceptEmission(
        generation,
        3L,
        requireUpdate("rootInfo visits 900 winrate 0.2 scoreLead -8.0 ownership 0.2"));
    assertEquals(-8.0, requireSnapshot(controller).blackScoreLead(), 1e-9);
    assertEquals(3L, requireSnapshot(controller).sequence());
  }

  @Test
  void terminatedGenerationDiscardsLaterEmissions() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    assertTrue(controller.open(context("node-a", true, "chinese", 7.5, 1L)));
    long staleGeneration = controller.generation();
    controller.preempt();

    assertTrue(controller.isOpen());
    assertTrue(controller.isUnavailable());
    assertTrue(controller.snapshot().isEmpty());
    assertEquals(1, provider.stopCount);

    controller.acceptEmission(staleGeneration, 1L, requireUpdate(ROOT_LEAD_LINE));
    assertTrue(controller.snapshot().isEmpty());
    assertTrue(controller.visibleSnapshot(context("node-a", true, "chinese", 7.5, 1L)).isEmpty());
  }

  @Test
  void deniedStartOpensAsUnavailableWithoutZeroScore() {
    RecordingProvider provider = new RecordingProvider(true, false);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext requested = context("node-a", true, "chinese", 7.5, 1L);
    assertTrue(controller.open(requested));
    assertTrue(controller.isOpen());
    assertTrue(controller.isUnavailable());
    assertTrue(controller.snapshot().isEmpty());
    assertTrue(controller.visibleSnapshot(requested).isEmpty());
    assertEquals(AiPositionDisplayState.UNAVAILABLE, controller.displayState(requested));
    assertEquals(1, provider.startCount);
  }

  @Test
  void userCanRetryClaimAfterUnavailable() {
    RecordingProvider provider = new RecordingProvider(true, false);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext requested = context("node-a", true, "chinese", 7.5, 1L);
    assertTrue(controller.open(requested));
    assertTrue(controller.isUnavailable());

    provider.startSucceeds = true;
    assertTrue(controller.open(requested));
    assertFalse(controller.isUnavailable());
    assertEquals(AiPositionDisplayState.WAITING, controller.displayState(requested));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    assertEquals(-71.4, requireSnapshot(controller).blackScoreLead(), 1e-9);
    assertEquals(AiPositionDisplayState.READY, controller.displayState(requested));
    assertEquals(2, provider.startCount);
  }

  @Test
  void displayStateNamesWaitingReadyAndClosed() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext requested = context("node-a", true, "chinese", 7.5, 1L);

    assertEquals(AiPositionDisplayState.CLOSED, controller.displayState(requested));
    assertTrue(controller.open(requested));
    assertEquals(AiPositionDisplayState.WAITING, controller.displayState(requested));

    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    assertEquals(AiPositionDisplayState.READY, controller.displayState(requested));
    assertEquals(
        AiPositionDisplayState.WAITING,
        controller.displayState(context("node-a", true, "chinese", 6.5, 1L)));

    controller.close();
    assertEquals(AiPositionDisplayState.CLOSED, controller.displayState(requested));
  }

  @Test
  void foregroundPonderDoesNotPreemptForegroundReuse() {
    RecordingProvider provider = new RecordingProvider(true);
    provider.yieldsToForegroundPonder = false;
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext requested = context("node-a", true, "chinese", 7.5, 1L);

    assertTrue(controller.open(requested));
    controller.preemptIfYieldsToForegroundPonder();

    assertTrue(controller.isOpen());
    assertFalse(controller.isUnavailable());
    assertEquals(AiPositionDisplayState.WAITING, controller.displayState(requested));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    assertEquals(-71.4, requireSnapshot(controller).blackScoreLead(), 1e-9);
    assertEquals(AiPositionDisplayState.READY, controller.displayState(requested));
    assertEquals(0, provider.stopCount);
  }

  @Test
  void foregroundPonderPreemptsManagedSearch() {
    RecordingProvider provider = new RecordingProvider(true);
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext requested = context("node-a", true, "chinese", 7.5, 1L);

    assertTrue(controller.open(requested));
    controller.preemptIfYieldsToForegroundPonder();

    assertTrue(controller.isUnavailable());
    assertEquals(AiPositionDisplayState.UNAVAILABLE, controller.displayState(requested));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    assertTrue(controller.snapshot().isEmpty());
    assertEquals(1, provider.stopCount);
  }

  @Test
  void competingUserTaskStillPreemptsForegroundReuse() {
    RecordingProvider provider = new RecordingProvider(true);
    provider.yieldsToForegroundPonder = false;
    AiPositionController controller = new AiPositionController(List.of(provider), () -> {});
    AiPositionRequestContext requested = context("node-a", true, "chinese", 7.5, 1L);

    assertTrue(controller.open(requested));
    controller.preempt();

    assertTrue(controller.isUnavailable());
    assertEquals(AiPositionDisplayState.UNAVAILABLE, controller.displayState(requested));
    assertEquals(1, provider.stopCount);
  }

  @Test
  void navigationAndProviderSwitchClearReadyState() {
    RecordingProvider chinese = new RecordingProvider(ctx -> "chinese".equals(ctx.rules()));
    RecordingProvider japanese = new RecordingProvider(ctx -> "japanese".equals(ctx.rules()));
    AiPositionController controller =
        new AiPositionController(List.of(chinese, japanese), () -> {});
    AiPositionRequestContext first = context("node-a", true, "chinese", 7.5, 1L);
    AiPositionRequestContext second = context("node-a", true, "japanese", 7.5, 1L);

    assertTrue(controller.open(first));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    assertEquals(AiPositionDisplayState.READY, controller.displayState(first));
    assertEquals(1, chinese.startCount);
    assertEquals(0, japanese.startCount);

    controller.sync(second);

    assertEquals(AiPositionDisplayState.WAITING, controller.displayState(second));
    assertTrue(controller.visibleSnapshot(first).isEmpty());
    assertTrue(controller.visibleSnapshot(second).isEmpty());
    assertEquals(1, chinese.stopCount);
    assertEquals(1, japanese.startCount);
  }



  private static AiPositionSnapshot requireSnapshot(AiPositionController controller) {
    Optional<AiPositionSnapshot> snapshot = controller.snapshot();
    assertTrue(snapshot.isPresent());
    return snapshot.get();
  }

  private static AiPositionRequestContext context(
      String node, boolean blackToPlay, String rules, double komi, long incarnation) {
    return new AiPositionRequestContext(
        node, 1L, "[stones]", blackToPlay, 19, 19, rules, komi, null, incarnation);
  }

  private static double ownershipSignedSum() {
    return 1 + 1 + 1 + 1 + 1 - 1 - 1 - 1 - 1 - 1;
  }

  private static AiPositionSearchUpdate requireUpdate(String line) {
    Optional<AiPositionSearchUpdate> parsed = AiPositionSearchUpdate.parse(line);
    assertTrue(parsed.isPresent());
    return parsed.get();
  }

  private static final class RecordingProvider implements AiPositionProvider {

    private final Predicate<AiPositionRequestContext> support;
    private boolean startSucceeds;
    private boolean yieldsToForegroundPonder = true;
    private int startCount;
    private int stopCount;

    private RecordingProvider(boolean supported) {
      this(ctx -> supported, true);
    }

    private RecordingProvider(boolean supported, boolean startSucceeds) {
      this(ctx -> supported, startSucceeds);
    }

    private RecordingProvider(Predicate<AiPositionRequestContext> support) {
      this(support, true);
    }

    private RecordingProvider(Predicate<AiPositionRequestContext> support, boolean startSucceeds) {
      this.support = support;
      this.startSucceeds = startSucceeds;
    }

    @Override
    public boolean supports(AiPositionRequestContext context) {
      return support.test(context);
    }

    @Override
    public boolean start(AiPositionRequestContext context, long generation) {
      startCount++;
      return startSucceeds;
    }

    @Override
    public void stop(long generation) {
      stopCount++;
    }

    @Override
    public boolean yieldsToForegroundPonder() {
      return yieldsToForegroundPonder;
    }
  }
}
