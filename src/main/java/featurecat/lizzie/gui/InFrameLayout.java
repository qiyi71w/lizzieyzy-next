package featurecat.lizzie.gui;

import java.awt.Rectangle;
import java.util.Optional;

/**
 * Default ExtraMode.Normal landscape in-frame rectangles. Optional leftover-left share overrides
 * the 0–8 board position split. Optional comment and variation-graph shares override the in-column
 * height splits. Matches the current leftover formula when the shares are absent, including the
 * comment / sub-board swap.
 */
final class InFrameLayout {
  static final int DEFAULT_BOARD_POSITION_PROPORTION = 4;

  static Double leftoverShareAfterAssignedProportion(Double currentShare, int proportion) {
    if (currentShare == null) {
      return null;
    }
    return Math.max(0, Math.min(8, proportion)) / 8.0;
  }

  private static final int MIN_COMMENT_HEIGHT = 68;
  private static final int MIN_CANDIDATE_TABLE_HEIGHT = 36;

  final Rectangle board;
  final Rectangle leftColumn;
  final Rectangle rightColumn;
  final Rectangle comment;
  final Rectangle variationGraph;
  final Rectangle candidateTable;
  final Rectangle captured;
  final Rectangle moveStatistics;
  final Rectangle winrateGraph;
  final Rectangle subBoard;
  final Optional<Rectangle> boardLeftDivider;
  final Optional<Rectangle> boardRightDivider;
  final Optional<Rectangle> commentTopDivider;
  final Optional<Rectangle> variationListDivider;
  final int panelMargin;
  final int ponderingY;
  final int treeX;
  final int treeY;
  final int treeW;
  final int treeH;
  final int treeContainerH;

  static final class Request {
    final int width;
    final int height;
    final int leftInset;
    final int topInset;
    final int rightInset;
    final int bottomInset;
    final int boardWidth;
    final int boardHeight;
    final int boardPositionProportion;
    final Optional<Double> leftoverLeftShare;
    final Optional<Double> commentHeightShare;
    final Optional<Double> variationGraphShare;
    final boolean showComment;
    final boolean showSubBoard;
    final boolean showVariationGraph;
    final boolean showListPane;
    final boolean showStatus;
    final boolean userKnownX;
    final boolean showCaptured;
    final boolean showWinrateGraph;

    Request(
        int width,
        int height,
        int leftInset,
        int topInset,
        int rightInset,
        int bottomInset,
        int boardWidth,
        int boardHeight,
        int boardPositionProportion,
        boolean showComment,
        boolean showSubBoard,
        boolean showVariationGraph,
        boolean showListPane,
        boolean showStatus,
        boolean userKnownX) {
      this(
          width,
          height,
          leftInset,
          topInset,
          rightInset,
          bottomInset,
          boardWidth,
          boardHeight,
          boardPositionProportion,
          Optional.empty(),
          showComment,
          showSubBoard,
          showVariationGraph,
          showListPane,
          showStatus,
          userKnownX,
          true,
          true);
    }

    Request(
        int width,
        int height,
        int leftInset,
        int topInset,
        int rightInset,
        int bottomInset,
        int boardWidth,
        int boardHeight,
        int boardPositionProportion,
        Optional<Double> leftoverLeftShare,
        boolean showComment,
        boolean showSubBoard,
        boolean showVariationGraph,
        boolean showListPane,
        boolean showStatus,
        boolean userKnownX,
        boolean showCaptured,
        boolean showWinrateGraph) {
      this(
          width,
          height,
          leftInset,
          topInset,
          rightInset,
          bottomInset,
          boardWidth,
          boardHeight,
          boardPositionProportion,
          leftoverLeftShare,
          Optional.empty(),
          Optional.empty(),
          showComment,
          showSubBoard,
          showVariationGraph,
          showListPane,
          showStatus,
          userKnownX,
          showCaptured,
          showWinrateGraph);
    }

    Request(
        int width,
        int height,
        int leftInset,
        int topInset,
        int rightInset,
        int bottomInset,
        int boardWidth,
        int boardHeight,
        int boardPositionProportion,
        Optional<Double> leftoverLeftShare,
        Optional<Double> commentHeightShare,
        Optional<Double> variationGraphShare,
        boolean showComment,
        boolean showSubBoard,
        boolean showVariationGraph,
        boolean showListPane,
        boolean showStatus,
        boolean userKnownX,
        boolean showCaptured,
        boolean showWinrateGraph) {
      this.width = width;
      this.height = height;
      this.leftInset = leftInset;
      this.topInset = topInset;
      this.rightInset = rightInset;
      this.bottomInset = bottomInset;
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.boardPositionProportion = boardPositionProportion;
      this.leftoverLeftShare = leftoverLeftShare == null ? Optional.empty() : leftoverLeftShare;
      this.commentHeightShare = commentHeightShare == null ? Optional.empty() : commentHeightShare;
      this.variationGraphShare =
          variationGraphShare == null ? Optional.empty() : variationGraphShare;
      this.showComment = showComment;
      this.showSubBoard = showSubBoard;
      this.showVariationGraph = showVariationGraph;
      this.showListPane = showListPane;
      this.showStatus = showStatus;
      this.userKnownX = userKnownX;
      this.showCaptured = showCaptured;
      this.showWinrateGraph = showWinrateGraph;
    }

