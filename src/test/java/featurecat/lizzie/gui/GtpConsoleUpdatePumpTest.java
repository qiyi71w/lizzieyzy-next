package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.DocType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class GtpConsoleUpdatePumpTest {
  @Test
  void timerDeliversOutputAndStatusOnlyOnEdt() throws Exception {
    GtpConsoleBuffer buffer = new GtpConsoleBuffer();
    CountDownLatch delivered = new CountDownLatch(1);
    AtomicBoolean outsideEdt = new AtomicBoolean();
    AtomicInteger appended = new AtomicInteger();
    try (GtpConsoleUpdatePump pump =
        new GtpConsoleUpdatePump(
            buffer,
            doc -> {
              outsideEdt.set(outsideEdt.get() || !SwingUtilities.isEventDispatchThread());
              appended.incrementAndGet();
            },
            () -> {
              outsideEdt.set(outsideEdt.get() || !SwingUtilities.isEventDispatchThread());
              if (appended.get() > 0) delivered.countDown();
            })) {
      buffer.offer(doc("engine output"));
      pump.start();
      assertTrue(delivered.await(5, TimeUnit.SECONDS));
      assertFalse(outsideEdt.get());
      assertEquals(1, appended.get());
    }
  }

  @Test
  void burstIsBoundedAndPreservesOrderingBetweenEdtTurns() throws Exception {
    GtpConsoleBuffer buffer = new GtpConsoleBuffer();
    List<String> rendered = new ArrayList<>();
    AtomicInteger statusUpdates = new AtomicInteger();
    for (int i = 0; i < GtpConsoleUpdatePump.MAX_EVENTS_PER_TICK + 3; i++) {
      buffer.offer(doc("line-" + i));
    }
    try (GtpConsoleUpdatePump pump =
        new GtpConsoleUpdatePump(
            buffer, doc -> rendered.add(doc.content), statusUpdates::incrementAndGet)) {
      SwingUtilities.invokeAndWait(pump::drain);
      assertEquals(GtpConsoleUpdatePump.MAX_EVENTS_PER_TICK, rendered.size());
      assertEquals(3, buffer.size());
      assertEquals(1, statusUpdates.get());
      SwingUtilities.invokeAndWait(pump::drain);
      assertEquals(0, buffer.size());
      for (int i = 0; i < rendered.size(); i++) assertEquals("line-" + i, rendered.get(i));
      assertEquals(2, statusUpdates.get());
    }
  }

  @Test
  void backgroundThreadCannotMutateDocuments() {
    GtpConsoleBuffer buffer = new GtpConsoleBuffer();
    AtomicInteger callbacks = new AtomicInteger();
    try (GtpConsoleUpdatePump pump =
        new GtpConsoleUpdatePump(
            buffer, doc -> callbacks.incrementAndGet(), callbacks::incrementAndGet)) {
      buffer.offer(doc("retain until EDT"));
      assertThrows(IllegalStateException.class, pump::drain);
      assertEquals(1, buffer.size());
      assertEquals(0, callbacks.get());
    }
  }

  @Test
  void disposalCancelsQueuedUpdatesAndCannotRestartPump() throws Exception {
    GtpConsoleBuffer buffer = new GtpConsoleBuffer();
    AtomicInteger callbacks = new AtomicInteger();
    GtpConsoleUpdatePump pump =
        new GtpConsoleUpdatePump(
            buffer, doc -> callbacks.incrementAndGet(), callbacks::incrementAndGet);
    SwingUtilities.invokeAndWait(
        () -> {
          pump.start();
          pump.close();
          buffer.offer(doc("late output"));
          pump.start();
          pump.drain();
        });
    assertEquals(0, callbacks.get());
    assertEquals(1, buffer.size());
  }

  private static DocType doc(String content) {
    DocType doc = new DocType();
    doc.content = content;
    return doc;
  }
}
