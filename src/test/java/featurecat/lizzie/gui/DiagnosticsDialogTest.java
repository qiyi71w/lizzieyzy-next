package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.ReadBoardLoggingControl;
import featurecat.lizzie.analysis.ReadBoardLoggingProtocol;
import featurecat.lizzie.analysis.ReadBoardLoggingSnapshot;
import featurecat.lizzie.logging.DiagnosticBundleExporter;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsDialogTest {
  @TempDir Path tempDir;
  private ResourceBundle originalResourceBundle;

  @BeforeEach
  void useDeterministicEnglishMessages() {
    originalResourceBundle = Lizzie.resourceBundle;
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
  }

  @AfterEach
  void tearDown() {
    Lizzie.resourceBundle = originalResourceBundle;
    LoggingRuntime.resetForTests();
  }

  @Test
  void dialogShowsHealthAndAppliesDiagnosticsOnApply() {
    LoggingRuntime runtime = start();
    DiagnosticsDialog dialog = dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true);
    assertTrue(dialog.healthText().contains(runtime.logsDirectory().toString()));
    assertTrue(dialog.healthText().contains("Persistence:"));
    assertFalse(dialog.cancelButton().isVisible());
    assertFalse(dialog.fullLogsEnabledBox().isSelected());
    dialog.diagnosticsEnabledBox().doClick();
    assertTrue(runtime.settings().diagnosticsEnabled());
    dialog.applyCurrentPlan();
    assertFalse(runtime.settings().diagnosticsEnabled());
  }

  @Test
  void applyFailureRestoresUiFromRuntime() {
    LoggingRuntime runtime = start();
    DiagnosticsDialog dialog = dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true);
    assertTrue(runtime.settings().diagnosticsEnabled());
    dialog.diagnosticsEnabledBox().setSelected(false);
    try {
      runtime.applySettings(
          runtime.settings().withDiagnosticsEnabled(false),
          settings -> {
            throw new java.io.IOException("disk full");
          });
    } catch (RuntimeException ignored) {
    }
    dialog.refreshFromRuntime();
    assertTrue(dialog.diagnosticsEnabledBox().isSelected());
    assertTrue(runtime.settings().diagnosticsEnabled());
  }

  @Test
  void fullTraceRequiresConfirmationAndRefreshesTitle() {
    LoggingRuntime runtime = start();
    AtomicInteger titles = new AtomicInteger();
    AtomicBoolean confirm = new AtomicBoolean(false);
    DiagnosticsDialog dialog = dialog(runtime, titles, new ArrayList<>(), confirm::get);
    dialog.fullLogsEnabledBox().setSelected(true);
    dialog.applyCurrentPlan();
    assertFalse(runtime.fullTraceActive());
    assertEquals(0, titles.get());
    confirm.set(true);
    dialog.fullLogsEnabledBox().setSelected(true);
    dialog.applyCurrentPlan();
    assertTrue(runtime.fullTraceActive());
    assertEquals(1, titles.get());
    assertTrue(dialog.fullLogsEnabledBox().isSelected());
    assertTrue(dialog.durationText().contains("s"));
    DiagnosticsDialog reopened = dialog(runtime, titles, new ArrayList<>(), () -> true);
    assertTrue(reopened.fullLogsEnabledBox().isSelected());
    assertTrue(reopened.durationText().contains("s"));
    dialog.fullLogsEnabledBox().setSelected(false);
    dialog.applyCurrentPlan();
    assertFalse(runtime.fullTraceActive());
    assertEquals(2, titles.get());
  }

  @Test
  void queuedEstimateRefreshDiscardsOldPresentationAndKeepsEdtEventsRunning() throws Exception {
    LoggingRuntime runtime = start();
    Path helperLogs = runtime.logsDirectory().resolve("readboard");
    Files.createDirectories(helperLogs);
    Files.writeString(helperLogs.resolve("trace.log"), "x".repeat(1024 * 1024));
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 dGVzdFByb2Nlc3NJRA off off off healthy 0"));
    RecordingHelper helper = new RecordingHelper(control);
    CountDownLatch estimateShown = new CountDownLatch(1);
    CountDownLatch heartbeat = new CountDownLatch(1);
    List<String> estimates = new ArrayList<>();
    SwingUtilities.invokeAndWait(
        () -> {
          DiagnosticsDialog dialog =
              dialog(
                  runtime, new AtomicInteger(), new ArrayList<>(), () -> true, helper, () -> true);
          observeEstimateLabels(
              dialog,
              value -> {
                estimates.add(value);
                estimateShown.countDown();
              });
          dialog.helperTraceBox().doClick();
          dialog.refreshFromRuntime();
          dialog.refreshFromRuntime();
          SwingUtilities.invokeLater(heartbeat::countDown);
        });
    assertTrue(heartbeat.await(10, TimeUnit.SECONDS));
    assertTrue(estimateShown.await(10, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> {
          assertEquals(1, estimates.size(), estimates.toString());
          assertTrue(estimates.get(0).contains("1.0 MiB"), estimates.toString());
        });
  }

  private static void observeEstimateLabels(
      java.awt.Container container, java.util.function.Consumer<String> observer) {
    for (java.awt.Component component : container.getComponents()) {
      if (component instanceof javax.swing.JLabel label) {
        label.addPropertyChangeListener(
            "text",
            event -> {
              if (event.getNewValue() instanceof String text && text.contains("MiB"))
                observer.accept(text);
            });
      }
      if (component instanceof java.awt.Container child) observeEstimateLabels(child, observer);
    }
  }

  @Test
  void lateThrowingFolderOpenerCannotOverturnSubsequentPublishedExport() throws Exception {
    LoggingRuntime runtime = start();
    CountDownLatch firstOpened = new CountDownLatch(1);
    CountDownLatch secondOpened = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch firstReturned = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<DiagnosticsDialog> panel = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            DiagnosticsDialog dialog =
                new DiagnosticsDialog(
                    runtime,
                    null,
                    new DiagnosticBundleExporter(tempDir.resolve("diagnostics")),
                    () -> {},
                    () -> true,
                    path -> {
                      if (calls.incrementAndGet() == 1) {
                        firstOpened.countDown();
                        try {
                          releaseFirst.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        } finally {
                          firstReturned.countDown();
                        }
                        throw new IllegalStateException("folder unavailable");
                      }
                      secondOpened.countDown();
                    });
            panel.set(dialog);
            assertTrue(clickExport(dialog));
          });
      assertTrue(firstOpened.await(10, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> assertTrue(clickExport(panel.get())));
      assertTrue(secondOpened.await(10, TimeUnit.SECONDS));
      AtomicReference<String> successfulStatus = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> successfulStatus.set(panel.get().statusText()));
      releaseFirst.countDown();
      assertTrue(firstReturned.await(10, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(
          () -> {
            assertEquals(successfulStatus.get(), panel.get().statusText());
            assertFalse(panel.get().cancelButton().isVisible());
          });
      try (var files = Files.list(tempDir.resolve("diagnostics"))) {
        assertEquals(2, files.filter(p -> p.toString().endsWith(".zip")).count());
      }
    } finally {
      releaseFirst.countDown();
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  @Test
  void activeScopesStayTruthfulAndApplyOnlyChangesTheNextSession() {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    runtime.applySettings(
        runtime.settings().withPreferredTraceScopes(EnumSet.of(TraceScope.NETWORK_WEBSOCKET)));
    DiagnosticsDialog dialog = dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true);

    assertTrue(dialog.fullLogsEnabledBox().isSelected());
    assertTrue(dialog.scopeEngineBox().isSelected());
    assertFalse(dialog.scopeReadBoardBox().isSelected());
    assertFalse(dialog.scopeNetworkBox().isSelected());
    assertFalse(dialog.scopeEngineBox().isEnabled());
    assertTrue(
        dialog
            .activeScopesText()
            .contains(Lizzie.resourceBundle.getString("DiagnosticsDialog.scope.engineGtp")),
        dialog.activeScopesText());

    dialog.diagnosticsEnabledBox().setSelected(false);
    dialog.applyCurrentPlan();
    assertTrue(runtime.fullTraceActive());
    assertEquals(EnumSet.of(TraceScope.ENGINE_GTP), runtime.activeTraceScopes());
    assertEquals(
        EnumSet.of(TraceScope.NETWORK_WEBSOCKET), runtime.settings().preferredTraceScopes());
    assertEquals(
        Lizzie.resourceBundle.getString("DiagnosticsDialog.activeScopesUnchanged"),
        dialog.statusText());

    dialog.fullLogsEnabledBox().doClick();
    assertTrue(dialog.scopeEngineBox().isEnabled());
    assertTrue(dialog.scopeNetworkBox().isEnabled());
    dialog.scopeEngineBox().setSelected(false);
    dialog.scopeNetworkBox().setSelected(true);
    dialog.applyCurrentPlan();
    assertFalse(runtime.fullTraceActive());
    assertEquals(
        EnumSet.of(TraceScope.NETWORK_WEBSOCKET), runtime.settings().preferredTraceScopes());
  }

  @Test
  void hostPaneShowsSessionAndDoesNotExposeHelperToggles() {
    LoggingRuntime runtime = start();
    List<Path> opened = new ArrayList<>();
    DiagnosticsDialog dialog = dialog(runtime, new AtomicInteger(), opened, () -> true);
    dialog.openLogsDirectory();

    assertTrue(dialog.hostSessionText().contains(runtime.applicationLogSessionId()));
    assertTrue(
        dialog.hostAppLogText().contains(runtime.logsDirectory().resolve("app.log").toString()));
    assertTrue(
        dialog
            .hostCrashLogText()
            .contains(runtime.logsDirectory().resolve("crash.log").toString()));
    assertFalse(dialog.hostAppLogText().contains("readboard"));
    assertFalse(dialog.hostCrashLogText().contains("readboard"));
    assertTrue(dialog.hostPaneText().contains("Engine/GTP"));
    assertTrue(dialog.hostPaneText().contains("ReadBoard/Yike"));
    assertFalse(dialog.hostPaneText().contains("Full Trace"));
    assertFalse(dialog.hostPaneText().contains("Capture"));
    assertTrue(dialog.helperPaneText().contains("Diagnostics"));
    assertTrue(dialog.helperPaneText().contains("Full Logs"));
    assertTrue(dialog.helperPaneText().contains("Capture"));
    assertEquals(runtime.logsDirectory(), opened.get(0));
    assertTrue(opened.get(0).endsWith("logs"));
    assertFalse(opened.get(0).endsWith("readboard"));
  }

  @Test
  void helperPaneRendersDesiredObservedFromHostSnapshot() {
    LoggingRuntime runtime = start();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 dGVzdFByb2Nlc3NJRA off off off healthy 0"));
    RecordingHelper helper = new RecordingHelper(control);
    DiagnosticsDialog dialog =
        dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true, helper, () -> true);

    assertTrue(dialog.helperCapabilityText().toLowerCase().contains("ready"));
    assertTrue(dialog.helperPersistenceText().toLowerCase().contains("healthy"));
    assertTrue(dialog.helperDropCountText().contains("0"));
    assertTrue(dialog.helperProcessSessionText().contains("dGVzdFByb2Nlc3NJRA"));
    assertFalse(dialog.helperDiagnosticsBox().isSelected());
    assertEquals("Off", dialog.helperDiagnosticsObservedText());

    dialog.helperDiagnosticsBox().doClick();

    assertEquals(1, helper.sets);
    assertTrue(control.desired().diagnostics);
    assertFalse(control.desired().capture);
    assertFalse(control.desired().trace);
    assertEquals("Not applied", dialog.helperDiagnosticsObservedText());
    assertNotEquals("On", dialog.helperDiagnosticsObservedText());

    control.onObserved(
        ReadBoardLoggingProtocol.tryParseObserved(
            "readboardLoggingObserved "
                + helper.lastRequestId
                + " dGVzdFByb2Nlc3NJRA on off off healthy 0 applied"));
    dialog.refreshFromRuntime();
    assertEquals("On", dialog.helperDiagnosticsObservedText());
    dialog.helperDiagnosticsBox().doClick();
    assertEquals(2, helper.sets);
    assertFalse(dialog.helperDiagnosticsBox().isSelected());
    assertEquals("On", dialog.helperDiagnosticsObservedText());
  }

  @Test
  void helperUnknownAndPathFailureAreDistinct() {
    LoggingRuntime runtime = start();
    ReadBoardLoggingControl unknown =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    DiagnosticsDialog unknownDialog =
        dialog(
            runtime,
            new AtomicInteger(),
            new ArrayList<>(),
            () -> true,
            new RecordingHelper(unknown),
            () -> true);
    assertEquals("Unknown", unknownDialog.helperDiagnosticsObservedText());
    assertEquals("Unknown", unknownDialog.helperCaptureObservedText());

    ReadBoardLoggingControl legacy =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), false);
    DiagnosticsDialog legacyDialog =
        dialog(
            runtime,
            new AtomicInteger(),
            new ArrayList<>(),
            () -> true,
            new RecordingHelper(legacy),
            () -> true);
    assertEquals("Legacy, unconfirmed", legacyDialog.helperDiagnosticsObservedText());

    ReadBoardLoggingControl degraded =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(true), true);
    degraded.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 dGVzdFByb2Nlc3NJRA on off off degraded 3"));
    DiagnosticsDialog degradedDialog =
        dialog(
            runtime,
            new AtomicInteger(),
            new ArrayList<>(),
            () -> true,
            new RecordingHelper(degraded),
            () -> true);
    assertEquals("On, storage degraded", degradedDialog.helperDiagnosticsObservedText());
    assertNotEquals("Unknown", degradedDialog.helperDiagnosticsObservedText());
    assertTrue(degradedDialog.helperDropCountText().contains("3"));
    assertTrue(degradedDialog.helperPersistenceText().toLowerCase().contains("degraded"));
  }

  @Test
  void captureConfirmationCanCancelAndIsRequiredEvenWhenDiagnosticsOn() {
    LoggingRuntime runtime = start();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(true), true);
    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 dGVzdFByb2Nlc3NJRA on off off healthy 0"));
    RecordingHelper helper = new RecordingHelper(control);
    AtomicBoolean confirm = new AtomicBoolean(false);
    DiagnosticsDialog dialog =
        dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true, helper, confirm::get);

    assertTrue(dialog.helperDiagnosticsBox().isSelected());
    assertEquals("On", dialog.helperDiagnosticsObservedText());
    dialog.helperCaptureBox().doClick();
    assertEquals(0, helper.sets);
    assertFalse(control.desired().capture);
    assertFalse(dialog.helperCaptureBox().isSelected());
    assertTrue(dialog.captureConfirmBody().toLowerCase().contains("capture"));

    confirm.set(true);
    dialog.helperCaptureBox().doClick();
    assertEquals(1, helper.sets);
    assertTrue(control.desired().capture);
    assertTrue(control.desired().diagnostics);
    assertFalse(control.desired().trace);
    assertEquals("Not applied", dialog.helperCaptureObservedText());
  }

  @Test
  void captureMustBeReconfirmedAfterProcessReset() {
    LoggingRuntime runtime = start();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(true), true);
    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 dGVzdFByb2Nlc3NJRA on off off healthy 0"));
    RecordingHelper helper = new RecordingHelper(control);
    AtomicInteger confirms = new AtomicInteger();
    DiagnosticsDialog dialog =
        dialog(
            runtime,
            new AtomicInteger(),
            new ArrayList<>(),
            () -> true,
            helper,
            () -> {
              confirms.incrementAndGet();
              return true;
            });

    dialog.helperCaptureBox().doClick();
    assertEquals(1, confirms.get());
    control.resetForNewProcess();
    dialog.refreshFromRuntime();
    assertFalse(dialog.helperCaptureBox().isSelected());
    assertEquals("Unknown", dialog.helperCaptureObservedText());

    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 bmV3UHJvY2Vzcw on off off healthy 0"));
    dialog.refreshFromRuntime();
    dialog.helperCaptureBox().doClick();
    assertEquals(2, confirms.get());
    assertTrue(control.desired().capture);
  }

  @Test
  void helperTogglesAreIndependent() {
    LoggingRuntime runtime = start();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 dGVzdFByb2Nlc3NJRA off off off healthy 0"));
    RecordingHelper helper = new RecordingHelper(control);
    DiagnosticsDialog dialog =
        dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true, helper, () -> true);

    dialog.helperTraceBox().doClick();
    assertFalse(control.desired().diagnostics);
    assertFalse(control.desired().capture);
    assertTrue(control.desired().trace);
    assertEquals("Not applied", dialog.helperTraceObservedText());
    assertEquals("Off", dialog.helperDiagnosticsObservedText());
    assertEquals("Off", dialog.helperCaptureObservedText());
  }

  @Test
  void exportRequestForwardsHelperSessionAndReadBoardTraceOptIn() {
    LoggingRuntime runtime = start();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(ReadBoardLoggingControl.Desired.launchDefaults(false), true);
    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 dGVzdFByb2Nlc3NJRA off off off healthy 0"));
    RecordingHelper helper = new RecordingHelper(control);
    DiagnosticsDialog dialog =
        dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true, helper, () -> true);

    assertTrue(dialog.currentRequest().includeCapture());
    assertFalse(dialog.currentRequest().includeReadBoardTrace());
    assertEquals(
        "dGVzdFByb2Nlc3NJRA", dialog.currentRequest().readBoardLogging().processSessionId());
    assertTrue(dialog.currentRequest().rawScopes().isEmpty());

    dialog.helperTraceBox().doClick();
    assertTrue(dialog.currentRequest().includeReadBoardTrace());
    assertTrue(dialog.currentRequest().includeCapture());
    assertTrue(dialog.currentRequest().rawScopes().isEmpty());
  }

  @Test
  void publishedExportCompletesBeforeBlockedFolderOpenerAndKeepsEdtResponsive() throws Exception {
    LoggingRuntime runtime = start();
    CountDownLatch opened = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean openerOnEdt = new AtomicBoolean();
    AtomicReference<DiagnosticsDialog> panel = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            DiagnosticsDialog dialog =
                new DiagnosticsDialog(
                    runtime,
                    null,
                    new DiagnosticBundleExporter(tempDir.resolve("diagnostics")),
                    () -> {},
                    () -> true,
                    path -> {
                      openerOnEdt.set(SwingUtilities.isEventDispatchThread());
                      opened.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
            panel.set(dialog);
            clickExport(dialog);
          });
      assertTrue(opened.await(10, TimeUnit.SECONDS));
      assertFalse(openerOnEdt.get(), "folder opening must not occupy the EDT");
      CountDownLatch heartbeat = new CountDownLatch(1);
      SwingUtilities.invokeLater(
          () -> {
            if (!panel.get().cancelButton().isVisible()
                && panel.get().statusText().contains("Exported")) {
              heartbeat.countDown();
            }
          });
      assertTrue(heartbeat.await(10, TimeUnit.SECONDS));
      try (var files = Files.list(tempDir.resolve("diagnostics"))) {
        assertEquals(1, files.filter(p -> p.toString().endsWith(".zip")).count());
      }
    } finally {
      release.countDown();
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  private static boolean clickExport(java.awt.Container container) {
    for (java.awt.Component component : container.getComponents()) {
      if (component instanceof JButton button
          && button
              .getText()
              .equals(Lizzie.resourceBundle.getString("DiagnosticsDialog.exportDefault"))) {
        button.doClick();
        return true;
      }
      if (component instanceof java.awt.Container child && clickExport(child)) {
        return true;
      }
    }
    return false;
  }

  private DiagnosticsDialog dialog(
      LoggingRuntime runtime,
      AtomicInteger titles,
      List<Path> opened,
      java.util.function.BooleanSupplier confirm) {
    return dialog(runtime, titles, opened, confirm, null, null);
  }

  private DiagnosticsDialog dialog(
      LoggingRuntime runtime,
      AtomicInteger titles,
      List<Path> opened,
      java.util.function.BooleanSupplier confirm,
      DiagnosticsDialog.HelperLogging helper,
      java.util.function.BooleanSupplier captureConfirm) {
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir));
    return new DiagnosticsDialog(
        runtime,
        null,
        exporter,
        titles::incrementAndGet,
        confirm,
        opened::add,
        helper,
        captureConfirm);
  }

  private static final class RecordingHelper implements DiagnosticsDialog.HelperLogging {
    private final ReadBoardLoggingControl control;
    private int sets;
    private String lastRequestId;

    private RecordingHelper(ReadBoardLoggingControl control) {
      this.control = control;
    }

    @Override
    public ReadBoardLoggingSnapshot snapshot() {
      return control.snapshot();
    }

    @Override
    public boolean requestSet(boolean diagnostics, boolean capture, boolean trace) {
      sets++;
      lastRequestId = control.beginSet(diagnostics, capture, trace).requestId;
      return true;
    }
  }

  private LoggingRuntime start() {
    LoggingRuntime.resetForTests();
    return LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
  }
}