    Request restored() {
      return new Request(
          width,
          height,
          leftInset,
          topInset,
          rightInset,
          bottomInset,
          boardWidth,
          boardHeight,
          DEFAULT_BOARD_POSITION_PROPORTION,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          showComment,
          showSubBoard,
          showVariationGraph,
          showListPane,
          showStatus,
          userKnownX,
          showCaptured,
          showWinrateGraph);
    }
  }

  private InFrameLayout(
      Rectangle board,
      Rectangle leftColumn,
      Rectangle rightColumn,
      Rectangle comment,
      Rectangle variationGraph,
      Rectangle candidateTable,
      Rectangle captured,
      Rectangle moveStatistics,
      Rectangle winrateGraph,
      Rectangle subBoard,
      Optional<Rectangle> boardLeftDivider,
      Optional<Rectangle> boardRightDivider,
      Optional<Rectangle> commentTopDivider,
      Optional<Rectangle> variationListDivider,
      int panelMargin,
      int ponderingY,
      int treeX,
      int treeY,
      int treeW,
      int treeH,
      int treeContainerH) {
    this.board = board;
    this.leftColumn = leftColumn;
    this.rightColumn = rightColumn;
    this.comment = comment;
    this.variationGraph = variationGraph;
    this.candidateTable = candidateTable;
    this.captured = captured;
    this.moveStatistics = moveStatistics;
    this.winrateGraph = winrateGraph;
    this.subBoard = subBoard;
    this.boardLeftDivider = boardLeftDivider;
    this.boardRightDivider = boardRightDivider;
    this.commentTopDivider = commentTopDivider;
    this.variationListDivider = variationListDivider;
    this.panelMargin = panelMargin;
    this.ponderingY = ponderingY;
    this.treeX = treeX;
    this.treeY = treeY;
    this.treeW = treeW;
    this.treeH = treeH;
    this.treeContainerH = treeContainerH;
  }

