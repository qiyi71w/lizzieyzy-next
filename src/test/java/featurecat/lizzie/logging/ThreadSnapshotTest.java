package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ThreadSnapshotTest {
  private static final Instant CAPTURED_AT = Instant.parse("2026-08-29T12:00:00Z");

  @AfterEach
  void tearDown() {
    ThreadSnapshot.resetHeldForTests();
  }

  @Test
  void renderIncludesNameStateDaemonAndStackAndHighlightsKeyThreads() {
    List<ThreadSnapshot.ThreadRecord> records =
        List.of(
            stub(
                "pool-3-thread-1",
                Thread.State.WAITING,
                true,
                9,
                frame("java.lang.Object", "wait")),
            stub(
                "AWT-EventQueue-0",
                Thread.State.RUNNABLE,
                false,
                1,
                frame("java.awt.EventDispatchThread", "pumpOneEventForFilters")),
            stub(
                "main",
                Thread.State.RUNNABLE,
                false,
                2,
                frame("featurecat.lizzie.Lizzie", "main")),
            stub(
                "lizzie-log-app",
                Thread.State.WAITING,
                true,
                3,
                frame("featurecat.lizzie.logging.BoundedAsyncAppender", "drain")),
            stub(
                "ReadBoardStream",
                Thread.State.RUNNABLE,
                true,
                4,
                frame("featurecat.lizzie.analysis.ReadBoardStream", "run")),
            stub(
                "WebSocketConnectReadThread-1",
                Thread.State.RUNNABLE,
                true,
                5,
                frame("org.java_websocket.client.WebSocketClient", "run")),
            stub(
                "engine-stdout-reader",
                Thread.State.RUNNABLE,
                true,
                6,
                frame("featurecat.lizzie.analysis.Leelaz", "read")),
            stub(
                "engine-stderr-reader",
                Thread.State.RUNNABLE,
                true,
                7,
                frame("featurecat.lizzie.analysis.Leelaz", "readError")));

    String text = ThreadSnapshot.render(records, CAPTURED_AT, "export");

    assertTrue(text.contains("reason=export"), text);
    assertTrue(text.contains("capturedAt=2026-08-29T12:00:00Z"), text);
    assertTrue(text.contains("name=AWT-EventQueue-0"), text);
    assertTrue(text.contains("state=RUNNABLE"), text);
    assertTrue(text.contains("daemon=false"), text);
    assertTrue(text.contains("daemon=true"), text);
    assertTrue(text.contains("--- stack ---"), text);
    assertTrue(text.contains("at java.awt.EventDispatchThread.pumpOneEventForFilters"), text);
    assertTrue(text.contains(">>> HIGHLIGHT edt"), text);
    assertTrue(text.contains(">>> HIGHLIGHT main"), text);
    assertTrue(text.contains(">>> HIGHLIGHT engine-io"), text);
    assertTrue(text.contains(">>> HIGHLIGHT log-worker"), text);
    assertTrue(text.contains(">>> HIGHLIGHT readboard"), text);
    assertTrue(text.contains(">>> HIGHLIGHT network"), text);
    assertTrue(
        text.contains(
            "highlightedNames=AWT-EventQueue-0, main, engine-stderr-reader, engine-stdout-reader, lizzie-log-app, ReadBoardStream, WebSocketConnectReadThread-1"),
        text);

    int edt = text.indexOf("name=AWT-EventQueue-0");
    int worker = text.indexOf("name=pool-3-thread-1");
    assertTrue(edt >= 0 && worker > edt, text);
  }

  @Test
  void singleThreadReadFailureKeepsRemainingThreads() {
    ThreadSnapshot.ThreadRecord broken =
        new ThreadSnapshot.ThreadRecord() {
          @Override
          public String name() {
            throw new IllegalStateException("thread disappeared");
          }

          @Override
          public Thread.State state() {
            return Thread.State.RUNNABLE;
          }

          @Override
          public boolean daemon() {
            return false;
          }

          @Override
          public long id() {
            return 99L;
          }

          @Override
          public int priority() {
            return 5;
          }

          @Override
          public StackTraceElement[] stack() {
            return new StackTraceElement[0];
          }
        };
    String text =
        ThreadSnapshot.render(
            List.of(
                stub(
                    "AWT-EventQueue-0",
                    Thread.State.RUNNABLE,
                    false,
                    1,
                    frame("java.awt.EventQueue", "dispatchEvent")),
                broken),
            CAPTURED_AT,
            "export");

    assertTrue(text.contains("name=AWT-EventQueue-0"), text);
    assertTrue(text.contains("name=<unreadable>"), text);
    assertTrue(text.contains("error=unreadable"), text);
    assertTrue(text.contains("threadCount=2"), text);
  }

  @Test
  void sanitizerIsAppliedToRenderedText() {
    String text =
        ThreadSnapshot.renderAndSanitize(
            List.of(
                stub(
                    "worker password=CANARY_THREAD_SECRET",
                    Thread.State.RUNNABLE,
                    true,
                    4,
                    frame("java.lang.Thread", "run"))),
            new ExportSanitizer(),
            CAPTURED_AT,
            "export");
    assertFalse(text.contains("CANARY_THREAD_SECRET"), text);
    assertTrue(text.contains("password=<redacted>"), text);
  }

  @Test
  void captureIncludesLiveThreadsAndHeldHangDump() {
    ThreadSnapshot.holdRaw(
        "=== Thread snapshot ===\nreason=edt-hang\nname=AWT-EventQueue-0\nstate=RUNNABLE\n");
    String text = ThreadSnapshot.capture(new ExportSanitizer());
    assertTrue(text.contains("reason=export"), text);
    assertTrue(text.contains("reason=edt-hang"), text);
    assertTrue(text.contains("name="), text);
    assertTrue(text.contains("state="), text);
    assertTrue(text.contains("daemon="), text);
    assertTrue(text.contains("--- stack ---"), text);
  }

  private static ThreadSnapshot.ThreadRecord stub(
      String name,
      Thread.State state,
      boolean daemon,
      long id,
      StackTraceElement frame) {
    return new ThreadSnapshot.ThreadRecord() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Thread.State state() {
        return state;
      }

      @Override
      public boolean daemon() {
        return daemon;
      }

      @Override
      public long id() {
        return id;
      }

      @Override
      public int priority() {
        return 5;
      }

      @Override
      public StackTraceElement[] stack() {
        return new StackTraceElement[] {frame};
      }
    };
  }

  private static StackTraceElement frame(String className, String methodName) {
    return new StackTraceElement(className, methodName, className.replace('.', '/') + ".java", 1);
  }
}
