package featurecat.lizzie.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.Locale;
import java.util.ResourceBundle;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** These fixtures use the production layout on the EDT; no engine or network is started. */
class KataGoAccelerationLayoutTest {
  private static final Font FONT = new Font(Font.DIALOG, Font.PLAIN, 14);

  @Test
  void statusRowsKeepTheirSharedTitleColumn() throws Exception {
    onEdt(() -> {
      JTextArea first = status("Ready");
      JTextArea second = status("HumanSL CUDA companion is missing");
      JPanel rows = new JPanel(new GridLayout(2, 1, 0, 7));
      rows.add(KataGoAccelerationLayout.statusRow(title("GPU", 132), first));
      rows.add(KataGoAccelerationLayout.statusRow(title("HumanSL CUDA companion", 190), second));
      rows.setSize(740, 150);
      layout(rows);
      equal(200, first.getX(), "first status uses shared title width");
      equal(first.getX(), second.getX(), "status columns remain aligned");
    });
  }

  @Test
  void narrowStatusRowUsesTheFullWidthInsteadOfA24PixelValueColumn() throws Exception {
    onEdt(() -> {
      JTextArea status = status("TensorRT acceleration requires Windows NVIDIA components.\n"
          + "Missing: NVIDIA GPU, runtime, HumanSL CUDA companion, TensorRT engine, weight, GTP config.");
      JLabel title = title("TensorRT profile", 187);
      JPanel row = KataGoAccelerationLayout.statusRow(title, status);
      row.setSize(221, 1500);
      layout(row);
      equal(0, status.getX(), "narrow status starts at the left edge");
      equal(221, status.getWidth(), "status uses the full visible row width");
      check(status.getY() >= title.getHeight(), "title and status do not overlap");
      assertTextFits(status);
    });
  }

  @Test
  void statusRowReturnsToColumnsAfterGrowing() throws Exception {
    onEdt(() -> {
      JTextArea status = status("C:\\engines\\" + "long-directory-".repeat(12) + "\\katago.exe");
      JPanel row = KataGoAccelerationLayout.statusRow(title("TensorRT profile", 190), status);
      row.setSize(221, 2000);
      layout(row);
      int narrow = row.getPreferredSize().height;
      row.setSize(740, 2000);
      layout(row);
      equal(200, status.getX(), "growing restores the title column");
      equal(0, status.getY(), "growing restores inline status");
      check(row.getPreferredSize().height < narrow, "growing releases wrapped height");
      assertTextFits(status);
      row.setSize(221, 2000);
      layout(row);
      equal(narrow, row.getPreferredSize().height, "second shrink is stable");
      assertTextFits(status);
    });
  }

  @Test
  void actualLocalizedExperimentalButtonSizeTracksBothLabels() throws Exception {
    onEdt(() -> {
      JFontButton install = new JFontButton("Install and enable");
      JPanel group = KataGoAccelerationLayout.experimentalActions(new JComboBox<>(), install);
      install.setPreferredSize(KataGoAutoSetupDialog.localizedButtonSize(install, 90, 32));
      int initial = install.getPreferredSize().width;
      install.setText("Enable installed backend");
      Dimension longer = KataGoAutoSetupDialog.localizedButtonSize(install, 90, 32);
      install.setPreferredSize(longer);
      install.setMinimumSize(longer);
      group.setSize(longer.width + 20, 100);
      layout(group);
      check(longer.width > initial, "real label measurement grows the application button");
      equal(longer.width, install.getWidth(), "layout honors the remeasured button");
      equal(install.getText(), install.getAccessibleContext().getAccessibleName(), "button remains named");
    });
  }

