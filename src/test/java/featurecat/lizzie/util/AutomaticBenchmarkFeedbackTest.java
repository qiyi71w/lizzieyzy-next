package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.LogCategories;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.InvocationEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

class AutomaticBenchmarkFeedbackTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();
  private Level previousLevel;
  private boolean previousAdditive;

  @BeforeEach
  void captureFeedback() {
    previousLevel = logger.getLevel();
    previousAdditive = logger.isAdditive();
    logger.setLevel(Level.INFO);
    logger.setAdditive(false);
    events.start();
    logger.addAppender(events);
  }

  @AfterEach
  void restoreLogging() {
    logger.detachAppender(events);
    events.stop();
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditive);
  }

  @ParameterizedTest
  @ValueSource(strings = {"apple-silicon", "first-run"})
  void failureKeepsItsDiagnosticCauseWithoutSchedulingAWindow(String source) throws Exception {
    IOException failure = new IOException("inference failed", new IOException("process exit 1"));

    ILoggingEvent event = reportWithoutUi(source, failure);

    assertEquals(Level.WARN, event.getLevel());
    assertEquals(
        "benchmark mode=automatic source=" + source + " outcome=failed",
        event.getFormattedMessage());
    assertEquals(IOException.class.getName(), event.getThrowableProxy().getClassName());
    assertEquals("inference failed", event.getThrowableProxy().getMessage());
    assertEquals("process exit 1", event.getThrowableProxy().getCause().getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"apple-silicon", "first-run"})
  void wrappedPreemptionStaysInformationalAndSilent(String source) throws Exception {
    IOException failure =
        new IOException("launch failed", KataGoRuntimeHelper.benchmarkIsolationLostException());

    ILoggingEvent event = reportWithoutUi(source, failure);

    assertEquals(Level.INFO, event.getLevel());
    assertEquals(
        "benchmark mode=automatic source=" + source + " outcome=preempted reason=active-analysis",
        event.getFormattedMessage());
    assertNull(event.getThrowableProxy(), "yielding to user analysis is not a warning or failure");
  }

  @ParameterizedTest
  @ValueSource(strings = {"apple-silicon", "first-run"})
  void matchingPreemptionTextDoesNotHideARealFailure(String source) throws Exception {
    IOException failure =
        new IOException(KataGoRuntimeHelper.benchmarkIsolationLostException().getMessage());

    ILoggingEvent event = reportWithoutUi(source, failure);

    assertEquals(Level.WARN, event.getLevel());
    assertTrue(event.getFormattedMessage().endsWith("outcome=failed"));
    assertEquals(failure.getMessage(), event.getThrowableProxy().getMessage());
  }

  private ILoggingEvent reportWithoutUi(String source, IOException failure) throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
    LizzieFrame previousFrame = Lizzie.frame;
    FeedbackEventQueue queue = new FeedbackEventQueue();
    Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
    try {
      // Keep the real "a main window exists" branch enabled without creating a native window.
      Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
      unsafe.setAccessible(true);
      Lizzie.frame = (LizzieFrame) ((Unsafe) unsafe.get(null)).allocateInstance(LizzieFrame.class);
      queue.reportingThread = Thread.currentThread();

      KataGoRuntimeHelper.reportAutomaticBenchmarkFailure(source, failure);

      assertEquals(0, queue.feedbackEvents, "automatic feedback must not enqueue Swing dialogs");
      assertEquals(1, events.list.size());
      return events.list.get(0);
    } finally {
      queue.reportingThread = null;
      Lizzie.frame = previousFrame;
      queue.remove();
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  private static final class FeedbackEventQueue extends EventQueue {
    private Thread reportingThread;
    private int feedbackEvents;

    @Override
    public void postEvent(AWTEvent event) {
      if (Thread.currentThread() == reportingThread && event instanceof InvocationEvent) {
        feedbackEvents++;
        return;
      }
      super.postEvent(event);
    }

    private void remove() {
      pop();
    }
  }
}
