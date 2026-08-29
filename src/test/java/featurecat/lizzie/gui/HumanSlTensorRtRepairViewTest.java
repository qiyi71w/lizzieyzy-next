package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtFailureKind;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class HumanSlTensorRtRepairViewTest {
  @Test
  void runtimeMissingShowsSpecificItemAndRepairAction() {
    TensorRtRepairContext context = context(List.of(TensorRtInstallStatus.MISSING_RUNTIME), true);
    HumanSlTensorRtRepairView view = HumanSlTensorRtRepairView.fromPreparation(true, true, context);

    assertTrue(view.offerRepair);
    assertEquals(List.of("AutoSetup.tensorRtMissingRuntime"), view.missingItemKeys);
    assertEquals(
        "AI Coach cannot start because TensorRT is incomplete: NVIDIA runtime.",
        view.inlineError(text()));
    assertEquals("Open Auto Setup repair", view.repairActionLabel(text()));
    assertFalse(view.startsRunnerOrCoach());
    KataGoAutoSetupDialog.OpenRequest request = view.openRequest();
    assertSame(context, request.context);
    assertTrue(request.directed);
    assertEquals(3, request.sectionIndex);
  }

  @Test
  void incompleteEngineAndCompanionStayIndependent() {
    TensorRtRepairContext context =
        context(
            List.of(
                TensorRtInstallStatus.MISSING_COMPANION,
                TensorRtInstallStatus.MISSING_ENGINE_STALE),
            true);
    HumanSlTensorRtRepairView view = HumanSlTensorRtRepairView.fromPreparation(true, true, context);

    assertEquals(
        List.of("AutoSetup.tensorRtMissingCompanion", "AutoSetup.tensorRtMissingEngineStale"),
        view.missingItemKeys);
    assertTrue(view.inlineError(text()).contains("HumanSL CUDA companion"));
    assertTrue(view.inlineError(text()).contains("current TensorRT engine"));
    assertTrue(view.offerRepair);
  }

  @Test
  void emptyCommandMissingModelCustomEngineAndOrdinaryFailuresDoNotOfferRepair() {
    TensorRtRepairContext repairable =
        context(List.of(TensorRtInstallStatus.MISSING_RUNTIME), true);
    assertFalse(HumanSlTensorRtRepairView.fromPreparation(false, true, repairable).offerRepair);
    assertFalse(HumanSlTensorRtRepairView.fromPreparation(true, false, repairable).offerRepair);

    TensorRtRepairContext custom = context(List.of(TensorRtInstallStatus.MISSING_RUNTIME), false);
    HumanSlTensorRtRepairView customView =
        HumanSlTensorRtRepairView.fromPreparation(true, true, custom);
    assertFalse(customView.offerRepair);
    assertFalse(customView.openRequest().directed);
    assertEquals("", customView.repairActionLabel(text()));

    assertFalse(HumanSlTensorRtRepairView.fromPreparation(true, true, null).offerRepair);
    assertFalse(
        HumanSlTensorRtRepairView.fromPreparation(true, true, context(List.of(), true))
            .offerRepair);
  }


  @Test
  void openingDirectedRepairHidesModalHostAndDoesNotStartRunner() {
    TensorRtRepairContext context = context(List.of(TensorRtInstallStatus.MISSING_COMPANION), true);
    HumanSlTensorRtRepairView view = HumanSlTensorRtRepairView.fromPreparation(true, true, context);
    AtomicBoolean hidden = new AtomicBoolean();
    AtomicReference<TensorRtRepairContext> opened = new AtomicReference<TensorRtRepairContext>();

    view.openDirectedRepair(() -> hidden.set(true), opened::set);

    assertTrue(hidden.get());
    assertSame(context, opened.get());
    assertFalse(view.startsRunnerOrCoach());
  }

  @Test
  void repairActionReusesExistingLocalizedSetupEntry() {
    assertEquals(
        "EngineFailedMessage.openTensorRtRepair", HumanSlTensorRtRepairView.REPAIR_ACTION_KEY);
    assertEquals(
        Lizzie.resourceBundle.getString("EngineFailedMessage.openTensorRtRepair"),
        HumanSlTensorRtRepairView.fromPreparation(
                true, true, context(List.of(TensorRtInstallStatus.MISSING_COMPANION), true))
            .repairActionLabel(Lizzie.resourceBundle::getString));
  }

  private static TensorRtRepairContext context(List<String> missingItems, boolean repairable) {
    return TensorRtRepairContext.of(
        Path.of("engines", "katago", "windows-x64-nvidia-tensorrt", "katago.exe"),
        "katago.exe analysis",
        missingItems.contains(TensorRtInstallStatus.MISSING_ENGINE)
                || missingItems.contains(TensorRtInstallStatus.MISSING_ENGINE_STALE)
            ? TensorRtFailureKind.MISSING_ENGINE
            : TensorRtFailureKind.MISSING_RUNTIME,
        missingItems,
        repairable,
        "display");
  }

  private static Function<String, String> text() {
    return key -> {
      if (HumanSlTensorRtRepairView.INLINE_ERROR_KEY.equals(key)) {
        return "AI Coach cannot start because TensorRT is incomplete: {0}.";
      }
      if (HumanSlTensorRtRepairView.REPAIR_ACTION_KEY.equals(key)) {
        return "Open Auto Setup repair";
      }
      if ("AutoSetup.tensorRtMissingRuntime".equals(key)) {
        return "NVIDIA runtime";
      }
      if ("AutoSetup.tensorRtMissingCompanion".equals(key)) {
        return "HumanSL CUDA companion";
      }
      if ("AutoSetup.tensorRtMissingEngineStale".equals(key)) {
        return "current TensorRT engine";
      }
      return key;
    };
  }
}
