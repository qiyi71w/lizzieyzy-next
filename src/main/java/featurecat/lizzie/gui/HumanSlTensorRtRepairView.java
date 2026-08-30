package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import java.awt.Window;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

final class HumanSlTensorRtRepairView {
  static final String INLINE_ERROR_KEY = "HumanSlGame.error.tensorRtMissingItems";
  static final String REPAIR_ACTION_KEY = "EngineFailedMessage.openTensorRtRepair";
  static final String REPAIR_ACTION_ACCESSIBLE_NAME_KEY =
      "EngineFailedMessage.openTensorRtRepairAccessibleName";
  static final String REPAIR_ACTION_ACCESSIBLE_DESCRIPTION_KEY =
      "EngineFailedMessage.openTensorRtRepairAccessibleDescription";

  final boolean offerRepair;
  final List<String> missingItemKeys;
  final TensorRtRepairContext context;

  private HumanSlTensorRtRepairView(
      boolean offerRepair, List<String> missingItemKeys, TensorRtRepairContext context) {
    this.offerRepair = offerRepair;
    this.missingItemKeys = List.copyOf(missingItemKeys);
    this.context = context;
  }

  static HumanSlTensorRtRepairView fromPreparation(
      boolean commandResolved, boolean humanModelPresent, TensorRtRepairContext context) {
    boolean offer =
        commandResolved
            && humanModelPresent
            && context != null
            && context.repairable
            && context.missingItems != null
            && !context.missingItems.isEmpty();
    return new HumanSlTensorRtRepairView(
        offer, offer ? missingKeys(context.missingItems) : List.of(), context);
  }

  String inlineError(Function<String, String> text) {
    if (!offerRepair) {
      return "";
    }
    return MessageFormat.format(text.apply(INLINE_ERROR_KEY), joinMissingItems(text));
  }

  String repairActionLabel(Function<String, String> text) {
    return offerRepair ? text.apply(REPAIR_ACTION_KEY) : "";
  }

  boolean startsRunnerOrCoach() {
    return false;
  }

  KataGoAutoSetupDialog.OpenRequest openRequest() {
    return offerRepair
        ? KataGoAutoSetupDialog.openRequestForRepair(context)
        : KataGoAutoSetupDialog.openRequestForMenu();
  }

  void openDirectedRepair(Window host) {
    openDirectedRepair(
        host == null ? null : () -> host.setVisible(false),
        Lizzie.frame == null ? null : Lizzie.frame::openKataGoAutoSetup);
  }

  void openDirectedRepair(Runnable hideHost, Consumer<TensorRtRepairContext> opener) {
    if (!offerRepair || opener == null) {
      return;
    }
    if (hideHost != null) {
      hideHost.run();
    }
    opener.accept(context);
  }

  String joinMissingItems(Function<String, String> text) {
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < missingItemKeys.size(); i++) {
      if (i > 0) {
        joined.append(", ");
      }
      joined.append(text.apply(missingItemKeys.get(i)));
    }
    return joined.toString();
  }

  private static List<String> missingKeys(List<String> missingItems) {
    List<String> keys = new ArrayList<String>();
    for (String item : missingItems) {
      if (TensorRtInstallStatus.MISSING_RUNTIME.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingRuntime");
      } else if (TensorRtInstallStatus.MISSING_COMPANION.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingCompanion");
      } else if (TensorRtInstallStatus.MISSING_ENGINE.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingEngine");
      } else if (TensorRtInstallStatus.MISSING_ENGINE_STALE.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingEngineStale");
      }
    }
    return keys;
  }
}
