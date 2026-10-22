package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.enginegame.EngineGameParameterState;
import featurecat.lizzie.enginegame.EngineGameParameterState.Position;
import featurecat.lizzie.enginegame.EngineGameParameterState.Value;
import java.awt.Dimension;
import org.junit.jupiter.api.Test;

class EngineGameParametersDialogTest {
  private static final Value DONE = new Value(true, 0.5, null);
  private static final Value WAITING = new Value(true, 0.5, 1.0);

  @Test
  void okClosesOnlyAfterEveryRequestedValueIsConfirmed() {
    Position settled = new Position(DONE, DONE);
    assertTrue(
        EngineGameParametersDialog.confirmed(
            new EngineGameParameterState(settled, settled, false, null)));
    assertFalse(
        EngineGameParametersDialog.confirmed(
            new EngineGameParameterState(settled, settled, true, null)),
        "an update still running");
    assertFalse(
        EngineGameParametersDialog.confirmed(
            new EngineGameParameterState(settled, new Position(DONE, WAITING), false, null)),
        "Engine 2 aggressiveness not confirmed yet");
    assertFalse(
        EngineGameParametersDialog.confirmed(
            new EngineGameParameterState(new Position(WAITING, DONE), settled, false, null)),
        "Engine 1 breadth not confirmed yet");
  }

  @Test
  void panelShrinksBackAfterATemporaryLabelUnlessTheUserResizedIt() {
    Dimension fitted = new Dimension(720, 200);
    Dimension widened = new Dimension(810, 200);
    assertEquals(
        widened, EngineGameParametersDialog.fittedSize(fitted, fitted, widened), "grows to fit");
    assertEquals(
        fitted,
        EngineGameParametersDialog.fittedSize(widened, widened, fitted),
        "shrinks back once the pending label is gone");

    Dimension userSize = new Dimension(900, 260);
    assertEquals(
        userSize,
        EngineGameParametersDialog.fittedSize(userSize, fitted, widened),
        "a size the user chose is kept while the content fits");
    assertEquals(
        new Dimension(1000, 260),
        EngineGameParametersDialog.fittedSize(userSize, fitted, new Dimension(1000, 200)),
        "and only grows when the content would be cut off");
  }
}
