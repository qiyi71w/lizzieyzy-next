package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.SGFParser.WinrateCommentSplit;
import java.util.Optional;

/** Builds trusted HTML for displaying user SGF comments and stored AI commentary. */
public final class CommentDisplayRenderer {
  private CommentDisplayRenderer() {}

  public static String render(String rawComment) {
    String userComment = TeacherCommentCodec.removeBlocks(rawComment);
    Optional<String> aiCommentary = TeacherCommentCodec.extract(rawComment);
    String personalComment = userComment;
    String matchInfo = "";
    if (shouldSplitOrdinaryAnalysisMatchInfo()) {
      WinrateCommentSplit split = SGFParser.splitWinrateComment(userComment);
      personalComment = split.personalComment;
      matchInfo = split.matchInfo;
    }
    StringBuilder body = new StringBuilder("<html><body>");
    if (!personalComment.isEmpty()) {
      appendSgfComment(body, personalComment);
    }
    if (!personalComment.isEmpty() && !matchInfo.isEmpty()) {
      body.append("<div class='match-info-divider'>&nbsp;</div>");
    }
    if (!matchInfo.isEmpty()) {
      appendSgfComment(body, matchInfo);
    }
    boolean hasSgfText = !personalComment.isEmpty() || !matchInfo.isEmpty();
    if (aiCommentary.isPresent()) {
      if (hasSgfText) {
        body.append("<div class='comment-spacer'>&nbsp;</div>");
      }
      body.append("<div class='ai-commentary'>")
          .append("<div class='ai-commentary-title'><strong>")
          .append(
              SafeMarkdownRenderer.escape(
                  TeacherStrings.get("Teacher.title", "AI Commentary")))
          .append("</strong></div>")
          .append(SafeMarkdownRenderer.toBodyHtml(aiCommentary.get()))
          .append("</div>");
    }
    return body.append("</body></html>").toString();
  }

  private static void appendSgfComment(StringBuilder body, String text) {
    body.append("<div class='sgf-comment'>")
        .append(SafeMarkdownRenderer.plainTextToBodyHtml(text))
        .append("</div>");
  }

  private static boolean shouldSplitOrdinaryAnalysisMatchInfo() {
    Board board = Lizzie.board;
    if (board == null) {
      return true;
    }
    if (EngineGamePresentation.current().playing()) {
      return false;
    }
    if (board.isPkBoard || board.isGameBoard) {
      return false;
    }
    if (board.getHistory() != null) {
      GameInfo info = board.getHistory().getGameInfo();
      if (info != null && info.hasEngineGameHistory()) {
        return false;
      }
    }
    return true;
  }
}
