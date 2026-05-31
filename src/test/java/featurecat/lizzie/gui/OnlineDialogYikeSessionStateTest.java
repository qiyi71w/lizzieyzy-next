package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.SyncDiagnosticsRecorder;
import featurecat.lizzie.analysis.YikeSessionDiagnosticsSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class OnlineDialogYikeSessionStateTest {
  @BeforeEach
  void resetDiagnosticsBeforeTest() {
    SyncDiagnosticsRecorder.getDefault().updateYikeSession(null);
  }

  @AfterEach
  void resetDiagnosticsAfterTest() {
    SyncDiagnosticsRecorder.getDefault().updateYikeSession(null);
  }

  @Test
  void pendingRoomDoesNotBecomeActiveUntilSyncAndGeometryAreBothReady() {
    OnlineDialog.YikeSessionState active =
        OnlineDialog.YikeSessionState.active("unite-board:66304678");
    OnlineDialog.YikeSessionState pending =
        OnlineDialog.YikeSessionState.pending("live-room:186538");

    pending = pending.withGeometryReady(true);
    assertFalse(OnlineDialog.shouldPromotePendingSession(active, pending));

    pending = pending.withSyncReady(true);
    assertTrue(OnlineDialog.shouldPromotePendingSession(active, pending));
  }

  @Test
  void waitingPageInvalidatesPlacementGeometryWithoutClearingDisplaySession() {
    assertTrue(
        OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/live"));
    assertTrue(
        OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/game"));
    assertFalse(
        OnlineDialog.shouldInvalidateYikePlacementGeometry(
            "https://home.yikeweiqi.com/#/unite/66304678"));
  }

  @Test
  void geometryFromDifferentSessionKeyIsRejected() {
    assertFalse(
        OnlineDialog.isGeometryForCurrentSession("live-room:186538", "unite-board:66304678"));
    assertTrue(OnlineDialog.isGeometryForCurrentSession("live-room:186538", "live-room:186538"));
  }

  @Test
  void waitingClearIsIgnoredWhenBrowserAlreadyOnBoardPage() {
    assertFalse(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            1000));
  }

  @Test
  void waitingClearIsIgnoredDuringRecentSyncBootstrapWindow() {
    assertFalse(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            1200));
    assertTrue(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            6000));
  }

  @Test
  void yikeRefreshUsesConfiguredSecondsWithoutFiveSecondFloor() {
    assertEquals(3, OnlineDialog.normalizeYikeRefreshSeconds(3));
    assertEquals(1, OnlineDialog.normalizeYikeRefreshSeconds(1));
    assertEquals(1, OnlineDialog.normalizeYikeRefreshSeconds(0));
  }

  @Test
  void staleLiveFetchResultIsRejectedAfterStopOrSessionChange() {
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(true, true, 6, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, false, 6, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 7, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 6, 6, 3, 2));
    assertTrue(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 6, 6, 2, 2));
  }

  @Test
  void publishesPendingGeometryReadyBeforeSyncReady() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");

    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("live-room:186538", snapshot.getPendingSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getPendingSyncReady());
    assertEquals(Boolean.TRUE, snapshot.getPendingGeometryReady());
    assertEquals("none", snapshot.getActiveSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getPlacementGeometryAllowed());
    assertEquals("none", snapshot.getLastGeometryClearReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  @Test
  void publishesPromotedActiveSessionAfterSyncAndGeometryReady() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");

    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});
    invoke(
        dialog,
        "markYikeSyncReady",
        new Class<?>[] {String.class, int.class},
        new Object[] {"live-room:186538", 19});

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("live-room:186538", snapshot.getActiveSessionKey());
    assertEquals(Boolean.TRUE, snapshot.getActiveSyncReady());
    assertEquals(Boolean.TRUE, snapshot.getActiveGeometryReady());
    assertEquals(19, snapshot.getActiveBoardSize());
    assertEquals("none", snapshot.getPendingSessionKey());
    assertEquals(Boolean.TRUE, snapshot.getPlacementGeometryAllowed());
    assertEquals("pending-promoted", snapshot.getLastSessionSwitchReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  @Test
  void publishesClearedSessionAfterReset() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");

    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});
    invoke(
        dialog,
        "markYikeSyncReady",
        new Class<?>[] {String.class, int.class},
        new Object[] {"live-room:186538", 19});
    invoke(dialog, "resetYikeSessions");

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("none", snapshot.getActiveSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getActiveGeometryReady());
    assertEquals("none", snapshot.getPendingSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getEffectiveGeometryReady());
    assertEquals(Boolean.FALSE, snapshot.getPlacementGeometryAllowed());
    assertEquals("sessions-reset", snapshot.getLastGeometryClearReason());
    assertEquals("sessions-reset", snapshot.getLastSessionSwitchReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  @Test
  void publishesClearedGeometryAfterInvalidateWithoutClearingActiveSession() throws Exception {
    OnlineDialog dialog = activeReadyDialog();

    invoke(dialog, "invalidateYikePlacementGeometry");

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("live-room:186538", snapshot.getActiveSessionKey());
    assertEquals(Boolean.TRUE, snapshot.getActiveSyncReady());
    assertEquals(Boolean.FALSE, snapshot.getActiveGeometryReady());
    assertEquals(Boolean.FALSE, snapshot.getEffectiveGeometryReady());
    assertEquals(Boolean.FALSE, snapshot.getPlacementGeometryAllowed());
    assertEquals("placement-geometry-invalidated", snapshot.getLastGeometryClearReason());
    assertEquals("pending-promoted", snapshot.getLastSessionSwitchReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  private static OnlineDialog dialogForYikeUrl(String url) throws Exception {
    OnlineDialog dialog = (OnlineDialog) unsafe().allocateInstance(OnlineDialog.class);
    JTextField txtUrl = new JTextField();
    txtUrl.setText(url);
    setField(dialog, "txtUrl", txtUrl);
    setField(dialog, "activeYikeSession", OnlineDialog.YikeSessionState.empty());
    setField(dialog, "pendingYikeSession", OnlineDialog.YikeSessionState.empty());
    setField(dialog, "boardSize", 19);
    setField(dialog, "hasResolvedYikeBoardSize", false);
    setField(dialog, "lastYikeGeometry", null);
    return dialog;
  }

  private static OnlineDialog activeReadyDialog() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");
    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});
    invoke(
        dialog,
        "markYikeSyncReady",
        new Class<?>[] {String.class, int.class},
        new Object[] {"live-room:186538", 19});
    return dialog;
  }

  private static String currentUrl(OnlineDialog dialog) throws Exception {
    JTextField txtUrl = (JTextField) getField(dialog, "txtUrl");
    return txtUrl.getText();
  }

  private static YikeSessionDiagnosticsSnapshot currentYikeSnapshot() {
    return SyncDiagnosticsRecorder.getDefault().snapshot().getYikeSnapshot();
  }

  private static Object newGeometry() throws Exception {
    Constructor<?> constructor =
        geometryClass()
            .getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                int.class,
                Double.class,
                Double.class,
                Double.class,
                Double.class,
                int.class,
                String.class,
                String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(10, 20, 380, 380, 10.0, 20.0, 20.0, 20.0, 100, "#board", "test");
  }

  private static Class<?> geometryClass() throws Exception {
    return Class.forName("featurecat.lizzie.gui.OnlineDialog$YikeGeometrySnapshot");
  }

  private static void invoke(Object target, String methodName) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName);
    method.setAccessible(true);
    method.invoke(target);
  }

  private static void invoke(Object target, String methodName, Class<?> argType, Object arg)
      throws Exception {
    invoke(target, methodName, new Class<?>[] {argType}, new Object[] {arg});
  }

  private static void invoke(Object target, String methodName, Class<?>[] argTypes, Object[] args)
      throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName, argTypes);
    method.setAccessible(true);
    method.invoke(target, args);
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Unsafe unsafe() throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (Unsafe) field.get(null);
  }
}
