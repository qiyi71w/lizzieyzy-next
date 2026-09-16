package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerEvidenceFilesTest {
  @TempDir Path directory;

  @Test
  void replacesCompleteStateAfterTransientSharingViolations() throws Exception {
    Path state = directory.resolve("state.txt");
    Files.writeString(state, "previous");
    AtomicInteger attempts = new AtomicInteger();
    PeerEvidenceFiles.write(
        state,
        "next",
        (temporary, destination) -> {
          if (attempts.incrementAndGet() < 4) {
            assertEquals("previous", Files.readString(destination));
            throw new AccessDeniedException(destination.toString());
          }
          PeerEvidenceFiles.replace(temporary, destination);
        },
        () -> {});
    assertEquals(4, attempts.get());
    assertEquals("next", Files.readString(state));
    assertFalse(Files.exists(directory.resolve("state.txt.tmp")));
  }

  @Test
  void permanentSharingDenialStillFailsAndKeepsPreviousEvidence() throws Exception {
    Path state = directory.resolve("state.txt");
    Files.writeString(state, "previous");
    AtomicInteger attempts = new AtomicInteger();
    assertThrows(
        AccessDeniedException.class,
        () ->
            PeerEvidenceFiles.write(
                state,
                "next",
                (temporary, destination) -> {
                  attempts.incrementAndGet();
                  throw new AccessDeniedException(destination.toString());
                },
                () -> {}));
    assertEquals(PeerEvidenceFiles.MAX_ATTEMPTS, attempts.get());
    assertEquals("previous", Files.readString(state));
    assertEquals("next", Files.readString(directory.resolve("state.txt.tmp")));
  }

  @Test
  void unrelatedIoFailureIsNotRetried() {
    IOException failure = new IOException("disk full");
    AtomicInteger attempts = new AtomicInteger();
    IOException observed =
        assertThrows(
            IOException.class,
            () ->
                PeerEvidenceFiles.write(
                    directory.resolve("state.txt"),
                    "next",
                    (temporary, destination) -> {
                      attempts.incrementAndGet();
                      throw failure;
                    },
                    () -> {
                      throw new AssertionError("unexpected retry");
                    }));
    assertSame(failure, observed);
    assertEquals(1, attempts.get());
  }

  @Test
  void interruptionStopsRetriesAndPreservesInterruptStatus() {
    try {
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  PeerEvidenceFiles.write(
                      directory.resolve("state.txt"),
                      "next",
                      (temporary, destination) -> {
                        throw new AccessDeniedException(destination.toString());
                      },
                      () -> {
                        throw new InterruptedException("cancel");
                      }));
      assertTrue(failure.getCause() instanceof InterruptedException);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void nativeWindowsReaderLockDoesNotKillEvidenceWriter() throws Exception {
    assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));
    Path state = directory.resolve("state.txt");
    Files.writeString(state, "previous");
    CountDownLatch denied = new CountDownLatch(1);
    CountDownLatch unlocked = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      java.util.concurrent.Future<?> write;
      try (FileChannel reader =
          FileChannel.open(state, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
        write =
            executor.submit(
                () -> {
                  PeerEvidenceFiles.write(
                      state,
                      "next",
                      (temporary, destination) -> {
                        try {
                          PeerEvidenceFiles.replace(temporary, destination);
                        } catch (AccessDeniedException locked) {
                          denied.countDown();
                          throw locked;
                        }
                      },
                      () -> {
                        if (!unlocked.await(5, TimeUnit.SECONDS))
                          throw new AssertionError("reader was not released");
                      });
                  return null;
                });
        assertTrue(denied.await(5, TimeUnit.SECONDS), "expected Windows sharing denial");
        assertEquals("previous", Files.readString(state));
      } finally {
        unlocked.countDown();
      }
      write.get(5, TimeUnit.SECONDS);
      assertEquals("next", Files.readString(state));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
