package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DesktopProbeOwnershipTest {
  @Test
  void anExitedProbeCannotDiscoverOwnershipFromItsStalePid() {
    ProcessHandle exited =
        handle(
            30,
            false,
            null,
            30,
            () -> {
              fail("Exited probe must not enumerate descendants from a potentially recycled PID");
              return Stream.empty();
            });
    Map<Long, ProcessHandle> owned = new LinkedHashMap<>();

    DesktopProbeProcess.rememberOwnedDescendants(exited, owned, Set.of(10L, 20L));

    assertTrue(owned.isEmpty());
  }

  @Test
  void liveProbeEnumerationCannotClaimThisJvmItsAncestorsOrOlderProcesses() {
    ProcessHandle ancestor = handle(10, true, null, 10, Stream::empty);
    ProcessHandle current = handle(20, true, ancestor, 20, Stream::empty);
    ProcessHandle older = handle(25, true, null, 25, Stream::empty);
    ProcessHandle descendant = handle(40, true, null, 40, Stream::empty);
    ProcessHandle child =
        handle(30, true, current, 30, () -> Stream.of(current, ancestor, older, descendant));
    Set<Long> protectedPids = DesktopProbeProcess.protectedProcessIds(current);
    Map<Long, ProcessHandle> owned = new LinkedHashMap<>();

    DesktopProbeProcess.rememberOwnedDescendants(child, owned, protectedPids);

    assertEquals(Set.of(10L, 20L), protectedPids);
    assertEquals(Set.of(40L), owned.keySet());
    assertSame(descendant, owned.get(40L));
  }

  @Test
  void aReusedDescendantPidDoesNotReplaceTheOriginallyObservedHandle() {
    ProcessHandle original = handle(40, false, null, 40, Stream::empty);
    ProcessHandle replacement = handle(40, true, null, 50, Stream::empty);
    ProcessHandle child = handle(30, true, null, 30, () -> Stream.of(replacement));
    Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
    owned.put(40L, original);

    DesktopProbeProcess.rememberOwnedDescendants(child, owned, Set.of(10L, 20L));

    assertSame(original, owned.get(40L));
  }

  @Test
  void contributionWaitKeepsTheObservationThatSatisfiedThePredicate() throws Exception {
    AtomicInteger observations = new AtomicInteger();

    ContributionLocalClearDesktopTest.await(() -> observations.getAndIncrement() == 0);

    assertEquals(1, observations.get(), "Readiness must not be re-read after it already passed");
  }

  private static ProcessHandle handle(
      long pid,
      boolean alive,
      ProcessHandle parent,
      long started,
      Supplier<Stream<ProcessHandle>> descendants) {
    ProcessHandle.Info info =
        (ProcessHandle.Info)
            Proxy.newProxyInstance(
                ProcessHandle.Info.class.getClassLoader(),
                new Class<?>[] {ProcessHandle.Info.class},
                (proxy, method, args) ->
                    method.getName().equals("startInstant")
                        ? Optional.of(Instant.ofEpochSecond(started))
                        : Optional.empty());
    return (ProcessHandle)
        Proxy.newProxyInstance(
            ProcessHandle.class.getClassLoader(),
            new Class<?>[] {ProcessHandle.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "pid" -> pid;
                  case "isAlive" -> alive;
                  case "parent" -> Optional.ofNullable(parent);
                  case "descendants" -> descendants.get();
                  case "info" -> info;
                  case "toString" -> "controlled process " + pid;
                  default -> throw new AssertionError("Unexpected process operation: " + method);
                });
  }
}
