package featurecat.lizzie.gui;

import static java.awt.RenderingHints.KEY_ANTIALIASING;
import static java.awt.RenderingHints.VALUE_ANTIALIAS_ON;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

public class DrawPainting extends JDialog {
  private static final BasicStroke PEN_STROKE = new BasicStroke(3);
  private BufferedImage cachedImage;
  private boolean canvasDirty = true;
  private List<DrawPoint> list = new ArrayList<DrawPoint>();
  private List<List<DrawPoint>> drawlist = new ArrayList<List<DrawPoint>>();
  private JPanel mainPanel;
  private JPanel btnPanel;
  private int totalWidth, totalHeight;
  private int startX, startY;

  public enum PEN_COLOR {
    BLUEPEN,
    GREENPEN,
    REDPEN;
  }

  private PEN_COLOR colorIndex = PEN_COLOR.BLUEPEN; // 0=蓝
  private Color backgroundColor = new Color(255, 255, 255, 1);
  private JButton btnRed;
  private JButton btnBlue;
  private JButton btnGreen;
  private ImageIcon iconGreen;
  private ImageIcon iconRed;
  private ImageIcon iconBlue;
  private ImageIcon iconGreen2;
  private ImageIcon iconRed2;
  private ImageIcon iconBlue2;

  public DrawPainting(int x, int y, int width, int height) {
    totalWidth = width;
    totalHeight = height;
    startX = x;
    startY = y;
    setSize(totalWidth, totalHeight);
    setUndecorated(true);
    setBackground(backgroundColor);
    setResizable(false);
    setLocation(startX, startY);
    switch (Lizzie.config.lastPaintingColor) {
      case 0:
        colorIndex = PEN_COLOR.BLUEPEN;
        break;
      case 1:
        colorIndex = PEN_COLOR.GREENPEN;
        break;
      case 2:
        colorIndex = PEN_COLOR.REDPEN;
        break;
    }

    Toolkit tk = Toolkit.getDefaultToolkit();
    try {
      Image image = ImageIO.read(getClass().getResourceAsStream("/assets/paint2.png"));
      Cursor cursor = tk.createCustomCursor(image, new Point(0, 0), "norm");
      this.setCursor(cursor);
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }

    mainPanel = new JPanel();
    mainPanel.setBackground(new Color(0, 0, 0, 0));
    mainPanel.enableInputMethods(false);
    getContentPane().enableInputMethods(false);
    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(mainPanel, BorderLayout.CENTER);

    addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
              saveLastColor();
              setVisible(false);
              dispose();
            }
          }
        });

    addMouseListener(
        new MouseAdapter() {
          public void mouseReleased(MouseEvent e) {
            if (e.getButton() == MouseEvent.BUTTON1) // left click
            {
              if (list.size() > 0) {
                drawlist.add(list);
                list = new ArrayList<DrawPoint>();
              }
            }
          }
        });

    addMouseMotionListener(
        new MouseMotionListener() {
          @Override
          public void mouseDragged(MouseEvent e) {
            DrawPoint p1 = new DrawPoint((e.getX()), (e.getY()), colorIndex);
            list.add(p1);
            drawLatestSegment();
          }

          @Override
          public void mouseMoved(MouseEvent arg0) {}
        });
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            rebuildCanvas();
          }
        });
    btnPanel = new JPanel();
    btnPanel.setBackground(new Color(0, 0, 0, 0));
    btnPanel.setLayout(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    getContentPane().add(btnPanel, BorderLayout.NORTH);

    ImageIcon iconClose = new ImageIcon();
    ImageIcon iconRevoke = new ImageIcon();
    ImageIcon iconEmpty = new ImageIcon();
    iconGreen = new ImageIcon();
    iconRed = new ImageIcon();
    iconBlue = new ImageIcon();
    iconGreen2 = new ImageIcon();
    iconRed2 = new ImageIcon();
    iconBlue2 = new ImageIcon();
    try {
      iconClose.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/drclose.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconRevoke.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/backmain.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconRed.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/red.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconRed2.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/red2.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconGreen.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/green.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconGreen2.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/green2.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconBlue.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/blue.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconBlue2.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/blue2.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));
      iconEmpty.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/drempty.png"))
              .getScaledInstance(
                  Config.menuIconSize * 2, Config.menuIconSize * 2, java.awt.Image.SCALE_SMOOTH));

    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    btnBlue = new JButton(iconBlue);
    btnBlue.setFocusable(false);
    btnBlue.setBorder(new EmptyBorder(5, 5, 5, 5));
    btnPanel.add(btnBlue);
    btnBlue.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            colorIndex = PEN_COLOR.BLUEPEN;
            setColorButton();
          }
        });

    btnGreen = new JButton(iconGreen);
    btnGreen.setFocusable(false);
    btnGreen.setBorder(new EmptyBorder(5, 5, 5, 5));
    btnPanel.add(btnGreen);
    btnGreen.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            colorIndex = PEN_COLOR.GREENPEN;
            setColorButton();
          }
        });

    btnRed = new JButton(iconRed);
    btnRed.setFocusable(false);
    btnRed.setBorder(new EmptyBorder(5, 5, 5, 5));
    btnPanel.add(btnRed);
    btnRed.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            colorIndex = PEN_COLOR.REDPEN;
            setColorButton();
          }
        });
    setColorButton();

    JButton btnRevoke = new JButton(iconRevoke);
    btnRevoke.setFocusable(false);
    btnRevoke.setBorder(new EmptyBorder(5, 5, 5, 5));
    btnPanel.add(btnRevoke);
    btnRevoke.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            if (drawlist.size() > 0) {
              drawlist.remove(drawlist.size() - 1);
              rebuildCanvas();
            }
          }
        });

    JButton btnEmpty = new JButton(iconEmpty);
    btnEmpty.setFocusable(false);
    btnEmpty.setBorder(new EmptyBorder(5, 5, 5, 5));
    btnPanel.add(btnEmpty);
    btnEmpty.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            list.clear();
            drawlist.clear();
            rebuildCanvas();
          }
        });

    JButton btnExit = new JButton(iconClose);
    btnExit.setToolTipText("ESC");
    btnExit.setFocusable(false);
    btnExit.setBorder(new EmptyBorder(5, 5, 5, 5));
    btnPanel.add(btnExit);
    btnExit.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            saveLastColor();
            setVisible(false);
            dispose();
          }
        });
    rebuildCanvas();
  }

  protected void saveLastColor() {
    // TODO Auto-generated method stub
    switch (colorIndex) {
      case BLUEPEN:
        Lizzie.config.lastPaintingColor = 0;
        break;
      case GREENPEN:
        Lizzie.config.lastPaintingColor = 1;
        break;
      case REDPEN:
        Lizzie.config.lastPaintingColor = 2;
        break;
    }
    Lizzie.config.uiConfig.put("last-painting-color", Lizzie.config.lastPaintingColor);
  }

  protected void setColorButton() {
    // TODO Auto-generated method stub
    switch (colorIndex) {
      case BLUEPEN:
        btnBlue.setIcon(iconBlue2);
        btnGreen.setIcon(iconGreen);
        btnRed.setIcon(iconRed);
        break;
      case GREENPEN:
        btnBlue.setIcon(iconBlue);
        btnGreen.setIcon(iconGreen2);
        btnRed.setIcon(iconRed);
        break;
      case REDPEN:
        btnBlue.setIcon(iconBlue);
        btnGreen.setIcon(iconGreen);
        btnRed.setIcon(iconRed2);
        break;
    }
  }

  private void drawLatestSegment() {
    ensureCanvasSize();
    if (canvasDirty) {
      redrawCanvasImage();
      repaint();
      return;
    }
    if (cachedImage == null || list.size() < 2) {
      repaint();
      return;
    }
    Graphics2D g0 = createCanvasGraphics();
    drawSegment(g0, list.get(list.size() - 2), list.get(list.size() - 1));
    g0.dispose();
    repaint();
  }

  private void rebuildCanvas() {
    ensureCanvasSize();
    if (cachedImage == null) return;
    redrawCanvasImage();
    repaint();
  }

  private void redrawCanvasImage() {
    if (cachedImage == null) return;
    Graphics2D g0 = createCanvasGraphics();
    clearCanvas(g0);
    for (List<DrawPoint> stroke : drawlist) {
      drawStroke(g0, stroke);
    }
    drawStroke(g0, list);
    g0.dispose();
    canvasDirty = false;
  }

  private void ensureCanvasSize() {
    int canvasWidth = Math.max(1, Utils.zoomOut(getWidth()));
    int canvasHeight = Math.max(1, Utils.zoomOut(getHeight()));
    if (cachedImage != null
        && cachedImage.getWidth() == canvasWidth
        && cachedImage.getHeight() == canvasHeight) {
      return;
    }
    cachedImage = new BufferedImage(canvasWidth, canvasHeight, TYPE_INT_ARGB);
    canvasDirty = true;
  }

  private Graphics2D createCanvasGraphics() {
    Graphics2D g0 = (Graphics2D) cachedImage.getGraphics();
    g0.setBackground(backgroundColor);
    g0.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
    g0.setStroke(PEN_STROKE);
    return g0;
  }

  private void clearCanvas(Graphics2D g0) {
    g0.clearRect(0, 0, cachedImage.getWidth(), cachedImage.getHeight());
  }

  private void drawStroke(Graphics2D g0, List<DrawPoint> stroke) {
    for (int i = 1; i < stroke.size(); i++) {
      drawSegment(g0, stroke.get(i - 1), stroke.get(i));
    }
  }

  private void drawSegment(Graphics2D g0, DrawPoint start, DrawPoint end) {
    setColor(end.color, g0);
    g0.drawLine(end.x, end.y, start.x, start.y);
  }

  private void setColor(PEN_COLOR color, Graphics2D g0) {
    // TODO Auto-generated method stub
    switch (color) {
      case BLUEPEN:
        g0.setColor(Color.BLUE);
        break;
      case GREENPEN:
        g0.setColor(Color.GREEN);
        break;
      case REDPEN:
        g0.setColor(Color.RED);
        break;
    }
  }

  @Override
  public void paint(Graphics g) {
    ensureCanvasSize();
    if (canvasDirty) {
      redrawCanvasImage();
    }
    super.paint(g);
    if (cachedImage != null) {
      Graphics2D g2 = (Graphics2D) g.create();
      int clipTop = btnPanel == null ? 0 : btnPanel.getHeight();
      g2.clipRect(0, clipTop, getWidth(), Math.max(0, getHeight() - clipTop));
      g2.drawImage(cachedImage, 0, 0, this);
      g2.dispose();
    }
  }

  public class DrawPoint {
    public int x, y;
    public PEN_COLOR color;

    public DrawPoint(int x, int y, PEN_COLOR color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
  }
}
