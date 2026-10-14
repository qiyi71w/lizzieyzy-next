package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

  @Test void unicodeInstallationRegistersOpaqueSourceNamespace() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.register();
      JSONObject registration = new JSONObject(Files.readString(fixture.path.resolveSibling("registration.json")));
      assertTrue(fixture.directory.toString().contains("Straße"));
      assertEquals(ID, registration.getString("operationId"));
      assertEquals(fixture.process.pid(), registration.getLong("sourceProcessId"));
      assertEquals(fixture.directory.toRealPath().toString(), registration.getString("installDirectory"));
      assertEquals(ReadBoardSelfUpdateRecords.hash(Files.readAllBytes(fixture.path)), registration.getString("requestSha256"));
    }
  }

  @Test void namespaceShapeParentOperationAndInstallationRemainBound() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String original = Files.readString(fixture.path);
      for (Path invalid : new Path[] {
          root.resolve(".readboard-update-" + "A".repeat(64)).resolve(ID).resolve("request.json"),
          root.resolve(".readboard-update-" + "a".repeat(63)).resolve(ID).resolve("request.json"),
          root.resolve("other").resolve(fixture.path.getParent().getParent().getFileName()).resolve(ID).resolve("request.json"),
          fixture.path.getParent().getParent().resolve("f".repeat(32)).resolve("request.json"),
          fixture.path.resolveSibling("other.json") }) {
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, original);
        assertThrows(IOException.class, () -> new ReadBoardSelfUpdate(fixture.source, ID, invalid));
      }
      JSONObject changed = new JSONObject(original).put("installDirectory", root.toString());
      Files.writeString(fixture.path, changed.toString());
      assertThrows(IOException.class, () -> new ReadBoardSelfUpdate(fixture.source, ID, fixture.path));
      changed = new JSONObject(original).put("sourceProcessStartTimeUtc", Instant.EPOCH.toString());
      Files.writeString(fixture.path, changed.toString());
      assertThrows(IOException.class, () -> new ReadBoardSelfUpdate(fixture.source, ID, fixture.path));
      Files.writeString(fixture.path, original);
      ReadBoardSelfUpdate update = new ReadBoardSelfUpdate(fixture.source, ID, fixture.path);
      Files.writeString(fixture.path, original + " ");
      update.register();
      JSONObject registration = new JSONObject(Files.readString(fixture.path.resolveSibling("registration.json")));
      assertEquals(ReadBoardSelfUpdateRecords.hash(original.getBytes(StandardCharsets.UTF_8)), registration.getString("requestSha256"));
      assertNotEquals(ReadBoardSelfUpdateRecords.hash(Files.readAllBytes(fixture.path)), registration.getString("requestSha256"));
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
      fixture.exit();
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
      fixture.exit(); set(fixture.source, "nativeReaderExited", true);
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
      fixture.exit();
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
        fixture.exit(); set(fixture.source, "nativeReaderExited", true);
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

  @Test void realParserRejectsMalformedMessagesAndCoalescesDuplicatePreparation() throws Exception {
    LizzieFrame previous = featurecat.lizzie.Lizzie.frame;
    try (Fixture fixture = new Fixture()) {
      UpdateFrame frame = frame(fixture); featurecat.lizzie.Lizzie.frame = frame;
      ByteArrayOutputStream wire = new ByteArrayOutputStream();
      set(fixture.source, "outputStream", new BufferedOutputStream(wire));
      String valid = "readboardSelfUpdatePrepare\t1\t" + ID + "\t" + fixture.path;
      for (String malformed : new String[] {
        valid + "\textra", valid.replace("\t1\t", "\t2\t"), valid.replace(ID, "INVALID"),
        valid + "\r", valid + "\nembedded", "readboardSelfUpdatePrepare\t1\t" + ID
      }) fixture.source.parseLine(malformed);
      assertNull(frameUpdate(frame));
      assertFalse(Files.exists(fixture.path.resolveSibling("registration.json")));
      fixture.source.parseLine(valid + "\n");
      await(() -> frameUpdate(frame) != null);
      String registration = Files.readString(fixture.path.resolveSibling("registration.json"));
      Object owner = frameUpdate(frame);
      fixture.source.parseLine(valid);
      assertSame(owner, frameUpdate(frame));
      await(() -> wire.toString(StandardCharsets.UTF_8).split("readboardSelfUpdatePrepared", -1).length == 3);
      assertEquals(registration, Files.readString(fixture.path.resolveSibling("registration.json")));
      fixture.source.parseLine("readboardSelfUpdateCancel\t1\t" + ID + "\n");
      assertNull(frameUpdate(frame));
      prepare(frame, fixture);
      assertNull(frameUpdate(frame));
      assertEquals(0, frame.starts.get());
    } finally { featurecat.lizzie.Lizzie.frame = previous; }
  }

  @Test void readyAdvertisesRestartOnlyForActuallyOwnedLivePipeProcess() throws Exception {
    Leelaz previous = featurecat.lizzie.Lizzie.leelaz;
    try (Fixture fixture = new Fixture()) {
      featurecat.lizzie.Lizzie.leelaz = null;
      ByteArrayOutputStream wire = new ByteArrayOutputStream();
      set(fixture.source, "outputStream", new BufferedOutputStream(wire));
      fixture.source.handleReady(); fixture.source.handleReady();
      assertEquals(1, wire.toString(StandardCharsets.UTF_8).split("readboardSelfUpdateRestartSupported\\t1", -1).length - 1);
      ReadBoard unowned = allocate(ReadBoard.class);
      ByteArrayOutputStream other = new ByteArrayOutputStream();
      set(unowned, "usePipe", true); set(unowned, "outputStream", new BufferedOutputStream(other));
      unowned.handleReady();
      assertFalse(other.toString(StandardCharsets.UTF_8).contains("readboardSelfUpdateRestartSupported"));
    } finally { featurecat.lizzie.Lizzie.leelaz = previous; }
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
      directory = Files.createTempDirectory(root, "Straße-install");
      Path executable = directory.resolve("readboard.exe");
      boolean windows = System.getProperty("os.name").startsWith("Windows");
      Path shell = windows
          ? Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe")
          : Path.of("/bin/sh").toRealPath();
      Files.copy(shell, executable);
      if (!windows) assertTrue(executable.toFile().setExecutable(true));
      ProcessBuilder builder = windows
          ? new ProcessBuilder(executable.toString(), "/d", "/q", "/c",
              "echo ready&set /p value=&exit /b 0")
          : new ProcessBuilder(executable.toString(), "-c", "echo ready; read value; exit 0");
      process = builder.redirectErrorStream(true).start();
      try {
        CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
          try {
            return new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8)).readLine();
          } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        assertEquals("ready", ready.get(5, TimeUnit.SECONDS));
        assertTrue(process.isAlive());
        assertTrue(process.pid() > 0);
        assertTrue(process.info().startInstant().isPresent());
        assertTrue(Files.isSameFile(executable, Path.of(process.info().command().orElseThrow())));
      } catch (Exception | AssertionError failure) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        throw failure;
      }
      source = allocate(ReadBoard.class);
      set(source, "usePipe", true); set(source, "nativeOwnedProcess", process); set(source, "nativeOwnedDirectory", directory);
      set(source, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
      // Opaque source-produced namespace; the Windows native test supplies the actual C# key.
      String namespace = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2);
      path = root.resolve(".readboard-update-" + namespace).resolve(ID).resolve("request.json");
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
    void exit() throws Exception {
      process.getOutputStream().close();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Child must exit after stdin EOF");
      assertEquals(0, process.exitValue());
      assertEquals(-1, process.getInputStream().read(), "Child stdout must reach EOF");
    }
    public void close() throws Exception {
      try {
        if (process.isAlive()) exit();
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
      }
    }
  }
  private static void set(Object target,String name,Object value) throws Exception { Field field=ReadBoard.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value); }
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
  }
}
