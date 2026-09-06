package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.LogArchiveBoundary;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

public final class ReadBoardLoggingControl {
  static final long NO_TIMEOUT_GENERATION = -1L;

  public enum Status {
    STARTING,
    CAPABILITY_READY,
    APPLYING,
    OBSERVED,
    UNKNOWN,
    LEGACY_UNCONFIRMED
  }

  public enum Presentation {
    OFF,
    ON,
    ON_STORAGE_DEGRADED,
    ON_STORAGE_UNAVAILABLE,
    NOT_APPLIED,
    UNKNOWN,
    LEGACY_UNCONFIRMED
  }

  public static final class Desired {
    private static final Desired DIAGNOSTICS_OFF = new Desired(false, false, false);
    private static final Desired DIAGNOSTICS_ON = new Desired(true, false, false);

    public final boolean diagnostics;
    public final boolean capture;
    public final boolean trace;

    public Desired(boolean diagnostics, boolean capture, boolean trace) {
      this.diagnostics = diagnostics;
      this.capture = capture;
      this.trace = trace;
    }

    public static Desired launchDefaults(boolean diagnosticsPolicy) {
      return diagnosticsPolicy ? DIAGNOSTICS_ON : DIAGNOSTICS_OFF;
    }
  }

  static final class PendingSet {
    final ReadBoardLoggingProtocol.SetRequest request;
    final long timeoutGeneration;

    private PendingSet(ReadBoardLoggingProtocol.SetRequest request, long timeoutGeneration) {
      this.request = request;
      this.timeoutGeneration = timeoutGeneration;
    }
  }

  private final boolean contractLaunch;
  private final SecureRandom random = new SecureRandom();
  private ReadBoardLoggingProtocol.RequestGate gate = new ReadBoardLoggingProtocol.RequestGate();
  private Desired desired;
  private ReadBoardLoggingProtocol.Observed observed;
  private String processSessionId;
  private Instant processSessionObservedAt;
  private Status status;
  private long stateGeneration;
  private boolean disconnected;
  private LogArchiveBoundary archiveBoundary = LogArchiveBoundary.empty();

  public ReadBoardLoggingControl(Desired desired, boolean contractLaunch) {
    this.desired = desired == null ? Desired.launchDefaults(false) : desired;
    this.contractLaunch = contractLaunch;
    this.status = contractLaunch ? Status.STARTING : Status.LEGACY_UNCONFIRMED;
  }

  public static ReadBoardLoggingControl forLaunch(
      Desired desired, boolean contractLaunch, Path logDirectory) {
    ReadBoardLoggingControl control = new ReadBoardLoggingControl(desired, contractLaunch);
    if (contractLaunch && logDirectory != null) {
      control.archiveBoundary = LogArchiveBoundary.capture(logDirectory);
    }
    return control;
  }

  synchronized LogArchiveBoundary archiveBoundary() {
    return archiveBoundary;
  }

  public static String readBoardLogDirectory(Path logsDirectory) {
    if (logsDirectory == null) {
      return "";
    }
    return logsDirectory.resolve("readboard").toAbsolutePath().toString();
  }

  public static List<String> appendNamedLoggingArguments(
      List<String> positional, String logDir, String hostSessionId, boolean diagnostics) {
    if (positional == null || positional.size() < 7) {
      return positional;
    }
    List<String> head = new ArrayList<String>(positional.subList(0, 7));
    if (!ReadBoardLoggingProtocol.isAbsoluteLogDirectory(logDir)
        || !ReadBoardLoggingProtocol.isOpaqueId(hostSessionId)) {
      return Collections.unmodifiableList(head);
    }
    return ReadBoardLoggingProtocol.appendLaunchArguments(
        head, logDir, hostSessionId, diagnostics, false);
  }

  public synchronized boolean isContractLaunch() {
    return contractLaunch;
  }

  public synchronized Status status() {
    return status;
  }

  public synchronized Desired desired() {
    return desired;
  }

  public synchronized ReadBoardLoggingProtocol.Observed observed() {
    return observed;
  }

  public synchronized String processSessionId() {
    return processSessionId;
  }

  public synchronized Instant processSessionObservedAt() {
    return processSessionObservedAt;
  }

  public synchronized boolean awaitsCapability() {
    return !disconnected && contractLaunch && status == Status.STARTING;
  }

