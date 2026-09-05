package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.MatchRulesAdmission;
import featurecat.lizzie.enginegame.MatchRulesSnapshot;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ResourceBundle;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class MatchRulesDetailsDialogTest {
  private static final EngineParticipantIdentity BLACK =
      new EngineParticipantIdentity("black-cmd", "BlackEngine");
  private static final EngineParticipantIdentity WHITE =
      new EngineParticipantIdentity("white-cmd", "WhiteEngine");

  @Test
  void preferredContentSizeStaysBoundedForLargeFonts() {
    assertTrue(
        MatchRulesDetailsDialog.preferredContentSize().width <= MatchRulesDetailsDialog.MAX_WIDTH);
    assertTrue(
        MatchRulesDetailsDialog.preferredContentSize().height
            <= MatchRulesDetailsDialog.MAX_HEIGHT);
    assertTrue(
        MatchRulesDetailsDialog.preferredContentSize().width >= MatchRulesDetailsDialog.MIN_WIDTH);
  }

  @Test
  void captionHitBoxCoversDrawnString() {
    Rectangle box = LizzieFrame.matchRulesCaptionHitBox(10, 20, 200, 80, true, 40, 12, 4);
    assertTrue(box.contains(10 + 200 / 2, 20 + 80 * 5 / 16 - 4));
    assertFalse(box.contains(10, 20));
  }

  @Test
  void dialogShowsSnapshotDetailsAndInstallsEscape() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle bundle = Lizzie.resourceBundle;
    KataGoRules chinese = KataGoRules.parse("chinese").orElseThrow();
    KataGoRules positional = KataGoRules.parse("chinese-ogs").orElseThrow();
    MatchRulesSnapshot snapshot =
        MatchRulesSnapshot.of(
            MatchRulesSnapshot.Phase.FAILED,
            chinese,
            confirmed(BLACK, chinese),
            confirmed(WHITE, positional),
            MatchRulesAdmission.Outcome.REJECT);
    JFrame owner = new JFrame("owner");
    owner.setVisible(true);
    try {
      MatchRulesDetailsDialog dialog = new MatchRulesDetailsDialog(owner, snapshot);
      assertEquals(MatchRulesTexts.details(snapshot, bundle), dialog.detailsText());
      assertTrue(dialog.detailsText().contains("ko=POSITIONAL"));
      assertEquals(bundle.getString("MatchRules.details.close"), dialog.closeButton().getText());
      assertEquals(
          "accessible-close-window",
          dialog
              .getRootPane()
              .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
              .get(KeyStroke.getKeyStroke("ESCAPE")));
      dialog.closeAndReturnFocus();
      assertFalse(dialog.isDisplayable());
    } finally {
      owner.dispose();
    }
  }

  private static MatchRulesAdmission.SideResult confirmed(
      EngineParticipantIdentity identity, KataGoRules rules) {
    return new MatchRulesAdmission.SideResult(
        identity,
        true,
        true,
        rules,
        rules,
        EngineRulesResult.Status.CONFIRMED,
        EngineRulesResult.Reason.NONE,
        false);
  }
}
