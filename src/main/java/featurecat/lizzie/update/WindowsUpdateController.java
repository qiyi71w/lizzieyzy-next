package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Window;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
  private WindowsUpdateController() {}

  public static void openCheckUpdatePage(Component parent) {
    SwingUtilities.invokeLater(() -> new CheckUpdateDialog(parent).setVisible(true));
  }

  public static void checkForUpdate(Component parent) {
    checkForUpdate(parent, UpdateChannel.current(), UpdateSource.current());
  }

  public static void checkForUpdate(
      Component parent, UpdateChannel channel, UpdateSource source) {
    UpdateChannel selected = channel == null ? UpdateChannel.STABLE : channel;
    UpdateSource selectedSource = source == null ? UpdateSource.OFFICIAL_SITE : source;
    UpdateChannel.persist(selected);
    if (selected != UpdateChannel.BETA) {
      UpdateSource.persist(selectedSource);
    }
    UpdateCheckSelection selection =
        UpdateCheckSelection.of(selected, selectedSource, Lizzie.nextVersion);
    Thread thread = new Thread(() -> check(parent, selection), "lizzie-update-manual");
    thread.setDaemon(true);
    thread.start();
  }

  private static void check(Component parent, UpdateCheckSelection selection) {
    UpdateCheckResult result = UpdateDiscovery.check(selection);
    switch (result.reason) {
      case OFFER:
        offer(parent, selection, result);
        return;
      case UNAVAILABLE_BUILD:
        SwingUtilities.invokeLater(
            () ->
                Utils.showMsg(
                    UpdateText.tr(
                        "WindowsUpdate.devBuild",
                        "当前是开发版或未打包版本，无法检查更新。",
                        "This development or unpackaged build cannot check for updates.")));
        return;
      case NO_UPDATE:
        showNoUpdate(selection.channel);
        return;
      case UNSUPPORTED_PLATFORM:
        SwingUtilities.invokeLater(
            () ->
                Utils.showMsg(
                    UpdateText.tr(
                        "WindowsUpdate.unsupportedPlatform",
                        "当前平台不支持应用内更新。",
                        "This platform cannot check for in-app updates.")));
        return;
      case NO_PACKAGE:
        SwingUtilities.invokeLater(
            () ->
                Utils.showMsg(
                    UpdateText.tr(
                        "WindowsUpdate.noPackage",
                        "已有更新版本，但没有匹配当前安装的更新包。",
                        "A newer release exists, but no matching installable update is available.")));
        return;
      case FAILURE:
        showFailure(selection.channel, result.failureKind);
        return;
    }
  }

  private static void offer(
      Component parent, UpdateCheckSelection selection, UpdateCheckResult result) {
    if (result.windowsPlan != null) {
      WindowsUpdateService service =
          new WindowsUpdateService(selection.channel, selection.effectiveSource);
      SwingUtilities.invokeLater(
          () -> {
            disposeCheckPage(parent);
            new WindowsUpdateDialog(Lizzie.frame, service, result.windowsPlan).setVisible(true);
          });
      return;
    }
    if (result.packagePlan != null) {
      PlatformUpdateService service =
          new PlatformUpdateService(selection.channel, selection.effectiveSource);
      SwingUtilities.invokeLater(
          () -> {
            disposeCheckPage(parent);
            new PackageUpdateDialog(Lizzie.frame, service, result.packagePlan).setVisible(true);
          });
      return;
    }
    showFailure(selection.channel, UpdateCheckResult.FailureKind.ADAPTER);
  }

  private static void showNoUpdate(UpdateChannel channel) {
    SwingUtilities.invokeLater(
        () -> Utils.showMsg(UpdateAdmission.noUpdateMessage(channel)));
  }

  private static void showFailure(
      UpdateChannel channel, UpdateCheckResult.FailureKind kind) {
    String message;
    if (kind == UpdateCheckResult.FailureKind.FETCH) {
      message = UpdateAdmission.fetchFailureMessage(channel);
    } else if (kind == UpdateCheckResult.FailureKind.INVALID_TEST_POINTER) {
      message = UpdateAdmission.invalidTestPointerMessage();
    } else {
      message =
          UpdateText.tr(
              "WindowsUpdate.checkFailed", "检查更新失败", "Update check failed");
    }
    SwingUtilities.invokeLater(() -> Utils.showMsg(message));
  }

  private static void disposeCheckPage(Component parent) {
    Window window =
        parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent);
    if (window instanceof CheckUpdateDialog) {
      window.dispose();
    }
  }
}