  public synchronized long onReady() {
    if (disconnected) {
      return NO_TIMEOUT_GENERATION;
    }
    if (!contractLaunch) {
      status = Status.LEGACY_UNCONFIRMED;
      clearObservedSuccess();
      invalidatePendingWaitLocked();
      return NO_TIMEOUT_GENERATION;
    }
    if (status == Status.CAPABILITY_READY
        || status == Status.OBSERVED
        || status == Status.APPLYING) {
      return NO_TIMEOUT_GENERATION;
    }
    status = Status.STARTING;
    return beginPendingWaitLocked();
  }

  public synchronized boolean onCapability(ReadBoardLoggingProtocol.Capability capability) {
    if (capability == null
        || disconnected
        || (processSessionId != null && !processSessionId.equals(capability.processSessionId))) {
      return false;
    }
    ReadBoardLoggingProtocol.Observed nextObserved = observedFromCapability(capability);
    ReadBoardLoggingProtocol.RequestGate nextGate = new ReadBoardLoggingProtocol.RequestGate();
    Instant nextObservedAt = observedSessionAt(capability.processSessionId);
    processSessionId = capability.processSessionId;
    processSessionObservedAt = nextObservedAt;
    observed = nextObserved;
    gate = nextGate;
    status = Status.CAPABILITY_READY;
    invalidatePendingWaitLocked();
    return true;
  }

  public synchronized boolean onObserved(ReadBoardLoggingProtocol.Observed incoming) {
    if (incoming == null
        || disconnected
        || status != Status.APPLYING
        || !gate.acceptObserved(incoming)) {
      return false;
    }
    if (processSessionId != null && !processSessionId.equals(incoming.processSessionId)) {
      return false;
    }
    Instant nextObservedAt = observedSessionAt(incoming.processSessionId);
    processSessionId = incoming.processSessionId;
    processSessionObservedAt = nextObservedAt;
    observed = incoming;
    status = Status.OBSERVED;
    invalidatePendingWaitLocked();
    return true;
  }

  public synchronized boolean onCapabilityTimeout() {
    return onCapabilityTimeoutIfCurrent(stateGeneration);
  }

  synchronized boolean onCapabilityTimeoutIfCurrent(long expectedGeneration) {
    if (disconnected || expectedGeneration != stateGeneration) {
      return false;
    }
    if (contractLaunch && status != Status.STARTING && status != Status.APPLYING) {
      return false;
    }
    if (!contractLaunch) {
      status = Status.LEGACY_UNCONFIRMED;
      clearObservedSuccess();
      invalidatePendingWaitLocked();
      return true;
    }
    if (status == Status.STARTING) {
      status = Status.UNKNOWN;
      clearObservedSuccess();
      invalidatePendingWaitLocked();
      return true;
    }
    if (status == Status.APPLYING) {
      status = Status.UNKNOWN;
      // Once timeout wins, a late acknowledgement for that request cannot revive success.
      invalidatePendingWaitLocked();
      return true;
    }
    return false;
  }

  public synchronized void onDisconnect() {
    disconnected = true;
    status = contractLaunch ? Status.UNKNOWN : Status.LEGACY_UNCONFIRMED;
    desired = Desired.launchDefaults(desired.diagnostics);
    clearObservedSuccess();
    invalidatePendingWaitLocked();
  }

  public synchronized void resetForNewProcess() {
    Desired nextDesired = Desired.launchDefaults(desired.diagnostics);
    ReadBoardLoggingProtocol.RequestGate nextGate = new ReadBoardLoggingProtocol.RequestGate();
    disconnected = false;
    processSessionId = null;
    processSessionObservedAt = null;
    observed = null;
    gate = nextGate;
    desired = nextDesired;
    status = contractLaunch ? Status.STARTING : Status.LEGACY_UNCONFIRMED;
    invalidatePendingWaitLocked();
  }

  public synchronized ReadBoardLoggingProtocol.SetRequest beginSet(
      boolean diagnostics, boolean capture, boolean trace) {
    return beginSetLocked(diagnostics, capture, trace).request;
  }

  synchronized PendingSet beginSetIfCurrent(boolean diagnostics, boolean capture, boolean trace) {
    if (disconnected || processSessionId == null || processSessionId.isEmpty()) {
      return null;
    }
    return beginSetLocked(diagnostics, capture, trace);
  }

