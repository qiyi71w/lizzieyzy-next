package featurecat.lizzie.logging;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Formats a process-wide thread snapshot for the diagnostic package {@code snapshots/threads.txt}.
 */
public final class ThreadSnapshot {
  static final String ENTRY_NAME = "threads.txt";
  private static final int MAX_STACK_FRAMES = 256;
  private static final AtomicReference<String> HELD_RAW = new AtomicReference<>();

  private ThreadSnapshot() {}

  public static String capture(ExportSanitizer sanitizer) {
    StringBuilder raw = new StringBuilder();
    raw.append(captureRaw("export"));
    String held = heldRaw();
    if (held != null && !held.isEmpty()) {
      raw.append('\n');
      raw.append(held);
    }
    ExportSanitizer active = sanitizer == null ? new ExportSanitizer() : sanitizer;
    return active.sanitizeText(raw.toString());
  }

  static String captureRaw(String reason) {
    return render(liveRecords(), Instant.now(), reason);
  }

  static void holdRaw(String snapshot) {
    HELD_RAW.set(snapshot);
  }

  static String heldRaw() {
    return HELD_RAW.get();
  }

  static void resetHeldForTests() {
    HELD_RAW.set(null);
  }

  static String render(
      List<? extends ThreadRecord> records, Instant capturedAt, String reason) {
    Instant at = capturedAt == null ? Instant.now() : capturedAt;
    String why = reason == null || reason.isEmpty() ? "export" : reason;
    List<RenderedThread> rendered = new ArrayList<>();
    if (records != null) {
      for (ThreadRecord record : records) {
        rendered.add(renderOne(record));
      }
    }
    rendered.sort(
        Comparator.comparingInt((RenderedThread thread) -> thread.rank)
            .thenComparing(thread -> thread.name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(thread -> thread.id));

    StringBuilder out = new StringBuilder();
    out.append("=== Thread snapshot ===\n");
    out.append("reason=").append(why).append('\n');
    out.append("capturedAt=").append(at).append('\n');
    out.append("threadCount=").append(rendered.size()).append('\n');
    List<String> highlightedNames = new ArrayList<>();
    int highlightedCount = 0;
    for (RenderedThread thread : rendered) {
      if (!thread.tags.isEmpty()) {
        highlightedCount++;
        highlightedNames.add(thread.name);
      }
    }
    out.append("highlightedCount=").append(highlightedCount).append('\n');
    out.append("highlightedNames=");
    for (int i = 0; i < highlightedNames.size(); i++) {
      if (i > 0) {
        out.append(", ");
      }
      out.append(highlightedNames.get(i));
    }
    out.append('\n');
    String deadlocks = deadlockIds();
    if (!deadlocks.isEmpty()) {
      out.append("deadlockedThreadIds=").append(deadlocks).append('\n');
    }
    out.append('\n');
    for (RenderedThread thread : rendered) {
      out.append(thread.body);
      if (thread.body.isEmpty() || thread.body.charAt(thread.body.length() - 1) != '\n') {
        out.append('\n');
      }
      out.append('\n');
    }
    return out.toString();
  }

  static String renderAndSanitize(
      List<? extends ThreadRecord> records,
      ExportSanitizer sanitizer,
      Instant capturedAt,
      String reason) {
    ExportSanitizer active = sanitizer == null ? new ExportSanitizer() : sanitizer;
    return active.sanitizeText(render(records, capturedAt, reason));
  }

  static List<ThreadRecord> liveRecords() {
    Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
    Map<Long, ThreadInfo> infos = threadInfos();
    List<ThreadRecord> records = new ArrayList<>(traces.size());
    for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
      Thread thread = entry.getKey();
      if (thread == null) {
        continue;
      }
      long id = -1L;
      try {
        id = thread.getId();
      } catch (Throwable ignored) {
      }
      records.add(
          new JdkThreadRecord(thread, entry.getValue(), id < 0 ? null : infos.get(id)));
    }
    return records;
  }

