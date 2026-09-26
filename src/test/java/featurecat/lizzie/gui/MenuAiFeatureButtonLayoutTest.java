package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class MenuAiFeatureButtonLayoutTest {
  @Test
  void longEngineNameDoesNotPushAiActionsOutOfNarrowMenuBar() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          javax.swing.JMenuBar bar = new javax.swing.JMenuBar();
          for (String title :
              new String[] {
                "File", "View", "Game", "Analyze", "Edit", "Sync", "Help", "Settings"
              }) {
            bar.add(new javax.swing.JMenu(title));
          }
          javax.swing.JMenu engine =
              new javax.swing.JMenu("[1]: Transformer B11 - a very long configured engine name");
          engine.putClientProperty("lizzie.engineMenu", Boolean.TRUE);
          bar.add(engine);
          JFontButton coach = new JFontButton("AI Coach");
          JFontButton commentary = new JFontButton("AI Commentary");
          Menu.configureAiFeatureButton(coach, false, 30);
          Menu.configureAiFeatureButton(commentary, false, 30);
          bar.add(coach);
          bar.add(commentary);
          WindowMenuStrip strip = new WindowMenuStrip(bar);
          for (int width : new int[] {1024, 1280, 1700, 1024}) {
            strip.setSize(width, 44);
            strip.doLayout();
            for (java.awt.Component child : strip.getComponents()) {
              assertTrue(child.getX() + child.getWidth() <= width);
              assertTrue(child.getY() + child.getHeight() <= strip.getHeight());
            }
          }
        });
  }

  @Test
  void repeatedStateRefreshDoesNotGrowButton() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JFontButton button = new JFontButton("AI Coach");

          Menu.configureAiFeatureButton(button, false, 34);
          Dimension first = new Dimension(button.getPreferredSize());

          for (int refresh = 0; refresh < 20; refresh++) {
            Menu.configureAiFeatureButton(button, false, 34);
          }

          assertEquals(first, button.getPreferredSize());
        });
  }

  @Test
  void returningToIdleRestoresOriginalWidthAfterLongStatus() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JFontButton button = new JFontButton("AI Coach");
          Menu.configureAiFeatureButton(button, false, 34);
          Dimension idle = new Dimension(button.getPreferredSize());

          button.setText("AI Coach is preparing the training session");
          Menu.configureAiFeatureButton(button, false, 34);
          int preparingWidth = button.getPreferredSize().width;

          button.setText("AI Coach");
          Menu.configureAiFeatureButton(button, false, 34);

          assertTrue(preparingWidth > idle.width);
          assertEquals(idle, button.getPreferredSize());
        });
  }
}
