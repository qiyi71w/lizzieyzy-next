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
    if (WindowsUpdatePaths.isWindowsRuntime()) {
      Thread thread =
          new Thread(() -> checkWindows(parent, selection), "lizzie-update-manual");
      thread.setDaemon(true);
      thread.start();
      return;
    }
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
                checkPackage(parent, selected, selectedSource);
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

  private static void checkWindows(Component parent, UpdateCheckSelection selection) {
    UpdateCheckResult result = UpdateDiscovery.check(selection);
    switch (result.reason) {
      case OFFER:
        if (result.windowsPlan == null) {
          showFailure(selection.channel, UpdateCheckResult.FailureKind.ADAPTER);
          return;
        }
        WindowsUpdateService service =
            new WindowsUpdateService(selection.channel, selection.effectiveSource);
        SwingUtilities.invokeLater(
            () -> {
              disposeCheckPage(parent);
              new WindowsUpdateDialog(Lizzie.frame, service, result.windowsPlan).setVisible(true);
            });
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

  private static void checkPackage(
      Component parent, UpdateChannel channel, UpdateSource source) throws Exception {
    PlatformUpdateService service = new PlatformUpdateService(channel, source);
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
