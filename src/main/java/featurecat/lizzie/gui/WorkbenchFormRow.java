package featurecat.lizzie.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** Keeps a setting beside its control, stacking the pair when the viewport is narrow. */
final class WorkbenchFormRow extends JPanel {
  WorkbenchFormRow() {
    super(null);
    setOpaque(false);
  }

  private int availableWidth() {
    int width = getWidth();
    if (width <= 0 && getParent() != null) width = getParent().getWidth();
    return Math.max(160, width > 0 ? width : 760);
  }

  private Dimension measure(boolean layout) {
    Insets insets = getInsets();
    if (getComponentCount() != 2) return new Dimension(760, 48);
    Component text = getComponent(0);
    Component control = getComponent(1);
    int width = availableWidth() - insets.left - insets.right;
    Dimension controls = control.getPreferredSize();
    int labelWidth = Math.min(330, Math.max(220, width - controls.width - 16));
    boolean stacked = width < labelWidth + 16 + controls.width;
    if (stacked) labelWidth = width;
    int controlWidth = stacked ? width : width - labelWidth - 16;
    controls.height = controlHeight(control, controlWidth, false);
    int textHeight = 0;
    for (Component child : ((JPanel) text).getComponents()) {
      if (child instanceof JTextArea) child.setSize(labelWidth, Short.MAX_VALUE);
      textHeight += child.getPreferredSize().height;
    }
    textHeight += 2;
    int height = stacked ? textHeight + 8 + controls.height : Math.max(textHeight, controls.height);
    if (layout) {
      text.setBounds(insets.left, insets.top, labelWidth, textHeight);
      control.setBounds(
          stacked ? insets.left : insets.left + labelWidth + 16,
          stacked ? insets.top + textHeight + 8 : insets.top + (height - controls.height) / 2,
          controlWidth,
          controls.height);
    }
    return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
  }

  static void prepareControls(Component component) {
    if (!(component instanceof JPanel panel)) return;
    if (panel.getLayout() instanceof FlowLayout flow) {
      panel.setLayout(
          new FlowLayout(FlowLayout.LEFT, flow.getHgap(), flow.getVgap()) {
            @Override
            public void layoutContainer(Container target) {
              controlHeight(target, target.getWidth(), true);
            }
          });
    }
    for (Component child : panel.getComponents()) prepareControls(child);
  }

  // FlowLayout wraps its children but reports only one row as its preferred height.
  private static int controlHeight(Component component, int width, boolean layout) {
    if (component instanceof JPanel panel && panel.getLayout() instanceof FlowLayout flow) {
      Insets insets = panel.getInsets();
      int available = Math.max(1, width - insets.left - insets.right - 2 * flow.getHgap());
      int x = 0;
      int rowHeight = 0;
      int height = insets.top + insets.bottom + 2 * flow.getVgap();
      for (Component child : panel.getComponents()) {
        if (!child.isVisible()) continue;
        Dimension size = child.getPreferredSize();
        int childWidth = Math.min(available, size.width);
        int childHeight = controlHeight(child, childWidth, false);
        if (x > 0 && x + flow.getHgap() + childWidth > available) {
          height += rowHeight + flow.getVgap();
          x = 0;
          rowHeight = 0;
        }
        if (x > 0) x += flow.getHgap();
        if (layout)
          child.setBounds(
              insets.left + flow.getHgap() + x,
              height - insets.bottom - flow.getVgap(),
              childWidth,
              childHeight);
        x += childWidth;
        rowHeight = Math.max(rowHeight, childHeight);
      }
      return height + rowHeight;
    }
    if (component instanceof JPanel panel
        && panel.getLayout() instanceof java.awt.BorderLayout
        && panel.getComponentCount() == 1) {
      Insets insets = panel.getInsets();
      return insets.top
          + insets.bottom
          + controlHeight(panel.getComponent(0), width - insets.left - insets.right, layout);
    }
    return component.getPreferredSize().height;
  }

  @Override
  public void setBounds(int x, int y, int width, int height) {
    boolean resized = width != getWidth();
    super.setBounds(x, y, width, height);
    if (resized) revalidate();
  }

  @Override
  public Dimension getPreferredSize() {
    return measure(false);
  }

  @Override
  public Dimension getMinimumSize() {
    return new Dimension(160, getPreferredSize().height);
  }

  @Override
  public Dimension getMaximumSize() {
    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
  }

  @Override
  public void doLayout() {
    measure(true);
  }
}
