package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SwingEngineGameChromeTest {
  @Test
  void backgroundPublishDeliversOnEdt() throws Exception {
    AtomicBoolean onEdt = new AtomicBoolean();
    CountDownLatch delivered = new CountDownLatch(1);
    SwingEngineGameChrome chrome =
        new SwingEngineGameChrome() {
          @Override
          void applyOnEdt(EngineGameChromeTransition transition) {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            delivered.countDown();
          }
        };

    chrome.publish(
        new EngineGameChromeTransition(
            EngineGameChromeTransition.Kind.STARTING, new EngineGameSnapshot.Idle()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertTrue(onEdt.get());
  }

  @Test
  void edtPublishAppliesImmediatelyOnEdt() throws Exception {
    AtomicBoolean onEdt = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () -> {
          SwingEngineGameChrome chrome =
              new SwingEngineGameChrome() {
                @Override
                void applyOnEdt(EngineGameChromeTransition transition) {
                  onEdt.set(SwingUtilities.isEventDispatchThread());
                }
              };
          chrome.publish(
              new EngineGameChromeTransition(
                  EngineGameChromeTransition.Kind.PLAYING, new EngineGameSnapshot.Idle()));
        });
    assertTrue(onEdt.get());
  }
}
