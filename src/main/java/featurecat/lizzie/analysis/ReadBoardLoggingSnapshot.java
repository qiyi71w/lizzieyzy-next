package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.LogArchiveBoundary;
import java.time.Instant;

public final class ReadBoardLoggingSnapshot {
  private final boolean attached;
  private final boolean contractLaunch;
  private final ReadBoardLoggingControl.Status status;
  private final String processSessionId;
  private final boolean capabilityKnown;
  private final ReadBoardLoggingControl.Desired desired;
  private final ReadBoardLoggingProtocol.Toggle observedDiagnostics;
  private final ReadBoardLoggingProtocol.Toggle observedCapture;
  private final ReadBoardLoggingProtocol.Toggle observedTrace;
  private final ReadBoardLoggingProtocol.Persistence persistence;
  private final int dropCount;
  private final ReadBoardLoggingProtocol.Reason reason;
  private final ReadBoardLoggingControl.Presentation diagnosticsPresentation;
  private final ReadBoardLoggingControl.Presentation capturePresentation;
  private final ReadBoardLoggingControl.Presentation tracePresentation;
  private final String captureSummary;
  private final Instant processSessionObservedAt;
  private final LogArchiveBoundary archiveBoundary;

  private ReadBoardLoggingSnapshot(
      boolean attached,
      boolean contractLaunch,
      ReadBoardLoggingControl.Status status,
      String processSessionId,
      boolean capabilityKnown,
      ReadBoardLoggingControl.Desired desired,
      ReadBoardLoggingProtocol.Toggle observedDiagnostics,
      ReadBoardLoggingProtocol.Toggle observedCapture,
      ReadBoardLoggingProtocol.Toggle observedTrace,
      ReadBoardLoggingProtocol.Persistence persistence,
      int dropCount,
      ReadBoardLoggingProtocol.Reason reason,
      ReadBoardLoggingControl.Presentation diagnosticsPresentation,
      ReadBoardLoggingControl.Presentation capturePresentation,
      ReadBoardLoggingControl.Presentation tracePresentation,
      String captureSummary,
      Instant processSessionObservedAt,
      LogArchiveBoundary archiveBoundary) {
    this.attached = attached;
    this.contractLaunch = contractLaunch;
    this.status = status;
    this.processSessionId = processSessionId;
    this.capabilityKnown = capabilityKnown;
    this.desired = desired;
    this.observedDiagnostics = observedDiagnostics;
    this.observedCapture = observedCapture;
    this.observedTrace = observedTrace;
    this.persistence = persistence;
    this.dropCount = dropCount;
    this.reason = reason;
    this.diagnosticsPresentation = diagnosticsPresentation;
    this.capturePresentation = capturePresentation;
    this.tracePresentation = tracePresentation;
    this.captureSummary = captureSummary;
    this.processSessionObservedAt = processSessionObservedAt;
    this.archiveBoundary = archiveBoundary;
  }

  public static ReadBoardLoggingSnapshot detached() {
    return new ReadBoardLoggingSnapshot(
        false,
        false,
        ReadBoardLoggingControl.Status.UNKNOWN,
        "",
        false,
        ReadBoardLoggingControl.Desired.launchDefaults(false),
        ReadBoardLoggingProtocol.Toggle.UNKNOWN,
        ReadBoardLoggingProtocol.Toggle.UNKNOWN,
        ReadBoardLoggingProtocol.Toggle.UNKNOWN,
        null,
        -1,
        null,
        ReadBoardLoggingControl.Presentation.UNKNOWN,
        ReadBoardLoggingControl.Presentation.UNKNOWN,
        ReadBoardLoggingControl.Presentation.UNKNOWN,
        "no capture session",
        null,
        LogArchiveBoundary.empty());
  }

  static ReadBoardLoggingSnapshot from(ReadBoardLoggingControl control) {
    if (control == null) {
      return detached();
    }
    ReadBoardLoggingControl.Desired desired = control.desired();
    ReadBoardLoggingProtocol.Observed observed = control.observed();
    String processSessionId = control.processSessionId() == null ? "" : control.processSessionId();
    ReadBoardLoggingProtocol.Toggle diagnostics =
        observed == null ? ReadBoardLoggingProtocol.Toggle.UNKNOWN : observed.diagnostics;
    ReadBoardLoggingProtocol.Toggle capture =
        observed == null ? ReadBoardLoggingProtocol.Toggle.UNKNOWN : observed.capture;
    ReadBoardLoggingProtocol.Toggle trace =
        observed == null ? ReadBoardLoggingProtocol.Toggle.UNKNOWN : observed.trace;
    ReadBoardLoggingProtocol.Persistence persistence =
        observed == null ? null : observed.persistence;
    int dropCount = observed == null ? -1 : observed.dropCount;
    ReadBoardLoggingProtocol.Reason reason = observed == null ? null : observed.reason;
    return new ReadBoardLoggingSnapshot(
        true,
        control.isContractLaunch(),
        control.status(),
        processSessionId,
        observed != null,
        desired,
        diagnostics,
        capture,
        trace,
        persistence,
        dropCount,
        reason,
        control.presentation(desired.diagnostics, diagnostics, persistence),
        control.presentation(desired.capture, capture, persistence),
        control.presentation(desired.trace, trace, persistence),
        captureSummary(processSessionId, desired.capture, capture, persistence),
        control.processSessionObservedAt(),
        control.archiveBoundary());
  }

  public boolean attached() {
    return attached;
  }

  public boolean contractLaunch() {
    return contractLaunch;
  }

  public ReadBoardLoggingControl.Status status() {
    return status;
  }

  public String processSessionId() {
    return processSessionId;
  }

  public boolean capabilityKnown() {
    return capabilityKnown;
  }

  public ReadBoardLoggingControl.Desired desired() {
    return desired;
  }

  public ReadBoardLoggingProtocol.Toggle observedDiagnostics() {
    return observedDiagnostics;
  }

  public ReadBoardLoggingProtocol.Toggle observedCapture() {
    return observedCapture;
  }

  public ReadBoardLoggingProtocol.Toggle observedTrace() {
    return observedTrace;
  }

  public ReadBoardLoggingProtocol.Persistence persistence() {
    return persistence;
  }

  public int dropCount() {
    return dropCount;
  }

  public ReadBoardLoggingProtocol.Reason reason() {
    return reason;
  }

  public ReadBoardLoggingControl.Presentation diagnosticsPresentation() {
    return diagnosticsPresentation;
  }

  public ReadBoardLoggingControl.Presentation capturePresentation() {
    return capturePresentation;
  }

  public ReadBoardLoggingControl.Presentation tracePresentation() {
    return tracePresentation;
  }

  public String captureSummary() {
    return captureSummary;
  }

  public Instant processSessionObservedAt() {
    return processSessionObservedAt;
  }

  public LogArchiveBoundary archiveBoundary() {
    return archiveBoundary;
  }

  private static String captureSummary(
      String processSessionId,
      boolean desiredCapture,
      ReadBoardLoggingProtocol.Toggle observedCapture,
      ReadBoardLoggingProtocol.Persistence persistence) {
    if (processSessionId == null || processSessionId.isEmpty()) {
      return "no capture session";
    }
    return "processSession="
        + processSessionId
        + " desired="
        + (desiredCapture ? "on" : "off")
        + " observed="
        + token(observedCapture)
        + " persistence="
        + (persistence == null ? "none" : persistence.name().toLowerCase().replace('_', '-'));
  }

  private static String token(ReadBoardLoggingProtocol.Toggle toggle) {
    if (toggle == null) {
      return "unknown";
    }
    return toggle.name().toLowerCase();
  }
}
