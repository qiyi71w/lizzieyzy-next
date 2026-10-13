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
import featurecat.lizzie.gui.LizzieFrame;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

class ReadBoardSelfUpdateTest {
  @TempDir Path root;
  private static final String ID = "0123456789abcdef0123456789abcdef";

  @Test void duplicateUnknownAndWrongSchemaRecordsAreRejected() throws Exception {
    Path file = root.resolve("progress.json");
    for (String json : new String[] {
      "{\"schemaVersion\":1,\"operationId\":\"" + ID + "\",\"operationId\":\"" + ID + "\"}",
      "{\"schemaVersion\":1,\"operationId\":\"" + ID + "\",\"command\":\"run\"}",
      "{\"schemaVersion\":2,\"operationId\":\"" + ID + "\"}",
      "{\"schemaVersion\":\"1\",\"operationId\":\"" + ID + "\"}",
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
      set(next, "nativeOwnedDirectory", fixture.directory);
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

  @Test void wrongVersionCancelsConnectionWithoutChangingInstalledResult() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ReadBoardSelfUpdate update = fixture.register(); fixture.adopt(); fixture.result("installed", "v3.2.0");
      ReadBoard next = allocate(ReadBoard.class);
      set(next, "usePipe", true); set(next, "nativeOwnedProcess", fixture.process); set(next, "isLoaded", true);
      set(next, "nativeOwnedDirectory", fixture.directory);
      update.successor(next);
      assertFalse(update.ready(next, ID, "v3.1.9"));
      assertTrue(update.cancelled());
      assertFalse(update.ready(next, ID, "v3.2.0"));
      assertEquals("handshakeFailed", new JSONObject(Files.readString(fixture.path.resolveSibling("connection.json"))).getString("state"));
      assertEquals("installed", new JSONObject(Files.readString(fixture.path.resolveSibling("result.json"))).getString("state"));
    }
  }

  @Test void missingResultRemainsWaitingUntilCancellation() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ReadBoardSelfUpdate update = fixture.register(); fixture.adopt();
      fixture.process.destroy(); fixture.process.waitFor(); set(fixture.source, "nativeReaderExited", true);
      CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> {
        try { return update.awaitLaunchable(); } catch (Exception failure) { throw new RuntimeException(failure); }
      });
      assertThrows(java.util.concurrent.TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
      update.cancel();
      assertNull(waiting.get(2, TimeUnit.SECONDS));
      assertFalse(Files.exists(fixture.path.resolveSibling("connection.json")));
    }
  }

  @Test void frameRepeatedOpenCoalescesAndStartFailurePreservesInstallation() throws Exception {
    try (Fixture fixture = new Fixture()) {
      UpdateFrame frame = frame(fixture);
      prepare(frame, fixture);
      fixture.adopt(); fixture.result("installed", "v3.2.0");
      SwingUtilities.invokeAndWait(() -> { for (int i = 0; i < 10; i++) frame.openBoardSync(); });
      assertEquals(0, frame.starts.get());
      fixture.process.destroy(); fixture.process.waitFor();
      assertEquals(0, frame.starts.get());
      set(fixture.source, "nativeReaderExited", true);
      await(() -> Files.exists(fixture.path.resolveSibling("connection.json")));
      assertEquals(1, frame.starts.get());
      assertEquals("startFailed", new JSONObject(Files.readString(fixture.path.resolveSibling("connection.json"))).getString("state"));
      assertEquals("installed", new JSONObject(Files.readString(fixture.path.resolveSibling("result.json"))).getString("state"));
      frame.cancelReadBoardSelfUpdateOnHostExit();
    }
  }

  @Test void hostExitBeforeOrAfterInstallCancelsOnlyRestartAndDoesNotBlockEdt() throws Exception {
    for (boolean installed : new boolean[] { false, true }) {
      try (Fixture fixture = new Fixture()) {
        UpdateFrame frame = frame(fixture); prepare(frame, fixture); fixture.adopt();
        if (installed) fixture.result("installed", "v3.2.0");
        CompletableFuture<Void> exited = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> { frame.cancelReadBoardSelfUpdateOnHostExit(); exited.complete(null); });
        exited.get(1, TimeUnit.SECONDS);
        if (!installed) fixture.result("installed", "v3.2.0");
        fixture.process.destroy(); fixture.process.waitFor(); set(fixture.source, "nativeReaderExited", true);
        await(() -> frameUpdate(frame) == null);
        SwingUtilities.invokeAndWait(frame::openBoardSync);
        assertEquals(0, frame.starts.get());
        assertFalse(Files.exists(fixture.path.resolveSibling("connection.json")));
        assertEquals("installed", new JSONObject(Files.readString(fixture.path.resolveSibling("result.json"))).getString("state"));
      }
    }
  }

  @Test void cancelledOperationAndStaleSourceCannotRegisterLate() throws Exception {
    try (Fixture fixture = new Fixture(); Fixture stale = new Fixture()) {
      UpdateFrame frame = frame(fixture);
      frame.handleReadBoardSelfUpdate(fixture.source, "readboardSelfUpdateCancel\t1\t" + ID);
      prepare(frame, fixture);
      assertNull(frameUpdate(frame));
      assertFalse(Files.exists(fixture.path.resolveSibling("registration.json")));
      prepare(frame, stale);
      assertNull(frameUpdate(frame));
      assertFalse(Files.exists(stale.path.resolveSibling("registration.json")));
    }
  }

  private static UpdateFrame frame(Fixture fixture) throws Exception {
    UpdateFrame frame = allocate(UpdateFrame.class);
    frame.starts = new AtomicInteger(); frame.readBoard = fixture.source;
    frameField(frame, "readBoardRestartLock", new Object());
    frameField(frame, "cancelledSelfUpdates", java.util.concurrent.ConcurrentHashMap.newKeySet());
    set(fixture.source, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    return frame;
  }
  private static void prepare(UpdateFrame frame, Fixture fixture) throws Exception {
    var method = LizzieFrame.class.getDeclaredMethod("prepareSelfUpdate", ReadBoard.class, String.class, String.class);
    method.setAccessible(true); method.invoke(frame, fixture.source, ID, fixture.path.toString());
  }
  private static Object frameUpdate(UpdateFrame frame) {
    try { var field = LizzieFrame.class.getDeclaredField("selfUpdate"); field.setAccessible(true); return field.get(frame); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static void frameField(UpdateFrame frame, String name, Object value) throws Exception {
    var field = LizzieFrame.class.getDeclaredField(name); field.setAccessible(true); field.set(frame, value);
  }
  private static void await(BooleanSupplier predicate) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(predicate.getAsBoolean());
  }
  private static final class UpdateFrame extends LizzieFrame {
    AtomicInteger starts;
    @Override protected boolean isNativeBoardSyncSupported() { return true; }
    @Override protected boolean isNativeReadBoardAvailable() { return true; }
    @Override protected ReadBoard createNativeReadBoard() throws Exception {
      starts.incrementAndGet(); throw new IOException("Controlled process-start failure");
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
      set(source, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
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
