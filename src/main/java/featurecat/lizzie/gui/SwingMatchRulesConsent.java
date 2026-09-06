package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.MatchRulesAdmission;
import featurecat.lizzie.enginegame.MatchRulesConsent;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class SwingMatchRulesConsent implements MatchRulesConsent {
  public static final SwingMatchRulesConsent INSTANCE = new SwingMatchRulesConsent();

  private SwingMatchRulesConsent() {}

  @Override
  public boolean confirmUnverified(MatchRulesAdmission.Decision decision) {
    if (decision == null) {
      return false;
    }
    boolean[] accepted = {false};
    Runnable prompt =
        () -> {
          int result =
              JOptionPane.showConfirmDialog(
                  Lizzie.frame,
                  MatchRulesTexts.consentMessage(decision, Lizzie.resourceBundle),
                  Lizzie.resourceBundle.getString("MatchRules.consentTitle"),
                  JOptionPane.YES_NO_OPTION,
                  JOptionPane.WARNING_MESSAGE);
          accepted[0] = result == JOptionPane.YES_OPTION;
        };
    if (SwingUtilities.isEventDispatchThread()) {
      prompt.run();
    } else {
      try {
        SwingUtilities.invokeAndWait(prompt);
      } catch (Exception interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return accepted[0];
  }
}
