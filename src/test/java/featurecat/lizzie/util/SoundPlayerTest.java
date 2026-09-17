package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Window;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.junit.jupiter.api.Test;

class SoundPlayerTest {
  @Test
  void unavailableDeviceDoesNotOpenDialogsAndCanRecoverOnNextMove() {
    int windows = Window.getWindows().length;
    AtomicInteger attempted = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      assertFalse(
          SoundPlayer.play(
              "/sound/Stone.wav",
              format -> {
                attempted.incrementAndGet();
                throw new LineUnavailableException("fixture device disconnected");
              }));
    }
    assertEquals(3, attempted.get(), "bundled sound must decode before device failure");
    assertEquals(windows, Window.getWindows().length);
    List<String> calls = new ArrayList<>();
    assertTrue(SoundPlayer.play("/sound/Stone.wav", format -> line(calls, null)));
    assertTrue(calls.contains("write"));
    assertEquals(List.of("drain", "close"), calls.subList(calls.size() - 2, calls.size()));
  }

  @Test
  void unsupportedAudioMixerIsNotMisreportedAsMissingFile() {
    AtomicInteger attempted = new AtomicInteger();
    assertFalse(
        SoundPlayer.play(
            "\\sound\\Stone.wav",
            format -> {
              attempted.incrementAndGet();
              throw new IllegalArgumentException("fixture has no matching mixer");
            }));
    assertEquals(1, attempted.get());
  }

  @Test
  void openFailureClosesAllocatedLine() {
    List<String> calls = new ArrayList<>();
    assertFalse(SoundPlayer.play("/sound/Stone.wav", format -> line(calls, "open")));
    assertEquals(List.of("open", "close"), calls);
  }

  @Test
  void writeFailureClosesLineWithoutDraining() {
    List<String> calls = new ArrayList<>();
    assertFalse(SoundPlayer.play("/sound/Stone.wav", format -> line(calls, "write")));
    assertTrue(calls.contains("write"));
    assertFalse(calls.contains("drain"));
    assertEquals("close", calls.get(calls.size() - 1));
  }

  @Test
  void missingOptionalSoundDoesNotAttemptToOpenAudioDevice() {
    assertFalse(
        SoundPlayer.play(
            "/sound/missing-test-sound.wav",
            format -> {
              throw new AssertionError("must not allocate a line without audio data");
            }));
  }

  @Test
  void bundledCaptureAndCountdownSoundsRemainPlayable() {
    for (String name : List.of("deadStone.wav", "deadStoneMore.wav", "1.wav")) {
      List<String> calls = new ArrayList<>();
      assertTrue(SoundPlayer.play("/sound/" + name, format -> line(calls, null)), name);
      assertTrue(calls.contains("write"), name);
      assertEquals("close", calls.get(calls.size() - 1));
    }
  }

  private static SourceDataLine line(List<String> calls, String failAt) {
    return (SourceDataLine)
        Proxy.newProxyInstance(
            SoundPlayerTest.class.getClassLoader(),
            new Class<?>[] {SourceDataLine.class},
            (proxy, method, args) -> {
              String name = method.getName();
              calls.add(name);
              if (name.equals(failAt)) {
                throw new IllegalStateException("fixture audio failure");
              }
              if (name.equals("write")) {
                return (int) args[2];
              }
              return null;
            });
  }
}
