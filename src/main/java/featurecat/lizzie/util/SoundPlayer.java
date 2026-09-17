package featurecat.lizzie.util;

import featurecat.lizzie.logging.LogCategories;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.LoggerFactory;

/** Optional audio must never open a dialog or change the user's sound preferences. */
final class SoundPlayer {
  private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

  @FunctionalInterface
  interface LineProvider {
    SourceDataLine openLine(AudioFormat format) throws Exception;
  }

  private SoundPlayer() {}

  static void play(String wav) {
    play(
        wav,
        format ->
            (SourceDataLine)
                AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, format, AudioSystem.NOT_SPECIFIED)));
  }

  static boolean play(String wav, LineProvider provider) {
    try (AudioInputStream stream = openStream(wav)) {
      SourceDataLine line = provider.openLine(stream.getFormat());
      try {
        line.open(stream.getFormat());
        line.start();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
          if (count > 0) {
            line.write(buffer, 0, count);
          }
        }
        line.drain();
        return true;
      } finally {
        line.close();
      }
    } catch (Exception failure) {
      // No message/path from the audio provider is logged; only the failure category is needed.
      if (FAILURE_REPORTED.compareAndSet(false, true)) {
        LoggerFactory.getLogger(LogCategories.APP)
            .warn(
                "Optional sound playback failed ({}); gameplay and sound preferences unchanged",
                failure.getClass().getSimpleName());
      }
      // A transiently disconnected device may be available for the next move.
      return false;
    }
  }

  private static AudioInputStream openStream(String wav) throws Exception {
    String normalized = wav.replace('\\', '/');
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    File soundFile = new File(new File("").getCanonicalPath() + normalized);
    if (soundFile.isFile()) {
      return AudioSystem.getAudioInputStream(soundFile);
    }
    String resourcePath =
        "/assets/sound"
            + (normalized.startsWith("/sound/")
                ? normalized.substring("/sound".length())
                : normalized);
    InputStream resource = SoundPlayer.class.getResourceAsStream(resourcePath);
    if (resource == null) {
      throw new IOException("Missing bundled sound resource");
    }
    BufferedInputStream buffered = new BufferedInputStream(resource);
    try {
      return AudioSystem.getAudioInputStream(buffered);
    } catch (Exception failure) {
      buffered.close();
      throw failure;
    }
  }
}
