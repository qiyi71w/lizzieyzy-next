package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineFailedMessage.DiagnosticActionResult;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtFailureKind;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EngineFailedMessageLayoutTest {
  @Test
  void longFailureDetailsStayBoundedOnFourKAtOneHundredFiftyPercent() {
    String longMessage = "Exit code: -1 Recent stderr: " + "failure details ".repeat(2_000);
    String longCommand = "C:\\very long engine path\\" + "nested directory\\".repeat(2_000);

    Dimension size =
        EngineFailedMessage.calculateDialogSize(
            longMessage, longCommand, 18, 730, 360, new Dimension(2560, 1440));

    assertEquals(EngineFailedMessage.MAX_DIALOG_WIDTH, size.width);
    assertEquals(360, size.height);
    assertTrue(size.width <= 2560 - EngineFailedMessage.SCREEN_MARGIN);
    assertTrue(size.height <= 1440 - EngineFailedMessage.SCREEN_MARGIN);
  }

  @Test
  void dialogSizeAlsoFitsASmallUsableScreen() {
    Dimension size =
        EngineFailedMessage.calculateDialogSize(
            "short failure", "katago.exe gtp", 16, 730, 360, new Dimension(640, 480));

    assertEquals(576, size.width);
    assertEquals(360, size.height);
    assertTrue(size.height <= 480 - EngineFailedMessage.SCREEN_MARGIN);
  }

  @Test
  void longTextUsesReadOnlyWrappedVerticalScrolling() {
    Font font = new Font(Font.MONOSPACED, Font.PLAIN, 18);
    JScrollPane pane = EngineFailedMessage.createScrollableText("command ".repeat(5_000), font);

    assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, pane.getVerticalScrollBarPolicy());
    assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, pane.getHorizontalScrollBarPolicy());
    assertTrue(pane.getViewport().getView() instanceof JTextArea);
    JTextArea area = (JTextArea) pane.getViewport().getView();
    assertFalse(area.isEditable());
    assertTrue(area.getLineWrap());
    assertTrue(area.getWrapStyleWord());
    assertEquals(0, area.getCaretPosition());
    assertSame(font, area.getFont());
  }

  @Test
  void sensitiveValuesNeverReachVisibleOrPersistedDiagnosticText() {
    String password = "qa-password-do-not-leak";
    String passwd = "qa-passwd-do-not-leak";
    String token = "qa-token-do-not-leak";
    String apiKey = "qa-api-key-do-not-leak";
    String secret = "qa-secret-do-not-leak";
    String message =
        "Recent stderr: password="
            + password
            + " token:"
            + token
            + " --secret \""
            + secret
            + "\"";
    String command =
        "engine.exe --password "
            + password
            + " --token=\""
            + token
            + "\" api_key="
            + apiKey;
    String contributeCommand =
        "engine.exe -override-config \"username=qa\",\"password="
            + password
            + "\",\"maxSimultaneousGames=1\"";

    JScrollPane messagePane =
        EngineFailedMessage.createScrollableText(
            message, new Font(Font.MONOSPACED, Font.PLAIN, 14));
    String visibleMessage = ((JTextArea) messagePane.getViewport().getView()).getText();
    String visibleCommand =
        EngineFailedMessage.redactSensitiveText(command + "\n" + contributeCommand);
    String diagnosticCommand =
        EngineFailedMessage.buildDiagnosticCommand(
            List.of(
                "engine.exe",
                "--password",
                password,
                "--passwd",
                passwd,
                "--token=" + token,
                "api-key=" + apiKey,
                "secret=" + secret),
            command);

    for (String sensitiveValue : List.of(password, passwd, token, apiKey, secret)) {
      assertFalse(visibleMessage.contains(sensitiveValue));
      assertFalse(visibleCommand.contains(sensitiveValue));
      assertFalse(diagnosticCommand.contains(sensitiveValue));
    }
    assertTrue(visibleMessage.contains(EngineFailedMessage.REDACTED_VALUE));
    assertTrue(visibleCommand.contains(EngineFailedMessage.REDACTED_VALUE));
    assertTrue(diagnosticCommand.contains(EngineFailedMessage.REDACTED_VALUE));
  }

  @Test
  void contributionSecretsWithSeparatorsAndQuoteInjectionAreFailSafeRedacted() {
    List<SensitiveCase> cases =
        List.of(
            contributionCase(
                '"',
                "QaSpaceLeft42 QaSpaceRight42",
                "QaSpaceLeft42",
                "QaSpaceRight42"),
            contributionCase(
                '"',
                "QaCommaLeft42,QaCommaRight42",
                "QaCommaLeft42",
                "QaCommaRight42"),
            contributionCase(
                '"',
                "QaSemiLeft42;QaSemiRight42",
                "QaSemiLeft42",
                "QaSemiRight42"),
            contributionCase(
                '"',
                "QaDoubleLeft42\"QaDoubleRight42",
                "QaDoubleLeft42",
                "QaDoubleRight42"),
            contributionCase(
                '\'',
                "QaSingleLeft42'QaSingleRight42",
                "QaSingleLeft42",
                "QaSingleRight42"),
            contributionCase(
                '"',
                "QaInjectLead42\",\"token=QaInjectedToken42\",\"QaInjectTail42",
                "QaInjectLead42",
                "QaInjectedToken42",
                "QaInjectTail42"),
            contributionCase(
                '\'',
                "QaSingleInjectLead42','secret=QaInjectedSecret42','QaSingleInjectTail42",
                "QaSingleInjectLead42",
                "QaInjectedSecret42",
                "QaSingleInjectTail42"),
            contributionCase(
                '"',
                "QaCrLeft42\rQaCrRight42",
                "QaCrLeft42",
                "QaCrRight42"),
            contributionCase(
                '\'',
                "QaLfLeft42\nQaLfRight42",
                "QaLfLeft42",
                "QaLfRight42"),
            contributionCase(
                '"',
                "QaMultilineLead42\r\n\",\"token=QaMultilineInjected42\",\"QaMultilineTail42",
                "QaMultilineLead42",
                "QaMultilineInjected42",
                "QaMultilineTail42"),
            new SensitiveCase(
                "Recent stderr: password=QaMessageLeft42 QaMessageRight42; status=failed",
                List.of(
                    "engine.exe",
                    "--password",
                    "QaMessageLeft42 QaMessageRight42",
                    "--mode",
                    "gtp"),
                List.of(
                    "QaMessageLeft42 QaMessageRight42",
                    "QaMessageLeft42",
                    "QaMessageRight42")),
            new SensitiveCase(
                "Recent stderr: token=QaMessageCrLeft42\r\nQaMessageCrRight42\nstatus=failed",
                List.of(
                    "engine.exe",
                    "--token",
                    "QaMessageCrLeft42\r\nQaMessageCrRight42",
                    "--mode",
                    "gtp"),
                List.of(
                    "QaMessageCrLeft42\r\nQaMessageCrRight42",
                    "QaMessageCrLeft42",
                    "QaMessageCrRight42")),
            new SensitiveCase(
                "engine.exe --api-key QaFlagLeft42 QaFlagRight42 --mode gtp",
                List.of(
                    "engine.exe", "--api-key", "QaFlagLeft42 QaFlagRight42", "--mode", "gtp"),
                List.of(
                    "QaFlagLeft42 QaFlagRight42", "QaFlagLeft42", "QaFlagRight42")));

    for (SensitiveCase sensitiveCase : cases) {
      JScrollPane pane =
          EngineFailedMessage.createScrollableText(
              sensitiveCase.text, new Font(Font.MONOSPACED, Font.PLAIN, 14));
      String visibleText = ((JTextArea) pane.getViewport().getView()).getText();
      String fallbackDiagnostic =
          EngineFailedMessage.buildDiagnosticCommand(null, sensitiveCase.text);
      String tokenizedDiagnostic =
          EngineFailedMessage.buildDiagnosticCommand(
              sensitiveCase.commandTokens, sensitiveCase.text);

      for (String output : List.of(visibleText, fallbackDiagnostic, tokenizedDiagnostic)) {
        assertTrue(output.contains(EngineFailedMessage.REDACTED_VALUE), output);
        for (String secretOrFragment : sensitiveCase.secretAndFragments) {
          assertFalse(output.contains(secretOrFragment), output);
        }
      }
    }
  }

  private static SensitiveCase contributionCase(
      char quote, String secret, String... secretFragments) {
    String delimiter = String.valueOf(quote);
    String override =
        delimiter
            + "username=qa-dummy-user"
            + delimiter
            + ","
            + delimiter
            + "password="
            + secret
            + delimiter
            + ","
            + delimiter
            + "maxSimultaneousGames=1"
            + delimiter;
    List<String> secretAndFragments = new java.util.ArrayList<>();
    secretAndFragments.add(secret);
    secretAndFragments.addAll(List.of(secretFragments));
    return new SensitiveCase(
        "engine.exe contribute -override-config " + override,
        List.of("engine.exe", "contribute", "-override-config", override),
        secretAndFragments);
  }

  private static final class SensitiveCase {
    private final String text;
    private final List<String> commandTokens;
    private final List<String> secretAndFragments;

    private SensitiveCase(
        String text, List<String> commandTokens, List<String> secretAndFragments) {
      this.text = text;
      this.commandTokens = commandTokens;
      this.secretAndFragments = secretAndFragments;
    }
  }

  @Test
  void secondaryMonitorBoundsKeepInsetsAndClampTheDialog() {
    Rectangle usable =
        EngineFailedMessage.calculateUsableBounds(
            new Rectangle(-2560, -120, 2560, 1440), new Insets(40, 0, 80, 12));

    assertEquals(new Rectangle(-2560, -80, 2548, 1320), usable);
    assertEquals(
        new Rectangle(-2560, -80, 980, 360),
        EngineFailedMessage.clampDialogBounds(
            new Rectangle(-2700, -200, 980, 360), usable));
    assertEquals(
        new Rectangle(-992, 880, 980, 360),
        EngineFailedMessage.clampDialogBounds(new Rectangle(-600, 1300, 980, 360), usable));
  }

  @Test
  void backgroundCallerRunsSynchronouslyOnTheEventDispatchThread() throws Exception {
    AtomicBoolean actionRanOnEventThread = new AtomicBoolean();
    AtomicBoolean actionCompletedBeforeReturn = new AtomicBoolean();
    AtomicInteger executionCount = new AtomicInteger();
    Thread worker =
        new Thread(
            () -> {
              EngineFailedMessage.runOnEventDispatchThreadAndWait(
                  () -> {
                    executionCount.incrementAndGet();
                    actionRanOnEventThread.set(SwingUtilities.isEventDispatchThread());
                  });
              actionCompletedBeforeReturn.set(actionRanOnEventThread.get());
            },
            "engine-failure-dialog-test");

    worker.start();
    worker.join(TimeUnit.SECONDS.toMillis(5));

    assertFalse(worker.isAlive());
    assertEquals(1, executionCount.get());
    assertTrue(actionRanOnEventThread.get());
    assertTrue(actionCompletedBeforeReturn.get());
  }

  @Test
  void eventDispatchCallerRunsInline() throws Exception {
    AtomicReference<Thread> actionThread = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          Thread eventThread = Thread.currentThread();
          EngineFailedMessage.runOnEventDispatchThreadAndWait(
              () -> actionThread.set(Thread.currentThread()));
          assertSame(eventThread, actionThread.get());
        });
  }

  @Test
  void eventDispatchFailureIsPropagatedToTheCaller() {
    IllegalArgumentException expected = new IllegalArgumentException("diagnostic failed");

    IllegalArgumentException actual =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                EngineFailedMessage.runOnEventDispatchThreadAndWait(
                    () -> {
                      throw expected;
                    }));

    assertSame(expected, actual);
  }

  @Test
  void eventDispatchErrorIsPropagatedToTheCaller() {
    AssertionError expected = new AssertionError("diagnostic error");

    AssertionError actual =
        assertThrows(
            AssertionError.class,
            () ->
                EngineFailedMessage.runOnEventDispatchThreadAndWait(
                    () -> {
                      throw expected;
                    }));

    assertSame(expected, actual);
  }

  @Test
  void interruptedWaitRestoresTheCallerInterruptFlag() throws Exception {
    CountDownLatch eventThreadBlocked = new CountDownLatch(1);
    CountDownLatch releaseEventThread = new CountDownLatch(1);
    SwingUtilities.invokeLater(
        () -> {
          eventThreadBlocked.countDown();
          try {
            releaseEventThread.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(eventThreadBlocked.await(5, TimeUnit.SECONDS));

    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interruptRestored = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              try {
                EngineFailedMessage.runOnEventDispatchThreadAndWait(() -> {});
              } catch (Throwable thrown) {
                failure.set(thrown);
                interruptRestored.set(Thread.currentThread().isInterrupted());
              }
            },
            "engine-failure-dialog-interrupt-test");

    try {
      worker.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (worker.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
        Thread.yield();
      }
      assertEquals(Thread.State.WAITING, worker.getState());
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(worker.isAlive());
      assertTrue(failure.get() instanceof IllegalStateException);
      assertTrue(failure.get().getCause() instanceof InterruptedException);
      assertTrue(interruptRestored.get());
    } finally {
      releaseEventThread.countDown();
      worker.join(TimeUnit.SECONDS.toMillis(5));
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  @Test
  void ordinaryFailureDoesNotRecordARepairClick() {
    assumeTrue(Lizzie.config != null);
    assumeTrue(!GraphicsEnvironment.isHeadless());
    EngineFailedMessage dialog =
        new EngineFailedMessage(
            List.of("engine.exe"), "engine.exe gtp", "ordinary process failure", true, true, false);
    assertFalse(dialog.offersTensorRtRepair());
    assertFalse(dialog.recordTensorRtRepairInvoked());
    assertFalse(dialog.tensorRtRepairInvoked());
    assertFalse(DiagnosticActionResult.of(dialog).directedRepairOpened);
  }

  @Test
  void repairClickIsRecordedWithoutOpeningSetup() {
    assumeTrue(Lizzie.config != null);
    assumeTrue(!GraphicsEnvironment.isHeadless());
    TensorRtRepairContext context =
        TensorRtRepairContext.of(
            Path.of("engines", "katago", "windows-x64-nvidia-tensorrt", "katago.exe"),
            "katago.exe gtp",
            TensorRtFailureKind.MISSING_RUNTIME,
            List.of(TensorRtInstallStatus.MISSING_RUNTIME),
            true,
            "display");
    EngineFailedMessage dialog =
        new EngineFailedMessage(
            List.of("katago.exe", "gtp"),
            context.originalCommand,
            context.displayMessage,
            true,
            true,
            false,
            context);
    assertTrue(dialog.offersTensorRtRepair());
    assertFalse(dialog.tensorRtRepairInvoked());
    assertFalse(DiagnosticActionResult.of(dialog).directedRepairOpened);
    assertTrue(dialog.recordTensorRtRepairInvoked());
    assertTrue(dialog.tensorRtRepairInvoked());
    assertTrue(DiagnosticActionResult.of(dialog).directedRepairOpened);
  }
}