  private static RenderedThread renderOne(ThreadRecord record) {
    try {
      String name = record.name();
      Thread.State state = record.state();
      boolean daemon = record.daemon();
      long id = record.id();
      int priority = record.priority();
      StackTraceElement[] stack = record.stack();
      String lockName = record.lockName();
      String lockOwnerName = record.lockOwnerName();
      boolean inNative = record.inNative();
      List<String> tags = highlightTags(name, stack);
      StringBuilder body = new StringBuilder();
      if (tags.isEmpty()) {
        body.append("--- thread ---\n");
      } else {
        body.append(">>> HIGHLIGHT");
        for (String tag : tags) {
          body.append(' ').append(tag);
        }
        body.append('\n');
      }
      body.append("name=").append(name == null ? "" : name).append('\n');
      // "id=" is treated as a Yike room parameter by ExportSanitizer.sanitizeText.
      body.append("threadId=").append(id).append('\n');
      body.append("state=").append(state == null ? "" : state.name()).append('\n');
      body.append("daemon=").append(daemon).append('\n');
      body.append("priority=").append(priority).append('\n');
      body.append("tags=");
      for (int i = 0; i < tags.size(); i++) {
        if (i > 0) {
          body.append(',');
        }
        body.append(tags.get(i));
      }
      body.append('\n');
      body.append("lock=").append(lockName == null ? "" : lockName).append('\n');
      body.append("lockOwner=").append(lockOwnerName == null ? "" : lockOwnerName).append('\n');
      body.append("native=").append(inNative).append('\n');
      body.append("--- stack ---\n");
      appendStack(body, stack);
      return new RenderedThread(name == null ? "" : name, id, rank(tags), tags, body.toString());
    } catch (Throwable ignored) {
      return new RenderedThread(
          "<unreadable>",
          Long.MAX_VALUE,
          99,
          List.of(),
          "--- thread ---\nname=<unreadable>\nstate=<unreadable>\ndaemon=<unreadable>\nerror=unreadable\n");
    }
  }

  static List<String> highlightTags(String name, StackTraceElement[] stack) {
    List<String> tags = new ArrayList<>();
    String threadName = name == null ? "" : name;
    String lower = threadName.toLowerCase(Locale.ROOT);
    if (threadName.startsWith("AWT-EventQueue")) {
      tags.add("edt");
    }
    if ("main".equals(threadName)) {
      tags.add("main");
    }
    if (lower.startsWith("lizzie-log-")) {
      tags.add("log-worker");
    }
    if (lower.contains("readboard")) {
      tags.add("readboard");
    }
    boolean engineName = lower.contains("stdout") || lower.contains("stderr");
    boolean engineStack = false;
    boolean readboardStack = false;
    boolean networkName =
        lower.contains("websocket")
            || lower.contains("webboard")
            || lower.contains("zhizi")
            || lower.contains("yike")
            || lower.contains("network");
    boolean networkStack = false;
    if (stack != null) {
      for (StackTraceElement frame : stack) {
        if (frame == null) {
          continue;
        }
        String cls = frame.getClassName();
        String method = frame.getMethodName();
        if (cls == null) {
          continue;
        }
        if (("featurecat.lizzie.analysis.Leelaz".equals(cls)
                || "featurecat.lizzie.analysis.AnalysisEngine".equals(cls))
            && ("read".equals(method) || "readError".equals(method))) {
          engineStack = true;
        }
        String clsLower = cls.toLowerCase(Locale.ROOT);
        if (clsLower.contains("readboard")) {
          readboardStack = true;
        }
        if (cls.contains("WebSocket")
            || cls.contains("WebBoard")
            || cls.startsWith("featurecat.lizzie.analysis.remote")
            || cls.startsWith("featurecat.lizzie.gui.web")
            || cls.startsWith("org.java_websocket")) {
          networkStack = true;
        }
      }
    }
    if (engineName || engineStack) {
      tags.add("engine-io");
    }
    if (readboardStack && !tags.contains("readboard")) {
      tags.add("readboard");
    }
    if (networkName || networkStack) {
      tags.add("network");
    }
    return tags;
  }

