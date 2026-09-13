package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.text.AttributedString;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

class SearchInputSessionTest {
  private final JTextField input = new JTextField();

  @Test
  void candidateCommitBetweenPressAndReleaseDoesNotActivateButNextEnterDoes() {
    SearchInputSession session = new SearchInputSession();
    session.textChanged(composition("权重", 0));
    assertFalse(session.enter(key(KeyEvent.KEY_PRESSED)));
    session.textChanged(composition("权重", 2));
    assertFalse(session.enter(key(KeyEvent.KEY_RELEASED)));
    assertFalse(session.enter(key(KeyEvent.KEY_PRESSED)));
    assertTrue(session.enter(key(KeyEvent.KEY_RELEASED)));
    assertFalse(session.enter(key(KeyEvent.KEY_RELEASED)));
  }

  @Test
  void commitDeliveredBeforeCandidateEnterCannotActivate() {
    SearchInputSession session = new SearchInputSession();
    session.textChanged(composition("权重", 2));
    assertFalse(session.enter(key(KeyEvent.KEY_PRESSED)));
    assertFalse(session.enter(key(KeyEvent.KEY_RELEASED)));
    assertFalse(session.enter(key(KeyEvent.KEY_PRESSED)));
    assertTrue(session.enter(key(KeyEvent.KEY_RELEASED)));
  }

  @Test
  void ongoingCompositionKeepsNavigationWithInputMethod() {
    SearchInputSession session = new SearchInputSession();
    session.textChanged(composition("黑方sheng", 2));
    assertTrue(session.isComposing());
    assertFalse(session.enter(key(KeyEvent.KEY_PRESSED)));
    assertFalse(session.enter(key(KeyEvent.KEY_RELEASED)));
    session.textChanged(
        new InputMethodEvent(
            input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, null, 0, null, null));
    assertFalse(session.isComposing());
    assertFalse(session.enter(key(KeyEvent.KEY_PRESSED)));
    assertTrue(session.enter(key(KeyEvent.KEY_RELEASED)));
  }

  @Test
  void independentEnterAfterSpaceOrMouseCommitActivatesImmediately() {
    SearchInputSession session = new SearchInputSession();
    session.textChanged(
        new InputMethodEvent(
            input,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            100L,
            new AttributedString("权重").getIterator(),
            2,
            null,
            null));
    assertFalse(
        session.enter(new KeyEvent(input, KeyEvent.KEY_PRESSED, 200L, 0, KeyEvent.VK_ENTER, '\n')));
    assertTrue(
        session.enter(
            new KeyEvent(input, KeyEvent.KEY_RELEASED, 201L, 0, KeyEvent.VK_ENTER, '\n')));
  }

  private InputMethodEvent composition(String text, int committed) {
    return new InputMethodEvent(
        input,
        InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
        new AttributedString(text).getIterator(),
        committed,
        null,
        null);
  }

  private KeyEvent key(int id) {
    return new KeyEvent(input, id, 1, 0, KeyEvent.VK_ENTER, '\n');
  }
}
