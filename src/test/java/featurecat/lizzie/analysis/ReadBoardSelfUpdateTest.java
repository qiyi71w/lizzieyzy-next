package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadBoardSelfUpdateTest {
  @TempDir Path root;
  private static final String ID = "0123456789abcdef0123456789abcdef";

  @Test void duplicateUnknownAndWrongSchemaRecordsAreRejected() throws Exception {
    Path file = root.resolve("progress.json");
    for (String json : new String[] {
      "{\"schemaVersion\":1,\"operationId\":\"" + ID + "\",\"operationId\":\"" + ID + "\"}",
      "{\"schemaVersion\":1,\"operationId\":\"" + ID + "\",\"command\":\"run\"}",
      "{\"schemaVersion\":2,\"operationId\":\"" + ID + "\"}",
      "{\"schemaVersion\":1,\"operationId\":\"WRONG\"}"
    }) {
      Files.writeString(file, json);
      assertThrows(IOException.class, () -> ReadBoardSelfUpdateRecords.read(file, "schemaVersion", "operationId"));
    }
  }

  @Test void sourceIdentityAndRequestLocationAreBound() throws Exception {
    try (Fixture fixture = new Fixture()) {
      JSONObject request = new JSONObject(Files.readString(fixture.path));
      request.put("sourceProcessId", fixture.process.pid() + 1);
      Files.writeString(fixture.path, request.toString());
      assertThrows(IOException.class, () -> new ReadBoardSelfUpdate(fixture.source, ID, fixture.path));
      request.put("sourceProcessId", fixture.process.pid());
      Path arbitrary = root.resolve("request.json");
      Files.writeString(arbitrary, request.toString());
      assertThrows(IOException.class, () -> new ReadBoardSelfUpdate(fixture.source, ID, arbitrary));
    }
  }

  @Test void installedResultWaitsForBothActualSourceExitAndReaderExit() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ReadBoardSelfUpdate update = fixture.register();
      fixture.adopt();
      fixture.result("installed", "v3.2.0");
      CompletableFuture<String> wait = CompletableFuture.supplyAsync(() -> {
        try { return update.awaitLaunchable(); } catch (Exception e) { throw new RuntimeException(e); }
      });
      assertThrows(java.util.concurrent.TimeoutException.class, () -> wait.get(100, TimeUnit.MILLISECONDS));
      fixture.process.destroy();
      fixture.process.waitFor();
      assertThrows(java.util.concurrent.TimeoutException.class, () -> wait.get(100, TimeUnit.MILLISECONDS));
      set(fixture.source, "nativeReaderExited", true);
      assertEquals("v3.2.0", wait.get(2, TimeUnit.SECONDS));
    }
  }

  @Test void cancellationAndUnadoptedRegistrationNeverLaunch() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ReadBoardSelfUpdate update = fixture.register();
      update.cancel();
      assertNull(update.awaitLaunchable());
    }
    try (Fixture fixture = new Fixture()) {
      ReadBoardSelfUpdate update = fixture.register();
      fixture.adopt();
      JSONObject progress = new JSONObject(Files.readString(fixture.path.resolveSibling("progress.json")));
      progress.put("restartRegistrationId", "ffffffffffffffffffffffffffffffff");
      Files.writeString(fixture.path.resolveSibling("progress.json"), progress.toString());
      fixture.result("installed", "v3.2.0");
      assertNull(update.awaitLaunchable());
    }
  }

  @Test void corruptWrongOperationAndRollbackFailureCannotLaunch() throws Exception {
    for (String state : new String[] { "rollbackFailed", "cancelled" }) {
      try (Fixture fixture = new Fixture()) {
        ReadBoardSelfUpdate update = fixture.register();
        fixture.adopt(); fixture.result(state, "v3.1.9");
        assertThrows(IOException.class, update::awaitLaunchable);
      }
    }
    try (Fixture fixture = new Fixture()) {
      ReadBoardSelfUpdate update = fixture.register(); fixture.adopt();
      Files.writeString(fixture.path.resolveSibling("result.json"), "broken");
      assertThrows(IOException.class, update::awaitLaunchable);
    }
  }

  @Test void handshakeRequiresSuccessorOrdinaryReadyAndMatchingVersion() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ReadBoardSelfUpdate update = fixture.register(); fixture.adopt(); fixture.result("installed", "v3.2.0");
      ReadBoard next = allocate(ReadBoard.class);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      set(next, "usePipe", true); set(next, "nativeOwnedProcess", fixture.process);
      set(next, "outputStream", new BufferedOutputStream(output));
      update.successor(next);
      assertFalse(update.ready(next, ID, "v3.2.0"));
      set(next, "isLoaded", true);
      assertFalse(update.ready(fixture.source, ID, "v3.2.0"));
      assertFalse(update.ready(next, "ffffffffffffffffffffffffffffffff", "v3.2.0"));
      assertTrue(update.ready(next, ID, "v3.2.0"));
      JSONObject connected = new JSONObject(Files.readString(fixture.path.resolveSibling("connection.json")));
      assertEquals("connected", connected.getString("state"));
      assertEquals(fixture.process.pid(), connected.getLong("processId"));
      assertEquals("readboardSelfUpdateConnected\t1\t" + ID + "\n", output.toString(StandardCharsets.UTF_8));
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path directory;
    final Path path;
    final Process process;
    final ReadBoard source;
    Fixture() throws Exception {
      directory = Files.createTempDirectory(root, "install");
      Path executable = directory.resolve("readboard.exe");
      Files.copy(Path.of("/bin/dash"), executable);
      executable.toFile().setExecutable(true);
      process = new ProcessBuilder(executable.toString(), "-c", "read value").start();
      source = allocate(ReadBoard.class);
      set(source, "usePipe", true); set(source, "nativeOwnedProcess", process); set(source, "nativeOwnedDirectory", directory);
      String key = ReadBoardSelfUpdateRecords.hash(directory.toString().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      path = root.resolve(".readboard-update-" + key).resolve(ID).resolve("request.json");
      Files.createDirectories(path.getParent());
      JSONObject request = new JSONObject().put("schemaVersion",1).put("operationId",ID).put("sourceVersion","v3.1.9")
          .put("targetTag","v3.2.0").put("assetName","readboard-webview2-v3.2.0.zip")
          .put("packagePath",root.resolve("readboard-webview2-v3.2.0.zip").toString()).put("expectedSha256","a".repeat(64))
          .put("installDirectory",directory.toString()).put("sourceProcessId",process.pid())
          .put("sourceProcessStartTimeUtc",process.info().startInstant().orElseThrow().toString()).put("language","en");
      Files.writeString(path,request.toString());
    }
    ReadBoardSelfUpdate register() throws Exception { var update = new ReadBoardSelfUpdate(source, ID, path); update.register(); return update; }
    void adopt() throws Exception {
      JSONObject registration = new JSONObject(Files.readString(path.resolveSibling("registration.json")));
      JSONObject progress = new JSONObject().put("schemaVersion",1).put("operationId",ID).put("phase","installed").put("restartAdopted",true)
          .put("restartRegistrationId",registration.get("registrationId")).put("hostProcessId",registration.get("hostProcessId"))
          .put("hostProcessStartTimeUtc",registration.get("hostProcessStartTimeUtc"));
      Files.writeString(path.resolveSibling("progress.json"),progress.toString());
    }
    void result(String state,String version) throws Exception {
      Files.writeString(path.resolveSibling("result.json"),new JSONObject().put("schemaVersion",1).put("operationId",ID)
          .put("state",state).put("actualVersion",version).put("installDirectory",directory.toString())
          .put("diagnostic", "").put("backupDirectory",JSONObject.NULL).toString());
    }
    public void close() throws Exception { process.destroy(); process.waitFor(); }
  }
  private static void set(Object target,String name,Object value) throws Exception { Field field=ReadBoard.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value); }
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
  }
}
