package featurecat.lizzie.gui;

import featurecat.lizzie.util.DocType;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Keeps engine output off Swing documents until a bounded EDT update. */
final class GtpConsoleUpdatePump implements AutoCloseable {
  static final int MAX_EVENTS_PER_TICK = 64;
  private final GtpConsoleBuffer buffer;
  private final Consumer<DocType> append;
  private final Runnable refreshStatus;
  private final Timer timer;
  private volatile boolean closed;

  GtpConsoleUpdatePump(GtpConsoleBuffer buffer, Consumer<DocType> append, Runnable refreshStatus) {
    this.buffer = Objects.requireNonNull(buffer);
    this.append = Objects.requireNonNull(append);
    this.refreshStatus = Objects.requireNonNull(refreshStatus);
    timer = new Timer(100, event -> drain());
    timer.setCoalesce(true);
  }

  synchronized void start() {
    if (!closed) timer.start();
  }

  void drain() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Console updates require the EDT");
    }
    if (closed) return;
    for (int count = 0; count < MAX_EVENTS_PER_TICK && !closed; count++) {
      DocType doc = buffer.poll();
      if (doc == null) break;
      append.accept(doc);
    }
    if (!closed) refreshStatus.run();
  }

  @Override
  public synchronized void close() {
    closed = true;
    timer.stop();
  }
}
