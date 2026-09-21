package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Exercises the production board and renderers, without an engine or headless GUI stubs. */
public final class OfflineBoardAcceptanceTest {
  @Test
  void editsAndImportsWithNoEngine() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            OfflineBoardAcceptanceTest.class, "offline-board", List.of(), List.of());
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    int exit = 1;
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"play-sound\":false}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(Lizzie.frame.isDisplayable());
            // Match a failed startup rather than the harmless empty-engine placeholder.
            Lizzie.setPrimaryEngine(null);
            assertNull(Lizzie.leelaz);
            Lizzie.board.place(3, 3, Stone.BLACK);
            Lizzie.board.place(15, 15, Stone.WHITE);
            assertEquals(2, Lizzie.board.getHistory().getMoveNumber());
            assertTrue(Lizzie.board.previousMove(true));
            // Clicking an existing next move uses a different path from keyboard navigation.
            Lizzie.board.place(15, 15, Stone.WHITE);
            assertEquals(2, Lizzie.board.getHistory().getMoveNumber());
            Lizzie.board.pass(Stone.BLACK);
            Lizzie.board.setKomi(6.5);
            String sgf = assertDoesNotThrow(() -> SGFParser.saveToString(false));
            assertTrue(sgf.contains("B[dd]"));
            assertTrue(SGFParser.loadFromString(sgf));
            assertEquals(3, Lizzie.board.getHistory().mainTrunkLength());
            assertEquals(6.5, Lizzie.board.getHistory().getGameInfo().getKomi());
            while (Lizzie.board.nextMove(true)) {}
            Lizzie.board.SpinAndMirror(3);
            assertEquals(3, Lizzie.board.getHistory().mainTrunkLength());
            Lizzie.board.clear(false);
            assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
          });
      Files.writeString(result, "PASS");
      exit = 0;
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, failure.toString());
    } finally {
      System.exit(exit);
    }
  }
}
