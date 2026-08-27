package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.ReadBoard;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LizzieInitializationTimeControlTest {
  @Test
  void ordinaryEngineInitializationDoesNotInstallGameMoveTime() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.initialize(false);

      assertEquals(List.of(), harness.engine.commands);
    }
  }

  @Test
  void automaticGameInitializationDoesNotInstallGameMoveTime() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.initialize(false);

      assertEquals(List.of(), harness.engine.commands);
    }
  }


  @Test
  void readBoardGmaInitializationDoesNotOverwriteGmaTime() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.readBoard = allocate(ActiveGmaReadBoard.class);
      harness.initialize(false);

      assertEquals(List.of(), harness.engine.commands);
    }
  }

  @Test
  void humanGameInitializationKeepsGameMoveTime() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.isPlayingAgainstLeelaz = true;
      harness.initialize(false);

      assertEquals(
          List.of("kata-time_settings none", "kata-set-param maxTime 2"), harness.engine.commands);
    }
  }

  @Test
  void humanEngineOwnedInitializationSendsNoClientTimeOverride() throws Exception {
    try (Harness harness = Harness.open()) {
      Lizzie.config.genmoveGameNoTime = true;
      harness.frame.isPlayingAgainstLeelaz = true;
      harness.initialize(false);

      assertEquals(List.of(), harness.engine.commands);
    }
  }


  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final Leelaz previousPrimaryEngine;
    private final Menu previousMenu;
    private final TestFrame frame;
    private final RecordingLeelaz engine;

    private Harness(
        Config previousConfig,
        LizzieFrame previousFrame,
        Leelaz previousPrimaryEngine,
        Menu previousMenu,
        TestFrame frame,
        RecordingLeelaz engine) {
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousPrimaryEngine = previousPrimaryEngine;
      this.previousMenu = previousMenu;
      this.frame = frame;
      this.engine = engine;
    }

    private static Harness open() throws Exception {
      Config config =
          ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-init-time"));
      config.maxGameThinkingTimeSeconds = 2;
      TestFrame frame = allocate(TestFrame.class);
      RecordingLeelaz engine = new RecordingLeelaz();
      engine.isKatago = true;

      Harness harness =
          new Harness(
              Lizzie.config,
              Lizzie.frame,
              Lizzie.leelaz,
              LizzieFrame.menu,
              frame,
              engine);
      Lizzie.config = config;
      Lizzie.frame = frame;
      Lizzie.leelaz = new Leelaz("");
      LizzieFrame.menu = allocate(SilentMenu.class);
      return harness;
    }

    private void initialize(boolean isEngineGame) {
      Lizzie.initializeAfterVersionCheck(isEngineGame, engine);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousPrimaryEngine;
      LizzieFrame.menu = previousMenu;
    }
  }

  private static final class TestFrame extends LizzieFrame {}

  private static final class SilentMenu extends Menu {
    @Override
    public void showPda(boolean show) {}
  }

  private static final class ActiveGmaReadBoard extends ReadBoard {
    private ActiveGmaReadBoard() throws Exception {
      super(false, false);
    }

    @Override
    public boolean isReadBoardGmaAutoPlayActive() {
      return true;
    }
  }

  private static final class RecordingLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();

    private RecordingLeelaz() throws IOException {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
