package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameMatchRulesSelection;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import featurecat.lizzie.enginegame.StartFailure;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineGameMatchRulesToolbarGateTest {
  private static final Map<RecordingFrame, AtomicInteger> DIALOG_OPENS = new IdentityHashMap<>();

  private Config previousConfig;
  private LizzieFrame previousFrame;

  @BeforeEach
  void saveGlobals() {
    previousConfig = Lizzie.config;
    previousFrame = Lizzie.frame;
  }

  @AfterEach
  void restoreGlobals() {
    Lizzie.config = previousConfig;
    Lizzie.frame = previousFrame;
    DIALOG_OPENS.clear();
  }

  @Test
  void emptyStoredSelectionOpensDialogInsteadOfStarting() throws Exception {
    Lizzie.config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-toolbar"));
    Lizzie.config.engineGameMatchRules = "";
    RecordingFrame frame = allocate(RecordingFrame.class);
    DIALOG_OPENS.put(frame, new AtomicInteger());
    Lizzie.frame = frame;
    BottomToolbar toolbar = allocate(SilentToolbar.class);

    assertTrue(EngineGameMatchRulesSelection.stored(Lizzie.config).isEmpty());
    assertFalse(toolbar.startEngineGame());
    assertEquals(1, DIALOG_OPENS.get(frame).get());
  }

  @Test
  void corruptStoredSelectionOpensDialogInsteadOfStarting() throws Exception {
    Lizzie.config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("match-rules-corrupt"));
    Lizzie.config.engineGameMatchRules = "not-a-rules-value";
    RecordingFrame frame = allocate(RecordingFrame.class);
    DIALOG_OPENS.put(frame, new AtomicInteger());
    Lizzie.frame = frame;
    BottomToolbar toolbar = allocate(SilentToolbar.class);

    assertTrue(EngineGameMatchRulesSelection.storedIsCorrupt(Lizzie.config));
    assertFalse(toolbar.startEngineGame());
    assertEquals(1, DIALOG_OPENS.get(frame).get());
  }

  @Test
  void matchRulesRejectAndConsentRefuseSurfaceSameLocalizedReasonAsDialog() throws Exception {
    RecordingToolbar toolbar = allocate(RecordingToolbar.class);

    toolbar.notifyEngineGameStartFailed(new StartFailure.MatchRulesFailed("mismatch"));
    assertEquals(
        MatchRulesTexts.failureMessage("mismatch", Lizzie.resourceBundle), toolbar.lastMessage);

    toolbar.notifyEngineGameStartFailed(
        new StartFailure.MatchRulesFailed("unverified-consent-refused"));
    assertEquals(
        MatchRulesTexts.failureMessage("unverified-consent-refused", Lizzie.resourceBundle),
        toolbar.lastMessage);

    toolbar.lastMessage = "keep";
    toolbar.notifyEngineGameStartFailed(new StartFailure.CancelledByUser());
    assertEquals("keep", toolbar.lastMessage);
  }

  private static final class RecordingFrame extends LizzieFrame {
    @Override
    public void startEngineGameDialog() {
      AtomicInteger opens = DIALOG_OPENS.get(this);
      if (opens != null) {
        opens.incrementAndGet();
      }
    }
  }

  private static final class SilentToolbar extends BottomToolbar {}

  private static final class RecordingToolbar extends BottomToolbar {
    String lastMessage;

    @Override
    void showEngineGameStartFailure(String message) {
      lastMessage = message;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }
}
