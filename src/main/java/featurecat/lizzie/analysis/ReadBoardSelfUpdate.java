package featurecat.lizzie.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** One operation under the frame's existing restart mutex; all waiting is off the EDT. */
public final class ReadBoardSelfUpdate {
  public final ReadBoard source;
  public final String operation;
  public final Path requestPath;
  private final Process process;
  private final JSONObject request;
  private final String requestHash;
  private final String registration = UUID.randomUUID().toString().replace("-", "");
  private final ProcessHandle host = ProcessHandle.current();
  private final Instant hostStart;
  private volatile boolean cancelled;
  private volatile ReadBoard successor;
  private volatile boolean connected;

  public ReadBoardSelfUpdate(ReadBoard source, String operation, Path path) throws IOException {
    this.hostStart = ReadBoardSelfUpdateRecords.processStart(host);
    this.source = source;
    this.operation = operation;
    this.requestPath = path;
    this.process = source.ownedNativeProcess();
    this.request = ReadBoardSelfUpdateRecords.request(path, operation, process, source.ownedNativeDirectory());
    this.requestHash = ReadBoardSelfUpdateRecords.hash(Files.readAllBytes(path));
  }
  public void register() throws IOException {
    JSONObject value = base().put("hostProcessId", host.pid()).put("hostProcessStartTimeUtc", hostStart.toString())
        .put("sourceProcessId", request.getLong("sourceProcessId"))
        .put("sourceProcessStartTimeUtc", request.getString("sourceProcessStartTimeUtc"))
        .put("installDirectory", request.getString("installDirectory"))
        .put("requestSha256", requestHash);
    ReadBoardSelfUpdateRecords.publish(requestPath.resolveSibling("registration.json"), value);
  }
  private JSONObject base() { return new JSONObject().put("schemaVersion", 1).put("operationId", operation).put("registrationId", registration); }
  public void cancel() { cancelled = true; }
  public boolean cancelled() { return cancelled; }
  public boolean matches(ReadBoard owner, String id) { return source == owner && operation.equals(id); }
  private boolean adopted(JSONObject progress) {
    return operation.equals(progress.getString("operationId")) && progress.getBoolean("restartAdopted")
        && registration.equals(progress.optString("restartRegistrationId"))
        && progress.getLong("hostProcessId") == host.pid()
        && ReadBoardSelfUpdateRecords.sameTime(hostStart, progress.getString("hostProcessStartTimeUtc"));
  }
  public String awaitLaunchable() throws IOException, InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    while (!cancelled && System.nanoTime() < deadline) {
      if (Files.exists(requestPath.resolveSibling("result.json"))) {
        if (!requestHash.equals(ReadBoardSelfUpdateRecords.hash(Files.readAllBytes(requestPath))))
          throw new IOException("Registered request changed");
        JSONObject progress = ReadBoardSelfUpdateRecords.progress(requestPath);
        if (!adopted(progress)) return null;
        JSONObject result = ReadBoardSelfUpdateRecords.result(requestPath);
        String state = result.getString("state");
        String expected = state.equals("installed") ? request.getString("targetTag") : "v" + request.getString("sourceVersion").replaceFirst("^v", "");
        if (!operation.equals(result.getString("operationId")) || !Set.of("installed", "rolledBack", "failedUnchanged").contains(state)
            || !request.getString("installDirectory").equals(result.getString("installDirectory"))
            || !expected.equals(result.getString("actualVersion"))) throw new IOException("Installation is not launchable");
        if (!process.isAlive() && source.updateReaderExited()) return expected;
      }
      Thread.sleep(50);
    }
    return null;
  }
  public void successor(ReadBoard value) { successor = value; }
  public boolean ready(ReadBoard value, String id, String version) throws IOException {
    if (cancelled || connected || value != successor || !operation.equals(id) || !value.isLoaded) return false;
    JSONObject progress = ReadBoardSelfUpdateRecords.progress(requestPath);
    JSONObject result = ReadBoardSelfUpdateRecords.result(requestPath);
    Process next = value.ownedNativeProcess();
    String state = result.getString("state");
    String expected = state.equals("installed") ? request.getString("targetTag") : "v" + request.getString("sourceVersion").replaceFirst("^v", "");
    if (!adopted(progress) || !operation.equals(result.getString("operationId"))
        || !Set.of("installed", "rolledBack", "failedUnchanged").contains(state)
        || !request.getString("installDirectory").equals(result.getString("installDirectory"))
        || !expected.equals(version) || !version.equals(result.getString("actualVersion"))
        || next == null || !next.isAlive() || value.ownedNativeDirectory() == null
        || !value.ownedNativeDirectory().equals(source.ownedNativeDirectory())
        || !requestHash.equals(ReadBoardSelfUpdateRecords.hash(Files.readAllBytes(requestPath)))) {
      outcome("handshakeFailed", value, version); cancelled = true; return false;
    }
    outcome("connected", value, version);
    connected = true;
    value.sendCommand("readboardSelfUpdateConnected\t1\t" + operation);
    return true;
  }
  public boolean connected() { return connected; }
  public void outcome(String state, ReadBoard value, String version) throws IOException {
    Process next = value == null ? null : value.ownedNativeProcess();
    JSONObject result = base().put("state", state)
        .put("processId", next == null ? JSONObject.NULL : next.pid())
        .put("processStartTimeUtc", next == null ? JSONObject.NULL : ReadBoardSelfUpdateRecords.processStart(next.toHandle()).toString())
        .put("actualVersion", version == null ? JSONObject.NULL : version);
    ReadBoardSelfUpdateRecords.publish(requestPath.resolveSibling("connection.json"), result);
  }
}
