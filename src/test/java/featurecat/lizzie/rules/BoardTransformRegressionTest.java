package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTransformRegressionTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 6})
  void transformationRetainsFirstMoveBranchesAndCurrentPosition(int transform) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5]KM[6.5];B[aa](;W[bb])(;W[cc]))"));
      env.board().nextMove(false);
      AllMovelist moves = env.board().getAllMovelist(transform);
      env.board().clear(false);
      env.board().playAllMovelist(moves, 0);
      assertEquals(1, env.board().getHistory().getMoveNumber());
      assertEquals(transform == 6 ? Stone.WHITE : Stone.BLACK, env.board().getData().lastMoveColor);
      assertEquals(2, env.board().getHistory().mainTrunkLength());
      assertEquals(2, env.current().numberOfChildren());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 6})
  void transformationRetainsLeadingPassColor(int transform) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5];W[];B[aa])"));
      AllMovelist moves = env.board().getAllMovelist(transform);
      env.board().clear(false);
      env.board().playAllMovelist(moves, 0);
      BoardData first = env.board().getHistory().getStart().next().orElseThrow().getData();
      assertTrue(first.isPassNode());
      assertEquals(transform == 6 ? Stone.BLACK : Stone.WHITE, first.lastMoveColor);
      assertEquals(2, env.board().getHistory().mainTrunkLength());
    }
  }
}
