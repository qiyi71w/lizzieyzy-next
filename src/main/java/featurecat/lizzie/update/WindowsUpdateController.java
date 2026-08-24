package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Window;
import java.util.Optional;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
  private WindowsUpdateController() {}

  public static void openCheckUpdatePage(Component parent) {
    SwingUtilities.invokeLater(() -> new CheckUpdateDialog(parent).setVisible(true));
  }

  public static void checkForUpdate(Component parent) {
    checkForUpdate(parent, UpdateChannel.current());
  }

  public static void checkForUpdate(Component parent, UpdateChannel channel) {
    UpdateChannel selected = channel == null ? UpdateChannel.STABLE : channel;
    UpdateChannel.persist(selected);
    if (!UpdateAdmission.shouldFetch(Lizzie.nextVersion)) {
      Utils.showMsg(
          UpdateText.tr(
              "WindowsUpdate.devBuild",
              "当前是开发版或未打包版本，无法检查更新。",
              "This development or unpackaged build cannot check for updates."));
      return;
    }
    Thread thread =
        new Thread(
            () -> {
              try {
                if (WindowsUpdatePaths.isWindowsRuntime()) {
                  checkWindows(parent, selected);
                } else {
                  checkPackage(parent, selected);
                }
              } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(
                    () ->
                        Utils.showMsg(
                            e.getMessage() != null && !e.getMessage().isBlank()
                                ? e.getMessage()
                                : UpdateText.tr(
                                        "WindowsUpdate.checkFailed",
                                        "检查更新失败",
                                        "Update check failed")
                                    + ": "
                                    + UpdateText.userFacingError(e)));
              }
            },
            "lizzie-update-manual");
    thread.setDaemon(true);
    thread.start();
  }

  private static void checkWindows(Component parent, UpdateChannel channel) throws Exception {
    WindowsUpdateService service = new WindowsUpdateService(channel);
    Optional<WindowsUpdatePlan> maybePlan = service.checkForUpdate();
    if (maybePlan.isEmpty()) {
      showNoUpdate(channel);
      return;
    }
    WindowsUpdatePlan plan = maybePlan.get();
    SwingUtilities.invokeLater(
        () -> {
          disposeCheckPage(parent);
          new WindowsUpdateDialog(Lizzie.frame, service, plan).setVisible(true);
        });
  }

  private static void checkPackage(Component parent, UpdateChannel channel) throws Exception {
    PlatformUpdateService service = new PlatformUpdateService(channel);
    Optional<PackageUpdatePlan> maybePlan = service.checkForUpdate();
    if (maybePlan.isEmpty()) {
      showNoUpdate(channel);
      return;
    }
    PackageUpdatePlan plan = maybePlan.get();
    SwingUtilities.invokeLater(
        () -> {
          disposeCheckPage(parent);
          new PackageUpdateDialog(Lizzie.frame, service, plan).setVisible(true);
        });
  }

  private static void showNoUpdate(UpdateChannel channel) {
    SwingUtilities.invokeLater(
        () -> Utils.showMsg(UpdateAdmission.noUpdateMessage(channel)));
  }

  private static void disposeCheckPage(Component parent) {
    Window window =
        parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent);
    if (window instanceof CheckUpdateDialog) {
      window.dispose();
    }
  }
}