  private PendingSet beginSetLocked(boolean diagnostics, boolean capture, boolean trace) {
    String requestId = newRequestId();
    ReadBoardLoggingProtocol.SetRequest request =
        new ReadBoardLoggingProtocol.SetRequest(
            requestId, toggle(diagnostics), toggle(capture), toggle(trace));
    Desired nextDesired = new Desired(diagnostics, capture, trace);
    ReadBoardLoggingProtocol.RequestGate nextGate = new ReadBoardLoggingProtocol.RequestGate();
    nextGate.noteRequest(requestId);
    long timeoutGeneration = stateGeneration + 1L;
    PendingSet pending = new PendingSet(request, timeoutGeneration);
    desired = nextDesired;
    gate = nextGate;
    status = Status.APPLYING;
    stateGeneration = timeoutGeneration;
    return pending;
  }

  synchronized boolean isTimeoutGenerationCurrent(long expectedGeneration) {
    return !disconnected
        && expectedGeneration == stateGeneration
        && (status == Status.STARTING || status == Status.APPLYING);
  }

  synchronized boolean isDisconnected() {
    return disconnected;
  }

  public synchronized ReadBoardLoggingSnapshot snapshot() {
    return ReadBoardLoggingSnapshot.from(this);
  }

  public synchronized Presentation presentation(
      boolean desiredOn,
      ReadBoardLoggingProtocol.Toggle observedToggle,
      ReadBoardLoggingProtocol.Persistence persistence) {
    if (status == Status.LEGACY_UNCONFIRMED) {
      return Presentation.LEGACY_UNCONFIRMED;
    }
    if (status == Status.UNKNOWN
        || status == Status.STARTING
        || observedToggle == null
        || observedToggle == ReadBoardLoggingProtocol.Toggle.UNKNOWN) {
      return Presentation.UNKNOWN;
    }
    if (desiredOn && observedToggle == ReadBoardLoggingProtocol.Toggle.OFF) {
      return Presentation.NOT_APPLIED;
    }
    if (observedToggle == ReadBoardLoggingProtocol.Toggle.ON) {
      if (persistence == ReadBoardLoggingProtocol.Persistence.DEGRADED) {
        return Presentation.ON_STORAGE_DEGRADED;
      }
      if (persistence == ReadBoardLoggingProtocol.Persistence.UNAVAILABLE) {
        return Presentation.ON_STORAGE_UNAVAILABLE;
      }
      return Presentation.ON;
    }
    return Presentation.OFF;
  }

  private void clearObservedSuccess() {
    processSessionId = null;
    processSessionObservedAt = null;
    observed = null;
  }

  private Instant observedSessionAt(String incoming) {
    if (incoming == null || incoming.isEmpty()) {
      return processSessionObservedAt;
    }
    if (processSessionObservedAt == null) {
      return Instant.now();
    }
    return processSessionObservedAt;
  }

  /** Caller holds this control's monitor. */
  private long beginPendingWaitLocked() {
    stateGeneration++;
    return stateGeneration;
  }

  /** Caller holds this control's monitor. */
  private void invalidatePendingWaitLocked() {
    stateGeneration++;
  }

  private String newRequestId() {
    byte[] bytes = new byte[12];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static ReadBoardLoggingProtocol.Toggle toggle(boolean on) {
    return on ? ReadBoardLoggingProtocol.Toggle.ON : ReadBoardLoggingProtocol.Toggle.OFF;
  }

  private static ReadBoardLoggingProtocol.Observed observedFromCapability(
      ReadBoardLoggingProtocol.Capability capability) {
    return new ReadBoardLoggingProtocol.Observed(
        "",
        capability.processSessionId,
        toObservedToggle(capability.diagnostics),
        toObservedToggle(capability.capture),
        toObservedToggle(capability.trace),
        capability.persistence,
        capability.dropCount,
        ReadBoardLoggingProtocol.Reason.APPLIED);
  }

  private static ReadBoardLoggingProtocol.Toggle toObservedToggle(
      ReadBoardLoggingProtocol.Toggle toggle) {
    return toggle == null ? ReadBoardLoggingProtocol.Toggle.UNKNOWN : toggle;
  }
}
