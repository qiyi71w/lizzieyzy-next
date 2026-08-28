package featurecat.lizzie.gui;

import featurecat.lizzie.util.Utils;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Optional;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;

/**
 * A few-pixel E_RESIZE hot zone on the board's left and right edges, covering comment / variation /
 * candidate-table components without overlapping the board.
 */
final class InFrameLeftoverDragHandles {
  private final LizzieFrame frame;
  private final JPanel left = createHandle(true);
  private final JPanel right = createHandle(false);
  private int leftoverWidth;
  private int boardX;
  private int boardWidth;
  private int grabOffset;
  private boolean draggingLeft;
  private boolean dragging;

  InFrameLeftoverDragHandles(LizzieFrame frame) {
    this.frame = frame;
  }

  void install(JLayeredPane basePanel) {
    basePanel.add(left, Integer.valueOf(11));
    basePanel.add(right, Integer.valueOf(11));
  }

  void update(InFrameLayout layout, int contentWidth, int chromeY, boolean active) {
    if (dragging) {
      return;
    }
    if (!active || layout == null) {
      left.setVisible(false);
      right.setVisible(false);
      return;
    }
    leftoverWidth = contentWidth - layout.board.width;
    boardX = layout.board.x;
    boardWidth = layout.board.width;
    place(left, layout.boardLeftDivider, layout.board, layout.leftColumn, true, chromeY);
    place(right, layout.boardRightDivider, layout.board, layout.rightColumn, false, chromeY);
  }

  private void place(
      JPanel handle,
      Optional<Rectangle> divider,
      Rectangle board,
      Rectangle column,
      boolean leftEdge,
      int chromeY) {
    if (!divider.isPresent() || leftoverWidth <= 0) {
      handle.setVisible(false);
      return;
    }
    int x;
    int w;
    if (leftEdge) {
      int columnRight = column.x + column.width;
      int hot = Math.max(6, board.x - columnRight + 4);
      x = Math.max(column.x, board.x - hot);
      w = board.x - x;
    } else {
      int boardRight = board.x + board.width;
      int hot = Math.max(6, column.x - boardRight + 4);
      x = boardRight;
      w = hot;
      int columnRight = column.x + column.width;
      if (x + w > columnRight) {
        w = columnRight - x;
      }
    }
    if (w < 1) {
      handle.setVisible(false);
      return;
    }
    int zx = Utils.zoomIn(x);
    int zy = Utils.zoomIn(board.y) + chromeY;
    int zw = Utils.zoomIn(x + w) - zx;
    int zh = Utils.zoomIn(board.height);
    if (zw < 1 || zh < 1) {
      handle.setVisible(false);
      return;
    }
    handle.setBounds(zx, zy, zw, zh);
    handle.setVisible(true);
  }

  private JPanel createHandle(boolean leftEdge) {
    JPanel handle = new JPanel();
    handle.setOpaque(false);
    handle.setVisible(false);
    handle.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
    MouseAdapter mouse =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            if (e.getButton() != MouseEvent.BUTTON1 || leftoverWidth <= 0) {
              return;
            }
            int layoutX = Utils.zoomOut(handle.getX() + e.getX());
            grabOffset = leftEdge ? layoutX - boardX : layoutX - (boardX + boardWidth);
            draggingLeft = leftEdge;
            dragging = true;
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            if (!dragging || leftoverWidth <= 0) {
              return;
            }
            int layoutX = Utils.zoomOut(handle.getX() + e.getX());
            int nextBoardX =
                draggingLeft
                    ? layoutX - grabOffset
                    : layoutX - grabOffset - boardWidth;
            frame.applyLeftoverShare(nextBoardX / (double) leftoverWidth);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            if (!dragging) {
              return;
            }
            dragging = false;
            frame.commitLeftoverShare();
          }
        };
    handle.addMouseListener(mouse);
    handle.addMouseMotionListener(mouse);
    return handle;
  }
}
