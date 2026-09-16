package featurecat.lizzie.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Test evidence must not turn a transient Windows sharing lock into a fake engine crash. */
final class PeerEvidenceFiles {
  static final int MAX_ATTEMPTS = 25;

  interface Move {
    void replace(Path temporary, Path destination) throws IOException;
  }

  interface Pause {
    void waitForRetry() throws InterruptedException;
  }

  private PeerEvidenceFiles() {}

  static void write(Path path, String text) throws IOException {
    write(path, text, PeerEvidenceFiles::replace, () -> Thread.sleep(20));
  }

  static void write(Path path, String text, Move move, Pause pause) throws IOException {
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(temporary, text, StandardCharsets.UTF_8);
    for (int attempt = 1; ; attempt++) {
      try {
        move.replace(temporary, path);
        return;
      } catch (AccessDeniedException sharingViolation) {
        if (attempt >= MAX_ATTEMPTS) throw sharingViolation;
        try {
          pause.waitForRetry();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while publishing peer evidence", interrupted);
        }
      }
    }
  }

  static void replace(Path temporary, Path path) throws IOException {
    try {
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
