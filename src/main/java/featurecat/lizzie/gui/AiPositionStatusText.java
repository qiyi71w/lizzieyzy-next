package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.AiPositionDisplayState;
import featurecat.lizzie.analysis.AiPositionSnapshot;
import featurecat.lizzie.util.Utils;
import java.util.Locale;
import java.util.ResourceBundle;

/** Formats the main-panel AI形势 status line from a named display state. */
public final class AiPositionStatusText {
  private AiPositionStatusText() {}

  public static String render(
      AiPositionDisplayState state,
      AiPositionSnapshot snapshot,
      ResourceBundle bundle,
      String rulesLabel) {
    if (state == null || state == AiPositionDisplayState.CLOSED || bundle == null) {
      return "";
    }
    if (state == AiPositionDisplayState.WAITING) {
      return bundle.getString("LizzieFrame.aiPositionWaiting");
    }
    if (state == AiPositionDisplayState.UNAVAILABLE) {
      return bundle.getString("LizzieFrame.aiPositionUnavailable");
    }
    if (snapshot == null) {
      return bundle.getString("LizzieFrame.aiPositionWaiting");
    }
    String rules = rulesLabel == null || rulesLabel.isEmpty() ? snapshot.rules() : rulesLabel;
    return bundle.getString("LizzieFrame.scoreLeadJustScore")
        + String.format(Locale.ENGLISH, "%.1f", snapshot.blackScoreLead())
        + " "
        + bundle.getString("LizzieFrame.winrate")
        + String.format(Locale.ENGLISH, "%.1f%%", snapshot.blackWinrate())
        + " "
        + bundle.getString("LizzieFrame.visits")
        + Utils.getPlayoutsString(snapshot.visits())
        + " "
        + rules
        + " "
        + bundle.getString("LizzieFrame.komi")
        + snapshot.komi();
  }

  public static String paintedLine(
      AiPositionDisplayState state, String statusLine, String lastMoveSuffix) {
    String status = statusLine == null ? "" : statusLine;
    if (state == AiPositionDisplayState.WAITING || state == AiPositionDisplayState.UNAVAILABLE) {
      return status;
    }
    String suffix = lastMoveSuffix == null ? "" : lastMoveSuffix;
    return status + suffix;
  }
}
