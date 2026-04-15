package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.theme.MorandiPalette;
import featurecat.lizzie.util.Utils;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowStateListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import org.json.JSONArray;

public class AnalysisFrame extends JFrame {
  private static final int STANDARD_COLUMN_COUNT = 7;
  private static final int EXTENDED_COLUMN_COUNT = 9;
  private static final int TABLE_REFRESH_INTERVAL_MS = 100;

  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  ;
  AnalysisTableModel dataModel;
  JScrollPane scrollpane;
  JPanel topPanel;
  JPanel bottomPanel;
  public JTable table;
  Timer timer;
  int sortnum = -1;
  public int selectedorder = -1;
  public int clickOrder = -1;
  int currentRow = -1;
  Font winrateFont;
  Font headFont;
  int index;
  private String oriTitle = "";

  public AnalysisFrame(int engine) {
    index = engine;
    dataModel = getTableModel();
    if (Lizzie.config.isDoubleEngineMode()) {
      if (index == 1) oriTitle = resourceBundle.getString("AnalysisFrame.titleMain");
      else if (index == 2) oriTitle = resourceBundle.getString("AnalysisFrame.titleSub");
    } else oriTitle = resourceBundle.getString("AnalysisFrame.title");
    setTitle(oriTitle);
    setAlwaysOnTop(Lizzie.config.suggestionsalwaysontop || Lizzie.frame.isAlwaysOnTop());
    setTopTitle();
    // JDialog dialog = new JDialog(owner,
    // "单击显示紫圈(小棋盘显示变化),右键落子,双击显示后续变化图,快捷键U显示/关闭");
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            Lizzie.frame.toggleBestMoves();
            if (timer != null) timer.stop();
          }
        });

    // Create and set up the content pane.
    // final AnalysisFrame newContentPane = new AnalysisFrame();
    // newContentPane.setOpaque(true); // content panes must be opaque
    // setContentPane(newContentPane);
    // Display the window.
    // jfs.setSize(521, 285);

    try {
      setIconImage(ImageIO.read(getClass().getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    table = new JTable(dataModel);

    winrateFont = new Font(Lizzie.config.uiFontName, Font.BOLD, Math.max(Config.frameFontSize, 14));
    headFont = new Font(Lizzie.config.uiFontName, Font.PLAIN, Math.max(Config.frameFontSize, 13));

    configureTableAppearance();
    scrollpane = new JScrollPane(table);
    topPanel = new JPanel(new BorderLayout());
    topPanel.add(scrollpane);

    bottomPanel =
        new JPanel() {
          @Override
          public void paintComponent(Graphics g) {
            paintBottomPanel(g, bottomPanel.getWidth(), bottomPanel.getHeight());
          }
        };
    bottomPanel.setLayout(null);

    JCheckBox checkList = new JCheckBox(resourceBundle.getString("AnalysisFrame.checkList"));
    bottomPanel.add(checkList);
    checkList.setBounds(370, 0, 50, 18);
    checkList.setFocusable(false);
    checkList.setMargin(new Insets(0, 0, 0, 0));
    checkList.setSelected(Lizzie.config.showBestMovesList);
    checkList.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.showBestMovesList = checkList.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put("show-bestmoves-list", Lizzie.config.showBestMovesList);
          }
        });

    JCheckBox checkGraph = new JCheckBox(resourceBundle.getString("AnalysisFrame.checkGraph"));
    bottomPanel.add(checkGraph);
    checkGraph.setBounds(420, 0, 65, 18);
    checkGraph.setFocusable(false);
    checkGraph.setMargin(new Insets(0, 0, 0, 0));
    checkGraph.setSelected(Lizzie.config.showBestMovesGraph);
    checkGraph.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.showBestMovesGraph = checkGraph.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put("show-bestmoves-graph", Lizzie.config.showBestMovesGraph);
          }
        });

    JCheckBox checkShowNext =
        new JCheckBox(resourceBundle.getString("AnalysisFrame.checkShowNext"));
    bottomPanel.add(checkShowNext);
    checkShowNext.setBounds(485, 0, 65, 18);
    checkShowNext.setFocusable(false);
    checkShowNext.setMargin(new Insets(0, 0, 0, 0));
    checkShowNext.setSelected(Lizzie.config.anaFrameShowNext);
    checkShowNext.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.anaFrameShowNext = checkShowNext.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put("anaframe-show-next", Lizzie.config.anaFrameShowNext);
          }
        });

    JCheckBox checkUseMouseMove =
        new JCheckBox(resourceBundle.getString("AnalysisFrame.checkUseMouseMove"));
    bottomPanel.add(checkUseMouseMove);
    checkUseMouseMove.setBounds(550, 0, 95, 18);
    checkUseMouseMove.setFocusable(false);
    checkUseMouseMove.setMargin(new Insets(0, 0, 0, 0));
    checkUseMouseMove.setSelected(Lizzie.config.anaFrameUseMouseMove);
    checkUseMouseMove.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.anaFrameUseMouseMove = checkUseMouseMove.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put(
                "anaframe-use-mousemove", Lizzie.config.anaFrameUseMouseMove);
          }
        });

    //    anaFrameShowNext = uiConfig.optBoolean("anaframe-show-next", true);
    // anaFrameUseMouseMove = uiConfig.optBoolean("anaframe-use-mousemove", true);

    this.getContentPane().setLayout(new BorderLayout());
    this.getContentPane().add(bottomPanel, BorderLayout.SOUTH);
    if (!Lizzie.config.showBestMovesList) {
    } else this.getContentPane().add(topPanel, BorderLayout.NORTH);

    this.addComponentListener(
        new ComponentAdapter() {
          public void componentResized(ComponentEvent e) {
            int height = getContentPane().getHeight();

            if (!Lizzie.config.showBestMovesList) {
              getContentPane().remove(topPanel);
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height));
            } else if (Lizzie.config.showBestMovesGraph) {
              topPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
            } else {
              topPanel.setPreferredSize(new Dimension(getWidth(), height - 22));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), 22));
            }
          }
        });

    this.addWindowStateListener(
        new WindowStateListener() {
          public void windowStateChanged(WindowEvent state) {
            int height = getHeight() - 40;
            if (!Lizzie.config.showBestMovesList) {
              getContentPane().remove(topPanel);
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height));
            } else if (Lizzie.config.showBestMovesGraph) {
              topPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
            } else {
              topPanel.setPreferredSize(new Dimension(getWidth(), height - 22));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), 22));
            }
          }
        });

    timer =
        new Timer(
            TABLE_REFRESH_INTERVAL_MS,
            new ActionListener() {
              public void actionPerformed(ActionEvent evt) {
                refreshAnalysisView();
              }
            });
    timer.start();

    boolean persisted = Lizzie.config.persistedUi != null;

    if (persisted) {

      if (table.getColumnCount() == EXTENDED_COLUMN_COUNT) {
        if (Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-9") != null
            && Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-9").length()
                == 13) {
          JSONArray pos = Lizzie.config.persistedUi.getJSONArray("suggestions-list-position-9");
          table.getColumnModel().getColumn(0).setPreferredWidth(pos.getInt(4));
          table.getColumnModel().getColumn(1).setPreferredWidth(pos.getInt(5));
          table.getColumnModel().getColumn(2).setPreferredWidth(pos.getInt(6));
          table.getColumnModel().getColumn(3).setPreferredWidth(pos.getInt(7));
          table.getColumnModel().getColumn(4).setPreferredWidth(pos.getInt(8));
          table.getColumnModel().getColumn(5).setPreferredWidth(pos.getInt(9));
          table.getColumnModel().getColumn(6).setPreferredWidth(pos.getInt(10));
          table.getColumnModel().getColumn(7).setPreferredWidth(pos.getInt(11));
          table.getColumnModel().getColumn(8).setPreferredWidth(pos.getInt(12));
          setBounds(pos.getInt(0), pos.getInt(1), pos.getInt(2), pos.getInt(3));
        } else {
          setBounds(-9, 550, 650, 320);
        }
      } else if (Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-7") != null
          && Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-7").length() == 11) {
        JSONArray pos = Lizzie.config.persistedUi.getJSONArray("suggestions-list-position-7");
        table.getColumnModel().getColumn(0).setPreferredWidth(pos.getInt(4));
        table.getColumnModel().getColumn(1).setPreferredWidth(pos.getInt(5));
        table.getColumnModel().getColumn(2).setPreferredWidth(pos.getInt(6));
        table.getColumnModel().getColumn(3).setPreferredWidth(pos.getInt(7));
        table.getColumnModel().getColumn(4).setPreferredWidth(pos.getInt(8));
        table.getColumnModel().getColumn(5).setPreferredWidth(pos.getInt(9));
        table.getColumnModel().getColumn(6).setPreferredWidth(pos.getInt(10));
        setBounds(pos.getInt(0), pos.getInt(1), pos.getInt(2), pos.getInt(3));
        Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
        int widths = (int) screensize.getWidth();
        int heights = (int) screensize.getHeight();
        if (pos.getInt(0) >= widths || pos.getInt(1) >= heights) this.setLocation(0, 0);
      } else {
        setBounds(-9, 480, 650, 320);
      }
    } else {
      setBounds(-9, 480, 650, 320);
    }

    if (!Lizzie.config.showBestMovesList) {
      bottomPanel.setPreferredSize(new Dimension(this.getWidth(), this.getHeight() - 40));
    }
    setVisible(false);
    setVisible(true);
    if (Lizzie.config.isDoubleEngineMode()) {
      if (index == 2) {
        if (Lizzie.frame.analysisFrame != null)
          this.setLocation(
              Lizzie.frame.analysisFrame.getX() + Lizzie.frame.analysisFrame.getWidth(),
              Lizzie.frame.analysisFrame.getY());
      }
    }
    JTableHeader header = table.getTableHeader();

    table.addMouseMotionListener(
        new MouseMotionListener() {
          @Override
          public void mouseDragged(MouseEvent e) {
            // TODO Auto-generated method stub
            mouseMoved(e);
          }

          @Override
          public void mouseMoved(MouseEvent e) {
            // TODO Auto-generated method stub
            if (!Lizzie.config.anaFrameUseMouseMove || clickOrder != -1) return;
            Point p = e.getPoint();
            int row = table.rowAtPoint(p);
            if (row < 0) {
              if (Lizzie.frame.suggestionclick != LizzieFrame.outOfBoundCoordinate) {
                LizzieFrame.boardRenderer.startNormalBoard();
                LizzieFrame.boardRenderer.clearBranch();
                Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
                Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
                selectedorder = -1;
                currentRow = -1;
                clickOrder = -1;
                Lizzie.frame.refresh();
              }
              return;
            }
            if (table.getValueAt(row, 1).toString().startsWith("pass")) return;
            String coordsName =
                Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
            int[] coords = Board.convertNameToCoordinates(coordsName);
            if (selectedorder >= 0
                && coords[0] == Lizzie.frame.suggestionclick[0]
                && coords[1] == Lizzie.frame.suggestionclick[1]) {
            } else {
              LizzieFrame.boardRenderer.startNormalBoard();
              selectedorder = row;
              currentRow = row;
              // int[] coords = Board.convertNameToCoordinates(table.getValueAt(row, 1).toString());
              Lizzie.frame.mouseOverCoordinate = coords;
              Lizzie.frame.suggestionclick = coords;
              Lizzie.frame.refresh();
            }
          }
        });

    table.addMouseWheelListener(
        new MouseWheelListener() {
          @Override
          public void mouseWheelMoved(MouseWheelEvent e) {
            // TODO Auto-generated method stub
            if (clickOrder != -1) {
              if (e.getWheelRotation() > 0) {
                Lizzie.frame.doBranch(1);
              } else if (e.getWheelRotation() < 0) {
                Lizzie.frame.doBranch(-1);
              }
              Lizzie.frame.refresh();
            } else {
              scrollpane.dispatchEvent(e);
            }
          }
        });
    table.addMouseListener(
        new MouseAdapter() {
          public void mouseExited(MouseEvent e) {
            if (!Lizzie.config.anaFrameUseMouseMove || clickOrder != -1) return;
            if (Lizzie.frame.suggestionclick != LizzieFrame.outOfBoundCoordinate) {
              LizzieFrame.boardRenderer.startNormalBoard();
              LizzieFrame.boardRenderer.clearBranch();
              Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
              Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
              selectedorder = -1;
              currentRow = -1;
              Lizzie.frame.refresh();
            }
          }

          public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            int col = table.columnAtPoint(e.getPoint());
            //            if (e.getClickCount() == 2) {
            //              if (row >= 0 && col >= 0) {
            //                try {
            //                  handleTableDoubleClick(row, col);
            //                } catch (Exception ex) {
            //                  ex.printStackTrace();
            //                }
            //              }
            //            } else {

            if (row >= 0 && col >= 0) {
              if (e.getButton() == MouseEvent.BUTTON3) {
                try {
                  handleTableRightClick(row, col);
                } catch (Exception ex) {
                  ex.printStackTrace();
                }
              } else
                try {
                  handleTableClick(row, col);
                } catch (Exception ex) {
                  ex.printStackTrace();
                }
            }
          }
          //    }
        });
    table.addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_U) {
              Lizzie.frame.toggleBestMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_UP) {
              Lizzie.board.previousMove(true);
            }
            if (e.getKeyCode() == KeyEvent.VK_DOWN) {
              Lizzie.board.nextMove(true);
            }
            if (e.getKeyCode() == KeyEvent.VK_Y) {
              Lizzie.frame.toggleBadMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_SPACE) {
              Lizzie.frame.togglePonderMannul();
            }

            if (e.getKeyCode() == KeyEvent.VK_Q) {
              Lizzie.frame.toggleAnalysisFrameAlwaysontop();
            }
          }
        });
    addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_U) {
              Lizzie.frame.toggleBestMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_Y) {
              Lizzie.frame.toggleBadMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_SPACE) {
              Lizzie.frame.togglePonderMannul();
            }

            if (e.getKeyCode() == KeyEvent.VK_Q) {
              Lizzie.frame.toggleAnalysisFrameAlwaysontop();
            }
          }
        });

    header.addMouseListener(
        new MouseAdapter() {
          public void mouseReleased(MouseEvent e) {
            int pick = header.columnAtPoint(e.getPoint());
            sortnum = pick;
          }
        });
  }

  public void setTopTitle() {
    if (this.isAlwaysOnTop())
      setTitle(Lizzie.resourceBundle.getString("Lizzie.alwaysOnTopTitle") + oriTitle);
    else setTitle(oriTitle);
  }

  private void refreshAnalysisView() {
    AnalysisTableModel.RefreshResult result = dataModel.refreshSnapshot();
    if (!result.isChanged()) {
      return;
    }
    if (result.isStructureChanged()) {
      configureTableAppearance();
    }
    repaint();
  }

  private void configureTableAppearance() {
    table.getTableHeader().setFont(headFont);
    table.getTableHeader().setReorderingAllowed(false);
    table.setFont(winrateFont);
    table.setRowHeight(Config.menuHeight);
    TableCellRenderer cellRenderer = new ColorTableCellRenderer();
    table.setDefaultRenderer(Object.class, cellRenderer);
    ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer())
        .setHorizontalAlignment(JLabel.CENTER);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    configureTableColumnWidths();
  }

  private void configureTableColumnWidths() {
    table.getColumnModel().getColumn(0).setPreferredWidth(35);
    table.getColumnModel().getColumn(1).setPreferredWidth(20);
    table.getColumnModel().getColumn(2).setPreferredWidth(40);
    table.getColumnModel().getColumn(3).setPreferredWidth(70);
    table.getColumnModel().getColumn(4).setPreferredWidth(35);
    table.getColumnModel().getColumn(5).setPreferredWidth(50);
    table.getColumnModel().getColumn(6).setPreferredWidth(35);
    if (table.getColumnCount() == EXTENDED_COLUMN_COUNT) {
      table.getColumnModel().getColumn(7).setPreferredWidth(70);
      table.getColumnModel().getColumn(8).setPreferredWidth(35);
    }
  }

  private void paintBottomPanel(Graphics g0, int width, int height) {
    // TODO Auto-generated method stub
    int minHeight = 22;
    int trueHeight = height - minHeight;
    int totalPlayouts = 0;
    int maxPlayouts = 0;
    double stable = 0;
    List<MoveData> bestMoves = null;
    if (index == 1) bestMoves = Lizzie.board.getData().bestMoves;
    else if (index == 2) bestMoves = Lizzie.board.getData().bestMoves2;
    if (bestMoves == null || bestMoves.isEmpty()) return;
    Graphics2D g = (Graphics2D) g0;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    for (MoveData move : bestMoves) {
      totalPlayouts += move.playouts;
      if (move.playouts > maxPlayouts) maxPlayouts = move.playouts;
    }
    int length = bestMoves.size();
    for (int i = 0; i < 11; i++) {
      if (i < length)
        stable +=
            (maxPlayouts - bestMoves.get(i).playouts) * (maxPlayouts - bestMoves.get(i).playouts);
      else stable += maxPlayouts * maxPlayouts;
    }
    stable = stable / 10 / totalPlayouts / totalPlayouts;
    stable = Math.pow(stable, 0.5) * 100;
    g.setColor(Color.BLACK);
    g.setFont(new Font(Lizzie.config.uiFontName, Font.PLAIN, 13));
    g.drawString(
        resourceBundle.getString("AnalysisFrame.totalVisits")
            + Utils.getPlayoutsString(totalPlayouts)
            + " "
            + resourceBundle.getString("AnalysisFrame.maxVisits")
            + Utils.getPlayoutsString(maxPlayouts)
            + " "
            + resourceBundle.getString("AnalysisFrame.concentration")
            + String.format(Locale.ENGLISH, "%.2f", stable)
            + "%",
        5,
        15);
    if (Lizzie.config.showBestMovesGraph)
    // 画横向柱状图,前X选点
    {
      g.setStroke(new BasicStroke(1));
      g.drawLine(0, 20, width, 20);
      int nums = trueHeight / 20;
      for (int i = 0; i < nums; i++) {
        g.drawString(String.valueOf(i + 1), 3, minHeight + i * 20 + 15);
        g.setColor(Color.DARK_GRAY);
        if (i < length) {
          double percents = (double) bestMoves.get(i).playouts / maxPlayouts;
          g.fillRect(20, minHeight + i * 20 + 2, (int) ((width - 80) * percents), 16);
          g.drawString(
              String.format(
                      Locale.ENGLISH,
                      "%.2f",
                      (double) bestMoves.get(i).playouts * 100 / totalPlayouts)
                  + "%",
              26 + (int) ((width - 80) * percents),
              minHeight + i * 20 + 15);
        }
      }
    }
  }

  class ColorTableCellRenderer extends DefaultTableCellRenderer {
    Object mainValue;
    boolean isPlayoutPercents = false;
    boolean isSelect = false;
    boolean isChanged = false;
    boolean isNextMove;
    double diff = 0;
    double scoreDiff = 0;

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      // if(row%2 == 0){
      setHorizontalAlignment(CENTER);
      if (column == 5) {
        isPlayoutPercents = true;
        mainValue = value;
      } else {
        isPlayoutPercents = false;
      }
      if (table.getValueAt(row, 0).toString().length() > 3) {
        isNextMove = true;
        String winrate = table.getValueAt(row, 3).toString();
        if (winrate.contains("("))
          diff = Double.parseDouble(winrate.substring(1, winrate.indexOf("(")));
        else diff = 0;
        if (table.getColumnCount() > 7) {
          String score = table.getValueAt(row, 7).toString();
          if (score.contains("("))
            scoreDiff = Double.parseDouble(score.substring(1, score.indexOf("(")));
          else scoreDiff = 0;
        } else scoreDiff = 0;
      } else isNextMove = false;

      String coordsName =
          Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
      int[] coords = new int[] {-2, -2};
      if (!coordsName.startsWith("pas") && coordsName.length() > 1) {
        coords = Board.convertNameToCoordinates(coordsName);
      }
      if (coords[0] == Lizzie.frame.suggestionclick[0]
          && coords[1] == Lizzie.frame.suggestionclick[1]) {
        if (selectedorder >= 0 && selectedorder != row) {
          currentRow = row;
          // selectedorder = -1;
          isChanged = true;
          // setForeground(Color.RED);
        } else {
          isChanged = false;
        }
        isSelect = true;
        // setBackground(new Color(238, 221, 130));
        return super.getTableCellRendererComponent(table, value, false, false, row, column);

      } else {
        isSelect = false;
        isChanged = false;
        return super.getTableCellRendererComponent(table, value, false, false, row, column);
      }
    }

    @Override
    public void paintComponent(Graphics g) {
      if (isPlayoutPercents) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setColor(Color.LIGHT_GRAY);
        g2.fillRect(
            0,
            0,
            (int) (getWidth() * (Double.parseDouble(mainValue.toString()) / 100)),
            getHeight());

      } else {
        if (isSelect) {
          setForeground(Lizzie.config.useMorandiColors ? MorandiPalette.MUDED_TEAL : Color.BLUE);
          setBackground(new Color(0, 0, 0, 35));
        }
        if (isChanged) {
          setForeground(Lizzie.config.useMorandiColors ? MorandiPalette.MUDED_RED : Color.RED);
        }
        if (isNextMove) {
          if (isSelect) {
            if (diff <= Lizzie.config.winLossThreshold5
                || scoreDiff <= Lizzie.config.scoreLossThreshold5)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BLUNDER_ALPHA
                      : new Color(85, 25, 80, 120));
            else if (diff <= Lizzie.config.winLossThreshold4
                || scoreDiff <= Lizzie.config.scoreLossThreshold4)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_MISTAKE_ALPHA
                      : new Color(208, 16, 19, 100));
            else if (diff <= Lizzie.config.winLossThreshold3
                || scoreDiff <= Lizzie.config.scoreLossThreshold3)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_SLOW_ALPHA
                      : new Color(200, 140, 50, 100));
            else if (diff <= Lizzie.config.winLossThreshold2
                || scoreDiff <= Lizzie.config.scoreLossThreshold2)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_CAUTION_ALPHA
                      : new Color(180, 180, 0, 100));
            else if (diff <= Lizzie.config.winLossThreshold1
                || scoreDiff <= Lizzie.config.scoreLossThreshold1)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_GOOD_ALPHA
                      : new Color(140, 202, 34, 100));
            else
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BEST_ALPHA
                      : new Color(0, 180, 0, 100));
          } else {
            if (diff <= Lizzie.config.winLossThreshold5
                || scoreDiff <= Lizzie.config.scoreLossThreshold5)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BLUNDER_LIGHT
                      : new Color(85, 25, 80, 70));
            else if (diff <= Lizzie.config.winLossThreshold4
                || scoreDiff <= Lizzie.config.scoreLossThreshold4)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_MISTAKE_LIGHT
                      : new Color(208, 16, 19, 50));
            else if (diff <= Lizzie.config.winLossThreshold3
                || scoreDiff <= Lizzie.config.scoreLossThreshold3)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_SLOW_LIGHT
                      : new Color(200, 140, 50, 50));
            else if (diff <= Lizzie.config.winLossThreshold2
                || scoreDiff <= Lizzie.config.scoreLossThreshold2)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_CAUTION_LIGHT
                      : new Color(180, 180, 0, 50));
            else if (diff <= Lizzie.config.winLossThreshold1
                || scoreDiff <= Lizzie.config.scoreLossThreshold1)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_GOOD_LIGHT
                      : new Color(140, 202, 34, 50));
            else
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BEST_LIGHT
                      : new Color(0, 180, 0, 60));
          }
        } else if (!isSelect && !isChanged) {
          setForeground(Color.BLACK);
          setBackground(Color.WHITE);
        }
      }
      super.paintComponent(g);
    }
  }

  private void handleTableClick(int row, int col) {
    LizzieFrame.boardRenderer.startNormalBoard();
    if (table.getValueAt(row, 1).toString().startsWith("pass")) return;
    String coordsName = Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
    int[] coords = Board.convertNameToCoordinates(coordsName);
    if (clickOrder != -1
        && selectedorder >= 0
        && coords[0] == Lizzie.frame.suggestionclick[0]
        && coords[1] == Lizzie.frame.suggestionclick[1]) {
      Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      LizzieFrame.boardRenderer.clearBranch();
      selectedorder = -1;
      clickOrder = -1;
      currentRow = -1;
      Lizzie.frame.refresh();
    } else {

      clickOrder = row;
      selectedorder = row;
      currentRow = row;
      // int[] coords = Board.convertNameToCoordinates(table.getValueAt(row, 1).toString());
      Lizzie.frame.mouseOverCoordinate = coords;
      Lizzie.frame.suggestionclick = coords;
      Lizzie.frame.refresh();
    }
  }

  private void handleTableRightClick(int row, int col) {
    if (table.getValueAt(row, 1).toString().startsWith("pass")) return;
    if (selectedorder != row) {
      String coordsName =
          Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
      int[] coords = Board.convertNameToCoordinates(coordsName);
      Lizzie.frame.suggestionclick = coords;
      Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame.refresh();
      selectedorder = row;
    } else {
      Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame.refresh();
      selectedorder = -1;
    }
  }

  public AnalysisTableModel getTableModel() {
    return new AnalysisTableModel();
  }

  class AnalysisTableModel extends AbstractTableModel {
    private TableSnapshot snapshot = buildSnapshot();

    RefreshResult refreshSnapshot() {
      TableSnapshot nextSnapshot = buildSnapshot();
      if (snapshot.equals(nextSnapshot)) {
        return RefreshResult.UNCHANGED;
      }
      boolean structureChanged = snapshot.getColumnCount() != nextSnapshot.getColumnCount();
      snapshot = nextSnapshot;
      if (structureChanged) {
        fireTableStructureChanged();
        return RefreshResult.STRUCTURE_CHANGED;
      }
      fireTableDataChanged();
      return RefreshResult.DATA_CHANGED;
    }

    @Override
    public int getColumnCount() {
      return snapshot.getColumnCount();
    }

    @Override
    public int getRowCount() {
      return snapshot.getRows().size();
    }

    @Override
    public String getColumnName(int column) {
      if (column == 0) return resourceBundle.getString("AnalysisFrame.column1");
      if (column == 1) return resourceBundle.getString("AnalysisFrame.column2");
      if (column == 2) return resourceBundle.getString("AnalysisFrame.column3");
      if (column == 3) return resourceBundle.getString("AnalysisFrame.column4");
      if (column == 4) return resourceBundle.getString("AnalysisFrame.column5");
      if (column == 5) return resourceBundle.getString("AnalysisFrame.column6");
      if (column == 6) return resourceBundle.getString("AnalysisFrame.column7");
      if (column == 7) return resourceBundle.getString("AnalysisFrame.column8");
      if (column == 8) return resourceBundle.getString("AnalysisFrame.column9");
      return "";
    }

    @Override
    public Object getValueAt(int row, int col) {
      RowSnapshot data = snapshot.getRows().get(row);
      switch (col) {
        case 0:
          if (data.order == -100) {
            return "\n" + resourceBundle.getString("AnalysisFrame.actual") + "\n";
          }
          if (data.isNextMove) {
            return data.order + 1 + "(" + resourceBundle.getString("AnalysisFrame.actual") + ")";
          }
          return data.order + 1;
        case 1:
          return Board.maybeConvertNormalCoordsToOther(data.coordinate);
        case 2:
          if (data.isNextMove && data.lcb < -1000) return "--";
          return String.format(Locale.ENGLISH, "%.1f", data.lcb);
        case 3:
          if (data.isNextMove && data.order != 0) {
            double diff = data.winrate - data.bestWinrate;
            return (diff > 0 ? "↑" : "↓")
                + String.format(Locale.ENGLISH, "%.1f", diff)
                + "("
                + String.format(Locale.ENGLISH, "%.1f", data.winrate)
                + ")";
          }
          return String.format(Locale.ENGLISH, "%.1f", data.winrate);
        case 4:
          if (data.order == -100) return resourceBundle.getString("AnalysisFrame.exclude");
          return Utils.getPlayoutsString(data.playouts);
        case 5:
          return String.format(
              Locale.ENGLISH, "%.1f", (double) data.playouts * 100 / snapshot.getTotalPlayouts());
        case 6:
          if (data.isNextMove && data.policy < -1000) return "--";
          return String.format(Locale.ENGLISH, "%.2f", data.policy);
        case 7:
          double score = snapshot.adjustScore(data.scoreMean);
          if (data.isNextMove && data.order != 0) {
            double diff = data.scoreMean - data.bestScoreMean;
            return (diff > 0 ? "↑" : "↓")
                + String.format(Locale.ENGLISH, "%.1f", diff)
                + "("
                + String.format(Locale.ENGLISH, "%.1f", score)
                + ")";
          }
          return String.format(Locale.ENGLISH, "%.1f", score);
        case 8:
          return String.format(Locale.ENGLISH, "%.1f", data.scoreStdev);
        default:
          return "";
      }
    }

    private TableSnapshot buildSnapshot() {
      int columnCount = determineColumnCount();
      ScoreDisplayState scoreState =
          new ScoreDisplayState(
              Lizzie.board.getHistory().isBlacksTurn(),
              EngineManager.isEngineGame && EngineManager.engineGameInfo.isGenmove,
              Lizzie.config.showKataGoScoreLeadWithKomi,
              Lizzie.board.getData().getKomi());
      if (Lizzie.frame.isInPlayMode()) {
        return TableSnapshot.fromMoves(new ArrayList<MoveData>(), sortnum, columnCount, scoreState);
      }
      return TableSnapshot.fromMoves(collectMoves(), sortnum, columnCount, scoreState);
    }

    private int determineColumnCount() {
      Leelaz leelaz = index == 1 ? Lizzie.leelaz : Lizzie.leelaz2;
      if (leelaz != null && (leelaz.isKatago || leelaz.isSai)) {
        return EXTENDED_COLUMN_COUNT;
      }
      boolean containsKataData =
          index == 1 ? Lizzie.board.isContainsKataData() : Lizzie.board.isContainsKataData2();
      return containsKataData ? EXTENDED_COLUMN_COUNT : STANDARD_COLUMN_COUNT;
    }

    private List<MoveData> collectMoves() {
      List<MoveData> moves = copyMoves(resolveBestMoves());
      addNextMoveIfNeeded(moves);
      return moves;
    }

    private List<MoveData> resolveBestMoves() {
      if (index == 1) {
        return resolveMainEngineBestMoves();
      }
      return Lizzie.board.getData().bestMoves2;
    }

    private List<MoveData> resolveMainEngineBestMoves() {
      if (EngineManager.isEngineGame && Lizzie.config.showPreviousBestmovesInEngineGame) {
        if (Lizzie.board.getHistory().getCurrentHistoryNode().previous().isPresent()) {
          List<MoveData> currentBestMoves = Lizzie.leelaz.getBestMoves();
          if (currentBestMoves.isEmpty()) {
            return Lizzie.board
                .getHistory()
                .getCurrentHistoryNode()
                .previous()
                .get()
                .getData()
                .bestMoves;
          }
          return currentBestMoves;
        }
        return null;
      }
      return Lizzie.board.getHistory().getCurrentHistoryNode().getData().bestMoves;
    }

    private void addNextMoveIfNeeded(List<MoveData> moves) {
      if (!Lizzie.config.anaFrameShowNext
          || !Lizzie.board.getHistory().getCurrentHistoryNode().next().isPresent()
          || moves.isEmpty()) {
        return;
      }
      BoardHistoryNode next = Lizzie.board.getHistory().getCurrentHistoryNode().next().get();
      if (!next.getData().lastMove.isPresent()) {
        return;
      }
      int[] coords = next.getData().lastMove.get();
      MoveData matchingMove = findMatchingMove(moves, coords);
      if (matchingMove == null) {
        prependMissingNextMove(moves, next, coords);
        return;
      }
      if (matchingMove.order == 0) {
        markTopMoveAsNext(matchingMove, moves.get(0));
        return;
      }
      if (!prependUpgradedNextMove(moves, matchingMove, next, coords)) {
        moves.add(0, createCopiedNextMove(matchingMove, moves.get(0)));
      }
    }

    private MoveData findMatchingMove(List<MoveData> moves, int[] coords) {
      for (MoveData move : moves) {
        int[] moveCoords = Board.convertNameToCoordinates(move.coordinate);
        if (moveCoords[0] == coords[0] && moveCoords[1] == coords[1]) {
          return move;
        }
      }
      return null;
    }

    private void markTopMoveAsNext(MoveData move, MoveData bestMove) {
      move.winrate = bestMove.winrate;
      move.isNextMove = true;
      move.bestWinrate = bestMove.winrate;
      move.bestScoreMean = bestMove.scoreMean;
    }

    private boolean prependUpgradedNextMove(
        List<MoveData> moves, MoveData matchingMove, BoardHistoryNode next, int[] coords) {
      MoveData bestMove = moves.get(0);
      if (index == 1
          && !next.getData().bestMoves.isEmpty()
          && next.getData().getPlayouts() > matchingMove.playouts) {
        moves.add(0, createUpdatedNextMove(next, coords, matchingMove, bestMove));
        return true;
      }
      if (index == 2
          && !next.getData().bestMoves2.isEmpty()
          && next.getData().getPlayouts2() > matchingMove.playouts) {
        moves.add(0, createUpdatedNextMove(next, coords, matchingMove, bestMove));
        return true;
      }
      return false;
    }

    private void prependMissingNextMove(List<MoveData> moves, BoardHistoryNode next, int[] coords) {
      if (index == 1 && !next.getData().bestMoves.isEmpty()) {
        moves.add(0, createMissingNextMove(next, coords, moves.get(0)));
      } else if (index == 2 && !next.getData().bestMoves2.isEmpty()) {
        moves.add(0, createMissingNextMove(next, coords, moves.get(0)));
      }
    }

    enum RefreshResult {
      UNCHANGED(false, false),
      DATA_CHANGED(true, false),
      STRUCTURE_CHANGED(true, true);

      private final boolean changed;
      private final boolean structureChanged;

      RefreshResult(boolean changed, boolean structureChanged) {
        this.changed = changed;
        this.structureChanged = structureChanged;
      }

      boolean isChanged() {
        return changed;
      }

      boolean isStructureChanged() {
        return structureChanged;
      }
    }
  }

  private static List<MoveData> copyMoves(List<MoveData> bestMoves) {
    List<MoveData> copies = new ArrayList<MoveData>();
    if (bestMoves == null) {
      return copies;
    }
    for (MoveData move : bestMoves) {
      copies.add(copyMove(move));
    }
    return copies;
  }

  private static MoveData copyMove(MoveData move) {
    MoveData copy = new MoveData();
    copy.coordinate = move.coordinate;
    copy.playouts = move.playouts;
    copy.winrate = move.winrate;
    copy.lcb = move.lcb;
    copy.policy = move.policy;
    copy.scoreMean = move.scoreMean;
    copy.scoreStdev = move.scoreStdev;
    copy.order = move.order;
    copy.isNextMove = move.isNextMove;
    copy.bestWinrate = move.bestWinrate;
    copy.bestScoreMean = move.bestScoreMean;
    return copy;
  }

  private MoveData createCopiedNextMove(MoveData move, MoveData bestMove) {
    MoveData nextMove = copyMove(move);
    nextMove.isNextMove = true;
    nextMove.bestWinrate = bestMove.winrate;
    nextMove.bestScoreMean = bestMove.scoreMean;
    return nextMove;
  }

  private MoveData createUpdatedNextMove(
      BoardHistoryNode next, int[] coords, MoveData move, MoveData bestMove) {
    MoveData nextMove = new MoveData();
    nextMove.playouts = index == 1 ? next.getData().getPlayouts() : next.getData().getPlayouts2();
    nextMove.coordinate = Board.convertCoordinatesToName(coords[0], coords[1]);
    nextMove.winrate =
        index == 1 ? 100.0 - next.getData().winrate : 100.0 - next.getData().winrate2;
    nextMove.policy = move.policy;
    nextMove.scoreMean = index == 1 ? -next.getData().scoreMean : -next.getData().scoreMean2;
    nextMove.scoreStdev = index == 1 ? next.getData().scoreStdev : next.getData().scoreStdev2;
    nextMove.order = move.order;
    nextMove.isNextMove = true;
    nextMove.lcb = -10000;
    nextMove.bestWinrate = bestMove.winrate;
    nextMove.bestScoreMean = bestMove.scoreMean;
    return nextMove;
  }

  private MoveData createMissingNextMove(BoardHistoryNode next, int[] coords, MoveData bestMove) {
    MoveData nextMove = new MoveData();
    nextMove.playouts = 0;
    nextMove.coordinate = Board.convertCoordinatesToName(coords[0], coords[1]);
    nextMove.winrate =
        index == 1 ? 100.0 - next.getData().winrate : 100.0 - next.getData().winrate2;
    nextMove.policy = -10000;
    nextMove.scoreMean = index == 1 ? -next.getData().scoreMean : -next.getData().scoreMean2;
    nextMove.scoreStdev = index == 1 ? next.getData().scoreStdev : next.getData().scoreStdev2;
    nextMove.order = -100;
    nextMove.isNextMove = true;
    nextMove.lcb = -10000;
    nextMove.bestWinrate = bestMove.winrate;
    nextMove.bestScoreMean = bestMove.scoreMean;
    return nextMove;
  }

  static final class TableSnapshot {
    private final int columnCount;
    private final List<RowSnapshot> rows;
    private final int totalPlayouts;
    private final ScoreDisplayState scoreState;

    static TableSnapshot fromMoves(
        List<MoveData> moves, int sortColumn, int columnCount, ScoreDisplayState scoreState) {
      List<RowSnapshot> rows = new ArrayList<RowSnapshot>();
      for (MoveData move : moves) {
        rows.add(RowSnapshot.fromMove(move));
      }
      if (sortColumn != -1) {
        rows.sort((left, right) -> compareRows(left, right, sortColumn));
      }
      return new TableSnapshot(columnCount, rows, calculateTotalPlayouts(rows), scoreState);
    }

    static TableSnapshot fromMoves(
        List<MoveData> moves,
        int sortColumn,
        int columnCount,
        boolean blacksTurn,
        boolean engineGameGenmove,
        boolean showScoreLeadWithKomi,
        double komi) {
      return fromMoves(
          moves,
          sortColumn,
          columnCount,
          new ScoreDisplayState(blacksTurn, engineGameGenmove, showScoreLeadWithKomi, komi));
    }

    private TableSnapshot(
        int columnCount, List<RowSnapshot> rows, int totalPlayouts, ScoreDisplayState scoreState) {
      this.columnCount = columnCount;
      this.rows = List.copyOf(rows);
      this.totalPlayouts = totalPlayouts;
      this.scoreState = scoreState;
    }

    int getColumnCount() {
      return columnCount;
    }

    List<RowSnapshot> getRows() {
      return rows;
    }

    int getTotalPlayouts() {
      return totalPlayouts;
    }

    double adjustScore(double scoreMean) {
      return scoreState.adjustScore(scoreMean);
    }

    private static int calculateTotalPlayouts(List<RowSnapshot> rows) {
      int total = 0;
      for (RowSnapshot row : rows) {
        total += row.playouts;
      }
      return total;
    }

    private static int compareRows(RowSnapshot left, RowSnapshot right, int sortColumn) {
      if (sortColumn == 2) return Double.compare(right.lcb, left.lcb);
      if (sortColumn == 3) return Double.compare(right.winrate, left.winrate);
      if (sortColumn == 4 || sortColumn == 5) return Integer.compare(right.playouts, left.playouts);
      if (sortColumn == 6) return Double.compare(right.policy, left.policy);
      if (sortColumn == 7) return Double.compare(right.scoreMean, left.scoreMean);
      if (sortColumn == 8) return Double.compare(right.scoreStdev, left.scoreStdev);
      return 0;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof TableSnapshot)) return false;
      TableSnapshot that = (TableSnapshot) other;
      return columnCount == that.columnCount
          && totalPlayouts == that.totalPlayouts
          && rows.equals(that.rows)
          && scoreState.equals(that.scoreState);
    }

    @Override
    public int hashCode() {
      return Objects.hash(columnCount, rows, totalPlayouts, scoreState);
    }
  }

  static final class RowSnapshot {
    final String coordinate;
    final int playouts;
    final double winrate;
    final double lcb;
    final double policy;
    final double scoreMean;
    final double scoreStdev;
    final int order;
    final boolean isNextMove;
    final double bestWinrate;
    final double bestScoreMean;

    private RowSnapshot(
        String coordinate,
        int playouts,
        double winrate,
        double lcb,
        double policy,
        double scoreMean,
        double scoreStdev,
        int order,
        boolean isNextMove,
        double bestWinrate,
        double bestScoreMean) {
      this.coordinate = coordinate;
      this.playouts = playouts;
      this.winrate = winrate;
      this.lcb = lcb;
      this.policy = policy;
      this.scoreMean = scoreMean;
      this.scoreStdev = scoreStdev;
      this.order = order;
      this.isNextMove = isNextMove;
      this.bestWinrate = bestWinrate;
      this.bestScoreMean = bestScoreMean;
    }

    static RowSnapshot fromMove(MoveData move) {
      return new RowSnapshot(
          move.coordinate,
          move.playouts,
          move.winrate,
          move.lcb,
          move.policy,
          move.scoreMean,
          move.scoreStdev,
          move.order,
          move.isNextMove,
          move.bestWinrate,
          move.bestScoreMean);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof RowSnapshot)) return false;
      RowSnapshot that = (RowSnapshot) other;
      return playouts == that.playouts
          && Double.compare(winrate, that.winrate) == 0
          && Double.compare(lcb, that.lcb) == 0
          && Double.compare(policy, that.policy) == 0
          && Double.compare(scoreMean, that.scoreMean) == 0
          && Double.compare(scoreStdev, that.scoreStdev) == 0
          && order == that.order
          && isNextMove == that.isNextMove
          && Double.compare(bestWinrate, that.bestWinrate) == 0
          && Double.compare(bestScoreMean, that.bestScoreMean) == 0
          && Objects.equals(coordinate, that.coordinate);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          coordinate,
          playouts,
          winrate,
          lcb,
          policy,
          scoreMean,
          scoreStdev,
          order,
          isNextMove,
          bestWinrate,
          bestScoreMean);
    }
  }

  static final class ScoreDisplayState {
    private final boolean blacksTurn;
    private final boolean engineGameGenmove;
    private final boolean showScoreLeadWithKomi;
    private final double komi;

    private ScoreDisplayState(
        boolean blacksTurn, boolean engineGameGenmove, boolean showScoreLeadWithKomi, double komi) {
      this.blacksTurn = blacksTurn;
      this.engineGameGenmove = engineGameGenmove;
      this.showScoreLeadWithKomi = showScoreLeadWithKomi;
      this.komi = komi;
    }

    double adjustScore(double scoreMean) {
      if (!showScoreLeadWithKomi) {
        return scoreMean;
      }
      boolean addKomi = engineGameGenmove ? !blacksTurn : blacksTurn;
      return addKomi ? scoreMean + komi : scoreMean - komi;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof ScoreDisplayState)) return false;
      ScoreDisplayState that = (ScoreDisplayState) other;
      return blacksTurn == that.blacksTurn
          && engineGameGenmove == that.engineGameGenmove
          && showScoreLeadWithKomi == that.showScoreLeadWithKomi
          && Double.compare(komi, that.komi) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(blacksTurn, engineGameGenmove, showScoreLeadWithKomi, komi);
    }
  }
}
