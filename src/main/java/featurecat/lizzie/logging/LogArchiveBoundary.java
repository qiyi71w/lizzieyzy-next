package featurecat.lizzie.logging;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

/** Identity evidence captured before an owner issues a new session identity. */
public final class LogArchiveBoundary {
  private static final int MAX_ENTRIES = 1024;
  private static final LogArchiveBoundary EMPTY = new LogArchiveBoundary(Map.of());
  private final Map<Path, Identity> archives;

  private LogArchiveBoundary(Map<Path, Identity> archives) {
    this.archives = Map.copyOf(archives);
  }

  public static LogArchiveBoundary empty() {
    return EMPTY;
  }

  /** Bounded metadata only; unknown identities remain export candidates. */
  public static LogArchiveBoundary capture(Path logsDirectory) {
    Map<Path, Identity> archives = new HashMap<>();
    Path directory = logsDirectory.resolve("archive");
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      return EMPTY;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      int visited = 0;
      for (Path path : entries) {
        if (++visited > MAX_ENTRIES) {
          break;
        }
        if (!path.getFileName().toString().endsWith(".log.gz")) {
          continue;
        }
        try {
          BasicFileAttributes attributes =
              Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attributes.isRegularFile() && attributes.fileKey() != null) {
            archives.put(
                path.toAbsolutePath().normalize(),
                new Identity(attributes.fileKey(), attributes.creationTime()));
          }
        } catch (IOException | SecurityException ignored) {
          // Incomplete evidence is safe: the exporter retains the candidate.
        }
      }
    } catch (IOException | SecurityException ignored) {
      // Retain any identities already observed before the session exists.
    }
    return new LogArchiveBoundary(archives);
  }

  public boolean predatesSession(Path path, Object fileKey, FileTime createdAt) {
    Identity identity = archives.get(path.toAbsolutePath().normalize());
    return identity != null
        && identity.fileKey().equals(fileKey)
        && identity.createdAt().equals(createdAt);
  }

  private record Identity(Object fileKey, FileTime createdAt) {}
}
