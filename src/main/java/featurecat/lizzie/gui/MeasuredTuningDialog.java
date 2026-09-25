package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.EngineThreadPolicy;
import featurecat.lizzie.util.MeasuredKataGoTuning;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredReport.Scene;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Review and explicit confirmation for measurement-backed settings in the existing setup page. */
final class MeasuredTuningDialog {
  private final MeasuredTuningOperation operation = new MeasuredTuningOperation();

  void invalidate() {
    operation.invalidate();
  }

  private boolean canDeliver(Window owner, long token) {
    return operation.canDeliver(token, owner.isVisible(), owner.isDisplayable());
  }

  void importReport(Window owner, String entryId, Consumer<Boolean> busy, Runnable changed) {
    if (!owner.isVisible() || !owner.isDisplayable()) return;
    long token = operation.begin(busy);
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(text("import"));
    chooser.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION || !canDeliver(owner, token)) {
      operation.finish(token);
      return;
    }
    java.nio.file.Path reportPath = chooser.getSelectedFile().toPath();
    SwingWorker<MeasuredKataGoTuning.Review, Void> worker =
        new SwingWorker<>() {
          @Override
          protected MeasuredKataGoTuning.Review doInBackground() throws Exception {
            return MeasuredKataGoTuning.review(entryId, reportPath);
          }

          @Override
          protected void done() {
            if (!canDeliver(owner, token)) {
              operation.finish(token);
              return;
            }
            try {
              var review = get();
              var report = review.report();
              String scene = text(report.scene() == Scene.LIVE ? "live" : "wholeGame");
              String message =
                  targetLabel(review.entryName(), review.entryId())
                      + "\n"
                      + scene
                      + "\n"
                      + report.baselineParameters()
                      + " → "
                      + report.candidateParameters()
                      + "\n"
                      + String.format(
                          Locale.getDefault(), text("gain"), (report.assess().speedup() - 1) * 100)
                      + "\n\n"
                      + text("confirm");
              if (JOptionPane.showConfirmDialog(
                          owner,
                          plainText(owner, message),
                          text("title"),
                          JOptionPane.OK_CANCEL_OPTION,
                          JOptionPane.QUESTION_MESSAGE)
                      == JOptionPane.OK_OPTION
                  && canDeliver(owner, token)) {
                runWorker(
                    owner,
                    busy,
                    changed,
                    () -> {
                      MeasuredKataGoTuning.apply(review);
                      return null;
                    });
              }
            } catch (Exception failure) {
              if (canDeliver(owner, token)) showFailure(owner, failure);
            } finally {
              operation.finish(token);
            }
          }
        };
    operation.attach(token, worker);
    worker.execute();
  }

  void restore(Window owner, String entryId, Consumer<Boolean> busy, Runnable changed) {
    if (!owner.isVisible() || !owner.isDisplayable()) return;
    EngineData entry = EngineThreadPolicy.findSavedEntry(entryId);
    if (entry == null) {
      showFailure(owner, new IllegalStateException(EngineThreadPolicy.message("targetDeleted")));
      return;
    }
    long token = operation.begin(busy);
    if (JOptionPane.showConfirmDialog(
                owner,
                plainText(
                    owner, targetLabel(entry.name, entryId) + "\n\n" + text("restoreConfirm")),
                text("title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE)
            != JOptionPane.OK_OPTION
        || !canDeliver(owner, token)) {
      operation.finish(token);
      return;
    }
    runWorker(
        owner,
        busy,
        changed,
        () -> {
          MeasuredKataGoTuning.restore(entryId);
          return null;
        });
  }

  private void runWorker(
      Window owner, Consumer<Boolean> busy, Runnable changed, Callable<Void> action) {
    long token = operation.begin(busy);
    SwingWorker<Void, Void> worker =
        new SwingWorker<>() {
          @Override
          protected Void doInBackground() throws Exception {
            return action.call();
          }

          @Override
          protected void done() {
            if (!canDeliver(owner, token)) {
              operation.finish(token);
              return;
            }
            try {
              get();
              changed.run();
              if (canDeliver(owner, token))
                JOptionPane.showMessageDialog(
                    owner,
                    plainText(owner, text("saved")),
                    text("title"),
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception failure) {
              if (canDeliver(owner, token)) showFailure(owner, failure);
            } finally {
              operation.finish(token);
            }
          }
        };
    operation.attach(token, worker);
    worker.execute();
  }

  private static void showFailure(Window owner, Exception failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    JOptionPane.showMessageDialog(
        owner,
        plainText(owner, text("rejected") + "\n" + cause.getMessage()),
        text("title"),
        JOptionPane.WARNING_MESSAGE);
  }

  private static JScrollPane plainText(Window owner, String value) {
    JTextArea area = new JTextArea(value);
    area.setFont(UIManager.getFont("Label.font"));
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setEditable(false);
    area.setOpaque(false);
    area.setCaretPosition(0);
    area.getAccessibleContext().setAccessibleName(text("title"));
    Rectangle screen = owner.getGraphicsConfiguration().getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(owner.getGraphicsConfiguration());
    int width = Math.min(580, Math.max(1, (screen.width - insets.left - insets.right) * 2 / 3));
    int maxHeight = Math.max(1, (screen.height - insets.top - insets.bottom) / 2);
    // Fix the viewport before JOptionPane packs: wrapped text can otherwise grow after packing
    // and push its confirmation buttons outside the native dialog on Windows HiDPI.
    area.setSize(width - 24, Short.MAX_VALUE);
    int height = Math.min(maxHeight, Math.min(320, area.getPreferredSize().height + 12));
    JScrollPane scroll =
        new JScrollPane(
            area, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setBorder(null);
    scroll.setOpaque(false);
    scroll.getViewport().setOpaque(false);
    scroll.setPreferredSize(new Dimension(width, height));
    return scroll;
  }

  static String targetLabel(String entryName, String entryId) {
    String identity = (entryName == null ? "" : entryName) + " [" + entryId + "]";
    return String.format(Locale.getDefault(), EngineThreadPolicy.message("target"), identity);
  }

  private static String text(String key) {
    return Lizzie.resourceBundle.getString("MeasuredTuning." + key);
  }
}
