package featurecat.lizzie.logging;

import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
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
          if (attributes.isRegularFile()) {
            Object key = fileKey(path, attributes.fileKey());
            if (key != null) {
              archives.put(
                  path.toAbsolutePath().normalize(), new Identity(key, attributes.creationTime()));
            }
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
    if (identity == null || !identity.createdAt().equals(createdAt)) {
      return false;
    }
    Object key = fileKey(path, fileKey);
    return key != null && identity.fileKey().equals(key);
  }

  private static Object fileKey(Path path, Object nioKey) {
    if (nioKey != null || !Platform.isWindows()) {
      return nioKey;
    }
    // Windows NIO can return null even on NTFS. Query identity, not file contents;
    // sharing deletion keeps this observation from interfering with rollover.
    try {
      Kernel32 kernel = Kernel32.INSTANCE;
      WinNT.HANDLE handle =
          kernel.CreateFile(
              path.toAbsolutePath().normalize().toString(),
              0,
              WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
              null,
              WinNT.OPEN_EXISTING,
              WinNT.FILE_FLAG_OPEN_REPARSE_POINT,
              null);
      if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
        return null;
      }
      // FILE_ID_INFO is a 64-bit volume serial followed by a 128-bit file identifier.
      try (Memory info = new Memory(24)) {
        if (!kernel.GetFileInformationByHandleEx(handle, 18, info, new WinDef.DWORD(24))) {
          return null;
        }
        return new WindowsFileKey(info.getLong(0), info.getLong(8), info.getLong(16));
      } finally {
        kernel.CloseHandle(handle);
      }
    } catch (RuntimeException | LinkageError unavailable) {
      return null;
    }
  }

  private record WindowsFileKey(long volumeSerial, long fileIdLow, long fileIdHigh) {}

  private record Identity(Object fileKey, FileTime createdAt) {}
}
