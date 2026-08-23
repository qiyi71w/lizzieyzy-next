package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.AiPositionController;
import featurecat.lizzie.analysis.AiPositionDisplayState;
import featurecat.lizzie.analysis.AiPositionProvider;
import featurecat.lizzie.analysis.AiPositionRequestContext;
import featurecat.lizzie.analysis.AiPositionSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class AiPositionStatusTextTest {
  private static final String ROOT_LEAD_LINE =
      "info move Q16 visits 10 winrate 0.40 scoreLead -13.0 pv Q16"
          + " rootInfo visits 800 winrate 0.120000 scoreLead -71.4"
          + " ownership 1 1 1 1 1 -1 -1 -1 -1 -1";

  @Test
  void waitingAndUnavailableOmitScoreNumbers() {
    ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

    String waiting =
        AiPositionStatusText.render(AiPositionDisplayState.WAITING, null, bundle, "chinese");
    String unavailable =
        AiPositionStatusText.render(AiPositionDisplayState.UNAVAILABLE, null, bundle, "chinese");

    assertEquals(bundle.getString("LizzieFrame.aiPositionWaiting"), waiting);
    assertEquals(bundle.getString("LizzieFrame.aiPositionUnavailable"), unavailable);
    assertFalse(waiting.contains("0.0"));
    assertFalse(unavailable.contains("0.0"));
    assertFalse(waiting.contains("71.4"));
    assertFalse(unavailable.contains("71.4"));
  }

  @Test
  void waitingAndUnavailablePaintedLineOmitsLastMoveZeroScore() {
    ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    String waiting =
        AiPositionStatusText.render(AiPositionDisplayState.WAITING, null, bundle, "chinese");
    String unavailable =
        AiPositionStatusText.render(AiPositionDisplayState.UNAVAILABLE, null, bundle, "chinese");
    String lastMoveZero = " lastMove(#  ): -0.0% -0.0pts";

    String waitingLine =
        AiPositionStatusText.paintedLine(AiPositionDisplayState.WAITING, waiting, lastMoveZero);
    String unavailableLine =
        AiPositionStatusText.paintedLine(
            AiPositionDisplayState.UNAVAILABLE, unavailable, lastMoveZero);

    assertEquals(waiting, waitingLine);
    assertEquals(unavailable, unavailableLine);
    assertFalse(waitingLine.contains("0.0"));
    assertFalse(unavailableLine.contains("0.0"));
  }

  @Test
  void readyLineUsesRootScoreLeadAndOmitsOwnershipHeuristic() {
    ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    AiPositionController controller =
        new AiPositionController(List.of(new AcceptingProvider()), () -> {});
    AiPositionRequestContext context =
        new AiPositionRequestContext(
            "node-a", 1L, "[stones]", false, 19, 19, "chinese", 7.5, null, 1L);
    assertTrue(controller.open(context));
    controller.acceptLine(controller.generation(), ROOT_LEAD_LINE);
    AiPositionSnapshot snapshot = controller.visibleSnapshot(context).orElseThrow();

    String line =
        AiPositionStatusText.render(
            controller.displayState(context), snapshot, bundle, "chinese");

    assertEquals(AiPositionDisplayState.READY, controller.displayState(context));
    assertTrue(line.contains("71.4"));
    assertTrue(line.contains("88.0%"));
    assertTrue(line.contains("800"));
    assertTrue(line.contains("chinese"));
    assertTrue(line.contains("7.5"));
    assertFalse(line.contains("-13.0"));
    assertFalse(line.contains("13.0"));
  }

  private static final class AcceptingProvider implements AiPositionProvider {
    @Override
    public boolean supports(AiPositionRequestContext context) {
      return true;
    }

    @Override
    public boolean start(AiPositionRequestContext context, long generation) {
      return true;
    }

    @Override
    public void stop(long generation) {}
  }
}