  private static int rank(List<String> tags) {
    if (tags.contains("edt")) {
      return 0;
    }
    if (tags.contains("main")) {
      return 1;
    }
    if (tags.contains("engine-io")) {
      return 2;
    }
    if (tags.contains("log-worker")) {
      return 3;
    }
    if (tags.contains("readboard")) {
      return 4;
    }
    if (tags.contains("network")) {
      return 5;
    }
    return 99;
  }

  private static void appendStack(StringBuilder body, StackTraceElement[] stack) {
    if (stack == null) {
      body.append("(no stack)\n");
      return;
    }
    if (stack.length == 0) {
      body.append("(empty stack)\n");
      return;
    }
    int limit = Math.min(stack.length, MAX_STACK_FRAMES);
    for (int i = 0; i < limit; i++) {
      StackTraceElement frame = stack[i];
      if (frame == null) {
        body.append("  at <unreadable>\n");
      } else {
        body.append("  at ").append(frame).append('\n');
      }
    }
    if (stack.length > MAX_STACK_FRAMES) {
      body.append("  ... ").append(stack.length - MAX_STACK_FRAMES).append(" frames omitted\n");
    }
  }

  private static Map<Long, ThreadInfo> threadInfos() {
    try {
      ThreadMXBean mx = ManagementFactory.getThreadMXBean();
      boolean monitors = mx.isObjectMonitorUsageSupported();
      boolean synchronizers = mx.isSynchronizerUsageSupported();
      ThreadInfo[] infos = mx.dumpAllThreads(monitors, synchronizers);
      if (infos == null || infos.length == 0) {
        return Map.of();
      }
      Map<Long, ThreadInfo> byId = new HashMap<>();
      for (ThreadInfo info : infos) {
        if (info != null) {
          byId.put(info.getThreadId(), info);
        }
      }
      return byId;
    } catch (Throwable ignored) {
      return Map.of();
    }
  }

  private static String deadlockIds() {
    try {
      long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
      if (ids == null || ids.length == 0) {
        return "";
      }
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < ids.length; i++) {
        if (i > 0) {
          text.append(',');
        }
        text.append(ids[i]);
      }
      return text.toString();
    } catch (Throwable ignored) {
      return "";
    }
  }

  interface ThreadRecord {
    String name();

    Thread.State state();

    boolean daemon();

    long id();

    int priority();

    StackTraceElement[] stack();

    default String lockName() {
      return "";
    }

    default String lockOwnerName() {
      return "";
    }

    default boolean inNative() {
      return false;
    }
  }

  private static final class JdkThreadRecord implements ThreadRecord {
    private final Thread thread;
    private final StackTraceElement[] stack;
    private final ThreadInfo info;

    private JdkThreadRecord(Thread thread, StackTraceElement[] stack, ThreadInfo info) {
      this.thread = thread;
      this.stack = stack == null ? new StackTraceElement[0] : stack;
      this.info = info;
    }

    @Override
    public String name() {
      return thread.getName();
    }

    @Override
    public Thread.State state() {
      return thread.getState();
    }

    @Override
    public boolean daemon() {
      return thread.isDaemon();
    }

    @Override
    public long id() {
      return thread.getId();
    }

    @Override
    public int priority() {
      return thread.getPriority();
    }

    @Override
    public StackTraceElement[] stack() {
      return stack;
    }

    @Override
    public String lockName() {
      return info == null || info.getLockName() == null ? "" : info.getLockName();
    }

    @Override
    public String lockOwnerName() {
      return info == null || info.getLockOwnerName() == null ? "" : info.getLockOwnerName();
    }

    @Override
    public boolean inNative() {
      return info != null && info.isInNative();
    }
  }

  private static final class RenderedThread {
    final String name;
    final long id;
    final int rank;
    final List<String> tags;
    final String body;

    private RenderedThread(String name, long id, int rank, List<String> tags, String body) {
      this.name = name;
      this.id = id;
      this.rank = rank;
      this.tags = tags;
      this.body = body;
    }
  }
}