  static InFrameLayout layout(Request request) {
    int width = request.width;
    int height = request.height;
    int leftInset = request.leftInset;
    int topInset = request.topInset;
    int rightInset = request.rightInset;
    int bottomInset = request.bottomInset;
    int maxBound = Math.max(width, height);
    int maxSize =
        Math.min(width - leftInset - rightInset, height - topInset - bottomInset);
    maxSize = Math.max(maxSize, Math.max(request.boardWidth, request.boardHeight) + 5);
    int panelMargin = (int) (maxSize * 0.02);
    int leftoverWidth = width - maxSize;
    int boardX = leftoverWidth / 8 * request.boardPositionProportion;
    if (request.leftoverLeftShare.isPresent()) {
      int minBoardX = panelMargin + leftInset;
      int maxBoardX = width - maxSize - panelMargin - rightInset;
      int raw = (int) Math.round(leftoverWidth * request.leftoverLeftShare.get());
      if (minBoardX <= maxBoardX) {
        boardX = Math.max(minBoardX, Math.min(maxBoardX, raw));
      }
    }
    int boardY = topInset + (height - topInset - bottomInset - maxSize) / 2;

    int capx = leftInset;
    int capy = topInset;
    int capw = boardX - panelMargin - leftInset;
    int caph = boardY + maxSize / 8 - topInset;

    int statx = capx;
    int staty = capy + caph;
    int statw = capw;
    int stath = maxSize / 10;

    int grx = statx;
    int gry = staty + stath;
    int grw = statw;
    int grh = maxSize / 3;

    int vx = boardX + maxSize + panelMargin;
    int vy = capy;
    int vw = width - vx - rightInset;
    int vh = height - vy - bottomInset;

    double ponderingSize = request.userKnownX ? 0.025 : 0.04;
    int ponderingY = Math.max(0, height - Math.max(0, bottomInset));
    if (request.showStatus) {
      ponderingY = ponderingY - (int) (maxSize * 0.023) - (int) (maxBound * ponderingSize);
    }

    int subBoardY = gry + grh;
    int subBoardWidth = grw;
    int subBoardHeight = ponderingY - subBoardY;
    int subBoardLength = Math.min(subBoardWidth, subBoardHeight);
    int subBoardX = statx + (statw - subBoardLength) / 2;

    int treex = vx;
    int treey = vy;
    int treew = vw;
    int treeh = vh;
    int cx = vx;
    int cy = vy;
    int cw = vw;
    int ch = vh;
    boolean noVariation = !request.showVariationGraph && !request.showListPane;
    if (request.showComment) {
      if (request.showVariationGraph || request.showListPane) {
        treeh = vh / 2;
        cy = vy + treeh;
        ch = treeh;
      }
      int tempx = cx;
      int tempy = cy;
      int tempw = cw;
      int temph = ch;
      if (subBoardWidth > subBoardHeight) {
        cx = subBoardX - (subBoardWidth - subBoardHeight) / 2;
      } else {
        cx = subBoardX;
      }
      cy = subBoardY;
      cw = subBoardWidth;
      ch = subBoardHeight;
      subBoardX = tempx;
      subBoardY = tempy;
      subBoardLength = Math.min(tempw, temph);
    }
    if (request.showSubBoard && request.showComment) {
      treeh = treeh + vh / 2 - subBoardLength;
      if (noVariation) {
        subBoardY = subBoardY + vh - subBoardLength;
      } else {
        subBoardY = subBoardY + vh / 2 - subBoardLength;
      }
      subBoardY = Math.max(subBoardY, vy);
    }

    if (request.showVariationGraph || request.showListPane) {
      if (request.showSubBoard && !request.showComment) {
        treeh = vh;
      }
      if (!request.showSubBoard && request.showComment) {
        treeh = vh;
      }
    }

    int treeContainerH = treeh;
    Rectangle candidateTable = new Rectangle();
    if (request.showListPane) {
      if (request.showVariationGraph) {
        int containerH = treeh;
        if (request.variationGraphShare.isPresent()) {
          int minList = MIN_CANDIDATE_TABLE_HEIGHT;
          int raw = (int) Math.round(containerH * request.variationGraphShare.get());
          int maxVar = containerH - minList;
          if (maxVar >= 0) {
            treeh = Math.max(0, Math.min(maxVar, raw));
            candidateTable = new Rectangle(treex, treey + treeh, treew, containerH - treeh);
          } else {
            treeh = containerH / 2;
            candidateTable = new Rectangle(treex, treey + treeh, treew, treeh);
          }
        } else {
          treeh = treeh / 2;
          candidateTable = new Rectangle(treex, treey + treeh, treew, treeh);
        }
      } else {
        candidateTable = new Rectangle(treex, treey, treew, treeh);
      }
    }

    if (request.showComment && request.commentHeightShare.isPresent() && ch > 0) {
      int splitTop = capy;
      int splitBottom = cy + ch;
      int splitH = splitBottom - splitTop;
      int raw = (int) Math.round(splitH * request.commentHeightShare.get());
      if (MIN_COMMENT_HEIGHT <= splitH) {
        ch = Math.max(MIN_COMMENT_HEIGHT, Math.min(splitH, raw));
        cy = splitBottom - ch;
        int aboveH = cy - splitTop;
        caph = Math.min(caph, Math.max(0, aboveH));
        stath = Math.min(stath, Math.max(0, aboveH - caph));
        grh = Math.max(0, aboveH - caph - stath);
        staty = capy + caph;
        gry = staty + stath;
      }
    }

    Rectangle comment =
        request.showComment ? new Rectangle(cx, cy, cw, ch) : new Rectangle();
    Rectangle variationGraph =
        request.showVariationGraph
            ? new Rectangle(treex, treey, treew, treeh)
            : new Rectangle();
    Rectangle subBoard =
        request.showSubBoard
            ? new Rectangle(subBoardX, subBoardY, subBoardLength, subBoardLength)
            : new Rectangle();
    Rectangle board = new Rectangle(boardX, boardY, maxSize, maxSize);
    Rectangle leftColumn = new Rectangle(capx, capy, capw, height - bottomInset - capy);
    Rectangle rightColumn = new Rectangle(vx, vy, vw, vh);
    boolean leftOccupied =
        request.showCaptured
            || request.showWinrateGraph
            || request.showComment
            || request.showSubBoard;
    boolean rightOccupied =
        request.showVariationGraph
            || request.showListPane
            || (request.showSubBoard && request.showComment);
    Optional<Rectangle> boardLeftDivider =
        leftOccupied
            ? Optional.of(new Rectangle(board.x, board.y, 1, board.height))
            : Optional.empty();
    Optional<Rectangle> boardRightDivider =
        rightOccupied
            ? Optional.of(new Rectangle(board.x + board.width - 1, board.y, 1, board.height))
            : Optional.empty();
    Optional<Rectangle> commentTopDivider =
        comment.height > 0
            ? Optional.of(new Rectangle(comment.x, comment.y, comment.width, 1))
            : Optional.empty();
    Optional<Rectangle> variationListDivider =
        request.showVariationGraph && request.showListPane
            ? Optional.of(
                new Rectangle(candidateTable.x, candidateTable.y, candidateTable.width, 1))
            : Optional.empty();

    return new InFrameLayout(
        board,
        leftColumn,
        rightColumn,
        comment,
        variationGraph,
        candidateTable,
        new Rectangle(capx, capy, capw, caph),
        new Rectangle(statx, staty, statw, stath),
        new Rectangle(grx, gry, grw, grh),
        subBoard,
        boardLeftDivider,
        boardRightDivider,
        commentTopDivider,
        variationListDivider,
        panelMargin,
        ponderingY,
        treex,
        treey,
        treew,
        treeh,
        treeContainerH);
  }
}
