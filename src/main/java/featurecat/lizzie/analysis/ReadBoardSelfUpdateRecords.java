package featurecat.lizzie.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Transaction records only: the host never downloads, installs or repairs files. */
public final class ReadBoardSelfUpdateRecords {
  private ReadBoardSelfUpdateRecords() {}

  public static JSONObject read(Path path, String... fields) throws IOException {
    assertLocal(path);
    try {
      JSONTokener tokens = new JSONTokener(Files.readString(path, StandardCharsets.UTF_8));
      JSONObject value = new JSONObject(tokens);
      if (tokens.nextClean() != 0 || !value.keySet().equals(Set.of(fields)))
        throw new IOException("Unexpected transaction fields");
      for (String field : fields) {
        Object item = value.get(field);
        if (item == JSONObject.NULL && Set.of("restartRegistrationId", "hostProcessId", "hostProcessStartTimeUtc", "actualVersion", "backupDirectory").contains(field)) continue;
        boolean valid;
        if (Set.of("schemaVersion", "sourceProcessId", "hostProcessId").contains(field)) {
          valid = (item instanceof Integer || item instanceof Long)
              && ((Number) item).longValue() > 0 && ((Number) item).longValue() <= Integer.MAX_VALUE;
        } else if (field.equals("restartAdopted")) valid = item instanceof Boolean;
        else valid = item instanceof String;
        if (!valid) throw new IOException("Invalid transaction field type: " + field);
      }
      if (value.getInt("schemaVersion") != 1 || !id(value.getString("operationId")))
        throw new IOException("Unsupported transaction identity");
      return value;
    } catch (RuntimeException failure) { throw new IOException("Invalid transaction JSON", failure); }
  }

  public static boolean id(String value) { return value != null && value.matches("[0-9a-f]{32}"); }
  public static void assertLocal(Path path) throws IOException {
    if (!path.isAbsolute() || path.toString().matches("(?s).*[\\r\\n\\t].*")) throw new IOException("Absolute local path required");
    for (Path current = path; current != null; current = current.getParent())
      if (Files.isSymbolicLink(current) || (Files.exists(current) && Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class).isOther()))
        throw new IOException("Linked transaction paths are forbidden");
  }
  public static String hash(byte[] bytes) throws IOException {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
  }
  public static JSONObject request(Path path, String operation, Process source, Path directory) throws IOException {
    JSONObject request = read(path, "schemaVersion", "operationId", "sourceVersion", "targetTag", "assetName", "packagePath", "expectedSha256", "installDirectory", "sourceProcessId", "sourceProcessStartTimeUtc", "language");
    Path install = directory.toRealPath();
    Path normalized = path.normalize();
    Path operationDirectory = normalized.getParent();
    Path namespace = operationDirectory == null ? null : operationDirectory.getParent();
    if (!request.getString("operationId").equals(operation)
        || !normalized.getFileName().toString().equals("request.json")
        || operationDirectory == null || !operationDirectory.getFileName().toString().equals(operation)
        || namespace == null || namespace.getFileName() == null
        || !namespace.getFileName().toString().matches("\\.readboard-update-[0-9a-f]{64}")
        || !install.getParent().equals(namespace.getParent())
        || !Path.of(request.getString("installDirectory")).toRealPath().equals(install)
        || source == null || !source.isAlive() || source.pid() != request.getLong("sourceProcessId")
        || !sameTime(processStart(source.toHandle()), request.getString("sourceProcessStartTimeUtc"))
        || !Path.of(source.info().command().orElseThrow()).toRealPath().equals(install.resolve("readboard.exe").toRealPath())
        || !request.getString("targetTag").matches("v[0-9]+\\.[0-9]+\\.[0-9]+")
        || !request.getString("sourceVersion").matches("v?[0-9]+\\.[0-9]+\\.[0-9]+")
        || !request.getString("assetName").equals("readboard-webview2-" + request.getString("targetTag") + ".zip")
        || !request.getString("expectedSha256").matches("[0-9a-f]{64}")
        || !Set.of("cn", "en", "jp", "kr").contains(request.getString("language"))) throw new IOException("Source registration mismatch");
    assertLocal(Path.of(request.getString("packagePath")));
    return request;
  }
  public static Instant processStart(ProcessHandle process) throws IOException {
    if (!com.sun.jna.Platform.isWindows()) return process.info().startInstant().orElseThrow(() -> new IOException("Process creation unavailable"));
    var kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
    var handle = kernel.OpenProcess(0x1000, false, Math.toIntExact(process.pid()));
    if (handle == null) throw new IOException("Cannot query process creation");
    try {
      var created = new com.sun.jna.platform.win32.WinBase.FILETIME();
      if (!kernel.GetProcessTimes(handle, created, new com.sun.jna.platform.win32.WinBase.FILETIME(),
          new com.sun.jna.platform.win32.WinBase.FILETIME(), new com.sun.jna.platform.win32.WinBase.FILETIME()))
        throw new IOException("Cannot read process creation");
      long ticks = ((long) created.dwHighDateTime << 32) | Integer.toUnsignedLong(created.dwLowDateTime);
      ticks -= 116444736000000000L;
      return Instant.ofEpochSecond(ticks / 10000000L, (ticks % 10000000L) * 100L);
    } finally { kernel.CloseHandle(handle); }
  }

  public static boolean sameTime(Instant actual, String serialized) {
    return actual.equals(Instant.parse(serialized));
  }
  public static JSONObject progress(Path path) throws IOException {
    return read(path.resolveSibling("progress.json"), "schemaVersion", "operationId", "phase", "restartAdopted", "restartRegistrationId", "hostProcessId", "hostProcessStartTimeUtc");
  }
  public static JSONObject result(Path path) throws IOException {
    return read(path.resolveSibling("result.json"), "schemaVersion", "operationId", "state", "actualVersion", "installDirectory", "diagnostic", "backupDirectory");
  }
  public static void publish(Path path, JSONObject value) throws IOException {
    assertLocal(path);
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
    try {
      Files.writeString(temporary, value.toString(2), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
      try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary); }
  }
}