  @Test
  void allEightResourceBundlesKeepNarrowStatusAndHintsVisible() throws Exception {
    onEdt(() -> {
      Locale[] locales = {Locale.ROOT, Locale.US, Locale.JAPAN, Locale.KOREAN,
          Locale.forLanguageTag("th-TH"), Locale.CHINA, Locale.forLanguageTag("zh-HK"), Locale.TAIWAN};
      for (Locale locale : locales) {
        ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
        JTextArea text = status(bundle.getString("AutoSetup.accelerationTensorRtHint"));
        JPanel row = KataGoAccelerationLayout.statusRow(
            title(bundle.getString("AutoSetup.tensorRtActivationStatus"), 230), text);
        for (int width : new int[] {260, 740, 260}) {
          row.setSize(width, 4000);
          layout(row);
          assertTextFits(text);
          assertInside(row, text);
        }
        check(!bundle.getString("AutoSetup.maintenanceActions").isBlank(), "maintenance title is translated");
      }
    });
  }

  @Test
  void wrappedHeightIncludesSwingsCaretMarginAtLineBreakBoundaries() throws Exception {
    onEdt(() -> {
      JTextArea hint = KataGoAccelerationLayout.hint(
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US)
              .getString("AutoSetup.accelerationTensorRtHint"), FONT, Color.DARK_GRAY);
      for (int caretWidth : new int[] {1, 3}) {
        hint.putClientProperty("caretWidth", caretWidth);
        for (int width = 120; width <= 360; width++) {
          hint.setSize(width, 5000);
          int height = hint.getPreferredSize().height;
          hint.setSize(width, height);
          assertTextFits(hint);
        }
      }
    });
  }

  private static JLabel title(String text, int width) {
    JLabel title = new JLabel(text);
    title.setFont(FONT);
    title.setPreferredSize(new Dimension(width, 32));
    return title;
  }

  private static void assertTextFits(JTextArea text) {
    try {
      java.awt.geom.Rectangle2D last = text.modelToView2D(text.getDocument().getLength());
      check(last != null && last.getMaxY() <= text.getHeight(), "actual final text line is visible");
      check(last.getMaxX() <= text.getWidth(), "actual final text line stays within width");
    } catch (javax.swing.text.BadLocationException error) {
      throw new AssertionError(error);
    }
  }

  @Test
  void primaryActionsAreAdjacentAndLeftAligned() throws Exception {
    onEdt(() -> {
      JButton repair = button("修复 TensorRT", 120);
      JButton enable = button("启用 TensorRT 加速", 160);
      JPanel row = KataGoAccelerationLayout.primaryActions(repair, enable);
      row.setSize(800, 100);
      row.doLayout();
      equal(0, repair.getX(), "left aligned");
      equal(repair.getY(), enable.getY(), "same row");
      equal(128, enable.getX(), "eight-pixel gap");
      equal(32, row.getPreferredSize().height, "single-row height");
    });
  }

  @Test
  void primaryActionsWrapWhenNarrow() throws Exception {
    onEdt(() -> {
      JButton repair = button("修复 TensorRT", 120);
      JButton enable = button("启用 TensorRT 加速", 160);
      JPanel row = KataGoAccelerationLayout.primaryActions(repair, enable);
      row.setSize(180, 100);
      row.doLayout();
      check(enable.getY() > repair.getY(), "narrow primary actions wrap");
      assertInside(row, repair, enable);
      equal(72, row.getPreferredSize().height, "height includes both rows");
    });
  }

  @Test
  void maintenanceActionsAreSeparateFromPrimary() throws Exception {
    onEdt(() -> {
      JPanel primary = KataGoAccelerationLayout.primaryActions(button("repair", 120), button("enable", 140));
      JPanel maintenance = maintenance();
      equal(2, primary.getComponentCount(), "only repair and enable");
      equal(3, maintenance.getComponentCount(), "only maintenance actions");
      maintenance.setSize(800, 100);
      maintenance.doLayout();
      for (Component item : maintenance.getComponents()) {
        equal(0, item.getY(), "wide maintenance group stays on one row");
      }
    });
  }

  @Test
  void maintenanceActionsWrapWithoutLeavingTheirGroup() throws Exception {
    onEdt(() -> {
      JPanel row = maintenance();
      row.setSize(200, 200);
      row.doLayout();
      check(row.getComponent(2).getY() > row.getComponent(0).getY(), "maintenance wraps");
      assertInside(row, row.getComponents());
      check(row.getPreferredSize().height >= 72, "all rows have height");
    });
  }

  @Test
  void hiddenButtonsDoNotReserveAColumn() throws Exception {
    onEdt(() -> {
      JPanel row = maintenance();
      row.getComponent(1).setVisible(false);
      row.setSize(400, 100);
      row.doLayout();
      equal(168, row.getComponent(2).getX(), "hidden button leaves no hole");
    });
  }

  @Test
  void overwideChildUsesTheVisibleAncestorWidth() throws Exception {
    onEdt(() -> {
      JPanel row = maintenance();
      JPanel ancestor = new JPanel(null);
      ancestor.setSize(210, 300);
      ancestor.add(row);
      row.setBounds(10, 0, 900, 200);
      row.doLayout();
      for (Component item : row.getComponents()) {
        check(item.getX() + item.getWidth() <= 200, "button fits visible ancestor");
      }
      check(row.getComponent(2).getY() > 0, "ancestor forces wrapping");
    });
  }

  @Test
  void viewportWidthIsRespected() throws Exception {
    onEdt(() -> {
      JPanel row = maintenance();
      JViewport viewport = new JViewport();
      viewport.setSize(220, 300);
      viewport.setView(row);
      row.setSize(900, 200);
      row.doLayout();
      for (Component item : row.getComponents()) {
        check(item.getX() + item.getWidth() <= 220, "button remains in viewport");
      }
    });
  }

  @Test
  void aVeryLongButtonDoesNotOverflowItsParent() throws Exception {
    onEdt(() -> {
      JButton longButton = button("ติดตั้งและใช้แบ็กเอนด์ทดลองที่เลือก", 600);
      JPanel row = new KataGoAccelerationLayout.WrappingActions(longButton);
      row.setSize(240, 100);
      row.doLayout();
      equal(240, longButton.getWidth(), "component width is bounded");
      check(longButton.getText().endsWith("ที่เลือก"), "full accessible button text retained");
      assertInside(row, longButton);
    });
  }

  @Test
  void anExactFitDoesNotWrap() throws Exception {
    onEdt(() -> {
      JButton first = button("first", 120);
      JButton second = button("second", 160);
      JPanel row = KataGoAccelerationLayout.primaryActions(first, second);
      row.setSize(288, 100);
      row.doLayout();
      equal(0, second.getY(), "exact fit stays inline");
    });
  }

  @Test
  void growingAndShrinkingReflowsBothDirections() throws Exception {
    onEdt(() -> {
      JPanel row = maintenance();
      row.setSize(190, 200);
      row.doLayout();
      int narrowHeight = row.getPreferredSize().height;
      row.setSize(800, 200);
      row.doLayout();
      equal(0, row.getComponent(2).getY(), "growing restores horizontal layout");
      check(row.getPreferredSize().height < narrowHeight, "growing releases excess height");
      row.setSize(190, 200);
      row.doLayout();
      equal(narrowHeight, row.getPreferredSize().height, "shrinking restores wrapped height");
    });
  }

  @Test
  void changedButtonTextCanTriggerReflow() throws Exception {
    onEdt(() -> {
      JButton first = button("first", 120);
      JButton second = button("install", 120);
      JPanel row = KataGoAccelerationLayout.primaryActions(first, second);
      row.setSize(280, 100);
      row.doLayout();
      equal(0, second.getY(), "initially inline");
      second.setText("Enable installed backend");
      second.setPreferredSize(new Dimension(190, 32));
      row.doLayout();
      check(second.getY() > 0, "new longer text gets its own row");
    });
  }

  @Test
  void experimentalSelectorAndButtonAreStacked() throws Exception {
    onEdt(() -> {
      JComboBox<String> selector = new JComboBox<>(new String[] {"DirectML（DirectX 12 显卡）"});
      selector.setPreferredSize(new Dimension(390, 32));
      JButton install = button("启用已安装后端", 190);
      JPanel group = KataGoAccelerationLayout.experimentalActions(selector, install);
      group.setSize(420, 120);
      layout(group);
      Rectangle buttonBounds = SwingUtilities.convertRectangle(install.getParent(), install.getBounds(), group);
      check(buttonBounds.y >= selector.getY() + selector.getHeight(), "button below selector");
      check(buttonBounds.x + buttonBounds.width <= 420, "long label does not clip outside group");
    });
  }

  @Test
  void shortStatusHasCompactIntrinsicWidth() throws Exception {
    onEdt(() -> {
      JTextArea status = status("Ready");
      check(status.getPreferredSize().width < 390, "short status does not demand a full column");
      check(!status.getText().startsWith("<html>"), "status is plain text");
    });
  }

  @Test
  void longStatusPreservesAllTextAndAccessibleName() throws Exception {
    onEdt(() -> {
      String text = "不支持：TensorRT 10.x 需要 SM 7.5+；这张显卡请使用 CUDA/OpenCL。";
      JTextArea status = status(text);
      equal(text, status.getText(), "full visible content");
      equal(text, status.getAccessibleContext().getAccessibleName(), "full accessible content");
      check(!status.isEditable(), "status cannot be edited");
      status.setFocusable(true);
      check(status.isFocusable(), "dialog can retain keyboard-reachable status");
    });
  }

  @Test
  void narrowStatusIsTallEnoughForItsWrappedView() throws Exception {
    onEdt(() -> {
      JTextArea status = status("尚未启用 TensorRT profile — NVIDIA 运行库, HumanSL CUDA companion, TensorRT 引擎尚未就绪");
      status.setSize(180, 20);
      int required = ((KataGoAccelerationLayout.WrappingText) status).heightForWidth(180);
      check(required > 32, "precondition: wrapped status needs multiple lines");
      check(status.getPreferredSize().height >= required, "height includes all wrapped lines");
    });
  }

  @Test
  void explicitNewlinesAreIncludedInHeight() throws Exception {
    onEdt(() -> {
      JTextArea status = status("one\ntwo\nthree");
      status.setSize(500, 20);
      int lineHeight = status.getFontMetrics(status.getFont()).getHeight();
      check(status.getPreferredSize().height >= lineHeight * 3, "newlines are not flattened for height");
    });
  }

  @Test
  void longPathsWrapWithoutEllipses() throws Exception {
    onEdt(() -> {
      String path = "C:\\Users\\测试用户\\" + "long-directory-".repeat(20) + "\\katago.exe";
      JTextArea status = status(path);
      status.setSize(200, 32);
      check(status.getPreferredSize().height > 64, "unbroken path wraps");
      equal(path, status.getText(), "path remains complete");
    });
  }

  @Test
  void translatedTextAndScaledFontsKeepAllLines() throws Exception {
    onEdt(() -> {
      String[] messages = {
          "TensorRT runtime and verified CUDA companion are missing; open Auto Setup to repair them.",
          "TensorRT 运行库与 HumanSL CUDA companion 尚未就绪，请修复后再启用。",
          "TensorRT 実行環境と CUDA コンパニオンを修復してください。",
          "TensorRT 런타임 및 CUDA 구성 요소를 복구하십시오.",
          "โปรดซ่อมแซมรันไทม์ TensorRT และส่วนประกอบ CUDA ก่อนเปิดใช้งาน"
      };
      for (int pointSize : new int[] {14, 21, 28}) {
        for (String message : messages) {
          JTextArea status = status(message);
          status.setFont(FONT.deriveFont((float) pointSize));
          status.setSize(240, 20);
          int required = ((KataGoAccelerationLayout.WrappingText) status).heightForWidth(240);
          check(status.getPreferredSize().height >= required, "scaled translation has sufficient height");
          equal(message, status.getText(), "translation remains complete");
        }
      }
    });
  }

  @Test
  void hintAndActionsDoNotOverlapInNarrowBlock() throws Exception {
    onEdt(() -> {
      JTextArea hint = KataGoAccelerationLayout.hint(
          "KataGo 1.18 的 Transformer 模型在许多较新 NVIDIA 显卡上使用 CUDA 更快。下载支持断点续传，首次启动可能需要数分钟生成缓存。",
          FONT, Color.DARK_GRAY);
      JPanel actions = KataGoAccelerationLayout.primaryActions(button("修复 TensorRT", 120), button("启用 TensorRT 加速", 160));
      JPanel block = KataGoAccelerationLayout.actionBlock(new JLabel("TensorRT"), hint, actions);
      JPanel host = new JPanel(new BorderLayout());
      host.add(block, BorderLayout.NORTH);
      host.setSize(220, 1200);
      for (int attempt = 0; attempt < 6; attempt++) {
        layout(host);
      }
      int required = ((KataGoAccelerationLayout.WrappingText) hint).heightForWidth(hint.getWidth());
      check(hint.getHeight() >= required, "hint is fully visible");
      check(actions.getY() >= hint.getY() + hint.getHeight(), "actions below complete hint");
      check(actions.getHeight() >= actions.getPreferredSize().height, "actions have enough height");
      assertInside(actions, actions.getComponents());
    });
  }

  @Test
  void emptyAndNullStatusAreSafe() throws Exception {
    onEdt(() -> {
      JTextArea status = status("previous");
      KataGoAccelerationLayout.setStatusText(status, null);
      equal("", status.getText(), "null clears status");
      equal("", status.getAccessibleContext().getAccessibleName(), "null clears accessible name");
      check(status.getPreferredSize().height > 0, "empty row retains usable height");
    });
  }

  @Test
  void rowInsetsAreRespected() throws Exception {
    onEdt(() -> {
      JPanel row = maintenance();
      row.setBorder(BorderFactory.createEmptyBorder(5, 11, 7, 13));
      row.setSize(230, 300);
      row.doLayout();
      for (Component item : row.getComponents()) {
        check(item.getX() >= 11, "left inset");
        check(item.getX() + item.getWidth() <= 217, "right inset");
        check(item.getY() >= 5, "top inset");
      }
    });
  }

  private static JButton button(String text, int width) {
    JButton button = new JButton(text);
    button.setFont(FONT);
    button.setPreferredSize(new Dimension(width, 32));
    return button;
  }

  private static JPanel maintenance() {
    return KataGoAccelerationLayout.maintenanceActions(
        button("检查英伟达整合包", 160), button("切回 CUDA", 110), button("清理 TensorRT 缓存", 170));
  }

  private static JTextArea status(String text) {
    JTextArea status = KataGoAccelerationLayout.statusChip(FONT, Color.WHITE, Color.GRAY);
    KataGoAccelerationLayout.setStatusText(status, text);
    return status;
  }

  private static void assertInside(JComponent parent, Component... children) {
    for (Component child : children) {
      check(child.getX() >= 0, "nonnegative x");
      check(child.getX() + child.getWidth() <= parent.getWidth(), "within parent width");
      check(child.getY() + child.getHeight() <= parent.getHeight(), "within parent height");
    }
  }

  private static void layout(Container parent) {
    parent.doLayout();
    for (Component child : parent.getComponents()) {
      if (child instanceof Container) {
        layout((Container) child);
      }
    }
  }

  private static void onEdt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static void equal(Object expected, Object actual, String message) {
    if (!java.util.Objects.equals(expected, actual)) {
      throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
  }
}
