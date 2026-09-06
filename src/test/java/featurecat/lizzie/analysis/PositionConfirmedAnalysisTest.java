package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PositionConfirmedAnalysisTest {
  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void positionMustSucceedBeforeSuccessorAnalysisIsWritten(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand(mutation);
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of(mutation), fixture.transport.commands());
      fixture.respond(0, "=");
      assertEquals(List.of(mutation, "kata-analyze B 10"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void queuedPositionCannotBeBypassedByAnalysis(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.requireResponseBeforeSend = true;
      fixture.engine.sendCommand("name");
      fixture.engine.sendCommand(mutation);
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of("name"), fixture.transport.commands());
      fixture.respond(0, "=");
      assertEquals(List.of("name", mutation), fixture.transport.commands());
      fixture.respond(1, "=");
      assertEquals(List.of("name", mutation, "kata-analyze B 10"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void rejectedPositionCannotResumeButFreshPositionCan(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand(mutation);
      fixture.engine.sendCommand("kata-analyze B 10");
      fixture.respond(0, "?");
      fixture.respond(0, "=");
      assertEquals(List.of(mutation), fixture.transport.commands());
      fixture.engine.sendCommand("set_position");
      fixture.engine.sendCommand("kata-analyze W 11");
      fixture.respond(1, "=");
      assertEquals(
          List.of(mutation, "set_position", "kata-analyze W 11"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void timeoutRetiresOnlyItsOwnResponseAndLetsQueueProgress(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      Runnable response = () -> {};
      fixture.engine.sendCommandWithResponseForTest(mutation, response);
      fixture.engine.sendCommand("kata-analyze B 10");
      fixture.engine.retireTimedOutNormalCommandForTest(response);
      assertEquals(List.of(mutation), fixture.transport.commands());
      fixture.engine.sendCommand("set_position");
      fixture.engine.sendCommand("kata-analyze W 11");
      fixture.respond(0, "=");
      assertEquals(List.of(mutation, "set_position"), fixture.transport.commands());
      fixture.respond(1, "=");
      assertEquals(
          List.of(mutation, "set_position", "kata-analyze W 11"), fixture.transport.commands());
    }
  }

  @Test
  void queuedSetPositionTimeoutCannotOpenAnalysis() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.requireResponseBeforeSend = true;
      fixture.engine.sendCommand("name");
      Runnable response = () -> {};
      fixture.engine.sendCommandWithResponseForTest("set_position", response);
      fixture.engine.sendCommand("kata-analyze B 10");
      fixture.engine.retireTimedOutNormalCommandForTest(response);
      fixture.respond(0, "=");
      assertEquals(List.of("name"), fixture.transport.commands());
      fixture.engine.sendCommand("protocol_version");
      assertEquals(List.of("name", "protocol_version"), fixture.transport.commands());
    }
  }

  @Test
  void failedPositionWriteDoesNotOpenAnalysis() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.failWrite.set(true);
      fixture.engine.sendCommandNoLeelaz2("set_position");
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of("set_position"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-analyze B 10", "kata-analyze", "lz-analyze"})
  void replacingBoardWhilePositionIsPendingDiscardsCapturedAnalysis(String command)
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand("undo");
      fixture.engine.sendCommand(command);
      Lizzie.board = new Board();
      fixture.respond(0, "=");
      assertEquals(List.of("undo"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-analyze B 10", "kata-analyze", "lz-analyze"})
  void replacingPrimaryWhilePositionIsPendingDiscardsCapturedAnalysis(String command)
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand("undo");
      fixture.engine.sendCommand(command);
      Lizzie.leelaz = new Leelaz("");
      fixture.respond(0, "=");
      assertEquals(List.of("undo"), fixture.transport.commands());
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Leelaz engine;
    private final AtomicBoolean failWrite = new AtomicBoolean();
    private final ExactSnapshotRestoreProtocolFixture.Transport transport;

    private Fixture() throws Exception {
      Lizzie.config = allocate(Config.class);
      engine = new Leelaz("");
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
      Lizzie.frame = allocate(LizzieFrame.class);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command -> {
                if (failWrite.getAndSet(false))
                  throw new IOException("controlled position write failure");
                return null;
              });
    }

    private void respond(int index, String prefix) {
      String raw = transport.rawCommands().get(index);
      int separator = raw.indexOf(' ');
      String id =
          separator > 0 && Character.isDigit(raw.charAt(0)) ? raw.substring(0, separator) : "";
      engine.processCommandResponseLineForTest(prefix + id);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
